package org.owntracks.android.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Watches the serving cell and Wi-Fi scans for signs that the device left the place of its last
 * accurate location. It never starts a scan: it reads the scans that the system and the network
 * location provider run anyway.
 *
 * Needs the fine location permission, which the caller checks.
 */
@SuppressLint("MissingPermission")
class PlaceWatcher(private val context: Context, private val onMoved: (reason: String) -> Unit) {
  private val telephonyManager =
      context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
  private val wifiManager =
      context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  private val handler = Handler(Looper.getMainLooper())

  // Only touched on the main thread.
  private val fingerprint = PlaceFingerprint()
  private var receiver: BroadcastReceiver? = null

  /** Can be called from any thread, like [stop]. */
  fun start() {
    handler.post {
      if (receiver == null) {
        val newReceiver =
            object : BroadcastReceiver() {
              override fun onReceive(context: Context, intent: Intent) {
                if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                  return
                }
                if (fingerprint.isNewScan(recentAccessPoints())) {
                  onMoved("new Wi-Fi scan")
                }
              }
            }
        receiver = newReceiver
        ContextCompat.registerReceiver(
            context,
            newReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            null,
            handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
      }
    }
  }

  fun stop() {
    handler.post {
      receiver?.let { context.unregisterReceiver(it) }
      receiver = null
    }
  }

  /** Adds the cells and access points around a fresh accurate location to the place. */
  fun onAccurateLocation(latitude: Double, longitude: Double) {
    val accessPoints = recentAccessPoints()
    requestServingCells { cells ->
      fingerprint.onAccurateLocation(latitude, longitude, cells, accessPoints)
    }
  }

  /** Reports a move when a serving cell doesn't belong to the place. */
  fun checkCell() {
    requestServingCells { cells ->
      cells.firstOrNull(fingerprint::isNewCell)?.let { onMoved("new cell $it") }
    }
  }

  private fun recentAccessPoints(): Set<String> {
    val since = (SystemClock.elapsedRealtime() - SCAN_MAX_AGE_MILLIS) * 1000
    return wifiManager.scanResults.filter { it.timestamp >= since }.map { it.BSSID }.toSet()
  }

  /** Calls [onCells] on the main thread. */
  private fun requestServingCells(onCells: (Set<String>) -> Unit) {
    val telephony = telephonyManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      telephony.requestCellInfoUpdate(
          ContextCompat.getMainExecutor(context),
          object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: List<CellInfo>) {
              onCells(servingCells(cellInfo))
            }
          },
      )
    } else {
      val cells = servingCells(telephony.allCellInfo.orEmpty())
      handler.post { onCells(cells) }
    }
  }

  private fun servingCells(cellInfo: List<CellInfo>): Set<String> =
      cellInfo.filter { it.isRegistered }.mapNotNull(::cellKey).toSet()

  private fun cellKey(info: CellInfo): String? {
    val id =
        when {
          info is CellInfoLte -> info.cellIdentity.ci.toLong()
          info is CellInfoGsm -> info.cellIdentity.cid.toLong()
          info is CellInfoWcdma -> info.cellIdentity.cid.toLong()
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr ->
              (info.cellIdentity as CellIdentityNr).nci
          else -> return null
        }
    return if (id == Int.MAX_VALUE.toLong() || id == Long.MAX_VALUE) {
      null
    } else {
      "${info.javaClass.simpleName}:$id"
    }
  }

  companion object {
    private const val SCAN_MAX_AGE_MILLIS = 120_000L
  }
}
