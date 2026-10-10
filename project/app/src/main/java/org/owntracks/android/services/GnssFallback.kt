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
import android.location.LocationManager
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
 *
 * In Significant mode with [Preferences.movingLocatorInterval], the window starts the moving state:
 * the probe and the check run every moving interval until [GnssFallbackPolicy] says the device is
 * still again. The motion activity decides that, so the fallback keeps [MotionActivityTracker]
 * running while it can be moving. Where only GNSS locates the moving device, a GNSS request at the
 * moving interval replaces the single fixes: the alarm of the check is inexact and fires too late
 * for one fix per interval.
 */
class GnssFallback(
    private val context: Context,
    private val preferences: Preferences,
    private val locationProviderClient: LocationProviderClient,
    private val requirementsChecker: RequirementsChecker,
    private val publishCallback: LocationCallback,
    private val checkIntent: PendingIntent,
    private val looper: Looper,
    private val motionActivityTracker: MotionActivityTracker,
    private val lastPublishedLocation: () -> Location?,
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
  private var movingIntervalMillis = 0L
  private var priority = LocatorPriority.LowPower
  private var appliedState: State? = null
  private var wasMoving = false
  private var gnssTracking = false
  private var ignoredMotionCellCheckAt = 0L
  private var checkAt = 0L

  private enum class State {
    STILL,
    WINDOW,
    MOVING,
  }

  /** True while the fallback needs the motion activity to leave the moving state. */
  val usesMotionActivity: Boolean
    get() = policy != null && movingIntervalMillis > 0

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
                policy?.onLocation(location.toFix(), SystemClock.elapsedRealtime())
              }
          when (action) {
            GnssFallbackPolicy.Action.PUBLISH -> {
              val distance = samePlaceDistance(location)
              if (distance != null) {
                Timber.d("GNSS fallback: location is ${distance}m away, same place")
              } else {
                Timber.d("GNSS fallback: publishing a fresh accurate location")
                publishCallback.onLocationResult(locationResult)
              }
            }
            GnssFallbackPolicy.Action.REQUEST_GNSS -> {
              Timber.d("GNSS fallback: location accuracy ${location.accuracy}m is too low")
              requestGnssFix()
            }
            GnssFallbackPolicy.Action.START_MOTION_WINDOW ->
                Timber.d("GNSS fallback: location away from the place, looking for a location")
            else -> {}
          }
          if (action != null) {
            recordPlace(location)
            applyState()
          }
        }

        override fun onLocationError() {}

        override fun onLocationAvailability(locationAvailability: LocationAvailability) {}
      }

  /**
   * The callback for the main location request. While moving, its locations go through the policy
   * like the ones of the probe, so both together publish at most one per moving interval.
   */
  val mainCallback =
      object : LocationCallback {
        override fun onLocationResult(locationResult: LocationResult) {
          if (synchronized(this@GnssFallback) { policy?.moving == true }) {
            probeCallback.onLocationResult(locationResult)
            return
          }
          // The displacement filter compares with the last location that this request delivered.
          // After a move, that one is far behind the last published location.
          val distance =
              if (usesMotionActivity) samePlaceDistance(locationResult.lastLocation) else null
          if (distance != null) {
            Timber.d("GNSS fallback: main location is ${distance}m away, same place")
          } else {
            publishCallback.onLocationResult(locationResult)
          }
        }

        override fun onLocationError() {
          publishCallback.onLocationError()
        }

        override fun onLocationAvailability(locationAvailability: LocationAvailability) {
          publishCallback.onLocationAvailability(locationAvailability)
        }
      }

  private val gnssCallback =
      object : LocationCallback {
        override fun onLocationResult(locationResult: LocationResult) {
          val location = locationResult.lastLocation
          lastGnssFixElapsedRealtimeNanos = location.elapsedRealtimeNanos
          val moving =
              synchronized(this@GnssFallback) {
                policy?.let {
                  val moving = it.moving
                  it.onGnssFix(location.toFix(), SystemClock.elapsedRealtime())
                  moving
                }
              } ?: false
          recordPlace(location)
          // While moving, the fixes keep coming after the device stops.
          val distance = if (moving) samePlaceDistance(location) else null
          if (distance != null) {
            Timber.d("GNSS fallback: GNSS fix is ${distance}m away, same place")
          } else {
            publishCallback.onLocationResult(locationResult)
          }
          applyState()
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
          onMovementHint("motion detected", displacement = false)
        }
      }

  private val placeWatcher = PlaceWatcher(context, { wifiLinkWatcher.joined }, ::onRadioHint)

  private val wifiLinkWatcher =
      WifiLinkWatcher(context) { joined ->
        onRadioHint(if (joined) "Wi-Fi joined" else "Wi-Fi lost", wifiJoined = joined)
      }

  /**
   * A radio change can happen without a move: a router restarts, or the modem switches between
   * nearby cells. It only counts when the motion activity doesn't say the device is still.
   */
  private fun onRadioHint(reason: String, wifiJoined: Boolean = false) {
    motionActivityTracker.withCurrentActivity { activity ->
      if (activity == MotionActivityClassifier.Activity.STATIONARY) {
        Timber.d("GNSS fallback: $reason, ignored while stationary")
      } else {
        onMovementHint(reason, displacement = true, wifiJoined)
      }
    }
  }

  /**
   * Starts or stops the fallback to match the main location request. It only runs in the modes that
   * publish locations on their own, and only when the main request doesn't already use GNSS.
   */
  @SuppressLint("MissingPermission")
  fun setup(monitoring: MonitoringMode, priority: LocatorPriority, interval: Duration) {
    // A location that the processor drops as inaccurate must not count as accurate here.
    val accuracy =
        preferences.ignoreInaccurateLocations
            .takeIf { it > 0 }
            ?.let { minOf(it, preferences.gnssFallbackAccuracy) }
            ?: preferences.gnssFallbackAccuracy
    if (
        accuracy <= 0 ||
            priority == LocatorPriority.HighAccuracy ||
            monitoring !in setOf(MonitoringMode.Significant, MonitoringMode.Move) ||
            !requirementsChecker.hasLocationPermissions()
    ) {
      cancel()
      return
    }
    // Move mode already polls fast.
    val movingInterval =
        if (monitoring == MonitoringMode.Significant) {
          Duration.ofSeconds(preferences.movingLocatorInterval.coerceAtLeast(0).toLong())
        } else {
          Duration.ZERO
        }
    // The location request is often set up several times in a row. Keeping the policy keeps its
    // state and rate limits, so each setup doesn't start another GNSS fix.
    synchronized(this) {
      if (
          policy == null ||
              accuracy != policyAccuracy ||
              interval.toMillis() != intervalMillis ||
              movingInterval.toMillis() != movingIntervalMillis
      ) {
        policy =
            GnssFallbackPolicy(
                accuracy,
                interval.toMillis(),
                movingInterval.toMillis(),
                motionSensor != null,
            )
        policyAccuracy = accuracy
        intervalMillis = interval.toMillis()
        movingIntervalMillis = movingInterval.toMillis()
      }
      this.priority = priority
      appliedState = null
    }
    Timber.d(
        "GNSS fallback enabled below ${accuracy}m, interval $interval, moving interval $movingInterval, priority $priority"
    )
    applyState()
    armMotionSensor()
    wifiLinkWatcher.start()
    placeWatcher.start()
  }

  /** Runs the check fired by the alarm set up in [scheduleCheck]. */
  fun onCheck() {
    synchronized(this) {
      val current = policy ?: return
      // The alarm fires once, and the motion activity can arrive after the device sleeps again.
      // Setting the next check now keeps the checks going when that answer is late.
      scheduleCheck(current.currentIntervalMillis())
    }
    placeWatcher.checkCell()
    // The motion activity comes first: a device that stopped moving needs no GNSS fix.
    motionActivityTracker.withCurrentActivity { activity ->
      val action =
          synchronized(this) {
            policy?.let {
              it.onActivity(
                  stationary = activity == MotionActivityClassifier.Activity.STATIONARY,
                  walking =
                      activity == MotionActivityClassifier.Activity.WALKING ||
                          activity == MotionActivityClassifier.Activity.RUNNING,
                  automotive = activity == MotionActivityClassifier.Activity.AUTOMOTIVE,
              )
              it.onCheck(SystemClock.elapsedRealtime())
            }
          } ?: return@withCurrentActivity
      when (action) {
        GnssFallbackPolicy.Action.REQUEST_GNSS -> {
          Timber.d("GNSS fallback: no accurate location in time")
          requestGnssFix()
        }
        GnssFallbackPolicy.Action.START_MOTION_WINDOW ->
            Timber.d("GNSS fallback: held back motion, looking for a low-power location")
        else -> {}
      }
      // The state can stay the same while the next check moves, like for a held back motion.
      if (!applyState()) {
        synchronized(this) { appliedState?.let(::scheduleCheckFor) }
      }
    }
  }

  private fun onMovementHint(reason: String, displacement: Boolean, wifiJoined: Boolean = false) {
    val now = SystemClock.elapsedRealtime()
    val (action, pendingAt, wasMoving) =
        synchronized(this) {
          policy?.let {
            val wasMoving = it.moving
            Triple(
                it.onMovementHint(now, displacement, wifiJoined),
                it.pendingMotionWindowAt,
                wasMoving,
            )
          }
        } ?: return
    if (wasMoving) {
      if (displacement) {
        Timber.d("GNSS fallback: $reason while moving")
      }
      if (action == GnssFallbackPolicy.Action.START_MOTION_WINDOW) {
        applyState()
      }
    } else if (action == GnssFallbackPolicy.Action.START_MOTION_WINDOW) {
      Timber.d("GNSS fallback: $reason, looking for a low-power location")
      applyState()
    } else if (pendingAt != null) {
      Timber.d("GNSS fallback: $reason, held back, window in ${pendingAt - now}ms")
      // The check that runs first opens the window, so only bring it forward.
      if (pendingAt < checkAt) {
        scheduleCheck(pendingAt - now)
      }
    } else if (
        !displacement &&
            synchronized(this) { policy?.stillAtSamePlace == true } &&
            now - ignoredMotionCellCheckAt >= movingIntervalMillis
    ) {
      // This motion came too soon after the last look, but it can mean the device leaves. A new
      // cell proves it sooner than the next check.
      Timber.d("GNSS fallback: $reason at the same place, checking the cell")
      ignoredMotionCellCheckAt = now
      placeWatcher.checkCell()
    }
  }

  /** The distance to the last published location, when [location] is at the same place. */
  private fun samePlaceDistance(location: Location): Float? {
    val published = lastPublishedLocation() ?: return null
    return location.distanceTo(published).takeIf {
      GnssFallbackPolicy.isSamePlace(it, location.accuracy, published.accuracy)
    }
  }

  /** Only fresh fixes describe where the device is now. */
  private fun recordPlace(location: Location) {
    val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
    if (age >= PLACE_MAX_FIX_AGE.toNanos()) {
      return
    }
    motionActivityTracker.onLocation(location)
    if (location.accuracy <= policyAccuracy) {
      placeWatcher.onAccurateLocation(location.latitude, location.longitude)
    }
  }

  /**
   * Matches the probe and the check to the state of the policy, when that state changed since the
   * last call. Returns true when it changed.
   */
  @Synchronized
  private fun applyState(): Boolean {
    val current = policy ?: return false
    val state =
        when {
          current.inMotionWindow -> State.WINDOW
          current.moving -> State.MOVING
          else -> State.STILL
        }
    if (current.moving != wasMoving) {
      wasMoving = current.moving
      Timber.d(
          when {
            current.moving -> "GNSS fallback: moving"
            current.stillAtSamePlace -> "GNSS fallback: still at the same place"
            else -> "GNSS fallback: still, stationary"
          }
      )
    }
    if (current.wantsGnssTracking != gnssTracking) {
      gnssTracking = current.wantsGnssTracking
      if (gnssTracking) {
        Timber.d("GNSS fallback: only GNSS locates the device, tracking with GNSS")
        registerGnssTracking()
      } else {
        Timber.d("GNSS fallback: GNSS tracking stopped")
        locationProviderClient.removeLocationUpdates(gnssCallback)
      }
    }
    if (state == appliedState) {
      return false
    }
    appliedState = state
    registerProbe(
        Duration.ofMillis(
            when (state) {
              State.WINDOW -> MOTION_PROBE_INTERVAL.toMillis()
              State.MOVING -> movingIntervalMillis
              State.STILL -> intervalMillis
            }
        )
    )
    scheduleCheckFor(state)
    return true
  }

  private fun scheduleCheckFor(state: State) {
    when (state) {
      State.WINDOW -> scheduleCheck(MOTION_WINDOW.toMillis())
      State.MOVING -> scheduleCheck(movingIntervalMillis)
      State.STILL -> scheduleNextCheck()
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

  @SuppressLint("MissingPermission")
  private fun registerGnssTracking() {
    locationProviderClient.requestLocationUpdates(
        LocationRequest(
            smallestDisplacement = 0f,
            priority = LocatorPriority.HighAccuracy,
            interval = Duration.ofMillis(movingIntervalMillis),
        ),
        gnssCallback,
        looper,
    )
  }

  private fun armMotionSensor() {
    motionSensor?.let {
      sensorManager.cancelTriggerSensor(motionListener, it)
      sensorManager.requestTriggerSensor(motionListener, it)
    }
  }

  @Synchronized
  fun cancel() {
    if (policy == null) {
      return
    }
    policy = null
    appliedState = null
    wasMoving = false
    if (gnssTracking) {
      gnssTracking = false
      locationProviderClient.removeLocationUpdates(gnssCallback)
    }
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

  private fun Location.toFix() =
      GnssFallbackPolicy.Fix(
          latitude,
          longitude,
          accuracy,
          elapsedRealtimeNanos / 1_000_000,
          provider == LocationManager.GPS_PROVIDER,
      )

  companion object {
    private val MOTION_PROBE_INTERVAL: Duration = Duration.ofSeconds(20)
    private val MOTION_WINDOW: Duration = Duration.ofSeconds(60)
    private val PLACE_MAX_FIX_AGE: Duration = Duration.ofSeconds(60)
  }
}
