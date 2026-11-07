package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import java.util.TimeZone

/**
 * The centre card of the dashboard: a rounded, bordered frame around the map. The CarPlay map's view goes into
 * [surfaceContainer], the cards drawn over the map into [overlays]; both fill the map and are
 * clipped to its corners. Without a map the card shows its neutral colour. While CarPlay guides, the next turn
 * and the arrival sit over the map.
 */
internal class DashboardMapCard(context: Context) : FrameLayout(context) {
    val surfaceContainer = FrameLayout(context)
    val overlays = FrameLayout(context).apply { clipChildren = false }
    val video = DashboardMapVideo(context)
    private val maneuver = DashboardManeuverCard(context)
    private val arrival = DashboardArrivalCard(context)
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
        addView(map, LayoutParams(0, 0).apply {
            leftMargin = L.MAP_BORDER
            topMargin = L.MAP_BORDER
        })
        surfaceContainer.addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        overlays.addView(maneuver, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))
        overlays.addView(arrival, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END))
        resize(L.defaultMap)
        showRoute(null, is24Hour = true)
    }

    /** Lays the map and its route cards out for [geometry]; the owner moves the card itself. */
    fun resize(geometry: L.MapGeometry) {
        val map = getChildAt(0)
        map.layoutParams = (map.layoutParams as LayoutParams).apply {
            width = geometry.width
            height = geometry.height
        }
        maneuver.maxWidth = geometry.maneuverCard.width
        maneuver.layoutParams = (maneuver.layoutParams as LayoutParams).apply {
            leftMargin = geometry.maneuverCard.left - geometry.map.left
            topMargin = geometry.maneuverCard.top - geometry.map.top
        }
        arrival.layoutParams = (arrival.layoutParams as LayoutParams).apply {
            rightMargin = geometry.map.left + geometry.width - (geometry.arrivalCard.left + geometry.arrivalCard.width)
            topMargin = geometry.arrivalCard.top - geometry.map.top
        }
    }

    fun showRoute(route: DashboardRoute?, is24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()) {
        val visibility = if (route == null) GONE else VISIBLE
        maneuver.visibility = visibility
        arrival.visibility = visibility
        if (route == null) return
        maneuver.show(route.turn)
        arrival.show(route, is24Hour, zone)
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
