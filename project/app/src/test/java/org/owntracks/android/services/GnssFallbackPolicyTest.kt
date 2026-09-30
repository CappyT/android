package org.owntracks.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.owntracks.android.services.GnssFallbackPolicy.Action

class GnssFallbackPolicyTest {
  private val interval = 500_000L

  private fun GnssFallbackPolicy.fresh(accuracy: Float, now: Long) = onLocation(accuracy, now, now)

  @Test
  fun `An accurate location does not request GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertEquals(Action.NONE, policy.fresh(30f, 0))
  }

  @Test
  fun `An inaccurate location requests GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1200f, 0))
  }

  @Test
  fun `An old inaccurate location does not request GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertEquals(Action.NONE, policy.onLocation(1200f, 0, interval + 1))
  }

  @Test
  fun `GNSS requests are rate limited to one per interval`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1200f, 0))
    assertEquals(Action.NONE, policy.fresh(1200f, interval - 1))
    assertEquals(Action.NONE, policy.onCheck(interval - 1))
    assertEquals(Action.REQUEST_GNSS, policy.fresh(1200f, interval))
  }

  @Test
  fun `A check without any location requests GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval))
  }

  @Test
  fun `A check within one and a half intervals of an accurate location does not request GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    policy.fresh(30f, 0)
    assertEquals(Action.NONE, policy.onCheck(interval))
    assertEquals(Action.NONE, policy.onCheck(interval * 3 / 2))
  }

  @Test
  fun `A check after one and a half intervals without an accurate location requests GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    policy.fresh(30f, 0)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 3 / 2 + 1))
  }

  @Test
  fun `A cached accurate location does not hide a gap`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    policy.fresh(30f, 0)
    policy.onLocation(30f, 0, interval)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 3 / 2 + 1))
  }

  @Test
  fun `The first fresh accurate location after a motion is published`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertTrue(policy.onMotion(1_000))
    assertEquals(Action.NONE, policy.onLocation(30f, 0, 1_000))
    assertEquals(Action.PUBLISH, policy.fresh(30f, 2_000))
    assertFalse(policy.inMotionWindow)
    assertEquals(Action.NONE, policy.fresh(30f, 3_000))
  }

  @Test
  fun `A motion window without an accurate location requests GNSS at the check`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    policy.fresh(30f, 0)
    assertTrue(policy.onMotion(1_000))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(61_000))
    assertFalse(policy.inMotionWindow)
  }

  @Test
  fun `Motion windows are rate limited to one per interval`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = false)
    assertTrue(policy.onMotion(0))
    policy.fresh(30f, 1_000)
    assertFalse(policy.onMotion(interval - 1))
    assertTrue(policy.onMotion(interval))
  }

  @Test
  fun `A motion aware check does not request GNSS while the device is still`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    policy.fresh(30f, 0)
    assertEquals(Action.NONE, policy.onCheck(interval * 4))
  }

  @Test
  fun `A motion aware check requests GNSS after a motion without an accurate location`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    policy.fresh(30f, 0)
    policy.onMotion(1_000)
    policy.onCheck(61_000)
    assertEquals(Action.NONE, policy.onCheck(interval))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 2))
  }

  @Test
  fun `A motion aware check without any location requests GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval))
  }

  @Test
  fun `A GNSS fix needs a new motion before the next check requests GNSS`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    policy.fresh(30f, 0)
    policy.onMotion(1_000)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(61_000))
    policy.onGnssFix(70_000)
    assertEquals(Action.NONE, policy.onCheck(interval * 3))
    policy.onMotion(interval * 3)
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval * 3 + 60_000))
  }

  @Test
  fun `A held back motion opens its window when the rate limit expires`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    assertTrue(policy.onMotion(0))
    policy.fresh(30f, 1_000)
    assertFalse(policy.onMotion(200_000))
    assertEquals(interval, policy.pendingMotionWindowAt)
    assertEquals(Action.NONE, policy.onCheck(interval - 1))
    assertEquals(Action.START_MOTION_WINDOW, policy.onCheck(interval))
    assertTrue(policy.inMotionWindow)
    assertEquals(null, policy.pendingMotionWindowAt)
    assertEquals(Action.PUBLISH, policy.fresh(30f, interval + 2_000))
  }

  @Test
  fun `A held back motion window without an accurate location requests GNSS at the check`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    assertTrue(policy.onMotion(0))
    policy.fresh(30f, 1_000)
    policy.onMotion(200_000)
    assertEquals(Action.START_MOTION_WINDOW, policy.onCheck(interval))
    assertEquals(Action.REQUEST_GNSS, policy.onCheck(interval + 60_000))
  }

  @Test
  fun `An accurate location after a held back motion is published and cancels its window`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    assertTrue(policy.onMotion(0))
    policy.fresh(30f, 1_000)
    policy.onMotion(200_000)
    assertEquals(Action.NONE, policy.onLocation(30f, 190_000, 210_000))
    assertEquals(interval, policy.pendingMotionWindowAt)
    assertEquals(Action.PUBLISH, policy.fresh(30f, 220_000))
    assertEquals(null, policy.pendingMotionWindowAt)
    assertEquals(Action.NONE, policy.fresh(30f, 230_000))
    assertEquals(Action.NONE, policy.onCheck(interval))
  }

  @Test
  fun `A motion during a window is held back`() {
    val policy = GnssFallbackPolicy(400, interval, motionAware = true)
    assertTrue(policy.onMotion(0))
    assertFalse(policy.onMotion(30_000))
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
}
