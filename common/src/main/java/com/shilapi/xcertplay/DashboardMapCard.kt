package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.shilapi.xcertplay.MonjaroDashboardLayout as L

/**
 * The centre card of the dashboard: a rounded, bordered frame around the map. The CarPlay map's view goes into
 * [surfaceContainer], the cards drawn over the map into [overlays]; both fill the map's 700x404 pixels and are
 * clipped to its corners. Without a map the card shows its neutral colour.
 */
internal class DashboardMapCard(context: Context) : FrameLayout(context) {
    val surfaceContainer = FrameLayout(context)
    val overlays = FrameLayout(context)
    val video = DashboardMapVideo(context)
    private val shadow = DashboardShadow(L.MAP_RADIUS, L.MAP_SHADOW_DY, L.MAP_SHADOW_BLUR, L.MAP_SHADOW)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = L.MAP_EMPTY }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = L.MAP_BORDER.toFloat()
        color = L.MAP_BORDER_COLOR
    }
    private val innerRadius = L.MAP_RADIUS - L.MAP_BORDER

    init {
        setWillNotDraw(false)
        val map = FrameLayout(context).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, innerRadius)
            }
            clipToOutline = true
            addView(surfaceContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(overlays, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(map, LayoutParams(L.map.width, L.map.height ?: 0).apply {
            leftMargin = L.MAP_BORDER
            topMargin = L.MAP_BORDER
        })
        surfaceContainer.addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun draw(canvas: Canvas) {
        shadow.draw(canvas, width, height)
        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val inset = L.MAP_BORDER.toFloat()
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, innerRadius, innerRadius, fill)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val half = L.MAP_BORDER / 2f
        canvas.drawRoundRect(half, half, width - half, height - half, L.MAP_RADIUS - half, L.MAP_RADIUS - half, border)
    }
}
