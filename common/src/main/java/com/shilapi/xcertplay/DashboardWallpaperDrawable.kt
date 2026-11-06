package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the dashboard draws for a saved background, and the photo it needs: [photo] is read only for the driver's
 * own photo, and Ocean stands in while there is none.
 */
internal fun DashboardBackground.drawn(photo: () -> Bitmap?): Pair<DashboardBackground, Bitmap?> {
    if (this != DashboardBackground.CUSTOM_PHOTO) return this to null
    val picture = photo() ?: return DashboardBackground.OCEAN to null
    return this to picture
}

/** The alpha of the black layer over the wallpaper for a dimming percentage. */
internal fun dashboardDimmingAlpha(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100.0).roundToInt()

/** The source rectangle that fills a [targetWidth] x [targetHeight] frame from the middle of a picture. */
internal fun centerCrop(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Rect {
    if (width.toLong() * targetHeight > height.toLong() * targetWidth) {
        val cropped = (height.toLong() * targetWidth / targetHeight).toInt()
        val left = (width - cropped) / 2
        return Rect(left, 0, left + cropped, height)
    }
    val cropped = (width.toLong() * targetHeight / targetWidth).toInt()
    val top = (height - cropped) / 2
    return Rect(0, top, width, top + cropped)
}

/**
 * The dashboard background of design v2: the wallpaper's CSS layers, the dimming, and the vignette that keeps the
 * firmware's speed and bottom line readable. It is painted once into a bitmap, because the window redraws whole
 * frames while the map plays. CSS gradients fade to transparent in premultiplied colour, so each transparent stop
 * here keeps its neighbour's colour at alpha 0.
 */
internal class DashboardWallpaperDrawable(
    private val wallpaper: DashboardBackground,
    /** The picture of [DashboardBackground.CUSTOM_PHOTO], as [drawn] gives it. */
    private val photo: Bitmap?,
    dimmingPercent: Int,
) : Drawable() {
    private class Layer(val rect: RectF, val shader: Shader?, val color: Int = WHITE)

    private val dimming = dashboardDimmingAlpha(dimmingPercent)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var painted: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        painted = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val bitmap = painted ?: paint(bounds.width(), bounds.height()).also { painted = it }
        canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat(), null)
    }

    private fun paint(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val area = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(BLACK)
        paint.shader = null
        paint.color = WHITE
        photo?.let { canvas.drawBitmap(it, centerCrop(it.width, it.height, width, height), area, paint) }
        val vignette = listOf(
            Layer(area, vertical(area, intArrayOf(0x4D000000, 0, 0, 0x73000000), floatArrayOf(0f, 0.22f, 0.78f, 1f))),
            Layer(area, horizontal(area, intArrayOf(0x73000000, 0, 0, 0x73000000), floatArrayOf(0f, 0.28f, 0.72f, 1f))),
        )
        val dim = if (dimming > 0) listOf(Layer(area, null, dimming shl 24)) else emptyList()
        for (layer in layers(area) + dim + vignette) {
            paint.shader = layer.shader
            paint.color = layer.color
            canvas.drawRect(layer.rect, paint)
        }
        paint.shader = null
        return bitmap
    }

    /** The wallpaper's CSS background layers, bottom first. */
    private fun layers(area: RectF): List<Layer> = when (wallpaper) {
        DashboardBackground.OCEAN -> listOf(
            Layer(area, vertical(area,
                intArrayOf(0xFF02060F.toInt(), 0xFF0A1A36.toInt(), 0xFF3D6FAE.toInt(), 0xFF183154.toInt(),
                    0xFF0B1C36.toInt(), 0xFF030915.toInt()),
                floatArrayOf(0f, 0.30f, 0.46f, 0.485f, 0.70f, 1f))),
            oceanRipples(area),
            Layer(area, ellipse(area, 0.50f, 0.45f, 0.42f, 0.28f,
                intArrayOf(0x99A0CDFF.toInt(), 0x383C6EBE, 0x003C6EBE), floatArrayOf(0f, 0.55f, 0.78f))),
        )
        DashboardBackground.AURORA -> listOf(
            Layer(area, vertical(area, intArrayOf(0xFF020A0C.toInt(), 0xFF06171B.toInt(), 0xFF020506.toInt()),
                floatArrayOf(0f, 0.60f, 1f))),
            Layer(area, fade(area, 0.76f, 0.22f, 0.45f, 0.32f, 0x52785FEB, 0.70f)),
            Layer(area, fade(area, 0.28f, 0.28f, 0.55f, 0.38f, 0x613CDCAA, 0.70f)),
        )
        DashboardBackground.DUSK -> listOf(
            Layer(area, vertical(area,
                intArrayOf(0xFF0B0710.toInt(), 0xFF2A1430.toInt(), 0xFF6D3242.toInt(), 0xFF1C0E1A.toInt(),
                    0xFF08050A.toInt()),
                floatArrayOf(0f, 0.40f, 0.53f, 0.555f, 1f))),
            Layer(area, fade(area, 0.50f, 0.54f, 0.48f, 0.30f, 0x80FF965F.toInt(), 0.72f)),
        )
        DashboardBackground.NEON -> listOf(
            Layer(area, vertical(area, intArrayOf(0xFF05040C.toInt(), 0xFF130E24.toInt(), 0xFF05040A.toInt()),
                floatArrayOf(0f, 0.60f, 1f))),
            Layer(area, circle(area, 0.82f, 0.66f, intArrayOf(0x473CB4FF, 0x003CB4FF), floatArrayOf(0f, 0.32f))),
            Layer(area, circle(area, 0.18f, 0.72f, intArrayOf(0x47FF50A5, 0x00FF50A5), floatArrayOf(0f, 0.32f))),
        )
        DashboardBackground.GRAPHITE -> listOf(
            Layer(area, ellipse(area, 0.50f, 0.40f, 0.70f, 0.60f,
                intArrayOf(0xFF2B2E34.toInt(), 0xFF131418.toInt(), 0xFF07080A.toInt()), floatArrayOf(0f, 0.60f, 1f))),
        )
        DashboardBackground.MIDNIGHT -> listOf(
            Layer(area, vertical(area, intArrayOf(0xFF01030A.toInt(), 0xFF050B1F.toInt(), 0xFF0B1634.toInt()),
                floatArrayOf(0f, 0.60f, 1f))),
            Layer(area, fade(area, 0.50f, 1f, 0.80f, 0.40f, 0x59466EC8, 0.70f)),
        ) + MIDNIGHT_STARS.map { (x, y, radius, color) -> star(area, x, y, radius, color) }.reversed()
        DashboardBackground.FOREST -> listOf(
            Layer(area, vertical(area,
                intArrayOf(0xFF050A07.toInt(), 0xFF0E2218.toInt(), 0xFF1D3A28.toInt(), 0xFF0A1A10.toInt(),
                    0xFF040805.toInt()),
                floatArrayOf(0f, 0.38f, 0.50f, 0.53f, 1f))),
            Layer(area, fade(area, 0.50f, 0.38f, 0.60f, 0.35f, 0x3896C88C, 0.70f)),
        )
        DashboardBackground.DUNES -> listOf(
            Layer(area, vertical(area, intArrayOf(0xFF120A06.toInt(), 0xFF2C170B.toInt(), 0xFF160B05.toInt()),
                floatArrayOf(0f, 0.55f, 1f))),
            Layer(area, fade(area, 0.85f, 0.95f, 0.80f, 0.35f, 0x806E3C1E.toInt(), 0.70f)),
            Layer(area, fade(area, 0.20f, 0.85f, 0.90f, 0.40f, 0x73965528, 0.70f)),
            Layer(area, fade(area, 0.70f, 0.30f, 0.70f, 0.30f, 0x4DFFBE78, 0.70f)),
        )
        DashboardBackground.MAGMA -> listOf(
            Layer(area, vertical(area, intArrayOf(0xFF050303.toInt(), 0xFF140706.toInt(), 0xFF2A0C06.toInt()),
                floatArrayOf(0f, 0.60f, 1f))),
            Layer(area, fade(area, 0.80f, 1f, 0.30f, 0.14f, 0x59FF3C28, 0.70f)),
            Layer(area, fade(area, 0.22f, 1f, 0.30f, 0.14f, 0x59FF8C28, 0.70f)),
            Layer(area, fade(area, 0.50f, 1f, 0.50f, 0.22f, 0x8CFF5A1E.toInt(), 0.75f)),
        )
        DashboardBackground.ICE -> listOf(
            Layer(area, angled(area, 160f, intArrayOf(0xFF04121A.toInt(), 0xFF0B2A36.toInt(), 0xFF061820.toInt()),
                floatArrayOf(0f, 0.50f, 1f), Shader.TileMode.CLAMP)),
            Layer(area, fade(area, 0.80f, 0.75f, 0.50f, 0.40f, 0x405096C8, 0.70f)),
            Layer(area, fade(area, 0.30f, 0.20f, 0.60f, 0.45f, 0x478CDCEB, 0.70f)),
        )
        DashboardBackground.VIOLET -> listOf(
            Layer(area, vertical(area,
                intArrayOf(0xFF06040E.toInt(), 0xFF1B1238.toInt(), 0xFF3D2466.toInt(), 0xFF140C26.toInt(),
                    0xFF05030A.toInt()),
                floatArrayOf(0f, 0.42f, 0.55f, 0.575f, 1f))),
            Layer(area, fade(area, 0.50f, 0.58f, 0.45f, 0.26f, 0x66C88CFF, 0.72f)),
        )
        DashboardBackground.CARBON -> listOf(
            Layer(area, null, 0xFF0B0C0D.toInt()),
            carbonWeave(area),
            Layer(area, fade(area, 0.50f, 0.40f, 0.70f, 0.60f, 0x0FFFFFFF, 0.70f)),
        )
        DashboardBackground.BLACK -> emptyList()
        DashboardBackground.CUSTOM_PHOTO -> emptyList()
    }

    /** Ocean's water: a 2 px light line every 9 px over the bottom 52 %. */
    private fun oceanRipples(area: RectF): Layer {
        val water = RectF(area.left, area.bottom - area.height() * 0.52f, area.right, area.bottom)
        val ripples = LinearGradient(0f, water.top, 0f, water.top + 9f,
            intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x06FFFFFF, 0x06FFFFFF), floatArrayOf(0f, 7f / 9f, 7f / 9f, 1f),
            Shader.TileMode.REPEAT)
        return Layer(water, ripples)
    }

    /** Carbon's weave: 6 px stripes at 45 degrees, repeating every 12 px. */
    private fun carbonWeave(area: RectF): Layer {
        val stripes = angled(area, 45f, intArrayOf(0xFF121315.toInt(), 0xFF121315.toInt(), 0xFF0B0C0D.toInt(),
            0xFF0B0C0D.toInt()), floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT, period = 12f)
        return Layer(area, stripes)
    }

    /** A star of Midnight: a dot that fades out to [radius] px. */
    private fun star(area: RectF, x: Float, y: Float, radius: Float, color: Int): Layer {
        val cx = area.left + area.width() * x
        val cy = area.top + area.height() * y
        val shader = RadialGradient(cx, cy, radius, intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        return Layer(RectF(cx - radius, cy - radius, cx + radius, cy + radius), shader)
    }

    private fun vertical(area: RectF, colors: IntArray, stops: FloatArray): Shader =
        LinearGradient(0f, area.top, 0f, area.bottom, colors, stops, Shader.TileMode.CLAMP)

    private fun horizontal(area: RectF, colors: IntArray, stops: FloatArray): Shader =
        LinearGradient(area.left, 0f, area.right, 0f, colors, stops, Shader.TileMode.CLAMP)

    /**
     * CSS `linear-gradient(<degrees>deg)`: the line runs through the centre, clockwise from upwards, and is as
     * long as the box's projection on it; a [period] makes it a repeating gradient of that many pixels.
     */
    private fun angled(area: RectF, degrees: Float, colors: IntArray, stops: FloatArray, tile: Shader.TileMode,
        period: Float? = null): Shader {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val length = abs(area.width() * dx) + abs(area.height() * dy)
        val startX = area.centerX() - dx * length / 2
        val startY = area.centerY() - dy * length / 2
        val span = period ?: length
        return LinearGradient(startX, startY, startX + dx * span, startY + dy * span, colors, stops, tile)
    }

    /** A CSS ellipse that fades from [color] to transparent at [end]. */
    private fun fade(area: RectF, x: Float, y: Float, rx: Float, ry: Float, color: Int, end: Float): Shader =
        ellipse(area, x, y, rx, ry, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, end))

    /** CSS `radial-gradient(ellipse rx% ry% at x% y%)`: a circle of radius rx stretched to ry vertically. */
    private fun ellipse(area: RectF, x: Float, y: Float, rx: Float, ry: Float, colors: IntArray, stops: FloatArray): Shader {
        val cx = area.left + area.width() * x
        val cy = area.top + area.height() * y
        val radiusX = area.width() * rx
        val radiusY = area.height() * ry
        return RadialGradient(cx, cy, radiusX, colors, stops, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(1f, radiusY / radiusX, cx, cy) })
        }
    }

    /** CSS `radial-gradient(circle at x% y%)`, sized to the farthest corner. */
    private fun circle(area: RectF, x: Float, y: Float, colors: IntArray, stops: FloatArray): Shader {
        val cx = area.left + area.width() * x
        val cy = area.top + area.height() * y
        val radius = hypot(max(cx - area.left, area.right - cx), max(cy - area.top, area.bottom - cy))
        return RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private data class Star(val x: Float, val y: Float, val radius: Float, val color: Int)

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** In CSS order, topmost first. */
        val MIDNIGHT_STARS = listOf(
            Star(0.12f, 0.18f, 1.5f, WHITE),
            Star(0.27f, 0.09f, 1f, 0xFFCFE0FF.toInt()),
            Star(0.41f, 0.24f, 1.5f, WHITE),
            Star(0.58f, 0.12f, 1f, WHITE),
            Star(0.69f, 0.30f, 1.5f, 0xFFDFE8FF.toInt()),
            Star(0.83f, 0.15f, 1f, WHITE),
            Star(0.92f, 0.34f, 1f, WHITE),
            Star(0.06f, 0.40f, 1f, WHITE),
        )
    }
}
