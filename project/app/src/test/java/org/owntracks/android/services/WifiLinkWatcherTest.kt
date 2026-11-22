package org.owntracks.android.services

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class WifiLinkWatcherTest {
  private fun capabilities(wifi: Boolean, vpn: Boolean): NetworkCapabilities = mock {
    on { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } doReturn wifi
    on { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } doReturn vpn
    on { hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } doReturn !vpn
  }

  @Test
  fun `A VPN over Wi-Fi is not a Wi-Fi network, the Wi-Fi under it is`() {
    val vpn = mock<Network>()
    val wifi = mock<Network>()
    val mobile = mock<Network>()
    val vpnCapabilities = capabilities(wifi = true, vpn = true)
    val wifiCapabilities = capabilities(wifi = true, vpn = false)
    val mobileCapabilities = capabilities(wifi = false, vpn = false)
    val connectivityManager =
        mock<ConnectivityManager> {
          on { activeNetwork } doReturn vpn
          on { allNetworks } doReturn arrayOf(vpn, wifi, mobile)
          on { getNetworkCapabilities(vpn) } doReturn vpnCapabilities
          on { getNetworkCapabilities(wifi) } doReturn wifiCapabilities
          on { getNetworkCapabilities(mobile) } doReturn mobileCapabilities
        }
    assertEquals(listOf(wifi), WifiLinkWatcher.wifiNetworks(connectivityManager))
  }
}
