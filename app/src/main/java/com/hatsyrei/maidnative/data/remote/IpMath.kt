package com.hatsyrei.maidnative.data.remote

/**
 * Pure IPv4 parsing / subnet math extracted from [EndpointScanner] so the
 * bit-twiddling can be reasoned about and unit-tested in isolation.
 */
internal object IpMath {

    fun isValidIpv4(ip: String): Boolean {
        val octets = ip.split(".")
        if (octets.size != 4) return false
        return octets.all { it.matches(Regex("\\d{1,3}")) && it.toInt() in 0..255 }
    }

    fun isValidPort(port: String): Boolean =
        port.matches(Regex("\\d{1,5}")) && port.toInt() in 1..65535

    fun ipToInt(ip: String): Long {
        val octets = ip.split(".").map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "Invalid IPv4 address" }
        return ((octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()) and 0xffffffffL
    }

    fun intToIp(value: Long): String = listOf(
        (value ushr 24) and 255,
        (value ushr 16) and 255,
        (value ushr 8) and 255,
        value and 255,
    ).joinToString(".")

    /**
     * All host addresses in [ip]'s /[prefixLength] subnet (excluding the network
     * and broadcast addresses, and [ip] itself), in ascending order.
     */
    fun buildSubnetTargets(ip: String, prefixLength: Int): List<String> {
        val ipInt = ipToInt(ip)
        val hostBits = 32 - prefixLength
        val networkMask = (0xffffffffL shl hostBits) and 0xffffffffL
        val networkBase = ipInt and networkMask
        val hostCount = (1L shl hostBits) - 2
        val targets = ArrayList<String>(hostCount.toInt().coerceAtLeast(0))
        var offset = 1L
        while (offset <= hostCount) {
            val candidate = intToIp((networkBase + offset) and 0xffffffffL)
            if (candidate != ip) targets.add(candidate)
            offset++
        }
        return targets
    }
}
