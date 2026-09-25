package com.hatsyrei.maidnative.domain.tools

import android.text.Html
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A plain HTTP GET, so the model can read a page instead of guessing at it. */
object FetchUrl : Tool {
    override val name = "fetch_url"
    override val label = "Fetch URL"
    override val summary = "Read a web page or text file, local network included."
    override val description =
        "Fetch a URL with an HTTP GET request and return its content as text; web pages are " +
            "converted to plain text. Use it to read a page the user mentions or to check a source. " +
            "Localhost and local network addresses work too. Long content is cut off."

    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_CHARS = 20_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) MaidNative"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "url",
                JSONObject().put("type", "string").put("description", "The http or https URL to fetch."),
            ),
        )
        .put("required", JSONArray().put("url"))

    override suspend fun invoke(arguments: JSONObject): String {
        val raw = (arguments.opt("url") as? String)?.trim()
        require(!raw.isNullOrEmpty()) { "\"url\" is required" }
        val url = (if ("://" in raw) raw else "https://$raw").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Not an http or https URL: $raw")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

                override fun onResponse(call: Call, response: Response) =
                    cont.resumeWith(runCatching { response.use { read(it, ::htmlToText) } })
            })
        }
    }

    /** [toText] turns an HTML page into readable text; a parameter because android.text.Html is a stub in unit tests. */
    internal fun read(response: Response, toText: (String) -> String): String {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}".trim())
        val body = response.body
        val type = body.contentType()
        require(type == null || isText(type)) { "Cannot read ${type?.type}/${type?.subtype} content as text" }
        val source = body.source()
        val cut = source.request(MAX_BYTES + 1)
        val raw = String(source.buffer.readByteArray(minOf(source.buffer.size, MAX_BYTES)), type?.charset() ?: Charsets.UTF_8)
        val html = type?.subtype in HTML_TYPES || (type == null && raw.trimStart().startsWith("<"))

        val out = JSONObject().put("url", response.request.url.toString())
        if (html) title(raw)?.let { out.put("title", toText(it)) }
        val content = if (html) toText(stripHidden(raw)) else raw
        out.put("content", content.take(MAX_CHARS))
        if (cut || content.length > MAX_CHARS) out.put("truncated", true)
        return out.toString()
    }

    private val HTML_TYPES = setOf("html", "xhtml+xml")
    private val TEXT_SUBTYPES = setOf("json", "xml", "javascript", "ecmascript", "yaml", "x-yaml", "toml", "csv", "x-ndjson")

    internal fun isText(type: MediaType): Boolean =
        type.type == "text" || type.subtype in HTML_TYPES || type.subtype in TEXT_SUBTYPES ||
            type.subtype.endsWith("+json") || type.subtype.endsWith("+xml")

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val HIDDEN = Regex("""<!--.*?-->|<(script|style|noscript|template|svg|head)\b[^>]*>.*?</\1\s*>""", OPTIONS)
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title\s*>""", OPTIONS)
    private val SPACES = Regex("""[ \t\u00A0]+""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    /** [html] without the parts a reader never sees, which Html.fromHtml would otherwise print. */
    internal fun stripHidden(html: String): String = HIDDEN.replace(html, "")

    internal fun title(html: String): String? = TITLE.find(html)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private fun htmlToText(html: String): String =
        tidy(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replace('\uFFFC', ' '))

    internal fun tidy(text: String): String =
        text.lines().joinToString("\n") { it.replace(SPACES, " ").trim() }.replace(BLANK_LINES, "\n\n").trim()
}
