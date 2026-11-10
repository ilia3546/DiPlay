package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import kotlin.math.roundToInt

/**
 * The frosted backdrop of design v4 under the firmware's driver assistance icon: a blurred, slightly more saturated
 * copy of the wallpaper and the map card under it, tinted and bordered. It copies them outside drawing, when the
 * owner says that they changed and, while the map plays under it, every [L.ICON_BACKDROP_REFRESH_MILLIS]. The
 * icon does not move, so the backdrop only fades in at the start-up.
 */
internal class DashboardIconBackdrop(
    context: Context,
    private val wallpaper: View,
    private val map: DashboardMapCard,
) : View(context) {
    private val box = L.iconBackdrop
    private val margin = L.ICON_BACKDROP_BLUR_RADIUS * L.ICON_BACKDROP_BLUR_PASSES * L.ICON_BACKDROP_DOWNSCALE
    private val area = Rect(box.left - margin, box.top - margin, box.left + box.width + margin,
        box.top + checkNotNull(box.height) + margin)
    private val mapArea = Rect()
    private val copy = Bitmap.createBitmap(area.width() / L.ICON_BACKDROP_DOWNSCALE,
        area.height() / L.ICON_BACKDROP_DOWNSCALE, Bitmap.Config.ARGB_8888)
    private val copyCanvas = Canvas(copy)
    private val pixels = IntArray(copy.width * copy.height)
    private val scratch = IntArray(pixels.size)
    private var copiedWallpaperAlpha = -1f
    private val shadow = DashboardShadow(L.ICON_BACKDROP_RADIUS, L.ICON_BACKDROP_SHADOW_DY, L.ICON_BACKDROP_SHADOW_BLUR,
        L.ICON_BACKDROP_SHADOW)
    private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(copy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(L.ICON_BACKDROP_DOWNSCALE.toFloat(), L.ICON_BACKDROP_DOWNSCALE.toFloat())
                postTranslate(-margin.toFloat(), -margin.toFloat())
            })
        }
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(L.ICON_BACKDROP_SATURATION) })
    }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = L.ICON_BACKDROP_TINT }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = L.ICON_BACKDROP_BORDER
    }
    private var shown = false
    private val copyNow = Runnable { copyAndFollow() }
    private val tick = Runnable { copyAndFollow() }

    /** The wallpaper, the map's place or what the map shows has changed. */
    fun changed() {
        removeCallbacks(copyNow)
        post(copyNow)
    }

    /**
     * The start-up's opacity, the map's; while the wallpaper fades in under it, the copy follows the wallpaper.
     */
    fun fade(alpha: Float) {
        this.alpha = alpha
        if (alpha > 0f && wallpaper.alpha != copiedWallpaperAlpha) changed() else followTheMap()
    }

    // Its shadow lies outside it, and a layer for the opacity would cut it off.
    override fun hasOverlappingRendering() = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shown = isShown
        if (shown) changed()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(copyNow)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == shown) return
        shown = isVisible
        if (shown) changed() else followTheMap()
    }

    private fun copyAndFollow() {
        map.video.keepFrame()
        copyWhatIsUnder()
        invalidate()
        followTheMap()
    }

    private fun followTheMap() {
        removeCallbacks(tick)
        if (isAttachedToWindow && shown && alpha > 0f && map.video.live && mapUnder()) {
            postDelayed(tick, L.ICON_BACKDROP_REFRESH_MILLIS)
        }
    }

    private fun mapUnder(): Boolean {
        val params = map.layoutParams as FrameLayout.LayoutParams
        mapArea.set(params.leftMargin, params.topMargin, params.leftMargin + params.width,
            params.topMargin + params.height)
        return Rect.intersects(area, mapArea)
    }

    override fun onDraw(canvas: Canvas) {
        shadow.draw(canvas, width, height)
        val radius = L.ICON_BACKDROP_RADIUS
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, picture)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, tint)
        val half = border.strokeWidth / 2
        canvas.drawRoundRect(half, half, width - half, height - half, radius - half, radius - half, border)
    }

    private fun copyWhatIsUnder() {
        copy.eraseColor(Color.BLACK)
        copyCanvas.save()
        val scale = 1f / L.ICON_BACKDROP_DOWNSCALE
        copyCanvas.scale(scale, scale)
        copyCanvas.translate(-area.left.toFloat(), -area.top.toFloat())
        copiedWallpaperAlpha = wallpaper.alpha
        wallpaper.background?.let {
            if (it.bounds.isEmpty) it.setBounds(0, 0, wallpaper.width, wallpaper.height)
            copyCanvas.saveLayerAlpha(null, (copiedWallpaperAlpha * 255).roundToInt())
            it.draw(copyCanvas)
            copyCanvas.restore()
        }
        if (map.width > 0 && mapUnder()) {
            copyCanvas.translate(map.left.toFloat(), map.top.toFloat())
            map.draw(copyCanvas)
        }
        copyCanvas.restore()
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        boxBlur(pixels, copy.width, copy.height, L.ICON_BACKDROP_BLUR_RADIUS, L.ICON_BACKDROP_BLUR_PASSES, scratch)
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
    }
}

/**
 * Blurs an ARGB picture of [width] x [height] in place with [passes] box blurs of [radius] pixels each way, which
 * together come close to a Gaussian blur; the edges repeat outward. [scratch] holds at least as many pixels.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int, scratch: IntArray) {
    require(pixels.size >= width * height && scratch.size >= width * height)
    if (radius <= 0 || width <= 0 || height <= 0) return
    repeat(passes) {
        boxBlurLines(pixels, scratch, lines = height, length = width, step = 1, lineStep = width, radius = radius)
        boxBlurLines(scratch, pixels, lines = width, length = height, step = width, lineStep = 1, radius = radius)
    }
}

private fun boxBlurLines(from: IntArray, to: IntArray, lines: Int, length: Int, step: Int, lineStep: Int, radius: Int) {
    val window = 2 * radius + 1
    val half = window / 2
    val last = length - 1
    for (line in 0 until lines) {
        val start = line * lineStep
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val pixel = from[start + i.coerceIn(0, last) * step]
            a += pixel ushr 24
            r += pixel shr 16 and 0xFF
            g += pixel shr 8 and 0xFF
            b += pixel and 0xFF
        }
        for (i in 0 until length) {
            to[start + i * step] = ((a + half) / window shl 24) or ((r + half) / window shl 16) or
                ((g + half) / window shl 8) or ((b + half) / window)
            val leaving = from[start + (i - radius).coerceIn(0, last) * step]
            val entering = from[start + (i + radius + 1).coerceIn(0, last) * step]
            a += (entering ushr 24) - (leaving ushr 24)
            r += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            g += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
