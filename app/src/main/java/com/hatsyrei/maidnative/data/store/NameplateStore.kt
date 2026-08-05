package com.hatsyrei.maidnative.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

/**
 * Copies a user-picked image into app storage as the composer nameplate.
 *
 * The source is sub-sampled and centre-cropped on the way in: a picked photo can
 * be tens of megapixels, and holding that decoded behind a 60dp-tall pill would
 * dwarf the rest of the app's heap. Copying (rather than keeping the picker's
 * URI) also outlives the temporary read grant the photo picker hands out.
 */
class NameplateStore(private val context: Context) {

    val file: File get() = File(context.filesDir, FILE_NAME)

    /** Returns false if the image could not be decoded. */
    fun import(uri: Uri): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null in bounds mode, so the stream itself is the
        // only thing worth null-checking here.
        val header = resolver.openInputStream(uri) ?: return false
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return false

        val banner = crop(source)

        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        val written = runCatching {
            temp.outputStream().use { banner.compress(format(), QUALITY, it) }
        }.getOrDefault(false)
        if (banner !== source) banner.recycle()
        source.recycle()

        if (!written) {
            temp.delete()
            return false
        }
        val target = file
        target.delete()
        return temp.renameTo(target)
    }

    fun delete() {
        file.delete()
    }

    fun decode(): Bitmap? = file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /**
     * Widest centred region matching the pill's aspect, scaled down to a width
     * that still covers the largest phone screen at full density.
     */
    private fun crop(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val bandHeight = (width / ASPECT).roundToInt().coerceAtLeast(1)
        val cropped = if (bandHeight <= height) {
            Bitmap.createBitmap(source, 0, (height - bandHeight) / 2, width, bandHeight)
        } else {
            val bandWidth = (height * ASPECT).roundToInt().coerceIn(1, width)
            Bitmap.createBitmap(source, (width - bandWidth) / 2, 0, bandWidth, height)
        }
        if (cropped.width <= TARGET_WIDTH) return cropped
        val scaledHeight = (cropped.height * TARGET_WIDTH.toFloat() / cropped.width).roundToInt()
        val scaled = Bitmap.createScaledBitmap(cropped, TARGET_WIDTH, scaledHeight.coerceAtLeast(1), true)
        if (scaled !== cropped && cropped !== source) cropped.recycle()
        return scaled
    }

    /** Largest power-of-two shrink that still leaves the crop above [TARGET_WIDTH]. */
    private fun sampleSize(width: Int, height: Int): Int {
        // A tall source loses most of its height to the crop, so size on the
        // dimension that actually survives it.
        val effective = if (height * ASPECT < width) (height * ASPECT).roundToInt() else width
        var sample = 1
        while (effective / (sample * 2) >= TARGET_WIDTH) sample *= 2
        return sample
    }

    @Suppress("DEPRECATION")
    private fun format(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    private companion object {
        const val FILE_NAME = "nameplate.webp"
        const val TARGET_WIDTH = 1440
        const val ASPECT = 6f
        const val QUALITY = 88
    }
}
