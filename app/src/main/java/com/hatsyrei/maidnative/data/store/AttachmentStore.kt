package com.hatsyrei.maidnative.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.hatsyrei.maidnative.domain.Attachment
import com.hatsyrei.maidnative.domain.tree.MessageNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Copies user-picked attachments into app storage and hands back descriptors.
 *
 * Copying is not optional: the photo picker and SAF both grant read access that
 * dies with the process (or the reboot), so a conversation that merely
 * remembered the source URI would show broken attachments the next morning.
 *
 * Images are capped on the way in. Beyond roughly one megapixel a vision model
 * sees no extra detail — measured against gemma-4, prompt cost plateaus at 282
 * tokens from 2 MP upward — while the base64 payload and the decode heap keep
 * growing, so a 12 MP camera shot would cost megabytes of upload for nothing.
 */
class AttachmentStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    sealed interface ImportResult {
        data class Success(val attachment: Attachment) : ImportResult

        data class Failure(val reason: String) : ImportResult
    }

    fun import(uri: Uri, kind: Attachment.Kind): ImportResult {
        val source = describe(uri, kind)
        return runCatching {
            if (kind == Attachment.Kind.IMAGE) {
                // No pre-check: an oversized photo is downscaled rather than refused.
                importImage(uri, source)
            } else {
                val ceiling = if (kind == Attachment.Kind.AUDIO) MAX_AUDIO_BYTES else MAX_TEXT_BYTES
                if (source.size > ceiling) {
                    ImportResult.Failure(tooLarge(source.name, ceiling))
                } else {
                    copyVerbatim(uri, source, ceiling)
                }
            }
        }.getOrElse { ImportResult.Failure(unreadable(source.name)) }
    }

    fun delete(attachments: Iterable<Attachment>) {
        for (attachment in attachments) File(attachment.path).delete()
    }

    /**
     * Deletes stored files that no path in [referenced] names. Attachments are
     * written to disk the moment they are picked, but a composer that never
     * sends keeps them only in memory, so a kill leaves them unreachable —
     * startup is the one place that can still collect them.
     */
    fun sweep(referenced: Set<String>) {
        val files = dir.listFiles() ?: return
        for (file in files) if (file.path !in referenced) file.delete()
    }

    // ---- export / import -------------------------------------------------

    /**
     * Rewrites [node]'s attachment records to carry their bytes inline, so an
     * export stays readable after an app wipe or on another device. The local
     * path is dropped on the way out: it means nothing anywhere else.
     */
    fun embed(node: MessageNode): MessageNode {
        val attachments = node.attachments()
        if (attachments.isEmpty()) return node
        val array = JSONArray()
        for (attachment in attachments) {
            val bytes = runCatching { File(attachment.path).readBytes() }.getOrNull() ?: continue
            array.put(
                JSONObject()
                    .put("id", attachment.id)
                    .put("kind", attachment.kind.name)
                    .put("name", attachment.name)
                    .put("mime", attachment.mime)
                    .put("size", attachment.sizeBytes)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
            )
        }
        return node.copy(metadata = node.metadata.replacingAttachments(array))
    }

    /**
     * Inverse of [embed]: writes inlined bytes into app storage under a fresh
     * id and restores a local path. Records without `data` come from an older
     * export and are passed through, still pointing at whatever the exporting
     * device called them.
     */
    fun materialize(node: MessageNode): MessageNode {
        val raw = node.metadata[METADATA_KEY] as? JSONArray ?: return node
        val array = JSONArray()
        for (index in 0 until raw.length()) {
            val entry = raw.optJSONObject(index) ?: continue
            val data = entry.optString("data").takeIf { it.isNotEmpty() }
            if (data == null) {
                array.put(entry)
                continue
            }
            val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > MAX_EMBED_BYTES) continue
            val id = UUID.randomUUID().toString()
            val target = File(dir, id + extensionOf(entry.optString("name")))
            val written = runCatching { target.writeBytes(bytes) }.isSuccess
            if (!written) {
                target.delete()
                continue
            }
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("kind", entry.optString("kind"))
                    .put("name", entry.optString("name"))
                    .put("mime", entry.optString("mime"))
                    .put("size", bytes.size.toLong())
                    .put("path", target.path),
            )
        }
        return node.copy(metadata = node.metadata.replacingAttachments(array))
    }

    // ---- images ----------------------------------------------------------

    private fun importImage(uri: Uri, source: Source): ImportResult {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null in bounds mode, so the stream itself is the
        // only thing worth null-checking here.
        val header = resolver.openInputStream(uri) ?: return ImportResult.Failure(unreadable(source.name))
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ImportResult.Failure("\"${source.name}\" is not a readable image.")
        }

        // Neither BitmapFactory nor llama.cpp's stb_image honours the EXIF
        // orientation flag, so a portrait phone photo arrives sideways unless
        // the rotation is baked into the pixels here.
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val pixels = bounds.outWidth.toLong() * bounds.outHeight

        // A screenshot is already small and re-encoding it to JPEG would only
        // smear its text, so pass it through untouched when nothing needs doing.
        if (rotation == 0 &&
            pixels <= MAX_PIXELS &&
            source.mime in PASSTHROUGH_MIME &&
            source.size in 1..MAX_PASSTHROUGH_BYTES
        ) {
            return copyVerbatim(uri, source, MAX_PASSTHROUGH_BYTES)
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(pixels) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return ImportResult.Failure(unreadable(source.name))
        val capped = scaleToCap(decoded)
        val oriented = rotate(capped, rotation)

        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.jpg")
        val written = runCatching {
            target.outputStream().use { oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (oriented !== capped) oriented.recycle()
        if (capped !== decoded) capped.recycle()
        decoded.recycle()
        if (!written) {
            target.delete()
            return ImportResult.Failure(unreadable(source.name))
        }
        return ImportResult.Success(
            Attachment(
                id = id,
                kind = Attachment.Kind.IMAGE,
                name = source.name,
                mime = "image/jpeg",
                sizeBytes = target.length(),
                path = target.path,
            ),
        )
    }

    /** Largest power-of-two shrink that still leaves the bitmap at or above the cap. */
    private fun sampleSize(pixels: Long): Int {
        var sample = 1
        while (pixels / (sample.toLong() * sample * 4) >= MAX_PIXELS) sample *= 2
        return sample
    }

    private fun scaleToCap(source: Bitmap): Bitmap {
        val total = source.width.toLong() * source.height
        if (total <= MAX_PIXELS) return source
        val factor = sqrt(MAX_PIXELS.toDouble() / total)
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).roundToInt().coerceAtLeast(1),
            (source.height * factor).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun exifRotation(stream: InputStream): Int =
        runCatching {
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

    // ---- verbatim copies -------------------------------------------------

    private fun copyVerbatim(uri: Uri, source: Source, ceiling: Long): ImportResult {
        val id = UUID.randomUUID().toString()
        val target = File(dir, id + extensionOf(source.name))
        var copied = 0L
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> copied = input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!ok || copied == 0L) {
            target.delete()
            return ImportResult.Failure(unreadable(source.name))
        }
        // The provider may under-report SIZE (or report none at all), so the
        // ceiling is enforced again against what actually landed on disk.
        if (copied > ceiling) {
            target.delete()
            return ImportResult.Failure(tooLarge(source.name, ceiling))
        }
        return ImportResult.Success(
            Attachment(
                id = id,
                kind = source.kind,
                name = source.name,
                mime = source.mime,
                sizeBytes = copied,
                path = target.path,
            ),
        )
    }

    private fun unreadable(name: String) = "Could not read \"$name\"."

    private fun tooLarge(name: String, ceiling: Long) =
        "\"$name\" is too large (limit ${ceiling / 1024} KB)."

    // ---- source metadata -------------------------------------------------

    private class Source(
        val kind: Attachment.Kind,
        val name: String,
        val mime: String,
        val size: Long,
    )

    private fun describe(uri: Uri, kind: Attachment.Kind): Source {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: defaultMime(kind)
        return Source(
            kind = kind,
            name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "attachment",
            mime = mime,
            size = size,
        )
    }

    private fun defaultMime(kind: Attachment.Kind): String = when (kind) {
        Attachment.Kind.IMAGE -> "image/jpeg"
        Attachment.Kind.AUDIO -> "audio/wav"
        Attachment.Kind.TEXT -> "text/plain"
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        val ext = name.substring(dot)
        return if (ext.length <= 6 && ext.drop(1).all { it.isLetterOrDigit() }) ext.lowercase() else ""
    }

    private companion object {
        const val DIR_NAME = "attachments"
        const val MAX_PIXELS = 2_000_000L
        const val JPEG_QUALITY = 85
        const val MAX_PASSTHROUGH_BYTES = 4L * 1024 * 1024
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024

        // Matches the viewer's read cap, so an imported file is always fully readable.
        const val MAX_TEXT_BYTES = 256L * 1024

        // Ceiling for a single inlined blob on import, so a corrupt or hostile
        // export cannot allocate the heap out from under the app.
        const val MAX_EMBED_BYTES = 32 * 1024 * 1024
        val PASSTHROUGH_MIME = setOf("image/jpeg", "image/png", "image/webp")
    }
}

private const val METADATA_KEY = "attachments"

/**
 * Attachments ride in [MessageNode.metadata], which Room and the export format
 * both persist as free-form JSON — so this needs no schema migration and old
 * conversations simply carry no such key.
 */
fun MessageNode.attachments(): List<Attachment> {
    val raw = metadata[METADATA_KEY] as? JSONArray ?: return emptyList()
    return (0 until raw.length()).mapNotNull { index ->
        val entry = raw.optJSONObject(index) ?: return@mapNotNull null
        val kind = runCatching { Attachment.Kind.valueOf(entry.optString("kind")) }.getOrNull()
            ?: return@mapNotNull null
        Attachment(
            id = entry.optString("id"),
            kind = kind,
            name = entry.optString("name"),
            mime = entry.optString("mime"),
            sizeBytes = entry.optLong("size"),
            path = entry.optString("path"),
        )
    }
}

/** Returns [metadata] carrying [attachments], dropping the key when there are none. */
fun withAttachments(metadata: Map<String, Any?>, attachments: List<Attachment>): Map<String, Any?> {
    val array = JSONArray()
    for (attachment in attachments) {
        array.put(
            JSONObject()
                .put("id", attachment.id)
                .put("kind", attachment.kind.name)
                .put("name", attachment.name)
                .put("mime", attachment.mime)
                .put("size", attachment.sizeBytes)
                .put("path", attachment.path),
        )
    }
    return metadata.replacingAttachments(array)
}

private fun Map<String, Any?>.replacingAttachments(array: JSONArray): Map<String, Any?> =
    if (array.length() == 0) this - METADATA_KEY else this + (METADATA_KEY to array)
