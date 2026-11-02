package com.hatsyrei.maidnative.data.remote

import okhttp3.OkHttpClient

/**
 * The connection pool and dispatcher every HTTP client in the app derives from.
 * A client built from scratch brings its own of each; `newBuilder()` shares them
 * while each caller still sets its own timeouts and redirect policy.
 */
object Http {
    val client: OkHttpClient by lazy { OkHttpClient() }
}
