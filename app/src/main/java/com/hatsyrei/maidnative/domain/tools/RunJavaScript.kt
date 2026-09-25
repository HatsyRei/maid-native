package com.hatsyrei.maidnative.domain.tools

import android.annotation.SuppressLint
import android.content.Context
import androidx.concurrent.futures.await
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exact computation for the model, run in the WebView's isolated sandbox
 * process: no network, files or device access, and nothing shipped in the APK.
 */
object RunJavaScript : Tool {
    override val name = "run_javascript"
    override val label = "JavaScript"
    override val summary = "Run code in a sandbox for exact math and data work."
    override val description =
        "Run JavaScript in an isolated sandbox and return the value of the last expression, plus " +
            "anything printed with console.log. Use it for arithmetic, unit conversions, date math, " +
            "counting and any other exact computation instead of working it out yourself. There is no " +
            "network, file system, DOM or require, and each call starts fresh. Do not use a top-level " +
            "return; for async code end with a Promise, e.g. (async () => { ... })()."

    private const val TIMEOUT_MS = 10_000L
    private const val MAX_HEAP_BYTES = 64L * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 20_000

    // Only ever the application context.
    @SuppressLint("StaticFieldLeak")
    private var context: Context? = null

    // The library allows one sandbox per app at a time.
    private val lock = Mutex()

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "code",
                JSONObject()
                    .put("type", "string")
                    .put("description", "JavaScript to run; the value of the last expression is returned."),
            ),
        )
        .put("required", JSONArray().put("code"))

    override suspend fun invoke(arguments: JSONObject): String {
        val code = arguments.opt("code") as? String
        require(!code.isNullOrBlank()) { "\"code\" is required" }
        val context = context
        check(context != null && JavaScriptSandbox.isSupported()) {
            "JavaScript is unavailable: it needs an up-to-date Android System WebView"
        }
        return lock.withLock {
            // Opened per call: the sandbox is a whole process, and tool calls are rare.
            JavaScriptSandbox.createConnectedInstanceAsync(context).await().use { sandbox ->
                val params = IsolateStartupParameters()
                if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
                    params.maxHeapSizeBytes = MAX_HEAP_BYTES
                }
                val script = wrap(code, sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN))
                sandbox.createIsolate(params).use { isolate ->
                    // Not withTimeout: its CancellationException would end the whole reply, not just this call.
                    withTimeoutOrNull(TIMEOUT_MS) { isolate.evaluateJavaScriptAsync(script).await() }
                        ?: error("Timed out after ${TIMEOUT_MS / 1000} s")
                }
            }
        }
    }

    /**
     * [code] evaluated globally, its result and console output folded into one
     * JSON string: the sandbox returns only strings, and only the last expression.
     */
    internal fun wrap(code: String, promises: Boolean): String = """
        (() => {
          const out = [];
          const show = (v) => {
            if (typeof v === 'string') return v;
            try { const j = JSON.stringify(v); if (j !== undefined) return j; } catch (e) {}
            return String(v);
          };
          const cut = (s) => s.length > $MAX_OUTPUT_CHARS ? s.slice(0, $MAX_OUTPUT_CHARS) + ' [truncated]' : s;
          const log = (...a) => { out.push(a.map(show).join(' ')); };
          globalThis.console = { log, info: log, warn: log, error: log, debug: log };
          const done = (fields) => JSON.stringify(out.length ? { ...fields, console: cut(out.join('\n')) } : fields);
          try {
            const r = (0, eval)(${JSONObject.quote(code)});
            if ($promises && r instanceof Promise) {
              return r.then((v) => done({ result: cut(show(v)) }), (e) => done({ error: cut(String(e)) }));
            }
            return done({ result: cut(show(r)) });
          } catch (e) {
            return done({ error: cut(String(e)) });
          }
        })()
    """.trimIndent()
}
