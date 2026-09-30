package org.owntracks.android.services

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.AlarmManagerCompat
import java.time.Duration
import org.owntracks.android.location.LocationAvailability
import org.owntracks.android.location.LocationCallback
import org.owntracks.android.location.LocationProviderClient
import org.owntracks.android.location.LocationRequest
import org.owntracks.android.location.LocationResult
import org.owntracks.android.location.LocatorPriority
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.MonitoringMode
import org.owntracks.android.support.RequirementsChecker
import timber.log.Timber

/**
 * Requests a single GNSS fix when a location request that doesn't use GNSS stops producing
 * locations within [Preferences.gnssFallbackAccuracy] meters.
 *
 * The main location request filters by displacement, so a missing update can mean either "hasn't
 * moved" or "no fix". A probe request with the same priority but no displacement filter tells the
 * two apart: it keeps delivering locations while the device is still, and goes quiet when the
 * low-power providers have nothing to offer. A periodic check catches the quiet case.
 *
 * When the significant motion sensor fires, the device joins or leaves Wi-Fi, or [PlaceWatcher]
 * sees a cell or Wi-Fi scan that doesn't belong to the last place, the probe polls faster for a
 * short window. The first accurate location in that window is published, and if none arrives, a
 * GNSS fix is requested.
 */
