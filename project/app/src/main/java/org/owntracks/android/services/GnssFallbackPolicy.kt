package org.owntracks.android.services

/**
 * Decides when a location request that doesn't use GNSS needs a single GNSS fix to fill a gap.
 *
 * A gap is a location that is less accurate than [accuracyMeters], no accurate location for one and
 * a half locator intervals, or no accurate location within the window that follows a detected
 * motion. GNSS requests are rate limited to one per interval, and so are motion windows. A motion
 * that the rate limit holds back opens its window when the limit expires, unless an accurate
 * location arrives first. That location is then published, as the window would have done.
 *
 * With [motionAware], a missing location only counts as a gap after a motion. A still device keeps
 * its last accurate location, and Android throttles the low-power providers while it stays still.
 *
 * All times are in the [android.os.SystemClock.elapsedRealtime] timebase.
 */
class GnssFallbackPolicy(
    private val accuracyMeters: Int,
    private val intervalMillis: Long,
    private val motionAware: Boolean,
) {
  enum class Action {
    NONE,
    REQUEST_GNSS,
    PUBLISH,
    START_MOTION_WINDOW,
  }

  // One interval for the next low-power location, plus half an interval of slack for batching.
  private val staleAfterMillis = intervalMillis * 3 / 2
  private var lastAccurateLocationAt: Long? = null
  private var lastGnssRequestAt: Long? = null
  private var lastMotionWindowAt: Long? = null
  private var lastMotionAt: Long? = null
  private var motionWindowStartedAt = 0L
  private var motionPending = false

  /** True between a detected motion and the first accurate location or check that follows it. */
  var inMotionWindow = false
    private set

  /** When the check must run to open the window of a motion that the rate limit held back. */
  val pendingMotionWindowAt: Long?
    get() = lastMotionWindowAt?.takeIf { motionPending }?.plus(intervalMillis)

  /** Returns true when a motion window starts, so the caller should look for a location now. */
  fun onMotion(now: Long): Boolean {
    lastMotionAt = now
    val lastWindow = lastMotionWindowAt
    if (lastWindow != null && now - lastWindow < intervalMillis) {
      motionPending = true
      return false
    }
    startMotionWindow(now)
    return true
  }

  private fun startMotionWindow(now: Long) {
    lastMotionWindowAt = now
    motionWindowStartedAt = now
    motionPending = false
    inMotionWindow = true
  }

  /**
   * @param fixedAt when the location was fixed. A provider can hand back a cached location, which
   *   must not count as fresh.
   */
  fun onLocation(accuracy: Float, fixedAt: Long, now: Long): Action =
      if (accuracy <= accuracyMeters) {
        lastAccurateLocationAt = maxOf(lastAccurateLocationAt ?: fixedAt, fixedAt)
        // The main request may hold this location back behind its displacement filter, but after a
        // motion it is news.
        if (inMotionWindow && fixedAt >= motionWindowStartedAt) {
          inMotionWindow = false
          Action.PUBLISH
        } else if (motionPending && fixedAt >= (lastMotionAt ?: 0)) {
          motionPending = false
          Action.PUBLISH
        } else {
          Action.NONE
        }
      } else if (fixedAt < now - intervalMillis) {
        Action.NONE
      } else if (requestIfDue(now)) {
        inMotionWindow = false
        Action.REQUEST_GNSS
      } else {
        Action.NONE
      }

  /** A GNSS fix locates the device as well as an accurate low-power location. */
  fun onGnssFix(fixedAt: Long) {
    lastAccurateLocationAt = maxOf(lastAccurateLocationAt ?: fixedAt, fixedAt)
  }

  /** Returns [Action.REQUEST_GNSS], [Action.START_MOTION_WINDOW] or [Action.NONE]. */
  fun onCheck(now: Long): Action {
    if (inMotionWindow) {
      inMotionWindow = false
      return gnssIfDue(now)
    }
    val pendingAt = pendingMotionWindowAt
    if (pendingAt != null && now >= pendingAt) {
      startMotionWindow(now)
      return Action.START_MOTION_WINDOW
    }
    val lastAccurate = lastAccurateLocationAt ?: return gnssIfDue(now)
    val lastMotion = lastMotionAt
    val moved = !motionAware || (lastMotion != null && lastMotion > lastAccurate)
    return if (now - lastAccurate > staleAfterMillis && moved) gnssIfDue(now) else Action.NONE
  }

  private fun gnssIfDue(now: Long) = if (requestIfDue(now)) Action.REQUEST_GNSS else Action.NONE

  private fun requestIfDue(now: Long): Boolean {
    val lastRequest = lastGnssRequestAt
    if (lastRequest != null && now - lastRequest < intervalMillis) {
      return false
    }
    lastGnssRequestAt = now
    return true
  }
}
