package org.owntracks.android.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Reports when the device joins its first Wi-Fi network or leaves its last one. Either is a strong
 * hint that the device moved: leaving home drops the home network before any location changes.
 */
class WifiLinkWatcher(context: Context, private val onChange: (joined: Boolean) -> Unit) {
  private val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val handler = Handler(Looper.getMainLooper())

  // Only touched on the main thread.
  private val networks = mutableSetOf<Network>()
  private var callback: ConnectivityManager.NetworkCallback? = null

  /** Can be called from any thread, like [stop]. */
  fun start() {
    handler.post(::register)
  }

  fun stop() {
    handler.post {
      callback?.let { connectivityManager.unregisterNetworkCallback(it) }
      callback = null
    }
  }

  private fun register() {
    if (callback != null) {
      return
    }
    networks.clear()
    // The callback reports the networks that already exist as new, so they must not count as a
    // join.
    connectivityManager.activeNetwork
        ?.takeIf {
          connectivityManager
              .getNetworkCapabilities(it)
              ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        ?.let { networks.add(it) }
    val newCallback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            handler.post {
              if (callback === this && networks.add(network) && networks.size == 1) onChange(true)
            }
          }

          override fun onLost(network: Network) {
            handler.post {
              if (callback === this && networks.remove(network) && networks.isEmpty())
                  onChange(false)
            }
          }
        }
    callback = newCallback
    connectivityManager.registerNetworkCallback(
        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
        newCallback,
    )
  }
}
