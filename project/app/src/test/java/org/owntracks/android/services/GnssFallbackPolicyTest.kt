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
    assertEquals(Action.NONE, policy.fresh(1000f, 1_000, northMeters = 2_000.0))
    assertFalse(policy.moving)
  }

  @Test
  fun `A far inaccurate location proves a move even while stationary`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    policy.fresh(20f, 0)
    policy.onActivity(stationary = true)
    assertEquals(Action.START_MOTION_WINDOW, policy.fresh(1000f, 1_000, northMeters = 30_000.0))
    assertTrue(policy.moving)
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
  fun `Moving publishes at most one accurate location per moving interval, with some slack`() {
    val policy = movingPolicy()
    assertEquals(Action.NONE, policy.fresh(10f, 2_000, northMeters = 30.0))
    assertEquals(Action.NONE, policy.fresh(10f, 45_999, northMeters = 1_500.0))
    assertEquals(Action.PUBLISH, policy.fresh(10f, 46_000, northMeters = 1_530.0))
  }

  @Test
  fun `A GNSS fix while moving takes the publish turn`() {
    val policy = movingPolicy()
    policy.onGnssFix(fix(15f, 50_000, northMeters = 800.0), 50_000)
    assertEquals(Action.NONE, policy.fresh(10f, 60_000, northMeters = 900.0))
  }

  @Test
  fun `Where only GNSS is accurate, a GNSS fix while moving starts GNSS tracking`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1647f, 56_000, northMeters = 1_000.0))
    assertFalse(policy.wantsGnssTracking)
    policy.onGnssFix(fix(15f, 61_000, northMeters = 1_000.0), 61_000)
    assertTrue(policy.wantsGnssTracking)
    for (k in 2..5) {
      val at = 1_000 + k * 60_000L
      assertEquals(Action.NONE, policy.fresh(1647f, at, northMeters = k * 1_000.0))
      assertEquals(Action.NONE, policy.onCheck(at + 1_000))
      policy.onGnssFix(fix(15f, at + 5_000, northMeters = k * 1_000.0), at + 5_000)
    }
    assertTrue(policy.wantsGnssTracking)
  }

  @Test
  fun `An accurate location from another source ends GNSS tracking`() {
    val policy = movingPolicy()
    policy.onGnssFix(fix(15f, 61_000, northMeters = 1_000.0), 61_000)
    // Another app's GNSS fix comes through the passive provider.
    policy.onLocation(fix(15f, 70_000, northMeters = 1_200.0).copy(fromGnss = true), 70_000)
    assertTrue(policy.wantsGnssTracking)
    policy.fresh(20f, 121_000, northMeters = 2_000.0)
    assertFalse(policy.wantsGnssTracking)
  }

  @Test
  fun `In a vehicle, a single location from another source keeps GNSS tracking`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = false, automotive = true)
    policy.onGnssFix(fix(15f, 61_000, northMeters = 1_000.0), 61_000)
    policy.fresh(20f, 100_000, northMeters = 2_000.0)
    assertTrue(policy.wantsGnssTracking)
    policy.onGnssFix(fix(15f, 121_000, northMeters = 3_000.0), 121_000)
    // The next one comes too late to belong to the same run.
    policy.fresh(20f, 100_000 + 90_001, northMeters = 5_000.0)
    assertTrue(policy.wantsGnssTracking)
  }

  @Test
  fun `In a vehicle, locations from another source that keep coming end GNSS tracking`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = false, automotive = true)
    policy.onGnssFix(fix(15f, 61_000, northMeters = 1_000.0), 61_000)
    policy.fresh(20f, 100_000, northMeters = 2_000.0)
    policy.fresh(20f, 144_999, northMeters = 2_500.0)
    assertTrue(policy.wantsGnssTracking)
    policy.fresh(20f, 145_000, northMeters = 2_500.0)
    assertFalse(policy.wantsGnssTracking)
  }

  @Test
  fun `GNSS tracking without a fix in time ends as a failed request`() {
    val policy = movingPolicy()
    policy.onGnssFix(fix(15f, 61_000, northMeters = 1_000.0), 61_000)
    assertEquals(Action.NONE, policy.onCheck(61_000 + 130_000))
    assertTrue(policy.wantsGnssTracking)
    assertEquals(Action.NONE, policy.onCheck(61_000 + 130_001))
    assertFalse(policy.wantsGnssTracking)
    assertEquals(Action.NONE, policy.onCheck(61_000 + 130_000 + interval))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(61_000 + 130_001 + interval))
  }

  @Test
  fun `A GNSS fix at the known place does not start GNSS tracking`() {
    val policy = movingAtKnownPlace()
    policy.onGnssFix(fix(15f, 61_000), 61_000)
    assertFalse(policy.wantsGnssTracking)
  }

  @Test
  fun `GNSS tracking ends with the moving state`() {
    val policy = movingPolicy()
    policy.onGnssFix(fix(15f, 61_000, northMeters = 1_000.0), 61_000)
    policy.onGnssFix(fix(15f, 121_000, northMeters = 1_010.0), 121_000)
    assertFalse(policy.moving)
    assertFalse(policy.wantsGnssTracking)
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
    policy.fresh(20f, 70_000, northMeters = 1_300.0)
    assertEquals(Action.NONE, policy.fresh(1647f, 106_999, northMeters = 2_000.0))
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1647f, 107_000, northMeters = 2_000.0))
  }

  // Moving without a proof of displacement.

  /** A policy at a known place, moving after a motion whose window found a location there. */
  private fun movingAtKnownPlace(): GnssFallbackPolicy {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    assertEquals(Action.NONE, policy.fresh(20f, 0))
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(1_000))
    assertEquals(Action.PUBLISH, policy.fresh(20f, 2_000))
    assertTrue(policy.moving)
    return policy
  }

  @Test
  fun `Moving after a motion at a known place waits as long as still for a missing location`() {
    val policy = movingAtKnownPlace()
    assertEquals(Action.NONE, policy.onCheck(2_000 + 91_000))
    assertEquals(Action.NONE, policy.onCheck(2_000 + interval * 3 / 2))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(2_001 + interval * 3 / 2))
  }

  @Test
  fun `A GNSS fix at the known place keeps the slow GNSS limit`() {
    val policy = movingAtKnownPlace()
    val requestedAt = 2_001 + interval * 3 / 2
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(requestedAt))
    policy.onGnssFix(fix(20f, requestedAt + 5_000), requestedAt + 5_000)
    assertEquals(Action.NONE, policy.onCheck(requestedAt + 5_000 + 91_000))
  }

  @Test
  fun `A radio hint while moving at a known place brings back the moving GNSS limit`() {
    val policy = movingAtKnownPlace()
    policy.onRadio(3_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(2_000 + 91_000))
  }

  @Test
  fun `An accurate location away from the known place brings back the moving GNSS limit`() {
    val policy = movingAtKnownPlace()
    assertEquals(Action.PUBLISH, policy.fresh(20f, 62_000, northMeters = 300.0))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(62_000 + 91_000))
  }

  @Test
  fun `An inaccurate location requests GNSS at a known place only when it is away from it`() {
    val policy = movingAtKnownPlace()
    assertEquals(Action.NONE, policy.fresh(1647f, 62_000, northMeters = 1_000.0))
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1647f, 122_000, northMeters = 2_000.0))
  }

  @Test
  fun `A coarse location does not take the publish turn while precise ones keep coming`() {
    val policy = movingPolicy()
    assertEquals(Action.NONE, policy.fresh(240f, 50_000, northMeters = 700.0))
    assertEquals(Action.PUBLISH, policy.fresh(240f, 100_000, northMeters = 1_500.0))
  }

  @Test
  fun `A stationary activity ends moving and keeps the last accurate location as the place`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = true)
    assertFalse(policy.moving)
    assertFalse(policy.stillAtSamePlace)
    assertEquals(interval, policy.currentIntervalMillis())
    assertEquals(Action.NONE, policy.fresh(1647f, 2_000, northMeters = 800.0))
    assertEquals(Action.PUBLISH, policy.fresh(20f, 3_000, northMeters = 150.0))
  }

  @Test
  fun `A stationary activity does not end a motion window`() {
    val policy = GnssFallbackPolicy(400, interval, movingInterval, motionAware = true)
    policy.onMotion(0)
    policy.onActivity(stationary = true)
    assertTrue(policy.moving)
    assertTrue(policy.inMotionWindow)
  }

  @Test
  fun `A check after a stationary activity does not request GNSS`() {
    val now = 1_000 + movingInterval * 3 / 2 + 1
    assertEquals(Action.REQUEST_GNSS, movingPolicy().onCheck(now))
    val policy = movingPolicy()
    policy.onActivity(stationary = true)
    assertEquals(Action.NONE, policy.onCheck(now))
  }

  // Same place while moving.

  /** Feeds accurate locations at the same place every moving interval, from [from] on. */
  private fun GnssFallbackPolicy.stayAt(from: Long, count: Int, accuracy: Float = 20f) {
    for (i in 0 until count) {
      fresh(accuracy, from + i * movingInterval, northMeters = (i % 2) * 40.0)
    }
  }

  @Test
  fun `Two locations at the same place most of a moving interval apart end moving`() {
    val policy = movingPolicy()
    policy.fresh(20f, 45_999, northMeters = 40.0)
    assertTrue(policy.moving)
    policy.fresh(20f, 46_000)
    assertFalse(policy.moving)
    assertTrue(policy.stillAtSamePlace)
  }

  @Test
  fun `While walking, four locations at the same place over three moving intervals end moving`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = false, walking = true)
    policy.stayAt(from = 2_000, count = 3)
    assertTrue(policy.moving)
    policy.stayAt(from = 2_000 + 3 * movingInterval, count = 1)
    assertFalse(policy.moving)
    assertTrue(policy.stillAtSamePlace)
  }

  @Test
  fun `Locations from a navigation app in a few seconds do not end moving`() {
    val policy = movingPolicy()
    for (at in 2_000L..45_000L step 1_000) {
      policy.fresh(5f, at)
    }
    assertTrue(policy.moving)
  }

  @Test
  fun `A slow walk stays moving`() {
    val policy = movingPolicy()
    policy.onActivity(stationary = false, walking = true)
    for (i in 1..10) {
      // 0.8 m/s, every moving interval.
      policy.fresh(20f, 1_000 + i * movingInterval, northMeters = i * 48.0)
    }
    assertTrue(policy.moving)
  }

  @Test
  fun `Imprecise locations whose circles overlap count as the same place`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 3, accuracy = 250f)
    policy.stayAt(from = 2_000 + 3 * movingInterval, count = 1, accuracy = 250f)
    assertFalse(policy.moving)
  }

  @Test
  fun `A drive with imprecise locations stays moving`() {
    val policy = movingPolicy()
    for (i in 1..10) {
      policy.fresh(250f, 1_000 + i * movingInterval, northMeters = i * 600.0)
    }
    assertTrue(policy.moving)
  }

  @Test
  fun `A radio hint while moving restarts the same place count`() {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 1)
    policy.onRadio(30_000)
    policy.stayAt(from = 2_000 + movingInterval, count = 1)
    assertTrue(policy.moving)
  }

  // Looks: motion after the locations stayed at the same place.

  /** A policy that went still at the same place at 62 s, after a motion at 0. */
  private fun stillAtSamePlacePolicy(): GnssFallbackPolicy {
    val policy = movingPolicy()
    policy.stayAt(from = 2_000, count = 2)
    assertTrue(policy.stillAtSamePlace)
    return policy
  }

  @Test
  fun `A motion at the same place starts a look that requests no GNSS`() {
    val policy = stillAtSamePlacePolicy()
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(300_000))
    assertTrue(policy.moving)
    assertEquals(Action.NONE, policy.fresh(1647f, 310_000, northMeters = 800.0))
    assertEquals(Action.NONE, policy.onCheck(360_000))
  }

  @Test
  fun `A look that finds the same place goes still again`() {
    val policy = stillAtSamePlacePolicy()
    policy.onMotion(300_000)
    assertEquals(Action.PUBLISH, policy.fresh(20f, 302_000, northMeters = 40.0))
    policy.fresh(20f, 362_000)
    assertTrue(policy.stillAtSamePlace)
  }

  @Test
  fun `A look that finds a location away from the place proves a displacement`() {
    val policy = stillAtSamePlacePolicy()
    policy.onMotion(300_000)
    assertEquals(Action.PUBLISH, policy.fresh(20f, 302_000, northMeters = 400.0))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(302_000 + 91_000))
  }

  @Test
  fun `A walk that looked like the same place is found by the next look`() {
    val policy = movingPolicy()
    // 78 m in a minute, before the motion activity says walking.
    policy.fresh(20f, 61_000, northMeters = 78.0)
    assertTrue(policy.stillAtSamePlace)
    policy.onMotion(70_000)
    assertEquals(Action.PUBLISH, policy.fresh(20f, 121_000, northMeters = 156.0))
    policy.onActivity(stationary = false, walking = true)
    policy.fresh(20f, 181_000, northMeters = 234.0)
    assertTrue(policy.moving)
  }

  @Test
  fun `Looks that find the same place are spaced out`() {
    val policy = stillAtSamePlacePolicy()
    var windowAt = 300_000L
    for (backoff in listOf(0, movingInterval, movingInterval * 2, interval, interval)) {
      if (backoff > 0) {
        assertEquals(Action.NONE, policy.onMotion(windowAt + backoff - 1))
      }
      windowAt += backoff
      assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(windowAt))
      policy.fresh(20f, windowAt + 1_000)
      policy.fresh(20f, windowAt + 50_000, northMeters = 40.0)
      assertTrue(policy.stillAtSamePlace)
    }
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
    val policy = stillAtSamePlacePolicy()
    policy.onActivity(stationary = true)
    assertFalse(policy.stillAtSamePlace)
    assertEquals(Action.START_MOTION_WINDOW, policy.onMotion(400_000))
  }

  // GNSS failures.

  @Test
  fun `A GNSS request without a fix fails after the timeout`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    // The next request waits one still interval.
    assertEquals(Action.NONE, policy.onCheck(160_000))
    assertEquals(Action.NONE, policy.onCheck(100_000 + interval - 1))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000 + interval))
  }

  @Test
  fun `A radio hint allows one GNSS request sooner after a failure`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    assertEquals(Action.NONE, policy.onCheck(160_000))
    policy.onRadio(170_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(180_000))
    assertEquals(Action.NONE, policy.onCheck(240_000))
  }

  @Test
  fun `An accurate location at the same place keeps the GNSS failures`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    policy.onActivity(stationary = false, walking = true)
    policy.fresh(20f, 150_000, northMeters = 30.0)
    assertEquals(Action.NONE, policy.onCheck(150_000 + movingInterval * 3 / 2 + 1))
  }

  @Test
  fun `The GNSS limit grows to four intervals after repeated failures`() {
    val policy = movingPolicy()
    var at = 100_000L
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
    assertEquals(Action.NONE, policy.onCheck(210_000 + movingInterval * 3 / 2))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(210_000 + movingInterval * 3 / 2 + 1))
  }

  @Test
  fun `A GNSS fix in time is not a failure`() {
    val policy = movingAtKnownPlace()
    policy.onRadio(3_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    policy.onGnssFix(fix(15f, 110_000), 110_000)
    policy.fresh(20f, 150_000, northMeters = 600.0)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(150_000 + movingInterval * 3 / 2 + 1))
  }

  @Test
  fun `An inaccurate GNSS fix counts as a failure`() {
    val policy = movingPolicy()
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000))
    policy.onGnssFix(fix(450f, 110_000), 110_000)
    assertEquals(Action.NONE, policy.onCheck(160_000))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(100_000 + interval))
  }

  @Test
  fun `A joined Wi-Fi while moving opens a window before the check requests GNSS`() {
    val policy = movingPolicy()
    assertEquals(
        Action.START_MOTION_WINDOW,
        policy.onMovementHint(140_000, displacement = true, wifiJoined = true),
    )
    assertTrue(policy.inMotionWindow)
    assertEquals(Action.PUBLISH, policy.fresh(20f, 142_000, northMeters = 2_000.0))
    assertFalse(policy.inMotionWindow)
    assertEquals(Action.NONE, policy.onCheck(200_000))
  }

  @Test
  fun `A joined Wi-Fi window without an accurate location requests GNSS at the check`() {
    val policy = movingPolicy()
    policy.onMovementHint(140_000, displacement = true, wifiJoined = true)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(200_000))
  }

  @Test
  fun `A joined Wi-Fi during a window does not restart it`() {
    val policy = movingPolicy()
    policy.onMovementHint(140_000, displacement = true, wifiJoined = true)
    assertEquals(
        Action.NONE,
        policy.onMovementHint(170_000, displacement = true, wifiJoined = true),
    )
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(200_000))
  }

  @Test
  fun `A joined Wi-Fi window while tracking with GNSS requests no single fix`() {
    val policy = movingPolicy()
    policy.onGnssFix(fix(15f, 100_000, northMeters = 2_000.0), 100_000)
    assertTrue(policy.wantsGnssTracking)
    policy.onMovementHint(110_000, displacement = true, wifiJoined = true)
    assertEquals(Action.NONE, policy.onCheck(170_000))
    assertTrue(policy.wantsGnssTracking)
    assertFalse(policy.inMotionWindow)
  }

  companion object {
    private const val METERS_PER_DEGREE = 111_195.0
  }
}
