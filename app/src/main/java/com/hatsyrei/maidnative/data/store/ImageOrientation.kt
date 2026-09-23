package com.hatsyrei.maidnative.data.store

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * Clockwise rotation [uri]'s EXIF orientation asks for, or 0. BitmapFactory
 * ignores the flag, so a portrait phone photo decodes sideways without it.
 */
internal fun ContentResolver.exifRotation(uri: Uri): Int =
    runCatching {
        openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrNull() ?: 0

/** This bitmap turned by [degrees], or the receiver itself when there is nothing to do. */
internal fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
