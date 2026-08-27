package com.hatsyrei.maidnative.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL policy shared by the settings store and the network layer.
 *
 * Parsing goes through OkHttp's `HttpUrl` rather than string matching so that
 * the origin the app authorises is the one the request actually reaches: the
 * same parser resolves the host, the default port and any userinfo/path tricks
 * in a URL the user typed or a scan returned.
 */
object Endpoints {

    /**
     * `scheme://host:port` with the default port made explicit, or null when
     * [url] is not a usable absolute http(s) URL.
     *
     * This is the unit an API key is bound to. Path is deliberately excluded —
     * a key that works at `/v1` works at `/props` on the same server — but the
     * port is not, since a different port is a different service.
     */
    fun origin(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        return "${parsed.scheme}://${parsed.host}:${parsed.port}"
    }

    /** True when talking to [url] would put the API key on the wire in the clear. */
    fun isCleartext(url: String): Boolean = url.trim().toHttpUrlOrNull()?.isHttps == false
}
