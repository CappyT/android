package org.owntracks.android.services

import org.owntracks.android.services.GnssFallbackPolicy.Fix

/**
 * Counts the accurate locations of a moving device that stay at the same place, to tell when it
 * stopped: two of them most of a moving interval apart, or four over three moving intervals while
 * the device walks, because a walk covers little ground between two locations.
 */
class SamePlaceTracker(private val movingIntervalMillis: Long) {
  private var start: Fix? = null
  private var last: Fix? = null
  private var count = 0

  /** Returns the first location at the place when the device stayed there long enough. */
  fun add(fix: Fix, walking: Boolean): Fix? {
    val previous = last
    if (previous != null && fix.fixedAt <= previous.fixedAt) {
      return null
    }
    val first = start
    if (first == null || !isSamePlaceWhileMoving(first, fix)) {
      start = fix
      last = fix
      count = 1
      return null
    }
    last = fix
    count++
    val span = fix.fixedAt - first.fixedAt
    val stayed =
        if (walking) {
          count >= MIN_FIXES && span >= movingIntervalMillis * MIN_INTERVALS
        } else {
          span >= movingIntervalMillis * 3 / 4
        }
    if (!stayed) {
      return null
    }
    reset()
    return first
  }

  fun reset() {
    start = null
    last = null
    count = 0
  }

  /**
   * Precise locations must stay within [RADIUS_METERS], so a walk keeps moving. Less precise ones
   * can't tell a walk from noise, and only their accuracy circles must overlap: GNSS indoors gives
   * such fixes, and moving would request one every interval.
   */
  private fun isSamePlaceWhileMoving(first: Fix, fix: Fix): Boolean =
      if (first.accuracy <= PRECISE_ACCURACY && fix.accuracy <= PRECISE_ACCURACY) {
        distanceMeters(first.latitude, first.longitude, fix.latitude, fix.longitude) <=
            RADIUS_METERS
      } else {
        GnssFallbackPolicy.isSamePlace(first, fix)
      }

  companion object {
    /** Locations at least this accurate tell a walk from a stop. */
    const val PRECISE_ACCURACY = 100f
    private const val RADIUS_METERS = 100.0
    private const val MIN_FIXES = 4
    private const val MIN_INTERVALS = 3
  }
}
