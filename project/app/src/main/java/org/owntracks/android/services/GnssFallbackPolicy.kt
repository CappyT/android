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
 * displacement, a missing or inaccurate location requests GNSS as slowly as in the still state.
 *
 * While moving away from the place, an accurate GNSS fix shows that only GNSS locates the device,
 * so [wantsGnssTracking] asks for a periodic GNSS request instead of one fix per check. It ends
 * with the moving state, with an accurate location from another source, or without a fix in time.
 * In a vehicle, the locations from another source must keep coming for most of a moving interval: a
 * single one is often a Wi-Fi scan along the road. A Wi-Fi network joined while moving opens a
 * window: the network locates the device without GNSS.
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
 * GNSS requests are rate limited to one per interval of the current state. A request that gets no
 * accurate fix within [GNSS_TIMEOUT_MILLIS] failed: the sky is probably hidden. The next request
 * then waits one interval, growing to four with more failures, until a GNSS fix or an accurate
 * location at another place. A radio hint allows one request sooner.
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
  private var lastAccurate: Fix? = null
  private var place: Fix? = null
  private var lastGnssRequestAt: Long? = null
  private var gnssPendingSince: Long? = null
  private var gnssFailures = 0
  private var lastMotionWindowAt: Long? = null
  private var lastMotionAt: Long? = null
  private var motionWindowStartedAt = 0L
  private var motionPending = false
  private var lastPublishAt: Long? = null
  private var samePlaceStart: Fix? = null
  private var samePlaceLast: Fix? = null
  private var samePlaceCount = 0
  private var stationary = false
  private var lastPreciseAt: Long? = null
  private var origin: Fix? = null
  private var displaced = false
  private var walking = false
  private var automotive = false
  private var otherSourceSince: Long? = null
  private var otherSourceLastAt: Long? = null
  private var looking = false
  private var looks = 0
  private var gnssTracking = false
  private var gnssRetryGranted = false

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
   * @return [Action.START_MOTION_WINDOW] when the caller should look for a location now.
   */
  fun onMovementHint(now: Long, displacement: Boolean, wifiJoined: Boolean = false): Action {
    if (displacement && gnssFailures > 0) {
      gnssRetryGranted = true
    }
    val look = stillAtSamePlace && !displacement
    if (look && lastMotionWindowAt.let { it != null && now - it < lookBackoffMillis() }) {
      return Action.NONE
    }
    lastMotionAt = now
    stationary = false
    if (moving) {
      // A new cell or network while moving means the device is not at the same place.
      if (displacement) {
        resetSamePlace()
        markDisplaced()
      }
      if (wifiJoined && !inMotionWindow) {
        // The low-power providers can be quiet on arrival. Without a window, the next check
        // would request GNSS, often indoors.
        motionWindowStartedAt = now
        inMotionWindow = true
        return Action.START_MOTION_WINDOW
      }
      return Action.NONE
    }
    val lastWindow = lastMotionWindowAt
    if (!movingEnabled && lastWindow != null && now - lastWindow < intervalMillis) {
      motionPending = true
      return Action.NONE
    }
    startMotionWindow(now, displacement)
    looking = look
    return Action.START_MOTION_WINDOW
  }

  fun onLocation(fix: Fix, now: Long): Action {
    settleGnssRequest(now)
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
        gnssFailures = 0
      }
      if (!fix.fromGnss && endsGnssTracking(fix)) {
        gnssTracking = false
      }
      noteDisplacement(fix)
      if (fix.accuracy <= SAME_PLACE_PRECISE_ACCURACY) {
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
      if (origin?.let { isSamePlace(it, fix) } == true) {
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
   * In a vehicle, a single accurate location from another source keeps GNSS tracking. It ends when
   * such locations keep coming for most of a moving interval.
   */
  private fun endsGnssTracking(fix: Fix): Boolean {
    val since = otherSourceSince
    val last = otherSourceLastAt
    otherSourceLastAt = fix.fixedAt
    if (!gnssTracking || !automotive) {
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
          fix.accuracy > SAME_PLACE_PRECISE_ACCURACY &&
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
      startMoving(displaced = true)
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
    gnssPendingSince = null
    if (fix.accuracy > accuracyMeters) {
      gnssFailures++
      gnssTracking = false
      return
    }
    gnssFailures = 0
    if (lastAccurate.let { it == null || fix.fixedAt > it.fixedAt }) {
      lastAccurate = fix
    }
    if (moving) {
      noteDisplacement(fix)
      if (fix.accuracy <= SAME_PLACE_PRECISE_ACCURACY) {
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
      startMoving(displaced = true)
      trackSamePlace(fix)
      lastPublishAt = now
    }
    place = fix
  }

  /**
   * Feeds the motion activity. A stationary device ends the moving state.
   *
   * @param walking true while the device counts steps, on a walk or a run.
   * @param automotive true while the device travels in a vehicle.
   */
  fun onActivity(stationary: Boolean, walking: Boolean = false, automotive: Boolean = false) {
    this.stationary = stationary
    this.walking = walking
    this.automotive = automotive
    if (!stationary) {
      return
    }
    stillAtSamePlace = false
    looks = 0
    if (moving && !inMotionWindow) {
      moving = false
      resetSamePlace()
      place = lastAccurate
    }
  }

  /** Returns [Action.REQUEST_GNSS], [Action.START_MOTION_WINDOW] or [Action.NONE]. */
  fun onCheck(now: Long): Action {
    settleGnssRequest(now)
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
      if (gnssTracking) {
        val fixDue = movingIntervalMillis * 3 / 2 + GNSS_TIMEOUT_MILLIS
        if (lastAccurateAt != null && now - lastAccurateAt <= fixDue) {
          return Action.NONE
        }
        // The periodic request found no fix in time, like a single request that fails.
        gnssTracking = false
        gnssFailures++
        lastGnssRequestAt = now
        return Action.NONE
      }
      val staleAfter = if (displaced) movingIntervalMillis * 3 / 2 else staleAfterMillis
      val stale = lastAccurateAt == null || now - lastAccurateAt > staleAfter
      return if (stale) gnssIfDue(now) else Action.NONE
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

  private fun startMotionWindow(now: Long, displaced: Boolean) {
    lastMotionWindowAt = now
    motionWindowStartedAt = now
    motionPending = false
    inMotionWindow = true
    if (movingEnabled) {
      startMoving(displaced)
    }
  }

  /** @param displaced false when only the motion sensors say the device moves. */
  private fun startMoving(displaced: Boolean) {
    moving = true
    stillAtSamePlace = false
    looking = false
    gnssTracking = false
    origin = place
    // Without a known place, nothing can show that the device stayed.
    this.displaced = false
    if (displaced || place == null) {
      markDisplaced()
    }
    resetSamePlace()
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
    val last = samePlaceLast
    if (last != null && fix.fixedAt <= last.fixedAt) {
      return false
    }
    val start = samePlaceStart
    if (start == null || !isSamePlaceWhileMoving(start, fix)) {
      samePlaceStart = fix
      samePlaceLast = fix
      samePlaceCount = 1
      return false
    }
    samePlaceLast = fix
    samePlaceCount++
    val span = fix.fixedAt - start.fixedAt
    // A walk covers less than the same place radius between two locations.
    val stayed =
        if (walking) {
          samePlaceCount >= SAME_PLACE_MIN_FIXES &&
              span >= movingIntervalMillis * SAME_PLACE_MIN_INTERVALS
        } else {
          span >= movingSlackMillis
        }
    if (stayed) {
      moving = false
      inMotionWindow = false
      stillAtSamePlace = true
      if (!displaced) {
        looks++
      }
      // The first location, so a slow move away from it adds up over the next looks.
      place = start
      resetSamePlace()
      return true
    }
    return false
  }

  /**
   * Precise locations must stay within [SAME_PLACE_RADIUS_METERS], so a walk keeps moving. Less
   * precise ones can't tell a walk from noise, and only their accuracy circles must overlap: GNSS
   * indoors gives such fixes, and moving would request one every interval.
   */
  private fun isSamePlaceWhileMoving(start: Fix, fix: Fix): Boolean =
      if (
          start.accuracy <= SAME_PLACE_PRECISE_ACCURACY &&
              fix.accuracy <= SAME_PLACE_PRECISE_ACCURACY
      ) {
        distanceMeters(start.latitude, start.longitude, fix.latitude, fix.longitude) <=
            SAME_PLACE_RADIUS_METERS
      } else {
        isSamePlace(start, fix)
      }

  private fun resetSamePlace() {
    samePlaceStart = null
    samePlaceLast = null
    samePlaceCount = 0
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

  private fun isSamePlace(a: Fix, b: Fix) =
      isSamePlace(
          distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude).toFloat(),
          a.accuracy,
          b.accuracy,
      )

  private fun settleGnssRequest(now: Long) {
    val since = gnssPendingSince ?: return
    if (now - since > GNSS_TIMEOUT_MILLIS) {
      gnssPendingSince = null
      gnssFailures++
    }
  }

  private fun gnssIfDue(now: Long) = if (requestIfDue(now)) Action.REQUEST_GNSS else Action.NONE

  private fun requestIfDue(now: Long): Boolean {
    val lastRequest = lastGnssRequestAt
    if (lastRequest != null && now - lastRequest < gnssLimitMillis()) {
      return false
    }
    lastGnssRequestAt = now
    gnssPendingSince = now
    gnssRetryGranted = false
    return true
  }

  private fun gnssLimitMillis(): Long {
    val base = if (moving && displaced) movingSlackMillis else intervalMillis
    if (gnssFailures == 0 || gnssRetryGranted) {
      return base
    }
    val shift = (gnssFailures - 1).coerceAtMost(GNSS_MAX_BACKOFF_SHIFT)
    return maxOf(base, intervalMillis shl shift)
  }

  companion object {
    private const val SAME_PLACE_MIN_METERS = 100f
    private const val SAME_PLACE_RADIUS_METERS = 100.0
    private const val SAME_PLACE_PRECISE_ACCURACY = 100f
    private const val SAME_PLACE_MIN_FIXES = 4
    private const val SAME_PLACE_MIN_INTERVALS = 3
    // Android's getCurrentLocation gives up after 30 seconds.
    const val GNSS_TIMEOUT_MILLIS = 40_000L
    // One, two, then four intervals.
    private const val GNSS_MAX_BACKOFF_SHIFT = 2
    private const val FAR_ACCURACY_FACTOR = 3

    /**
     * True when two locations can't be told apart: their accuracy circles overlap, or they are
     * closer than [SAME_PLACE_MIN_METERS]. A location after a movement at the same place tells
     * nobody anything new.
     */
    fun isSamePlace(distance: Float, accuracy: Float, otherAccuracy: Float): Boolean =
        distance <= maxOf(accuracy + otherAccuracy, SAME_PLACE_MIN_METERS)
  }
}
