package org.owntracks.android.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Reports when the device joins its first Wi-Fi network or leaves its last one. Either is a strong
 * hint that the device moved: leaving home drops the home network before any location changes.
 *
 * A network lost for less than [LOST_GRACE_MILLIS] is neither lost nor joined: access points drop
 * and roam clients for a few seconds while the device stays. A wake lock holds the CPU for that
 * time: the handler clock stops while the device sleeps, and the loss came minutes late.
 */
class WifiLinkWatcher(context: Context, private val onChange: (joined: Boolean) -> Unit) {
  private val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val handler = Handler(Looper.getMainLooper())
  private val wakeLock =
      (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OwnTracks:WifiLinkWatcher")
          .apply { setReferenceCounted(false) }

  // Only touched on the main thread.
  private val networks = mutableSetOf<Network>()
  private var callback: ConnectivityManager.NetworkCallback? = null
  private val reportLost = Runnable {
    lostPending = false
    releaseWakeLock()
    onChange(false)
  }
  private var lostPending = false

  /** True while the device is on a Wi-Fi network. Only read on the main thread. */
  val joined: Boolean
    get() = networks.isNotEmpty() || lostPending

  /** Can be called from any thread, like [stop]. */
  fun start() {
    handler.post(::register)
  }

  fun stop() {
    handler.post {
      callback?.let { connectivityManager.unregisterNetworkCallback(it) }
      callback = null
      cancelLost()
    }
  }

  private fun cancelLost() {
    handler.removeCallbacks(reportLost)
    lostPending = false
    releaseWakeLock()
  }

  private fun releaseWakeLock() {
    if (wakeLock.isHeld) wakeLock.release()
  }

  private fun register() {
    if (callback != null) {
      return
    }
    networks.clear()
    // The callback reports the networks that already exist as new, so they must not count as a
    // join.
    networks.addAll(wifiNetworks(connectivityManager))
    val newCallback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            handler.post {
              if (callback === this && networks.add(network) && networks.size == 1) {
                if (lostPending) cancelLost() else onChange(true)
              }
            }
          }

          override fun onLost(network: Network) {
            handler.post {
              if (callback === this && networks.remove(network) && networks.isEmpty()) {
                lostPending = true
                wakeLock.acquire(LOST_GRACE_MILLIS * 2)
                handler.postDelayed(reportLost, LOST_GRACE_MILLIS)
              }
            }
          }
        }
    callback = newCallback
    connectivityManager.registerNetworkCallback(
        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
        newCallback,
    )
  }

  companion object {
    // Measured drops at home lasted 4-30 s.
    private const val LOST_GRACE_MILLIS = 45_000L

    /**
     * The Wi-Fi networks that the callback reports, which exclude VPNs. A VPN over Wi-Fi also has
     * the Wi-Fi transport, and is the active network: it would never be reported lost.
     */
    // The callback replacement for allNetworks reports each network as new.
    @Suppress("DEPRECATION")
    internal fun wifiNetworks(connectivityManager: ConnectivityManager): List<Network> =
        connectivityManager.allNetworks.filter {
          connectivityManager.getNetworkCapabilities(it)?.let { capabilities ->
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
          } == true
        }
  }
}
