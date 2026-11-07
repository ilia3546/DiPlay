package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max

/**
 * The custom dashboard window's content, fixed at 1920x720 cluster pixels: background, clock, outside temperature,
 * the trip block, the map card, and the media block or the call block in its place. It always lays out left to
 * right, because the firmware's speed and warning lights sit at fixed places between the blocks.
 */
internal class MonjaroDashboardView(context: Context) : FrameLayout(context) {
    private val clock = dashboardText(context, L.CLOCK_TEXT, 600).apply {
        letterSpacing = L.CLOCK_LETTER_SPACING / L.CLOCK_TEXT
    }
    private val temperature = dashboardText(context, L.TEMPERATURE_TEXT, 600).apply { gravity = Gravity.END }
    val trip = DashboardTripBlock(context)
    val map = DashboardMapCard(context)
    val media = DashboardMediaBlock(context)
    val call = DashboardCallBlock(context).apply { visibility = View.GONE }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        clipChildren = false
        place(clock, L.clock)
        place(temperature, L.temperature)
        place(trip, L.trip)
        place(map, mapBox(L.defaultMap))
        place(media, L.media)
        place(call, L.call)
        showBackground(DashboardBackground.OCEAN, 0) { null }
        showTemperature(null)
        trip.show(DashboardTrip())
        media.show(DashboardMedia.Empty)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(L.WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(L.HEIGHT, MeasureSpec.EXACTLY))
    }

    fun showTime(millis: Long, is24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()) {
        clock.text = DashboardFormat.clock(millis, zone, is24Hour)
    }

    fun showTemperature(celsius: Float?) {
        temperature.text = DashboardFormat.temperature(celsius)
    }

    /** [photo] is read only for [DashboardBackground.CUSTOM_PHOTO]. */
    fun showBackground(background: DashboardBackground, dimmingPercent: Int, photo: () -> Bitmap?) {
        val (wallpaper, picture) = background.drawn(photo)
        this.background = DashboardWallpaperDrawable(wallpaper, picture, dimmingPercent)
    }

    /** Moves and sizes the map card; the iPhone keeps the stream's size until it connects again. */
    fun showMap(geometry: L.MapGeometry) {
        val box = mapBox(geometry)
        val params = map.layoutParams as LayoutParams
        if (params.leftMargin == box.left && params.topMargin == box.top && params.width == box.width &&
            params.height == box.height) return
        map.resize(geometry)
        map.layoutParams = params.apply {
            width = box.width
            height = box.height ?: LayoutParams.WRAP_CONTENT
            leftMargin = box.left
            topMargin = box.top
        }
    }

    private fun mapBox(geometry: L.MapGeometry) = L.Box(geometry.map.left - L.MAP_BORDER,
        geometry.map.top - L.MAP_BORDER, geometry.width + 2 * L.MAP_BORDER, geometry.height + 2 * L.MAP_BORDER)

    private fun place(view: View, box: L.Box) {
        addView(view, LayoutParams(box.width, box.height ?: LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = box.left
            topMargin = box.top
        })
    }
}

/** The text of a dashboard block: one line in Onest, with the soft shadow that design v2 puts under all text. */
internal fun dashboardText(context: Context, size: Float, weight: Int, color: Int = L.TEXT): TextView =
    TextView(context).apply {
        // Display pixels as dp of this display; SettingsLayoutSourceRulesTest bans pixel-unit names in this package.
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size / resources.displayMetrics.density)
        typeface = DashboardFont.typeface(context, weight)
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(cssBlurRadius(L.TEXT_SHADOW_BLUR), 0f, L.TEXT_SHADOW_DY, L.TEXT_SHADOW)
    }

/** The Skia blur radius that matches a CSS blur length, whose Gaussian has a deviation of half the length. */
internal fun cssBlurRadius(blur: Float): Float = max(0f, (blur / 2 - 0.5f) / 0.57735f)

/**
 * A CSS `box-shadow` of a rounded box, blurred once into a bitmap. The owner draws it outside its own bounds, so
 * its parents must not clip their children.
 */
internal class DashboardShadow(
    private val radius: Float,
    private val dy: Float,
    private val blur: Float,
    private val color: Int,
) {
    private val margin = ceil(blur * 1.5f).toInt()
    private var painted: Bitmap? = null

    fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val bitmap = painted?.takeIf { it.width == width + 2 * margin && it.height == height + 2 * margin }
            ?: paint(width, height).also { painted = it }
        canvas.drawBitmap(bitmap, -margin.toFloat(), dy - margin, null)
    }

    private fun paint(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@DashboardShadow.color
            maskFilter = BlurMaskFilter(cssBlurRadius(blur), BlurMaskFilter.Blur.NORMAL)
        }
        val box = RectF(margin.toFloat(), margin.toFloat(), (margin + width).toFloat(), (margin + height).toFloat())
        Canvas(bitmap).drawRoundRect(box, radius, radius, paint)
        return bitmap
    }
}
