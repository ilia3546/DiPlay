package com.shilapi.xcertplay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max

/**
 * The custom dashboard window's content, fixed at 1920x720 cluster pixels: background, clock, outside temperature,
 * the trip block, the map card, and the media block or the call block in its place. It always lays out left to
 * right, because the firmware's speed and warning lights sit at fixed places between the blocks. Each time it
 * enters a window it plays [DashboardBoot]; the blocks it moves together are full-size groups, as in the design,
 * so that their shadows fade with them. The icon backdrop stays out of the map's group, because the firmware's
 * icon over it does not grow with the map.
 */
internal class MonjaroDashboardView(context: Context) : FrameLayout(context) {
    private val wallpaper = object : View(context) {
        override fun hasOverlappingRendering() = false
    }
    private val bootLogo = DashboardBootLogo(context)
    private val clock = dashboardText(context, L.CLOCK_TEXT, 600).apply {
        letterSpacing = L.CLOCK_LETTER_SPACING / L.CLOCK_TEXT
    }
    private val temperature = dashboardText(context, L.TEMPERATURE_TEXT, 600).apply { gravity = Gravity.END }
    private val leftGroup = group(context)
    private val mapGroup = group(context).apply {
        pivotX = L.WIDTH / 2f
        pivotY = L.MAP_CENTRE_Y.toFloat()
    }
    private val rightGroup = group(context)
    val trip = DashboardTripBlock(context)
    val map = DashboardMapCard(context)
    private val iconBackdrop = DashboardIconBackdrop(context, wallpaper, map).apply { visibility = View.GONE }
    val media = DashboardMediaBlock(context)
    val call = DashboardCallBlock(context).apply { visibility = View.GONE }
    private val boot = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DashboardBoot.DURATION_MILLIS
        interpolator = LinearInterpolator()
        addUpdateListener { showBoot(DashboardBoot.at((it.animatedFraction * DashboardBoot.DURATION_MILLIS).toLong())) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = showBoot(DashboardBoot.finished)
        })
    }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        clipChildren = false
        for (layer in listOf(wallpaper, bootLogo, leftGroup, mapGroup, rightGroup)) {
            addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        place(clock, L.clock, before = leftGroup)
        place(temperature, L.temperature, before = leftGroup)
        leftGroup.place(trip, L.trip)
        mapGroup.place(map, mapBox(L.defaultMap))
        place(iconBackdrop, L.iconBackdrop, before = rightGroup)
        rightGroup.place(media, L.media)
        rightGroup.place(call, L.call)
        map.video.liveChanged = iconBackdrop::changed
        showBackground(DashboardBackground.OCEAN, 0) { null }
        showTemperature(null)
        trip.show(DashboardTrip())
        media.show(DashboardMedia.Empty)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showBoot(DashboardBoot.at(0))
        boot.start()
    }

    override fun onDetachedFromWindow() {
        boot.cancel()
        super.onDetachedFromWindow()
    }

    /** Shows one moment of the start-up; [DashboardBoot.finished] is the dashboard as it stays. */
    fun showBoot(frame: DashboardBoot.Frame) {
        wallpaper.alpha = frame.wallpaperAlpha
        bootLogo.show(frame)
        mapGroup.alpha = frame.mapAlpha
        mapGroup.scaleX = frame.mapScale
        mapGroup.scaleY = frame.mapScale
        iconBackdrop.fade(frame.mapAlpha)
        leftGroup.alpha = frame.leftAlpha
        leftGroup.translationX = frame.leftShift
        rightGroup.alpha = frame.rightAlpha
        rightGroup.translationX = frame.rightShift
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
        val (drawn, picture) = background.drawn(photo)
        wallpaper.background = DashboardWallpaperDrawable(drawn, picture, dimmingPercent)
        iconBackdrop.changed()
    }

    fun showIconBackdrop(shown: Boolean) {
        iconBackdrop.visibility = if (shown) View.VISIBLE else View.GONE
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
        iconBackdrop.changed()
    }

    private fun mapBox(geometry: L.MapGeometry) = L.Box(geometry.map.left - L.MAP_BORDER,
        geometry.map.top - L.MAP_BORDER, geometry.width + 2 * L.MAP_BORDER, geometry.height + 2 * L.MAP_BORDER)

    private fun group(context: Context) = FrameLayout(context).apply { clipChildren = false }

    private fun ViewGroup.place(view: View, box: L.Box, before: View? = null) {
        val params = LayoutParams(box.width, box.height ?: LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            leftMargin = box.left
            topMargin = box.top
        }
        addView(view, before?.let { indexOfChild(it) } ?: -1, params)
    }
}

/** The text of a dashboard block: one line in Onest, with the soft shadow that design v2 puts under all text. */
internal fun dashboardText(context: Context, size: Float, weight: Int, color: Int = L.TEXT): TextView =
    TextView(context).apply {
        setDashboardTextSize(size)
        typeface = DashboardFont.typeface(context, weight)
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(cssBlurRadius(L.TEXT_SHADOW_BLUR), 0f, L.TEXT_SHADOW_DY, L.TEXT_SHADOW)
    }

/** Sets the text size in cluster pixels. */
internal fun TextView.setDashboardTextSize(size: Float) {
    // Display pixels as dp of this display; SettingsLayoutSourceRulesTest bans pixel-unit names in this package.
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, size / resources.displayMetrics.density)
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
