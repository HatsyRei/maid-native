package com.hatsyrei.maidnative.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Local-network endpoint discovery, ported from the RN app's
 * `utilities/scan-endpoint.ts`. Probes `http://<host>:8080/v1/models` across the
 * device's /24 (then /21) subnet and returns the first OpenAI-compatible base URL.
 */
object EndpointScanner {

    private const val DEFAULT_PORT = 8080
    private const val REQUEST_TIMEOUT_MS = 400L
    private const val CONCURRENCY = 64

    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Parses a user-entered base URL and returns the canonical
     * `http://<ip>:<port>/v1` form, or `null` when the input is not a valid
     * `http://<ip>`, `http://<ip>:<port>`, or `http://<ip>:<port>/v1` address.
     * When no port is supplied, the default port is assumed.
     */
    fun normalizeBaseUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val match = Regex("^http://([^/:]+)(?::(\\d+))?(?:/v1)?$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed) ?: return null
        val ip = match.groupValues[1]
        val port = match.groupValues[2].ifEmpty { DEFAULT_PORT.toString() }
        if (!isValidIpv4(ip) || !isValidPort(port)) return null
        return "http://$ip:$port/v1"
    }

    /** Probe `{baseUrl}/models`; true when it looks OpenAI-compatible. */
    suspend fun validateEndpoint(baseUrl: String): Boolean =
        isOpenAiCompatible("${baseUrl.trimEnd('/')}/models")

    /** Scan the local subnet, returning the first OpenAI-compatible base URL. */
    suspend fun scanForEndpoint(): String? {
        val ip = localIpv4() ?: throw IllegalStateException("Could not determine local IP")

        scanTargets(buildSubnetTargets(ip, 24))?.let { return it }

        val subnet24 = buildSubnetTargets(ip, 24).toHashSet()
        val extended = buildSubnetTargets(ip, 21).filter { it !in subnet24 }
        return scanTargets(extended)
    }

    private suspend fun scanTargets(targets: List<String>): String? {
        var index = 0
        while (index < targets.size) {
            val batch = targets.subList(index, minOf(index + CONCURRENCY, targets.size))
            firstSuccess(batch)?.let { return it }
            index += CONCURRENCY
        }
        return null
    }

    /** Race a batch of probes, returning the first success (or null if none). */
    private suspend fun firstSuccess(batch: List<String>): String? = coroutineScope {
        val found = CompletableDeferred<String?>()
        val jobs = batch.map { target ->
            launch(Dispatchers.IO) {
                val result = probeTarget(target)
                if (result != null && !found.isCompleted) found.complete(result)
            }
        }
        launch {
            jobs.joinAll()
            if (!found.isCompleted) found.complete(null)
        }
        val result = found.await()
        coroutineContext.cancelChildren() // stop the remaining probes in this batch
        result
    }

    private suspend fun probeTarget(target: String): String? {
        val baseUrl = "http://$target:$DEFAULT_PORT"
        return if (isOpenAiCompatible("$baseUrl/v1/models")) "$baseUrl/v1" else null
    }

    private suspend fun isOpenAiCompatible(modelsUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(modelsUrl).get().build()).execute().use { response ->
                // 401/403 can still indicate a valid OpenAI-compatible endpoint.
                response.code in 200..499 && response.code != 404
            }
        }.getOrDefault(false)
    }

    private fun buildSubnetTargets(ip: String, prefixLength: Int): List<String> {
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

    private fun ipToInt(ip: String): Long {
        val octets = ip.split(".").map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "Invalid IPv4 address" }
        return ((octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()) and 0xffffffffL
    }

    private fun intToIp(value: Long): String = listOf(
        (value ushr 24) and 255,
        (value ushr 16) and 255,
        (value ushr 8) and 255,
        value and 255,
    ).joinToString(".")

    private fun localIpv4(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress

    private fun isValidIpv4(ip: String): Boolean {
        val octets = ip.split(".")
        if (octets.size != 4) return false
        return octets.all { it.matches(Regex("\\d{1,3}")) && it.toInt() in 0..255 }
    }

    private fun isValidPort(port: String): Boolean =
        port.matches(Regex("\\d{1,5}")) && port.toInt() in 1..65535
}
