package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.AndroidHudCanvas
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.HudFrame
import com.shilapi.xcertplay.hud.HudManeuver
import com.shilapi.xcertplay.hud.ManeuverIcon
import java.util.TimeZone

/** CarPlay's route as the map card shows it: the next turn and the arrival. */
internal data class DashboardRoute(
    val turn: ClusterTurnGuidance,
    val arrivalEpochSeconds: Long?,
    val remainingSeconds: Long?,
    val remainingMeters: Long?,
    /** The sign the head-up display draws for the same maneuver, so both read alike. */
    val sign: HudManeuver,
    val exitNumber: Int,
) {
    companion object {
        /** Null without a route, including once the glance lets an instruction expire. */
        fun of(glance: CarPlayGlance.Snapshot): DashboardRoute? {
            val type = glance.maneuverType ?: return null
            return DashboardRoute(
                ClusterTurnGuidance.fromApple(type, glance.drivingSide, glance.distanceMeters, glance.road),
                glance.arrivalEpochSeconds,
                glance.remainingSeconds,
                glance.remainingMeters,
                HudFrame.sign(type),
                HudFrame.exitNumber(type),
            )
        }
    }
}

/** The next turn over the map's top left: the glyph on a blue tile, the distance and the road. */
internal class DashboardManeuverCard(context: Context) : LinearLayout(context) {
    private val shadow = DashboardShadow(L.MANEUVER_RADIUS, L.MANEUVER_SHADOW_DY, L.MANEUVER_SHADOW_BLUR,
        L.ROUTE_CARD_SHADOW)
    private val glyph = DashboardManeuverGlyph(context)
    private val distance = routeCardText(context, L.MANEUVER_DISTANCE_TEXT, 700)
    private val road = routeCardText(context, L.MANEUVER_ROAD_TEXT, 400, L.SECONDARY)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = routeCardBackground(L.ROUTE_CARD, L.MANEUVER_RADIUS)
        setPadding(L.MANEUVER_PADDING_START, L.MANEUVER_PADDING_VERTICAL, L.MANEUVER_PADDING_END,
            L.MANEUVER_PADDING_VERTICAL)
        val tile = FrameLayout(context).apply {
            background = routeCardBackground(L.MANEUVER_ICON, L.MANEUVER_ICON_RADIUS)
            addView(glyph, FrameLayout.LayoutParams(L.MANEUVER_GLYPH_SIZE, L.MANEUVER_GLYPH_SIZE, Gravity.CENTER))
        }
        addView(tile, LayoutParams(L.MANEUVER_ICON_SIZE, L.MANEUVER_ICON_SIZE))
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(distance, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(road, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = L.MANEUVER_ROAD_GAP
            })
        }
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = L.MANEUVER_GAP
        })
    }

    fun show(route: DashboardRoute) {
        val turn = route.turn
        glyph.show(route.sign, route.exitNumber)
        distance.text = ClusterTurnCardView.distanceLabel(context, turn.distanceMeters)
        road.text = ClusterTurnCardView.roadLabel(context, turn)
        road.visibility = if (road.text.isEmpty()) GONE else VISIBLE
    }

    /** The widest the card grows, from the map it sits on. */
    var maxWidth = L.defaultMap.maneuverCard.width
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
    }

    override fun draw(canvas: Canvas) {
        shadow.draw(canvas, width, height)
        super.draw(canvas)
    }
}

/** The head-up display's maneuver sign, white, as large as the view. */
internal class DashboardManeuverGlyph(context: Context) : View(context) {
    var sign: HudManeuver? = null
        private set
    var exitNumber = 0
        private set

    fun show(sign: HudManeuver, exitNumber: Int) {
        if (sign == this.sign && exitNumber == this.exitNumber) return
        this.sign = sign
        this.exitNumber = exitNumber
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val sign = sign ?: return
        val size = minOf(width, height).toDouble()
        ManeuverIcon.draw(AndroidHudCanvas(canvas), sign, width / 2.0, height / 2.0, size, size * STROKE_PER_SIZE,
            Color.WHITE, exitNumber)
    }

    private companion object {
        /** The head-up display's balanced sign: a 6 px stroke on a 56 px sign. */
        const val STROKE_PER_SIZE = 6.0 / 56
    }
}

/** The arrival over the map's top right: the time of arrival by the clock, the minutes and kilometres left. */
internal class DashboardArrivalCard(context: Context) : LinearLayout(context) {
    private val time = value()
    private val minutes = value()
    private val distance = value()

    init {
        orientation = HORIZONTAL
        background = routeCardBackground(L.ROUTE_CARD, L.ARRIVAL_RADIUS)
        setPadding(L.ARRIVAL_PADDING_HORIZONTAL, L.ARRIVAL_PADDING_VERTICAL, L.ARRIVAL_PADDING_HORIZONTAL,
            L.ARRIVAL_PADDING_VERTICAL)
        column(time, R.string.custom_dashboard_arrival, 0)
        column(minutes, R.string.custom_dashboard_unit_minutes, L.ARRIVAL_GAP)
        column(distance, R.string.custom_dashboard_unit_km, L.ARRIVAL_GAP)
    }

    fun show(route: DashboardRoute, is24Hour: Boolean, zone: TimeZone) {
        time.text = DashboardFormat.arrival(route.arrivalEpochSeconds, zone, is24Hour)
        minutes.text = DashboardFormat.remainingMinutes(route.remainingSeconds)
        distance.text = DashboardFormat.remainingKm(route.remainingMeters)
    }

    private fun value(): TextView = routeCardText(context, L.ARRIVAL_VALUE_TEXT, 700)

    private fun column(value: TextView, label: Int, gap: Int) {
        val item = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(value)
            addView(routeCardText(context, L.ARRIVAL_LABEL_TEXT, 400, L.TITLE_MUTED).apply { setText(label) })
        }
        addView(item, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = gap })
    }
}

/** Design v2 draws the route cards' text without the shadow of the rest of the dashboard. */
private fun routeCardText(context: Context, size: Float, weight: Int, color: Int = L.TEXT): TextView =
    dashboardText(context, size, weight, color).apply { setShadowLayer(0f, 0f, 0f, 0) }

private fun routeCardBackground(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}
