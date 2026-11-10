package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.PathInterpolator
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map card's look while it has no live picture: a dark grid with a faint shine that sweeps across it, as in
 * design v4. The shine runs only while the skeleton is shown in a window.
 */
internal class DashboardMapSkeleton(context: Context) : View(context) {
    private val grid = Paint().apply { color = L.MAP_SKELETON_GRID }
    private val shine = Paint()
    private val sweeping = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = L.SKELETON_SHINE_MILLIS
        repeatCount = ValueAnimator.INFINITE
        interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
        addUpdateListener { shinePosition = it.animatedValue as Float }
    }

    /** The shine's place: at 0 it lies whole left of the map, at 1 whole right of it. */
    var shinePosition = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        shine.shader = shineShader(width.toFloat(), height.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(L.MAP_SKELETON)
        val step = L.SKELETON_GRID_STEP
        for (x in 0 until width step step) canvas.drawRect(x.toFloat(), 0f, x + 1f, height.toFloat(), grid)
        // CSS draws a 0deg gradient's first pixel at the bottom of each tile.
        for (y in step - 1 until height step step) canvas.drawRect(0f, y.toFloat(), width.toFloat(), y + 1f, grid)
        val shift = width * (2 * shinePosition - 1)
        if (abs(shift) >= width) return
        canvas.save()
        canvas.translate(shift, 0f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shine)
        canvas.restore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweepWhile(isShown)
    }

    override fun onDetachedFromWindow() {
        sweeping.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        sweepWhile(isVisible)
    }

    private fun sweepWhile(shown: Boolean) {
        if (shown && isAttachedToWindow) {
            if (!sweeping.isStarted) sweeping.start()
        } else {
            sweeping.cancel()
        }
    }

    /** CSS `linear-gradient(100deg, transparent 30%, white 6% 50%, transparent 70%)` over a box of this size. */
    private fun shineShader(width: Float, height: Float): Shader {
        val radians = Math.toRadians(L.SKELETON_SHINE_DEGREES.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val length = abs(width * dx) + abs(height * dy)
        val startX = width / 2 - dx * length / 2
        val startY = height / 2 - dy * length / 2
        val clear = L.MAP_SKELETON_SHINE and 0x00FFFFFF
        return LinearGradient(startX, startY, startX + dx * length, startY + dy * length,
            intArrayOf(clear, L.MAP_SKELETON_SHINE, clear), floatArrayOf(0.3f, 0.5f, 0.7f), Shader.TileMode.CLAMP)
    }
}
