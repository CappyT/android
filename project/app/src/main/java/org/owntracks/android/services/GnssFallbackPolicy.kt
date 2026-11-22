package org.owntracks.android.services

/**
 * Decides when a location request that doesn't use GNSS needs a single GNSS fix to fill a gap, and
 * whether the device is moving or at a place.
 *
 * A gap is a location that is less accurate than [accuracyMeters], no accurate location for one and
 * a half intervals, or no accurate location within the window that follows a movement hint.
 *
 * With a [movingIntervalMillis], a movement hint switches to the moving state: the probe runs every
 * moving interval and each accurate location is offered for publishing, at most one per moving
 * interval. The device goes back to the still state, where everything runs every [intervalMillis],
 * when the motion activity says it is stationary, or when its accurate locations stay at the same
 * place: two of them most of a moving interval apart, or four over three moving intervals while the
 * device counts steps, because a walk covers little ground between two locations.
 *
 * The motion sensors also fire when the device moves in place, like on a treadmill. After the
 * locations stayed at the same place, a motion only starts a look: the moving state without GNSS at
 * the end of its window. Looks that find the same place again are spaced out, up to one per
 * interval. Until a radio hint or a location away from the place it started at proves a
 * displacement, a missing or inaccurate location requests GNSS as slowly as in the still state. In
 * a vehicle, the device stops at the same place only for a while, so a motion starts moving away
 * from the place, and the vehicle activity proves the displacement of a look.
 *
 * A moving state without an accurate location for [MAX_UNLOCATED_INTERVALS] intervals, or a look
 * without one for an interval, ends like a stop at the same place: nothing locates the device, and
 * the probe and the check would run every moving interval for nothing. The look backoff then starts
 * at that end.
 *
 * While moving away from the place, an accurate GNSS fix shows that only GNSS locates the device,
 * so [wantsGnssTracking] asks for a periodic GNSS request instead of one fix per check. It ends
 * with the moving state, with an accurate location from another source, or without a fix in time.
 * Unless the device walks, the locations from another source must keep coming for most of a moving
 * interval: in a vehicle, a single one is often a Wi-Fi scan along the road, and the motion
 * activity often can't tell a vehicle yet. A Wi-Fi network joined while moving opens a window: the
 * network locates the device without GNSS, and its first accurate location ends GNSS tracking at
 * once.
 *
 * A radio hint while moving proves a displacement. Without a recent accurate location, it requests
 * GNSS at once: the next check can be most of a moving interval away.
 *
 * Without a moving interval, a movement hint only opens a window, at most one per interval. A hint
 * that the rate limit holds back opens its window when the limit expires, unless an accurate
 * location arrives first. That location is then published, as the window would have done.
 *
 * In the still state, a location whose accuracy circle overlaps the last accurate place doesn't
 * request GNSS: nothing says the device left. An inaccurate location away from the place starts a
 * motion window. While the motion activity says the device is stationary, it has to be far beyond
 * both accuracy circles to count. With [motionAware], a missing location only counts as a gap after
 * a motion. Android throttles the low-power providers while the device stays still.
 *
 * GNSS requests are rate limited to one per interval of the current state, and [GnssRequestLimiter]
 * backs them off after failures.
 *
 * All times are in the [android.os.SystemClock.elapsedRealtime] timebase.
 */
