package org.owntracks.android.services

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class WifiLinkWatcherTest {
  private class Link(
      wifi: Boolean = true,
      vpn: Boolean = false,
      trusted: Boolean = true,
      restricted: Boolean = false,
      val connected: Boolean = true,
  ) {
    val network = mock<Network>()
    val capabilities: NetworkCapabilities = mock {
      on { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } doReturn wifi
      on { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } doReturn vpn
      on { hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } doReturn !vpn
      on { hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED) } doReturn trusted
      on { hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) } doReturn !restricted
    }
    val info: NetworkInfo = mock { on { isConnected } doReturn connected }
  }

  private fun connectivityManager(vararg links: Link): ConnectivityManager = mock {
    on { allNetworks } doReturn links.map { it.network }.toTypedArray()
    for (link in links) {
      on { getNetworkCapabilities(link.network) } doReturn link.capabilities
      on { getNetworkInfo(link.network) } doReturn link.info
    }
  }

  @Test
  fun `A VPN over Wi-Fi is not a Wi-Fi network, the Wi-Fi under it is`() {
    val vpn = Link(vpn = true)
    val wifi = Link()
    val mobile = Link(wifi = false)
    assertEquals(
        listOf(wifi.network),
        WifiLinkWatcher.wifiNetworks(connectivityManager(vpn, wifi, mobile)),
    )
  }

  @Test
  fun `Only the Wi-Fi networks that the callback reports are seeded`() {
    val wifi = Link()
    val untrusted = Link(trusted = false)
    val restricted = Link(restricted = true)
    val connecting = Link(connected = false)
    assertEquals(
        listOf(wifi.network),
        WifiLinkWatcher.wifiNetworks(connectivityManager(untrusted, restricted, connecting, wifi)),
    )
  }
}
