package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import java.io.File

/** The driver's own dashboard background: one 1920x720 JPEG in the app's private files. */
internal object DashboardPhoto {
    private const val TAG = "DiPlay-DashboardPhoto"
    private const val FILE = "dashboard_background.jpg"
    private const val QUALITY = 85

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun load(context: Context): Bitmap? = file(context).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** Off the main thread: decodes [uri], fills the dashboard with its middle and replaces the saved photo. */
    fun save(context: Context, uri: Uri): Boolean {
        val target = file(context)
        val staging = File(target.path + ".tmp")
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
                    MonjaroDashboardLayout.WIDTH, MonjaroDashboardLayout.HEIGHT)
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return false
            val filled = fill(decoded)
            decoded.recycle()
            try {
                staging.outputStream().use { output ->
                    if (!filled.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) return false
                }
            } finally {
                filled.recycle()
            }
            if (!staging.renameTo(target)) return false
            Log.i(TAG, "dashboard photo saved (${bounds.outWidth}x${bounds.outHeight}, sample ${options.inSampleSize})")
            return true
        } catch (error: Exception) {
            Log.w(TAG, "dashboard photo not saved (${error.javaClass.simpleName})")
            return false
        } finally {
            staging.delete()
        }
    }

    /** The largest power of two that still leaves the picture at least [targetWidth] x [targetHeight]. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    private fun fill(picture: Bitmap): Bitmap {
        val width = MonjaroDashboardLayout.WIDTH
        val height = MonjaroDashboardLayout.HEIGHT
        val filled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(filled).drawBitmap(picture, centerCrop(picture.width, picture.height, width, height),
            Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return filled
    }
}
