package com.hatsyrei.maidnative.data.remote

import android.util.Base64
import android.util.Base64OutputStream
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * A JSON request body whose attachment bytes are base64-encoded straight from
 * disk into the socket. Building the whole payload as one string held the raw
 * bytes, their base64, the JSON text and its UTF-8 encoding at once — for a
 * 20 MB audio clip, well over 150 MB of heap per send.
 *
 * The length is exact (base64 of a known file size is fixed), so the request
 * still goes out with a Content-Length rather than chunked.
 */
internal class JsonStreamBody private constructor(
    private val segments: List<Segment>,
) : RequestBody() {

    private sealed interface Segment {
        class Json(val bytes: ByteArray) : Segment

        class Encoded(val file: File) : Segment
    }

    private val length = segments.sumOf {
        when (it) {
            is Segment.Json -> it.bytes.size.toLong()
            is Segment.Encoded -> (it.file.length() + 2) / 3 * 4
        }
    }

    override fun contentType(): MediaType = JSON

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        for (segment in segments) {
            when (segment) {
                is Segment.Json -> sink.write(segment.bytes)
                is Segment.Encoded -> {
                    val out = Base64OutputStream(sink.outputStream(), Base64.NO_WRAP or Base64.NO_CLOSE)
                    segment.file.inputStream().use { it.copyTo(out) }
                    // Emits the final quantum and padding; NO_CLOSE leaves the sink open.
                    out.close()
                }
            }
        }
    }

    class Builder {
        private val segments = ArrayList<Segment>()
        private val pending = StringBuilder()

        /** Appends raw JSON text; the caller is responsible for its validity. */
        fun json(text: String): Builder = apply { pending.append(text) }

        /** Appends [file]'s bytes as base64, which needs no JSON escaping. */
        fun encoded(file: File): Builder = apply {
            flush()
            segments += Segment.Encoded(file)
        }

        fun build(): JsonStreamBody {
            flush()
            return JsonStreamBody(segments)
        }

        private fun flush() {
            if (pending.isEmpty()) return
            segments += Segment.Json(pending.toString().toByteArray(Charsets.UTF_8))
            pending.setLength(0)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
