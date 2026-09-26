package com.saab.tv.remote_input

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility to get the device's local IPv4 address for the web server.
 */
object NetworkUtils {

    /**
     * Finds the first non-loopback IPv4 address on the device.
     * Returns null if no suitable address is found.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<AddressCandidate>()
            for (networkInterface in interfaces) {
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                for (address in networkInterface.inetAddresses) {
                    // Only consider IPv4 addresses
                    if (address is Inet4Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        candidates += AddressCandidate(
                            address = address.hostAddress ?: continue,
                            priority = interfacePriority(
                                interfaceName = networkInterface.name.orEmpty(),
                                isSiteLocal = address.isSiteLocalAddress,
                                isVirtual = networkInterface.isVirtual
                            )
                        )
                    }
                }
            }
            return candidates.maxByOrNull(AddressCandidate::priority)?.address
        } catch (e: Exception) {
            if (com.saab.tv.BuildConfig.DEBUG) android.util.Log.w("NetworkUtils", "Failed to get local IP", e)
        }
        return null
    }

    internal fun interfacePriority(
        interfaceName: String,
        isSiteLocal: Boolean,
        isVirtual: Boolean
    ): Int {
        val name = interfaceName.lowercase()
        var score = if (isSiteLocal) 100 else 0
        score += when {
            name.startsWith("wlan") || name.startsWith("wifi") -> 50
            name.startsWith("eth") || name.startsWith("en") -> 45
            name.startsWith("ap") -> 20
            else -> 0
        }
        if (!isVirtual) score += 10
        if (listOf("tun", "tap", "vpn", "rmnet", "p2p", "dummy").any(name::startsWith)) {
            score -= 200
        }
        return score
    }

    private data class AddressCandidate(val address: String, val priority: Int)
}
