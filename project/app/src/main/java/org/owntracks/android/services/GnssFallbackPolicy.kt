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
 * place for at least three moving intervals. The second case means the motion sensors move without
 * the device going anywhere, like on a treadmill, so only a proof of displacement switches back to
 * moving: a radio hint, or a location away from the place.
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
 * accurate fix within [GNSS_TIMEOUT_MILLIS] failed, and after two failures in a row the limit grows
 * from one to four intervals, until an accurate location arrives.
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

  /** @param fixedAt when the location was fixed. A provider can hand back a cached location. */
  data class Fix(
      val latitude: Double,
      val longitude: Double,
      val accuracy: Float,
      val fixedAt: Long,
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

  /** True between a movement hint and the first accurate location or check that follows it. */
  var inMotionWindow = false
    private set

  /** True in the moving state, which only exists with a moving interval. */
  var moving = false
    private set

  /** True when the device went still because its locations stayed at the same place. */
  var stillAtSamePlace = false
    private set

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
   * @return [Action.START_MOTION_WINDOW] when the caller should look for a location now.
   */
  fun onMovementHint(now: Long, displacement: Boolean): Action {
    if (stillAtSamePlace && !displacement) {
      return Action.NONE
    }
    lastMotionAt = now
    stationary = false
    if (moving) {
      // A new cell or network while moving means the device is not at the same place.
      if (displacement) resetSamePlace()
      return Action.NONE
    }
    val lastWindow = lastMotionWindowAt
    if (!movingEnabled && lastWindow != null && now - lastWindow < intervalMillis) {
      motionPending = true
      return Action.NONE
    }
    startMotionWindow(now)
    return Action.START_MOTION_WINDOW
  }

  fun onLocation(fix: Fix, now: Long): Action {
    settleGnssRequest(now)
    if (fix.accuracy <= accuracyMeters) {
      if (lastAccurate.let { it == null || fix.fixedAt > it.fixedAt }) {
        lastAccurate = fix
      }
      if (now - fix.fixedAt > currentIntervalMillis()) {
        return Action.NONE
      }
      gnssFailures = 0
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
        startMotionWindow(now)
        return Action.START_MOTION_WINDOW
      }
    }
    if (moving && hasRecentAccurateLocation(now)) {
      return Action.NONE
    }
    if (requestIfDue(now)) {
      inMotionWindow = false
      return Action.REQUEST_GNSS
    }
    return Action.NONE
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
      startMoving()
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
      return
    }
    gnssFailures = 0
    if (lastAccurate.let { it == null || fix.fixedAt > it.fixedAt }) {
      lastAccurate = fix
    }
    if (moving) {
      // The caller publishes every GNSS fix.
      lastPublishAt = now
      trackSamePlace(fix)
      return
    }
    val lastPlace = place
    if (movingEnabled && lastPlace != null && !isSamePlace(lastPlace, fix)) {
      startMoving()
      trackSamePlace(fix)
      lastPublishAt = now
    }
    place = fix
  }

  /** Feeds the motion activity. A stationary device ends the moving state. */
  fun onActivity(stationary: Boolean) {
    this.stationary = stationary
    if (!stationary) {
      return
    }
    stillAtSamePlace = false
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
      return gnssIfDue(now)
    }
    val lastAccurateAt = lastAccurate?.fixedAt
    if (moving) {
      val stale = lastAccurateAt == null || now - lastAccurateAt > movingIntervalMillis * 3 / 2
      return if (stale) gnssIfDue(now) else Action.NONE
    }
    val pendingAt = pendingMotionWindowAt
    if (pendingAt != null && now >= pendingAt) {
      startMotionWindow(now)
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

  private fun startMotionWindow(now: Long) {
    lastMotionWindowAt = now
    motionWindowStartedAt = now
    motionPending = false
    inMotionWindow = true
    if (movingEnabled) {
      startMoving()
    }
  }

  private fun startMoving() {
    moving = true
    stillAtSamePlace = false
    resetSamePlace()
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
    if (
        samePlaceCount >= SAME_PLACE_MIN_FIXES &&
            fix.fixedAt - start.fixedAt >= movingIntervalMillis * SAME_PLACE_MIN_INTERVALS
    ) {
      moving = false
      inMotionWindow = false
      stillAtSamePlace = true
      place = fix
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
    return true
  }

  private fun gnssLimitMillis(): Long {
    val base = if (moving) movingSlackMillis else intervalMillis
    if (gnssFailures < GNSS_FAILURES_BEFORE_BACKOFF) {
      return base
    }
    val shift = (gnssFailures - GNSS_FAILURES_BEFORE_BACKOFF).coerceAtMost(GNSS_MAX_BACKOFF_SHIFT)
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
    private const val GNSS_FAILURES_BEFORE_BACKOFF = 2
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