class GnssFallback(
    private val context: Context,
    private val preferences: Preferences,
    private val locationProviderClient: LocationProviderClient,
    private val requirementsChecker: RequirementsChecker,
    private val publishCallback: LocationCallback,
    private val checkIntent: PendingIntent,
    private val looper: Looper,
) {
  private val alarmManager by lazy {
    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  }
  private val sensorManager by lazy {
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  }
  private val motionSensor: Sensor? by lazy {
    sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
  }

  @Volatile private var policy: GnssFallbackPolicy? = null
  private var policyAccuracy = 0
  private var intervalMillis = 0L
  private var priority = LocatorPriority.LowPower
  private var fastProbe = false
  private var checkAt = 0L

  // The passive provider hands our own GNSS fix back to the probe, where it would hide a gap in
  // the low-power providers.
  @Volatile private var lastGnssFixElapsedRealtimeNanos = 0L

  private val probeCallback =
      object : LocationCallback {
        override fun onLocationResult(locationResult: LocationResult) {
          val location = locationResult.lastLocation
          if (location.elapsedRealtimeNanos == lastGnssFixElapsedRealtimeNanos) {
            return
          }
          val action =
              synchronized(this@GnssFallback) {
                policy?.onLocation(
                    location.accuracy,
                    location.elapsedRealtimeNanos / 1_000_000,
                    SystemClock.elapsedRealtime(),
                )
              }
          when (action) {
            GnssFallbackPolicy.Action.PUBLISH -> {
              Timber.d("GNSS fallback: publishing the first accurate location after a movement")
              publishCallback.onLocationResult(locationResult)
            }
            GnssFallbackPolicy.Action.REQUEST_GNSS -> {
              Timber.d("GNSS fallback: location accuracy ${location.accuracy}m is too low")
              requestGnssFix()
            }
            else -> {}
          }
          if (action != null && action != GnssFallbackPolicy.Action.NONE) {
            endMotionWindow()
          }
          if (action != null) {
            recordPlace(location)
          }
        }

        override fun onLocationError() {}

        override fun onLocationAvailability(locationAvailability: LocationAvailability) {}
      }

  private val gnssCallback =
      object : LocationCallback {
        override fun onLocationResult(locationResult: LocationResult) {
          lastGnssFixElapsedRealtimeNanos = locationResult.lastLocation.elapsedRealtimeNanos
          synchronized(this@GnssFallback) {
            policy?.onGnssFix(locationResult.lastLocation.elapsedRealtimeNanos / 1_000_000)
          }
          recordPlace(locationResult.lastLocation)
          publishCallback.onLocationResult(locationResult)
        }

        override fun onLocationError() {
          publishCallback.onLocationError()
        }

        override fun onLocationAvailability(locationAvailability: LocationAvailability) {
          publishCallback.onLocationAvailability(locationAvailability)
        }
      }

  private val motionListener =
      object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
          armMotionSensor()
          onMovementHint("motion detected")
        }
      }

  private val placeWatcher = PlaceWatcher(context, ::onMovementHint)

  private val wifiLinkWatcher =
      WifiLinkWatcher(context) { joined ->
        onMovementHint(if (joined) "Wi-Fi joined" else "Wi-Fi lost")
      }

  /**
   * Starts or stops the fallback to match the main location request. It only runs in the modes that
   * publish locations on their own, and only when the main request doesn't already use GNSS.
   */
  @SuppressLint("MissingPermission")
  fun setup(monitoring: MonitoringMode, priority: LocatorPriority, interval: Duration) {
    val accuracy = preferences.gnssFallbackAccuracy
    if (
        accuracy <= 0 ||
            priority == LocatorPriority.HighAccuracy ||
            monitoring !in setOf(MonitoringMode.Significant, MonitoringMode.Move) ||
            !requirementsChecker.hasLocationPermissions()
    ) {
      cancel()
      return
    }
    // The location request is often set up several times in a row. Keeping the policy keeps its
    // rate limit, so each setup doesn't start another GNSS fix.
    if (policy == null || accuracy != policyAccuracy || interval.toMillis() != intervalMillis) {
      policy = GnssFallbackPolicy(accuracy, interval.toMillis(), motionSensor != null)
      policyAccuracy = accuracy
      intervalMillis = interval.toMillis()
    }
    this.priority = priority
    Timber.d("GNSS fallback enabled below ${accuracy}m, interval $interval, priority $priority")
    registerProbe(interval)
    scheduleNextCheck()
    armMotionSensor()
    wifiLinkWatcher.start()
    placeWatcher.start()
  }

  /** Runs the check fired by the alarm set up in [scheduleCheck]. */
  fun onCheck() {
    val action = synchronized(this) { policy?.onCheck(SystemClock.elapsedRealtime()) } ?: return
    placeWatcher.checkCell()
    when (action) {
      GnssFallbackPolicy.Action.REQUEST_GNSS -> {
        Timber.d("GNSS fallback: no accurate location in time")
        requestGnssFix()
      }
      GnssFallbackPolicy.Action.START_MOTION_WINDOW -> {
        Timber.d("GNSS fallback: held back motion, looking for a low-power location")
        startMotionWindow()
        return
      }
      else -> {}
    }
    endMotionWindow()
    scheduleNextCheck()
  }

  private fun onMovementHint(reason: String) {
    val now = SystemClock.elapsedRealtime()
    val (windowStarted, pendingAt) =
        synchronized(this) { policy?.let { it.onMotion(now) to it.pendingMotionWindowAt } }
            ?: return
    if (!windowStarted) {
      Timber.d("GNSS fallback: $reason, held back, window in ${pendingAt?.minus(now)}ms")
      // The check that runs first opens the window, so only bring it forward.
      if (pendingAt != null && pendingAt < checkAt) {
        scheduleCheck(pendingAt - now)
      }
      return
    }
    Timber.d("GNSS fallback: $reason, looking for a low-power location")
    startMotionWindow()
  }

  /** Only fresh fixes describe where the device is now. */
  private fun recordPlace(location: Location) {
    val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
    if (location.accuracy <= policyAccuracy && age < PLACE_MAX_FIX_AGE.toNanos()) {
      placeWatcher.onAccurateLocation(location.latitude, location.longitude)
    }
  }

  private fun startMotionWindow() {
    fastProbe = true
    registerProbe(MOTION_PROBE_INTERVAL)
    scheduleCheck(MOTION_WINDOW.toMillis())
  }

  private fun endMotionWindow() {
    if (fastProbe && policy != null) {
      fastProbe = false
      registerProbe(Duration.ofMillis(intervalMillis))
    }
  }

  @SuppressLint("MissingPermission")
  private fun registerProbe(interval: Duration) {
    locationProviderClient.requestLocationUpdates(
        LocationRequest(smallestDisplacement = 0f, priority = priority, interval = interval),
        probeCallback,
        looper,
    )
  }

  private fun armMotionSensor() {
    motionSensor?.let {
      sensorManager.cancelTriggerSensor(motionListener, it)
      sensorManager.requestTriggerSensor(motionListener, it)
    }
  }

  fun cancel() {
    if (policy == null) {
      return
    }
    policy = null
    fastProbe = false
    locationProviderClient.removeLocationUpdates(probeCallback)
    alarmManager.cancel(checkIntent)
    motionSensor?.let { sensorManager.cancelTriggerSensor(motionListener, it) }
    wifiLinkWatcher.stop()
    placeWatcher.stop()
    Timber.d("GNSS fallback disabled")
  }

  private fun scheduleNextCheck() {
    val pendingAt = synchronized(this) { policy?.pendingMotionWindowAt }
    val untilPending = pendingAt?.let { it - SystemClock.elapsedRealtime() }
    scheduleCheck(untilPending?.coerceIn(0, intervalMillis) ?: intervalMillis)
  }

  private fun scheduleCheck(delayMillis: Long) {
    checkAt = SystemClock.elapsedRealtime() + delayMillis
    AlarmManagerCompat.setAndAllowWhileIdle(
        alarmManager,
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        checkAt,
        checkIntent,
    )
  }

  @SuppressLint("MissingPermission")
  private fun requestGnssFix() {
    if (requirementsChecker.hasLocationPermissions()) {
      locationProviderClient.singleHighAccuracyLocation(gnssCallback, looper)
    }
  }

  companion object {
    private val MOTION_PROBE_INTERVAL: Duration = Duration.ofSeconds(20)
    private val MOTION_WINDOW: Duration = Duration.ofSeconds(60)
    private val PLACE_MAX_FIX_AGE: Duration = Duration.ofSeconds(60)
  }
}
