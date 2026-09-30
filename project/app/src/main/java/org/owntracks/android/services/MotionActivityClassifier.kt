package org.owntracks.android.services

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Guesses what the user is doing from the step counter, the significant motion sensor and the speed
 * between locations. The names match the `motionactivities` that the iOS app publishes.
 *
 * All times are in the [android.os.SystemClock.elapsedRealtime] timebase.
 */
class MotionActivityClassifier {
  enum class Activity(val value: String) {
    STATIONARY("stationary"),
    WALKING("walking"),
    RUNNING("running"),
    AUTOMOTIVE("automotive"),
    UNKNOWN("unknown"),
  }

  private data class Steps(val at: Long, val total: Long)

  private data class Fix(
      val at: Long,
      val latitude: Double,
      val longitude: Double,
      val accuracy: Float,
  )

  private val steps = ArrayDeque<Steps>()
  private var lastMotionAt: Long? = null
  private var lastFix: Fix? = null
  private var lastSpeed: Pair<Long, Float>? = null

  fun onSteps(at: Long, total: Long) {
    if (steps.lastOrNull()?.let { at <= it.at } == true) {
      return
    }
    steps.addLast(Steps(at, total))
    while (steps.size > 1 && steps[1].at < at - STILL_AFTER_MILLIS) {
      steps.removeFirst()
    }
  }

  fun onMotion(at: Long) {
    lastMotionAt = maxOf(lastMotionAt ?: at, at)
  }

  /** @param speed the speed the provider measured, if any, in m/s. */
  fun onLocation(at: Long, latitude: Double, longitude: Double, accuracy: Float, speed: Float?) {
    val previous = lastFix
    if (previous != null && at <= previous.at) {
      return
    }
    if (speed != null) {
      lastSpeed = at to speed
    } else if (previous != null) {
      val distance = distance(previous, latitude, longitude)
      // Two fixes whose accuracy circles overlap can't tell a move from noise.
      if (distance > previous.accuracy + accuracy && at - previous.at >= MIN_SPEED_SPAN_MILLIS) {
        lastSpeed = at to (distance / ((at - previous.at) / 1000.0)).toFloat()
      }
    }
    if (accuracy <= MAX_FIX_ACCURACY) {
      lastFix = Fix(at, latitude, longitude, accuracy)
    }
  }

  /**
   * @param hasStepCounter false when the step counter is missing or not allowed. Walking can't be
   *   told apart from other motion then.
   * @param hasMotionSensor false without a significant motion sensor. Nothing can prove the device
   *   is still then.
   */
  fun classify(now: Long, hasStepCounter: Boolean, hasMotionSensor: Boolean): Activity {
    val recentSteps = stepsSince(now - STEP_WINDOW_MILLIS)
    if (hasStepCounter && recentSteps >= MIN_WALKING_STEPS) {
      return if (cadence(now) >= RUNNING_CADENCE) Activity.RUNNING else Activity.WALKING
    }
    val speed = lastSpeed?.takeIf { now - it.first <= SPEED_MAX_AGE_MILLIS }?.second
    if (speed != null && speed >= AUTOMOTIVE_SPEED) {
      return Activity.AUTOMOTIVE
    }
    val motion = lastMotionAt
    val still =
        hasMotionSensor &&
            (motion == null || now - motion > STILL_AFTER_MILLIS) &&
            (!hasStepCounter || stepsSince(now - STILL_AFTER_MILLIS) < MIN_WALKING_STEPS) &&
            (speed == null || speed < STILL_SPEED)
    return if (still) Activity.STATIONARY else Activity.UNKNOWN
  }

  private fun stepsSince(since: Long): Long {
    val last = steps.lastOrNull()?.takeIf { it.at >= since } ?: return 0
    // The last count before the window is the baseline. Without one, the first count in it is.
    val base = steps.lastOrNull { it.at < since } ?: steps.first { it.at >= since }
    return last.total - base.total
  }

  /** Steps per minute over the step counts in the step window. */
  private fun cadence(now: Long): Double {
    val recent = steps.filter { it.at >= now - STEP_WINDOW_MILLIS }
    val span = recent.last().at - recent.first().at
    if (span < MIN_CADENCE_SPAN_MILLIS) {
      return 0.0
    }
    return (recent.last().total - recent.first().total) * 60_000.0 / span
  }

  private fun distance(from: Fix, latitude: Double, longitude: Double): Double {
    val dLat = Math.toRadians(latitude - from.latitude)
    val dLon = Math.toRadians(longitude - from.longitude)
    val a =
        sin(dLat / 2).pow(2) +
            cos(Math.toRadians(from.latitude)) *
                cos(Math.toRadians(latitude)) *
                sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
  }

  companion object {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val STEP_WINDOW_MILLIS = 120_000L
    private const val STILL_AFTER_MILLIS = 300_000L
    private const val SPEED_MAX_AGE_MILLIS = 180_000L
    private const val MIN_SPEED_SPAN_MILLIS = 30_000L
    private const val MIN_CADENCE_SPAN_MILLIS = 20_000L
    private const val MIN_WALKING_STEPS = 30
    private const val RUNNING_CADENCE = 145.0
    private const val MAX_FIX_ACCURACY = 500f
    // Faster than a brisk cyclist, so bicycles mostly stay out.
    private const val AUTOMOTIVE_SPEED = 7f
    private const val STILL_SPEED = 1f
  }
}
