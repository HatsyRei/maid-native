package com.hatsyrei.maidnative.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
        // A full scan is up to ~2300 probes. Running them as blocking `execute()`
        // calls on Dispatchers.IO consumed its entire default 64-thread pool for
        // the duration of the scan, starving Room writes, DataStore edits and the
        // models fetch. OkHttp's own dispatcher runs them asynchronously instead,
        // so no application thread is ever parked on a probe.
        .dispatcher(
            Dispatcher().apply {
                maxRequests = CONCURRENCY
                maxRequestsPerHost = CONCURRENCY
            },
        )
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
        if (!IpMath.isValidIpv4(ip) || !IpMath.isValidPort(port)) return null
        return "http://$ip:$port/v1"
    }

    /** Probe `{baseUrl}/models`; true when it looks OpenAI-compatible. */
    suspend fun validateEndpoint(baseUrl: String): Boolean =
        isOpenAiCompatible("${baseUrl.trimEnd('/')}/models")

    /** Scan the local subnet, returning the first OpenAI-compatible base URL. */
    suspend fun scanForEndpoint(): String? {
        val ip = localIpv4() ?: throw IllegalStateException("Could not determine local IP")

        val subnet24 = IpMath.buildSubnetTargets(ip, 24)
        probeAll(subnet24)?.let { return it }

        val seen = subnet24.toHashSet()
        return probeAll(IpMath.buildSubnetTargets(ip, 21).filter { it !in seen })
    }

    /**
     * Probe every target, at most [CONCURRENCY] at a time, and return the first
     * success.
     *
     * `flatMapMerge` keeps a *sliding* window of in-flight probes — a new one
     * starts the moment any finishes — where the previous fixed-batch loop made
     * every host in a batch wait on the slowest one. `firstOrNull` cancels the
     * upstream on the first hit, and because each probe suspends on a
     * cancellable `enqueue`, that cancels the outstanding OkHttp calls too.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun probeAll(targets: List<String>): String? =
        targets.asFlow()
            .flatMapMerge(CONCURRENCY) { target -> flow { probeTarget(target)?.let { emit(it) } } }
            .firstOrNull()

    private suspend fun probeTarget(target: String): String? {
        val baseUrl = "http://$target:$DEFAULT_PORT"
        return if (isOpenAiCompatible("$baseUrl/v1/models")) "$baseUrl/v1" else null
    }

    private suspend fun isOpenAiCompatible(modelsUrl: String): Boolean {
        val call = client.newCall(Request.Builder().url(modelsUrl).get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    // 401/403 can still indicate a valid OpenAI-compatible endpoint.
                    val ok = response.use { it.code in 200..499 && it.code != 404 }
                    if (continuation.isActive) continuation.resume(ok)
                }
            })
        }
    }

    private suspend fun localIpv4(): String? = withContext(Dispatchers.IO) {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}
