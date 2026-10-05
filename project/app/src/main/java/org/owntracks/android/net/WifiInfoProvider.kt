package org.owntracks.android.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.owntracks.android.model.messages.MessageStatus

@Singleton
class WifiInfoProvider @Inject constructor(@ApplicationContext context: Context) {
  @SuppressLint("WifiManagerPotentialLeak")
  private val wifiManager: WifiManager =
      context.getSystemService(Context.WIFI_SERVICE) as WifiManager

  // A network callback with the location flag counts as a location access at every change of the
  // network, like each time the screen turns on. Reading on demand keeps it to the messages.
  @Suppress("DEPRECATION") fun getBSSID(): String? = wifiManager.connectionInfo.bssid

  @Suppress("DEPRECATION") fun getSSID(): String? = wifiManager.connectionInfo.getUnquotedSSID()

  fun isConnected(): Boolean = getBSSID() != null

  fun isWiFiEnabled(): Int =
      if (wifiManager.isWifiEnabled) {
        MessageStatus.STATUS_WIFI_ENABLED
      } else {
        MessageStatus.STATUS_WIFI_DISABLED
      }
}

fun WifiInfo.getUnquotedSSID(): String = this.ssid.replace(Regex("^\"(.*)\"$"), "$1")