class GnssFallbackPolicy(
    private val accuracyMeters: Int,
    private val intervalMillis: Long,
    private val movingIntervalMillis: Long,
    private val motionAware: Boolean,
) {
  enum class Action {
    NONE,
    REQUEST_GNSS,
    PUBLISH,
    START_MOTION_WINDOW,
  }

  /**
   * @param fixedAt when the location was fixed. A provider can hand back a cached location.
   * @param fromGnss true when the GNSS provider made the location, whoever asked for it.
   */
  data class Fix(
      val latitude: Double,
      val longitude: Double,
      val accuracy: Float,
      val fixedAt: Long,
      val fromGnss: Boolean = false,
  )

  // One interval for the next location, plus half an interval of slack for batching.
  private val staleAfterMillis = intervalMillis * 3 / 2
  private val unlocatedLimitMillis = intervalMillis * MAX_UNLOCATED_INTERVALS
  private var lastAccurate: Fix? = null
  private var place: Fix? = null
  private val gnssRequests = GnssRequestLimiter(intervalMillis)
  private val samePlace = SamePlaceTracker(movingIntervalMillis)
  private var lastMotionWindowAt: Long? = null
  private var lastMotionAt: Long? = null
  private var motionWindowStartedAt = 0L
  private var motionPending = false
  private var lastPublishAt: Long? = null
  private var stationary = false
  private var lastPreciseAt: Long? = null
  private var origin: Fix? = null
  private var displaced = false
  private var walking = false
  private var automotive = false
  private var movingSince = 0L
  private var wifiWindow = false
  private var otherSourceSince: Long? = null
  private var otherSourceLastAt: Long? = null
  private var looking = false
  private var looks = 0
  private var gnssTracking = false

  /** True between a movement hint and the first accurate location or check that follows it. */
  var inMotionWindow = false
    private set

  /** True in the moving state, which only exists with a moving interval. */
  var moving = false
    private set

  /** True when the device went still because its locations stayed at the same place. */
  var stillAtSamePlace = false
    private set

  /** True while a periodic GNSS request should replace the single fixes. */
  val wantsGnssTracking: Boolean
    get() = moving && gnssTracking

  /** When the check must run to open the window of a motion that the rate limit held back. */
  val pendingMotionWindowAt: Long?
    get() = lastMotionWindowAt?.takeIf { motionPending }?.plus(intervalMillis)

  private val movingEnabled = movingIntervalMillis > 0

  // Android delivers a location up to a tenth of the interval early, and a probe location can come
  // just before the limit. Without slack, every other one would be skipped.
  private val movingSlackMillis = movingIntervalMillis * 3 / 4

  /**
   * @param displacement true when the hint comes from the radios, which change when the device
   *   changes place. The motion sensors also fire when the device moves in place.
   * @param wifiJoined true when the device joined a Wi-Fi network.
   * @return [Action.START_MOTION_WINDOW] when the caller should look for a location now,
   *   [Action.REQUEST_GNSS] when a displacement while moving finds no recent accurate location.
   */
  fun onMovementHint(now: Long, displacement: Boolean, wifiJoined: Boolean = false): Action {
    if (displacement) {
      gnssRequests.onRadioHint(now)
    }
    val look = stillAtSamePlace && !displacement && !automotive
    if (look && lastMotionWindowAt.let { it != null && now - it < lookBackoffMillis() }) {
      return Action.NONE
    }
    lastMotionAt = now
    stationary = false
    if (moving) {
      // A new cell or network while moving means the device is not at the same place. Joining a
      // network usually means arriving at one.
      if (displacement) {
        if (!wifiJoined) {
          samePlace.reset()
        }
        markDisplaced()
      }
      if (wifiJoined && !inMotionWindow) {
        // The low-power providers can be quiet on arrival. Without a window, the next check
        // would request GNSS, often indoors.
        motionWindowStartedAt = now
        inMotionWindow = true
        wifiWindow = true
        return Action.START_MOTION_WINDOW
      }
      if (displacement && !inMotionWindow && !gnssTracking && isMovingStale(now)) {
        return gnssIfDue(now)
      }
      return Action.NONE
    }
    val lastWindow = lastMotionWindowAt
    if (!movingEnabled && lastWindow != null && now - lastWindow < intervalMillis) {
      motionPending = true
      return Action.NONE
    }
    startMotionWindow(now, displacement || automotive, look)
    return Action.START_MOTION_WINDOW
  }

  fun onLocation(fix: Fix, now: Long): Action {
    gnssRequests.settle(now)
    if (fix.accuracy <= accuracyMeters) {
      val previous = lastAccurate
      if (previous == null || fix.fixedAt > previous.fixedAt) {
        lastAccurate = fix
      }
      if (now - fix.fixedAt > currentIntervalMillis()) {
        return Action.NONE
      }
      // At the same place, the sky is as hidden as when the GNSS request failed.
      if (previous == null || !isSamePlace(previous, fix)) {
        gnssRequests.resetFailures()
      }
      if (!fix.fromGnss && endsGnssTracking(fix)) {
        gnssTracking = false
      }
      noteDisplacement(fix)
      if (fix.accuracy <= SamePlaceTracker.PRECISE_ACCURACY) {
        lastPreciseAt = fix.fixedAt
      }
      return onFreshAccurateLocation(fix, now)
    }
    if (now - fix.fixedAt > currentIntervalMillis()) {
      return Action.NONE
    }
    if (!moving && !inMotionWindow) {
      val lastPlace = place
      if (lastPlace != null && isSamePlace(lastPlace, fix)) {
        return Action.NONE
      }
      // A cell location can be far off. While the motion activity says still, only a location far
      // beyond both accuracy circles proves a move, like a smooth train ride.
      if (lastPlace != null && stationary && !isFarFrom(lastPlace, fix)) {
        return Action.NONE
      }
      if (lastPlace != null && movingEnabled) {
        startMotionWindow(now, displaced = true)
        return Action.START_MOTION_WINDOW
      }
    }
    if (moving && !displaced && (looking || !inMotionWindow)) {
      if (origin?.let { isSamePlace(it, fix) } ?: looking) {
        return Action.NONE
      }
      markDisplaced()
    }
    if (wantsGnssTracking || (moving && hasRecentAccurateLocation(now))) {
      return Action.NONE
    }
    if (requestIfDue(now)) {
      inMotionWindow = false
      return Action.REQUEST_GNSS
    }
    return Action.NONE
  }

  /**
   * How long the moving device can go without an accurate location. The low-power providers often
   * deliver later than their interval, so their last location lasts longer than a GNSS fix. A walk
   * covers little ground, and in a town GNSS often finds no fix where the network locates well.
   */
  private fun movingStaleAfterMillis(): Long =
      when {
        !displaced || walking -> staleAfterMillis
        lastAccurate?.fromGnss == false -> movingIntervalMillis * 2
        else -> movingIntervalMillis * 3 / 2
      }

  private fun isMovingStale(now: Long): Boolean =
      lastAccurate?.fixedAt.let { it == null || now - it > movingStaleAfterMillis() }

  /**
   * Unless the device walks, a single accurate location from another source keeps GNSS tracking. It
   * ends when such locations keep coming for most of a moving interval, or with the first one after
   * a Wi-Fi network was joined.
   */
  private fun endsGnssTracking(fix: Fix): Boolean {
    val since = otherSourceSince
    val last = otherSourceLastAt
    otherSourceLastAt = fix.fixedAt
    val arrived = inMotionWindow && wifiWindow && fix.fixedAt >= motionWindowStartedAt
    if (!gnssTracking || walking || arrived) {
      otherSourceSince = null
      return true
    }
    if (since == null || last == null || fix.fixedAt - last > movingIntervalMillis * 3 / 2) {
      otherSourceSince = fix.fixedAt
      return false
    }
    return fix.fixedAt - since >= movingSlackMillis
  }

  private fun onFreshAccurateLocation(fix: Fix, now: Long): Action {
    if (inMotionWindow && fix.fixedAt >= motionWindowStartedAt) {
      // The main request may hold this location back behind its displacement filter, but after a
      // movement hint it is news.
      inMotionWindow = false
      place = fix
      trackSamePlace(fix)
      lastPublishAt = now
      return Action.PUBLISH
    }
    if (moving) {
      if (trackSamePlace(fix)) {
        return Action.NONE
      }
      val lastPrecise = lastPreciseAt
      if (
          fix.accuracy > SamePlaceTracker.PRECISE_ACCURACY &&
              lastPrecise != null &&
              now - lastPrecise <= movingIntervalMillis * 3 / 2
      ) {
        // Precise locations keep coming, like with a navigation app. A coarse one in between
        // would take their turn.
        return Action.NONE
      }
      if (lastPublishAt.let { it == null || now - it >= movingSlackMillis }) {
        lastPublishAt = now
        return Action.PUBLISH
      }
      return Action.NONE
    }
    if (motionPending && fix.fixedAt >= (lastMotionAt ?: 0)) {
      motionPending = false
      place = fix
      return Action.PUBLISH
    }
    val lastPlace = place
    if (movingEnabled && lastPlace != null && !isSamePlace(lastPlace, fix)) {
      startMoving(now, displaced = true)
      place = fix
      trackSamePlace(fix)
      lastPublishAt = now
      return Action.PUBLISH
    }
    if (lastPlace == null || !isSamePlace(lastPlace, fix) || fix.accuracy <= lastPlace.accuracy) {
      place = fix
    }
    return Action.NONE
  }

  /**
   * An accurate GNSS fix locates the device as well as an accurate low-power location. An
   * inaccurate one, like a weak fix indoors, counts as a failure.
   */
  fun onGnssFix(fix: Fix, now: Long) {
    val accurate = fix.accuracy <= accuracyMeters
    gnssRequests.onFix(accurate)
    if (!accurate) {
      gnssTracking = false
      return
    }
    if (lastAccurate.let { it == null || fix.fixedAt > it.fixedAt }) {
      lastAccurate = fix
    }
    if (moving) {
      noteDisplacement(fix)
      if (fix.accuracy <= SamePlaceTracker.PRECISE_ACCURACY) {
        lastPreciseAt = fix.fixedAt
      }
      // The caller publishes the GNSS fixes.
      lastPublishAt = now
      gnssTracking = displaced
      trackSamePlace(fix)
      return
    }
    val lastPlace = place
    if (movingEnabled && lastPlace != null && !isSamePlace(lastPlace, fix)) {
      startMoving(now, displaced = true)
      trackSamePlace(fix)
      lastPublishAt = now
    }
    place = fix
  }

  /**
   * Feeds the motion activity. A stationary device ends the moving state.
   *
   * @param walking true while the device counts steps, on a walk or a run.
   * @param automotive true while the device is in a vehicle.
   */
  fun onActivity(stationary: Boolean, walking: Boolean = false, automotive: Boolean = false) {
    this.stationary = stationary
    this.walking = walking
    this.automotive = automotive
    if (automotive && moving) {
      markDisplaced()
    }
    if (!stationary) {
      return
    }
    stillAtSamePlace = false
    looks = 0
    if (moving && !inMotionWindow) {
      moving = false
      samePlace.reset()
      place = lastAccurate
    }
  }

  /** Returns [Action.REQUEST_GNSS], [Action.START_MOTION_WINDOW] or [Action.NONE]. */
  fun onCheck(now: Long): Action {
    gnssRequests.settle(now)
    if (inMotionWindow) {
      inMotionWindow = false
      if (looking) {
        return Action.NONE
      }
      // A window opened while tracking with GNSS leaves the fixes to the periodic request.
      if (!wantsGnssTracking) {
        return gnssIfDue(now)
      }
    }
    val lastAccurateAt = lastAccurate?.fixedAt
    if (moving) {
      val unlocatedLimit = if (looking) intervalMillis else unlocatedLimitMillis
      if (!gnssTracking && now - maxOf(movingSince, lastAccurateAt ?: 0) > unlocatedLimit) {
        endMovingUnlocated(now)
        return Action.NONE
      }
      if (gnssTracking) {
        val fixDue = movingIntervalMillis * 3 / 2 + GnssRequestLimiter.TIMEOUT_MILLIS
        if (lastAccurateAt != null && now - lastAccurateAt <= fixDue) {
          return Action.NONE
        }
        // The periodic request found no fix in time, like a single request that fails.
        gnssTracking = false
        gnssRequests.onTrackingTimeout(now)
        return Action.NONE
      }
      return if (isMovingStale(now)) gnssIfDue(now) else Action.NONE
    }
    val pendingAt = pendingMotionWindowAt
    if (pendingAt != null && now >= pendingAt) {
      startMotionWindow(now, displaced = false)
      return Action.START_MOTION_WINDOW
    }
    if (lastAccurateAt == null) {
      return gnssIfDue(now)
    }
    val lastMotion = lastMotionAt
    val moved = !motionAware || (lastMotion != null && lastMotion > lastAccurateAt)
    return if (now - lastAccurateAt > staleAfterMillis && moved) gnssIfDue(now) else Action.NONE
  }

  /** How often the probe and the check run outside a motion window. */
  fun currentIntervalMillis(): Long = if (moving) movingIntervalMillis else intervalMillis

  private fun startMotionWindow(now: Long, displaced: Boolean, look: Boolean = false) {
    lastMotionWindowAt = now
    motionWindowStartedAt = now
    motionPending = false
    inMotionWindow = true
    wifiWindow = false
    if (movingEnabled) {
      startMoving(now, displaced, look)
    }
  }

  /**
   * @param displaced false when only the motion sensors say the device moves.
   * @param look true for a motion after the locations stayed at the same place, or found none.
   */
  private fun startMoving(now: Long, displaced: Boolean, look: Boolean = false) {
    moving = true
    movingSince = now
    stillAtSamePlace = false
    looking = look
    gnssTracking = false
    origin = place
    // Without a known place, nothing can show that the device stayed. A look found no place.
    this.displaced = false
    if (displaced || (place == null && !look)) {
      markDisplaced()
    }
    samePlace.reset()
  }

  private fun markDisplaced() {
    displaced = true
    looking = false
    looks = 0
  }

  private fun noteDisplacement(fix: Fix) {
    if (moving && origin?.let { isSamePlace(it, fix) } == false) {
      markDisplaced()
    }
  }

  /** How long after the last window a motion at the same place starts the next look. */
  private fun lookBackoffMillis(): Long =
      when {
        looks <= 1 -> movingIntervalMillis
        looks == 2 -> movingIntervalMillis * 2
        else -> intervalMillis
      }

  /**
   * Counts accurate locations at the same place while moving. Returns true when they stayed there
   * long enough to switch to the still state.
   */
  private fun trackSamePlace(fix: Fix): Boolean {
    if (!moving) {
      return false
    }
    val first = samePlace.add(fix, walking) ?: return false
    moving = false
    inMotionWindow = false
    stillAtSamePlace = true
    if (!displaced) {
      looks++
    }
    // The first location, so a slow move away from it adds up over the next looks.
    place = first
    return true
  }

  /** Like a stop at the same place, so the next motions only start looks, spaced out. */
  private fun endMovingUnlocated(now: Long) {
    lastMotionWindowAt = now
    moving = false
    samePlace.reset()
    stillAtSamePlace = true
    looks++
    place = lastAccurate
  }

  /**
   * An accurate location since the window started, or within half a moving interval. A whole
   * interval would skip every other GNSS fix when only GNSS gives accurate locations.
   */
  private fun hasRecentAccurateLocation(now: Long): Boolean {
    val at = lastAccurate?.fixedAt ?: return false
    val since = if (inMotionWindow) motionWindowStartedAt else now - movingIntervalMillis / 2
    return at >= since
  }

  private fun isFarFrom(a: Fix, b: Fix) =
      distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude) >
          FAR_ACCURACY_FACTOR * (a.accuracy + b.accuracy)

  private fun gnssIfDue(now: Long) = if (requestIfDue(now)) Action.REQUEST_GNSS else Action.NONE

  private fun requestIfDue(now: Long): Boolean =
      gnssRequests.requestIfDue(now, if (moving && displaced) movingSlackMillis else intervalMillis)

  companion object {
    private const val SAME_PLACE_MIN_METERS = 100f
    private const val FAR_ACCURACY_FACTOR = 3
    private const val MAX_UNLOCATED_INTERVALS = 2

    /**
     * True when two locations can't be told apart: their accuracy circles overlap, or they are
     * closer than [SAME_PLACE_MIN_METERS]. A location after a movement at the same place tells
     * nobody anything new.
     */
    fun isSamePlace(distance: Float, accuracy: Float, otherAccuracy: Float): Boolean =
        distance <= maxOf(accuracy + otherAccuracy, SAME_PLACE_MIN_METERS)

    fun isSamePlace(a: Fix, b: Fix): Boolean =
        isSamePlace(
            distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude).toFloat(),
            a.accuracy,
            b.accuracy,
        )
  }
}
