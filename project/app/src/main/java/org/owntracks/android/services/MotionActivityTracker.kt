package org.owntracks.android.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.owntracks.android.services.MotionActivityClassifier.Activity
import timber.log.Timber

/**
 * Feeds [MotionActivityClassifier] from the step counter and the significant motion sensor.
 *
 * The step counter is a non-wake-up sensor that the sensor hub batches, so counting steps doesn't
 * wake the device. The batch is flushed before each classification.
 */
@Singleton
class MotionActivityTracker @Inject constructor(@ApplicationContext private val context: Context) {
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val motionSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
  private val handler = Handler(Looper.getMainLooper())

  // Only touched on the main thread.
  private val classifier = MotionActivityClassifier()
  private var running = false
  private var stepCounter: Sensor? = null
  private val waiting = mutableListOf<(Activity) -> Unit>()
  private val flushTimeout = Runnable(::classifyWaiting)

  private val stepListener =
      object : SensorEventListener2 {
        // Registered with the main thread handler.
        override fun onSensorChanged(event: SensorEvent) {
          classifier.onSteps(event.timestamp / 1_000_000, event.values[0].toLong())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

        override fun onFlushCompleted(sensor: Sensor) {
          classifyWaiting()
        }
      }

  private val motionListener =
      object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
          classifier.onMotion(SystemClock.elapsedRealtime())
          motionSensor?.let { sensorManager.requestTriggerSensor(this, it) }
        }
      }

  /** Can be called from any thread. */
  fun setEnabled(enabled: Boolean) {
    handler.post { if (enabled) start() else stop() }
  }

  private fun start() {
    if (running) {
      return
    }
    running = true
    // Nothing is known about the time before tracking, so it can't count as still.
    classifier.onMotion(SystemClock.elapsedRealtime())
    motionSensor?.let { sensorManager.requestTriggerSensor(motionListener, it) }
    stepCounter =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.takeIf { hasActivityPermission() }
    stepCounter?.let {
      sensorManager.registerListener(
          stepListener,
          it,
          SensorManager.SENSOR_DELAY_NORMAL,
          STEP_BATCH_LATENCY_MICROS,
          handler,
      )
    }
    Timber.d(
        "Motion activity tracking started, step counter: ${stepCounter != null}, motion sensor: ${motionSensor != null}"
    )
  }

  private fun stop() {
    if (!running) {
      return
    }
    running = false
    motionSensor?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
    stepCounter?.let { sensorManager.unregisterListener(stepListener) }
    stepCounter = null
    classifyWaiting()
  }

  /** Feeds the speed between locations. Can be called from any thread. */
  fun onLocation(location: Location) {
    val at = location.elapsedRealtimeNanos / 1_000_000
    val speed = if (location.hasSpeed()) location.speed else null
    handler.post {
      classifier.onLocation(at, location.latitude, location.longitude, location.accuracy, speed)
    }
  }

  /**
   * Calls [block] on the main thread with the current activity, after the batched steps arrive.
   * Gives [Activity.UNKNOWN] while tracking is off.
   */
  fun withCurrentActivity(block: (Activity) -> Unit) {
    handler.post {
      if (!running) {
        block(Activity.UNKNOWN)
        return@post
      }
      waiting += block
      val counter = stepCounter
      if (waiting.size == 1) {
        if (counter == null || !sensorManager.flush(stepListener)) {
          classifyWaiting()
        } else {
          // Not every sensor hub reports a completed flush.
          handler.postDelayed(flushTimeout, FLUSH_TIMEOUT_MILLIS)
        }
      }
    }
  }

  suspend fun currentActivity(): Activity = suspendCancellableCoroutine { continuation ->
    withCurrentActivity { if (continuation.isActive) continuation.resume(it) }
  }

  private fun classifyWaiting() {
    if (waiting.isEmpty()) {
      return
    }
    handler.removeCallbacks(flushTimeout)
    val activity =
        if (running) {
          classifier.classify(
              SystemClock.elapsedRealtime(),
              hasStepCounter = stepCounter != null,
              hasMotionSensor = motionSensor != null,
          )
        } else {
          Activity.UNKNOWN
        }
    val blocks = waiting.toList()
    waiting.clear()
    blocks.forEach { it(activity) }
  }

  private fun hasActivityPermission() =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
              PackageManager.PERMISSION_GRANTED

  companion object {
    private const val STEP_BATCH_LATENCY_MICROS = 60_000_000
    private const val FLUSH_TIMEOUT_MILLIS = 2_000L
  }
}
