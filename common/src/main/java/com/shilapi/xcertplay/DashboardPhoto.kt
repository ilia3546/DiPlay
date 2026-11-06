package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The part of a stored picture that fills the dashboard once upright, and how far to subsample it. */
internal data class DashboardPhotoCrop(val region: Rect, val sample: Int)

/** The driver's own dashboard background: one 1920x720 JPEG in the app's private files. */
internal object DashboardPhoto {
    private const val TAG = "DiPlay-DashboardPhoto"
    private const val FILE = "dashboard_background.jpg"
    private const val QUALITY = 85
    private val TURNED = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun load(context: Context): Bitmap? = file(context).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /**
     * Off the main thread: decodes the middle of [uri] that fills the dashboard, turned as its EXIF orientation
     * says, and replaces the saved photo. Only that middle is decoded, so a long panorama fits in memory.
     */
    fun save(context: Context, uri: Uri): Boolean {
        val target = file(context)
        val staging = File(target.path + ".tmp")
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val orientation = runCatching {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
            val crop = crop(bounds.outWidth, bounds.outHeight, orientation)
            val decoded = resolver.openInputStream(uri)?.use { decodeRegion(it, crop) }
                ?: resolver.openInputStream(uri)?.use { decodeWhole(it, crop) }
                ?: return false
            val filled = fill(decoded, orientation)
            decoded.recycle()
            try {
                staging.outputStream().use { output ->
                    if (!filled.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) return false
                }
            } finally {
                filled.recycle()
            }
            if (!staging.renameTo(target)) return false
            Log.i(TAG, "dashboard photo saved (${bounds.outWidth}x${bounds.outHeight}, orientation $orientation, " +
                "region ${crop.region.toShortString()}, sample ${crop.sample})")
            return true
        } catch (error: Exception) {
            Log.w(TAG, "dashboard photo not saved (${error.javaClass.simpleName})")
            return false
        } catch (_: OutOfMemoryError) {
            Log.w(TAG, "dashboard photo not saved (out of memory)")
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

    /**
     * For a [width] x [height] picture stored with the EXIF [orientation]: the stored pixels of its middle that
     * fills the dashboard once upright, and the subsampling that leaves that middle at least the dashboard's size.
     */
    fun crop(width: Int, height: Int, orientation: Int): DashboardPhotoCrop {
        val turned = orientation in TURNED
        val upright = if (turned) centerCrop(height, width, MonjaroDashboardLayout.WIDTH, MonjaroDashboardLayout.HEIGHT)
        else centerCrop(width, height, MonjaroDashboardLayout.WIDTH, MonjaroDashboardLayout.HEIGHT)
        val sample = sampleSize(upright.width(), upright.height(), MonjaroDashboardLayout.WIDTH, MonjaroDashboardLayout.HEIGHT)
        // The middle is symmetric, so every turn stores it at the same place; only the axes swap.
        val region = if (turned) Rect(upright.top, upright.left, upright.bottom, upright.right) else upright
        return DashboardPhotoCrop(region, sample)
    }

    /** The turn and mirror that bring a picture stored with the EXIF [orientation] upright, about the origin. */
    fun upright(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    /** Null for a format the region decoder does not read. */
    private fun decodeRegion(input: InputStream, crop: DashboardPhotoCrop): Bitmap? {
        val decoder = try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(input, false)
        } catch (_: IOException) {
            null
        } ?: return null
        return try {
            decoder.decodeRegion(crop.region, BitmapFactory.Options().apply { inSampleSize = crop.sample })
        } finally {
            decoder.recycle()
        }
    }

    private fun decodeWhole(input: InputStream, crop: DashboardPhotoCrop): Bitmap? {
        val whole = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = crop.sample })
            ?: return null
        val region = Rect(crop.region.left / crop.sample, crop.region.top / crop.sample,
            crop.region.right / crop.sample, crop.region.bottom / crop.sample)
        if (!region.intersect(0, 0, whole.width, whole.height)) {
            whole.recycle()
            return null
        }
        val part = Bitmap.createBitmap(whole, region.left, region.top, region.width(), region.height())
        if (part !== whole) whole.recycle()
        return part
    }

    private fun fill(part: Bitmap, orientation: Int): Bitmap {
        val width = MonjaroDashboardLayout.WIDTH
        val height = MonjaroDashboardLayout.HEIGHT
        val matrix = upright(orientation)
        val turned = RectF(0f, 0f, part.width.toFloat(), part.height.toFloat()).also(matrix::mapRect)
        matrix.postTranslate(-turned.left, -turned.top)
        matrix.postScale(width / turned.width(), height / turned.height())
        val filled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(filled).drawBitmap(part, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return filled
    }
}
