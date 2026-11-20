package com.rocam.app.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Resolves the primary IPv4 address of the Android device.
     * Checks wlan0/wifi network interfaces first, then falls back to other non-loopback IPv4 addresses.
     */
    fun getLocalIpAddress(context: Context? = null): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Priority 1: wlan0 / wifi / softap / hotspot interfaces
            for (intf in interfaces) {
                val name = intf.name.lowercase(java.util.Locale.US)
                if (name.contains("wlan") ||
                    name.contains("wifi") ||
                    name.contains("ap") ||
                    name.contains("softap") ||
                    name.contains("tether") ||
                    name.contains("rndis") ||
                    name.contains("eth")
                ) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val hostAddress = addr.hostAddress
                            if (!hostAddress.isNullOrBlank() && !hostAddress.startsWith("127.")) {
                                return hostAddress
                            }
                        }
                    }
                }
            }

            // Priority 2: Any non-loopback IPv4 address
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        if (!hostAddress.isNullOrBlank() && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }

            // Priority 3: WifiManager fallback if Context provided
            if (context != null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wifiManager != null) {
                    val ipInt = wifiManager.connectionInfo.ipAddress
                    if (ipInt != 0) {
                        return String.format(
                            "%d.%d.%d.%d",
                            ipInt and 0xff,
                            ipInt shr 8 and 0xff,
                            ipInt shr 16 and 0xff,
                            ipInt shr 24 and 0xff
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
