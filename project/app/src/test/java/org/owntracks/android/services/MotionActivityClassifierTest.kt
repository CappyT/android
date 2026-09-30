package org.owntracks.android.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.owntracks.android.services.MotionActivityClassifier.Activity

class MotionActivityClassifierTest {
  private val minute = 60_000L
  private val start = 60 * minute

  private fun MotionActivityClassifier.classify(now: Long) =
      classify(now, hasStepCounter = true, hasMotionSensor = true)

  /** Counts [perMinute] steps every 10 s from [from] to [to]. */
  private fun MotionActivityClassifier.walk(from: Long, to: Long, perMinute: Int, total: Long = 0) {
    var steps = total
    var at = from
    while (at <= to) {
      onSteps(at, steps)
      steps += perMinute / 6
      at += 10_000
    }
  }

  @Test
  fun `A device without motion or steps is stationary`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    classifier.onSteps(start, 100)
    assertEquals(Activity.STATIONARY, classifier.classify(start + 6 * minute))
  }

  @Test
  fun `A recent motion is not stationary`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    assertEquals(Activity.UNKNOWN, classifier.classify(start + minute))
  }

  @Test
  fun `Steps at a walking cadence are walking`() {
    val classifier = MotionActivityClassifier()
    classifier.walk(start, start + 2 * minute, perMinute = 108)
    assertEquals(Activity.WALKING, classifier.classify(start + 2 * minute))
  }

  @Test
  fun `Steps at a running cadence are running`() {
    val classifier = MotionActivityClassifier()
    classifier.walk(start, start + 2 * minute, perMinute = 162)
    assertEquals(Activity.RUNNING, classifier.classify(start + 2 * minute))
  }

  @Test
  fun `Moving around a room is not walking`() {
    val classifier = MotionActivityClassifier()
    classifier.walk(start, start + 2 * minute, perMinute = 20)
    assertNotEquals(Activity.WALKING, classifier.classify(start + 2 * minute))
  }

  @Test
  fun `A few steps are not walking`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    classifier.onSteps(start, 100)
    classifier.onSteps(start + 10_000, 110)
    assertEquals(Activity.UNKNOWN, classifier.classify(start + 20_000))
  }

  @Test
  fun `Walking ends when the steps stop`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    classifier.walk(start, start + 2 * minute, perMinute = 108)
    assertEquals(Activity.UNKNOWN, classifier.classify(start + 5 * minute))
    assertEquals(Activity.STATIONARY, classifier.classify(start + 8 * minute))
  }

  @Test
  fun `A measured speed above city traffic is automotive`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    classifier.onLocation(start, 45.60, 9.97, 10f, 15f)
    assertEquals(Activity.AUTOMOTIVE, classifier.classify(start + minute))
    assertEquals(Activity.UNKNOWN, classifier.classify(start + 4 * minute))
  }

  @Test
  fun `The speed between two distant fixes is automotive`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    classifier.onLocation(start, 45.60, 9.97, 20f, null)
    // About 1.1 km in 60 s.
    classifier.onLocation(start + minute, 45.61, 9.97, 20f, null)
    assertEquals(Activity.AUTOMOTIVE, classifier.classify(start + minute))
  }

  @Test
  fun `Two fixes within their accuracy give no speed`() {
    val classifier = MotionActivityClassifier()
    classifier.onLocation(start, 45.6000, 9.97, 400f, null)
    // About 110 m apart, much less than the accuracy.
    classifier.onLocation(start + minute, 45.6010, 9.97, 400f, null)
    assertEquals(Activity.STATIONARY, classifier.classify(start + minute))
  }

  @Test
  fun `Steps win over a speed`() {
    val classifier = MotionActivityClassifier()
    classifier.onLocation(start, 45.60, 9.97, 10f, 15f)
    classifier.walk(start, start + 2 * minute, perMinute = 108)
    assertEquals(Activity.WALKING, classifier.classify(start + 2 * minute))
  }

  @Test
  fun `Without a step counter a quiet device is still stationary`() {
    val classifier = MotionActivityClassifier()
    classifier.onMotion(start)
    classifier.walk(start, start + 2 * minute, perMinute = 108)
    assertEquals(
        Activity.STATIONARY,
        classifier.classify(start + 6 * minute, hasStepCounter = false, hasMotionSensor = true),
    )
    assertEquals(
        Activity.UNKNOWN,
        classifier.classify(start + 2 * minute, hasStepCounter = false, hasMotionSensor = true),
    )
  }

  @Test
  fun `Without a motion sensor nothing is stationary`() {
    val classifier = MotionActivityClassifier()
    assertEquals(
        Activity.UNKNOWN,
        classifier.classify(start, hasStepCounter = true, hasMotionSensor = false),
    )
  }
}
