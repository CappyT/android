package org.owntracks.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.services.GnssFallbackPolicy.Action
import org.owntracks.android.services.GnssFallbackPolicy.Fix

class GnssFallbackPolicyTest {
  private val interval = 300_000L
  private val movingInterval = 60_000L

  private fun fix(accuracy: Float, at: Long, northMeters: Double = 0.0) =
      Fix(45.0 + northMeters / METERS_PER_DEGREE, 10.0, accuracy, at)

  private fun GnssFallbackPolicy.fresh(accuracy: Float, now: Long, northMeters: Double = 0.0) =
      onLocation(fix(accuracy, now, northMeters), now)

  private fun GnssFallbackPolicy.onMotion(now: Long) = onMovementHint(now, displacement = false)

  private fun GnssFallbackPolicy.onRadio(now: Long) = onMovementHint(now, displacement = true)

  private fun windowless(motionAware: Boolean = false) =
      GnssFallbackPolicy(400, interval, 0, motionAware)

  /** A policy in the moving state, after the window of a motion at 0 found a location. */
  private fun movingPolicy(): GnssFallbackPolicy {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(0))
    assertEquals(Action.PUBLISH, policy.fresh(20f, 1_000))
    assertTrue(policy.moving)
    return policy
  }

  @Test
  fun `An accurate location does not request GNSS`() {
    assertEquals(Action.NONE, windowless().fresh(30f, 0))
  }

  @Test
  fun `An inaccurate location requests GNSS`() {
    assertEquals(Action.REQUEST_GNSS, windowless().fresh(1200f, 0))
  }

  @Test
  fun `An old inaccurate location does not request GNSS`() {
    val policy = windowless()
    assertEquals(Action.NONE, policy.onLocation(fix(1200f, 0), interval + 1))
  }

  @Test
  fun `GNSS requests are rate limited to one per interval`() {
    val policy = windowless()
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1200f, 0))
    policy.onGnssFix(fix(1200f, 5_000), 5_000)
    assertEquals(Action.NONE, policy.fresh(1200f, interval - 1))
    assertEquals(Action.NONE, policy.onCheck(interval - 1))
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1200f, interval))
  }

  @Test
  fun `A check without any location requests GNSS`() {
    assertEquals(Action.REQUEST_GNSS, windowless().onCheck(interval))
  }

  @Test
  fun `A check within one and a half intervals of an accurate location does not request GNSS`() {
    val policy = windowless()
    policy.fresh(30f, 0)
    assertEquals(Action.NONE, policy.onCheck(interval))
    assertEquals(Action.NONE, policy.onCheck(interval * 3 / 2))
  }

  @Test
  fun `A check after one and a half intervals without an accurate location requests GNSS`() {
    val policy = windowless()
    policy.fresh(30f, 0)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 3 / 2 + 1))
  }

  @Test
  fun `A cached accurate location does not hide a gap`() {
    val policy = windowless()
    policy.fresh(30f, 0)
    policy.onLocation(fix(30f, 0), interval)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 3 / 2 + 1))
  }

  @Test
  fun `The first fresh accurate location after a motion is published`() {
    val policy = windowless()
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(1_000))
    assertEquals(Action.NONE, policy.onLocation(fix(30f, 0), 1_000))
    assertEquals(Action.PUBLISH, policy.fresh(30f, 2_000))
    assertFalse(policy.inMotionWindow)
    assertEquals(Action.NONE, policy.fresh(30f, 3_000))
  }

  @Test
  fun `A motion window without an accurate location requests GNSS at the check`() {
    val policy = windowless()
    policy.fresh(30f, 0)
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(1_000))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(61_000))
    assertFalse(policy.inMotionWindow)
  }

  @Test
  fun `Without a moving interval motion windows are rate limited to one per interval`() {
    val policy = windowless()
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(0))
    policy.fresh(30f, 1_000)
    assertEquals(Action.NONE, policy.onMotion(interval - 1))
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(interval))
  }

  @Test
  fun `A motion aware check does not request GNSS while the device is still`() {
    val policy = windowless(motionAware = true)
    policy.fresh(30f, 0)
    assertEquals(Action.NONE, policy.onCheck(interval * 4))
  }

  @Test
  fun `A motion aware check requests GNSS after a motion without an accurate location`() {
    val policy = windowless(motionAware = true)
    policy.fresh(30f, 0)
    policy.onMotion(1_000)
    policy.onCheck(61_000)
    assertEquals(Action.NONE, policy.onCheck(interval))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 2))
  }

  @Test
  fun `A motion aware check without any location requests GNSS`() {
    assertEquals(Action.REQUEST_GNSS, windowless(motionAware = true).onCheck(interval))
  }

  @Test
  fun `A GNSS fix needs a new motion before the next check requests GNSS`() {
    val policy = windowless(motionAware = true)
    policy.fresh(30f, 0)
    policy.onMotion(1_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(61_000))
    policy.onGnssFix(fix(30f, 70_000), 70_000)
    assertEquals(Action.NONE, policy.onCheck(interval * 3))
    policy.onMotion(interval * 3)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 3 + 60_000))
  }

  @Test
  fun `A held back motion opens its window when the rate limit expires`() {
    val policy = windowless(motionAware = true)
    policy.onMotion(0)
    policy.fresh(30f, 1_000)
    assertEquals(Action.NONE, policy.onMotion(200_000))
    assertEquals(interval, policy.pendingMotionWindowAt)
    assertEquals(Action.NONE, policy.onCheck(interval - 1))
    assertEquals(Action.START_MOTION_WINDOW, policy.onCheck(interval))
    assertTrue(policy.inMotionWindow)
    assertEquals(null, policy.pendingMotionWindowAt)
    assertEquals(Action.PUBLISH, policy.fresh(30f, interval + 2_000))
  }

  @Test
  fun `A held back motion window without an accurate location requests GNSS at the check`() {
    val policy = windowless(motionAware = true)
    policy.onMotion(0)
    policy.fresh(30f, 1_000)
    policy.onMotion(200_000)
    assertEquals(Action.START_MOTION_WINDOW, policy.onCheck(interval))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval + 60_000))
  }

  @Test
  fun `An accurate location after a held back motion is published and cancels its window`() {
    val policy = windowless(motionAware = true)
    policy.onMotion(0)
    policy.fresh(30f, 1_000)
    policy.onMotion(200_000)
    assertEquals(Action.NONE, policy.onLocation(fix(30f, 190_000), 210_000))
    assertEquals(interval, policy.pendingMotionWindowAt)
    assertEquals(Action.PUBLISH, policy.fresh(30f, 220_000))
    assertEquals(null, policy.pendingMotionWindowAt)
    assertEquals(Action.NONE, policy.fresh(30f, 230_000))
    assertEquals(Action.NONE, policy.onCheck(interval))
  }

  @Test
  fun `A motion during a window is held back`() {
    val policy = windowless(motionAware = true)
    policy.onMotion(0)
    assertEquals(Action.NONE, policy.onMotion(30_000))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(60_000))
    assertEquals(interval, policy.pendingMotionWindowAt)
  }

  @Test
  fun `Locations within their accuracy circles are the same place`() {
    assertTrue(GnssFallbackPolicy.isSamePlace(150f, 100f, 80f))
    assertFalse(GnssFallbackPolicy.isSamePlace(200f, 100f, 80f))
  }

  @Test
  fun `Accurate locations closer than 100 m are the same place`() {
    assertTrue(GnssFallbackPolicy.isSamePlace(6f, 6f, 7f))
    assertTrue(GnssFallbackPolicy.isSamePlace(100f, 6f, 7f))
    assertFalse(GnssFallbackPolicy.isSamePlace(101f, 6f, 7f))
  }

  // The still state keeps its place.

  @Test
  fun `An inaccurate location that overlaps the place does not request GNSS`() {
    val policy = windowless()
    policy.fresh(20f, 0)
    assertEquals(Action.NONE, policy.fresh(1647f, 1_000, northMeters = 800.0))
  }

  @Test
  fun `An inaccurate location away from the place starts moving`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    policy.fresh(20f, 0)
    assertEquals(Action.START_MOTION_WINDOW, policy.fresh(1000f, 1_000, northMeters = 3_000.0))
    assertTrue(policy.moving)
  }

  @Test
  fun `An inaccurate location away from the place proves nothing while stationary`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    policy.fresh(20f, 0)
    policy.onActivity(stationary = true)
    assertEquals(Action.NONE, policy.fresh(1000f, 1_000, northMeters = 3_000.0))
    assertFalse(policy.moving)
  }

  @Test
  fun `An accurate location away from the place is published and starts moving`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    policy.fresh(20f, 0)
    assertEquals(Action.PUBLISH, policy.fresh(20f, 1_000, northMeters = 500.0))
    assertTrue(policy.moving)
    assertFalse(policy.inMotionWindow)
  }

  // The moving state.

  @Test
  fun `A motion starts moving with a window`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(0))
    assertTrue(policy.moving)
    assertTrue(policy.inMotionWindow)
    assertEquals(movingInterval, policy.currentIntervalMillis())
  }

  @Test
  fun `Moving keeps going after the window`() {
    val policy = movingPolicy()
    assertFalse(policy.inMotionWindow)
    assertTrue(policy.moving)
  }

  @Test
  fun `Motion windows are not rate limited while moving is enabled`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = true)
    assertFalse(policy.moving)
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(10_000))
  }

  @Test
  fun `Moving publishes at most one accurate location per moving interval`() {
    val policy = movingPolicy()
    assertEquals(Action.NONE, policy.fresh(10f, 2_000, northMeters = 30.0))
    assertEquals(Action.NONE, policy.fresh(10f, 60_999, northMeters = 1_500.0))
    assertEquals(Action.PUBLISH, policy.fresh(10f, 61_000, northMeters = 1_530.0))
  }

  @Test
  fun `An inaccurate location right after an accurate one does not request GNSS while moving`() {
    val policy = movingPolicy()
    assertEquals(Action.NONE, policy.fresh(1647f, 2_000, northMeters = 300.0))
  }

  @Test
  fun `An inaccurate location without a recent accurate one requests GNSS while moving`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1647f, 62_000, northMeters = 1_000.0))
  }

  @Test
  fun `A moving check requests GNSS after one and a half moving intervals without a location`() {
    val policy = movingPolicy()
    assertEquals(Action.NONE, policy.onCheck(91_000))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(91_001))
  }

  @Test
  fun `GNSS requests are rate limited to one per moving interval while moving`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1647f, 62_000, northMeters = 1_000.0))
    policy.onGnssFix(fix(15f, 65_000, northMeters = 1_000.0), 65_000)
    assertEquals(Action.NONE, policy.fresh(1647f, 121_999, northMeters = 2_000.0))
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1647f, 126_000, northMeters = 2_000.0))
  }

  @Test
  fun `A stationary activity ends moving and keeps the last accurate location as the place`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = true)
    assertFalse(policy.moving)
    assertFalse(policy.stillAtSamePlace)
    assertEquals(interval, policy.currentIntervalMillis())
    assertEquals(Action.NONE, policy.fresh(1647f, 2_000, northMeters = 800.0))
  }

  @Test
  fun `A stationary activity does not end a motion window`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    policy.onMotion(0)
    policy.onActivity(stationary = true)
    assertTrue(policy.moving)
    assertTrue(policy.inMotionWindow)
  }

  // Same place while moving.

  /** Feeds accurate locations at the same place every moving interval, from [from] on. */
  private fun GnssFallbackPolicy.stayAt(from: Long, count: Int, accuracy: Float = 20f) {
    for (i in 0 until count) {
      fresh(accuracy, from + i * movingInterval, northMeters = (i % 2) * 40.0)
    }
  }

  @Test
  fun `Four locations at the same place over three moving intervals end moving`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 3)
    assertTrue(policy.moving)
    policy.stayAt(from = 2_000 + 3 * movingInterval, count = 1)
    assertFalse(policy.moving)
    assertTrue(policy.stillAtSamePlace)
  }

  @Test
  fun `Locations from a navigation app in a few seconds do not end moving`() {
    val policy = movingPolicy()
    for (at in 2_000L..60_000L step 1_000) {
      policy.fresh(5f, at)
    }
    assertTrue(policy.moving)
  }

  @Test
  fun `A slow walk stays moving`() {
    val policy = movingPolicy()
    for (i in 1..10) {
      // 0.8 m/s, every moving interval.
      policy.fresh(20f, 1_000 + i * movingInterval, northMeters = i * 48.0)
    }
    assertTrue(policy.moving)
  }

  @Test
  fun `Locations less accurate than 100 m do not count as the same place`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 6, accuracy = 150f)
    assertTrue(policy.moving)
  }

  @Test
  fun `A radio hint while moving restarts the same place count`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 3)
    policy.onRadio(130_000)
    policy.stayAt(from = 2_000 + 3 * movingInterval, count = 1)
    assertTrue(policy.moving)
  }

  @Test
  fun `Still at the same place ignores motion`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 4)
    assertEquals(Action.NONE, policy.onMotion(300_000))
    assertFalse(policy.moving)
    assertEquals(Action.NONE, policy.onCheck(300_000 + interval * 4))
  }

  @Test
  fun `Still at the same place starts moving on a radio hint`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 4)
    assertEquals(Action.START_MOTION_WINDOW, policy.onRadio(300_000))
    assertTrue(policy.moving)
    assertFalse(policy.stillAtSamePlace)
  }

  @Test
  fun `Still at the same place starts moving on a location away from the place`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 4)
    assertEquals(Action.PUBLISH, policy.fresh(20f, 300_000, northMeters = 400.0))
    assertTrue(policy.moving)
  }

  @Test
  fun `A stationary activity turns still at the same place into plain still`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 4)
    policy.onActivity(stationary = true)
    assertFalse(policy.stillAtSamePlace)
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(400_000))
  }

  // GNSS failures.

  @Test
  fun `A GNSS request without a fix fails after the timeout`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(160_000))
    // Two failures: the next request waits one still interval after the last one.
    assertEquals(Action.NONE, policy.onCheck(220_000))
    assertEquals(Action.NONE, policy.onCheck(160_000 + interval - 1))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(160_000 + interval))
  }

  @Test
  fun `The GNSS limit grows to four intervals after repeated failures`() {
    val policy = movingPolicy()
    var at = 100_000L
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(at))
    at += movingInterval
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(at))
    for (gap in listOf(interval, interval * 2, interval * 4, interval * 4)) {
      assertEquals(Action.NONE, policy.onCheck(at + gap - 1))
      at += gap
      assertEquals(Action.REQUEST_GNSS, policy.onCheck(at))
    }
  }

  @Test
  fun `An accurate location resets the GNSS failures`() {
    val policy = movingPolicy()
    policy.onCheck(100_000)
    policy.onCheck(160_000)
    policy.fresh(20f, 210_000, northMeters = 2_000.0)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(210_000 + movingInterval * 3 / 2 + 1))
  }

  @Test
  fun `A GNSS fix in time is not a failure`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    policy.onGnssFix(fix(500f, 110_000), 110_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(200_001))
    policy.onGnssFix(fix(500f, 205_000), 205_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(295_001))
  }

  companion object {
    private const val METERS_PER_DEGREE = 111_195.0
  }
}
