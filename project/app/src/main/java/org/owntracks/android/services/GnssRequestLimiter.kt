package org.owntracks.android.services

/**
 * Rate limits the GNSS requests of [GnssFallbackPolicy] and backs off after failures.
 *
 * A request that gets no accurate fix within [TIMEOUT_MILLIS] failed: the sky is probably hidden.
 * The next request then waits one interval, growing to four with more failures, until a GNSS fix or
 * an accurate location at another place. A radio hint allows one request sooner, up to
 * [MAX_HINT_RETRY_FAILURES] failures: in a subway or a tunnel, each new cell would search again.
 *
 * All times are in the [android.os.SystemClock.elapsedRealtime] timebase.
 */
class GnssRequestLimiter(private val intervalMillis: Long) {
  private var lastRequestAt: Long? = null
  private var pendingSince: Long? = null
  private var failures = 0
  private var retryGranted = false

  /** Counts a request that got no answer in time as a failure. */
  fun settle(now: Long) {
    val since = pendingSince ?: return
    if (now - since > TIMEOUT_MILLIS) {
      pendingSince = null
      failures++
    }
  }

  /**
   * Returns true and records the request when it is due.
   *
   * @param limitMillis the time between two requests while they don't fail.
   */
  fun requestIfDue(now: Long, limitMillis: Long): Boolean {
    settle(now)
    val lastRequest = lastRequestAt
    if (lastRequest != null && now - lastRequest < backedOff(limitMillis)) {
      return false
    }
    lastRequestAt = now
    pendingSince = now
    retryGranted = false
    return true
  }

  /** An inaccurate fix, like a weak one indoors, counts as a failure. */
  fun onFix(accurate: Boolean) {
    pendingSince = null
    if (accurate) failures = 0 else failures++
  }

  /** A periodic request that found no fix in time failed like a single request. */
  fun onTrackingTimeout(now: Long) {
    failures++
    lastRequestAt = now
  }

  /** An accurate location at another place: the sky can be open there. */
  fun resetFailures() {
    failures = 0
  }

  fun onRadioHint(now: Long) {
    // A request that failed before this hint must count, or the hints never reach the limit.
    settle(now)
    if (failures in 1..MAX_HINT_RETRY_FAILURES) {
      retryGranted = true
    }
  }

  private fun backedOff(limitMillis: Long): Long {
    if (failures == 0 || retryGranted) {
      return limitMillis
    }
    val shift = (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
    return maxOf(limitMillis, intervalMillis shl shift)
  }

  companion object {
    // Android's getCurrentLocation gives up after 30 seconds.
    const val TIMEOUT_MILLIS = 40_000L
    // One, two, then four intervals.
    private const val MAX_BACKOFF_SHIFT = 2
    private const val MAX_HINT_RETRY_FAILURES = 2
  }
}
