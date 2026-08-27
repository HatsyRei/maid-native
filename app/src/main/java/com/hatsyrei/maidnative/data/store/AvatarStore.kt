package com.hatsyrei.maidnative.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.InputStream
import kotlin.math.min

/**
 * Copies a user-picked image into app storage as a role's profile picture.
 *
 * Same reasoning as [NameplateStore]: a picked photo can be tens of megapixels,
 * which would dwarf the app's heap behind a 28dp circle, and the photo picker's
 * read grant does not survive a reboot. The source is sub-sampled, turned
 * upright, centre-cropped square and copied in.
 */
class AvatarStore(private val context: Context) {

    enum class Role(internal val fileName: String) {
        USER("avatar-user.webp"),
        ASSISTANT("avatar-assistant.webp"),
    }

    private fun file(role: Role): File = File(context.filesDir, role.fileName)

    /** Returns false if the image could not be decoded. */
    fun import(role: Role, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null in bounds mode, so the stream itself is the
        // only thing worth null-checking here.
        val header = resolver.openInputStream(uri) ?: return false
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // BitmapFactory ignores the EXIF orientation flag, so a portrait phone
        // photo would be cropped — and shown — sideways.
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(min(bounds.outWidth, bounds.outHeight))
        }
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false

        val oriented = rotate(source, rotation)
        val square = crop(oriented)

        val temp = File(context.filesDir, "${role.fileName}.tmp")
        val written = runCatching {
            temp.outputStream().use { square.compress(format(), QUALITY, it) }
        }.getOrDefault(false)
        if (square !== oriented) square.recycle()
        if (oriented !== source) oriented.recycle()
        source.recycle()

        if (!written) {
            temp.delete()
            return false
        }
        val target = file(role)
        target.delete()
        return temp.renameTo(target)
    }

    fun delete(role: Role) {
        file(role).delete()
    }

    fun decode(role: Role): Bitmap? =
        file(role).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Largest centred square, scaled down to [TARGET_PX]. */
    private fun crop(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )
        if (cropped.width <= TARGET_PX) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, TARGET_PX, TARGET_PX, true)
        if (scaled !== cropped && cropped !== source) cropped.recycle()
        return scaled
    }

    /** Largest power-of-two shrink that still leaves the square above [TARGET_PX]. */
    private fun sampleSize(side: Int): Int {
        var sample = 1
        while (side / (sample * 2) >= TARGET_PX) sample *= 2
        return sample
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

    private fun format(): Bitmap.CompressFormat =
        // Lossy WebP would flatten a transparent PNG's alpha onto a background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.PNG
        }

    private companion object {
        const val TARGET_PX = 256
        const val QUALITY = 88
    }
}
