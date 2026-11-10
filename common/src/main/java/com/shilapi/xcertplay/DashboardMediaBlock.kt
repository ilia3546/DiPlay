package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.host.R
import kotlin.math.max
import kotlin.math.roundToInt

/** What the dashboard's media block shows. */
internal sealed interface DashboardMedia {
    /**
     * Something may sound, but there is nothing to show of it: the placeholder alone, so nothing from an earlier
     * source stays on screen.
     */
    object Empty : DashboardMedia

    /** Nothing sounds and nothing is there to show: the empty tile asks for music on the iPhone. */
    object Idle : DashboardMedia

    /** A track or a station, with the badges under it: paused, and the car's media sound off. */
    sealed interface Playing : DashboardMedia {
        val paused: Boolean
        val muted: Boolean
    }

    /** [progress] runs from 0 to 1, and is null for a track of unknown length. */
    data class Track(
        val title: String,
        val artist: String?,
        val artwork: Bitmap?,
        val progress: Float?,
        override val paused: Boolean = false,
        override val muted: Boolean = false,
    ) : Playing

    data class Radio(
        val title: String,
        val subtitle: String?,
        val logo: Bitmap?,
        override val paused: Boolean = false,
        override val muted: Boolean = false,
    ) : Playing
}

/**
 * The right block of design v2 A: the artwork, the title, the artist and the progress of what plays, and design v5's
 * badges under them. While nothing sounds it shows design v4's empty tile and asks for music on the iPhone.
 */
internal class DashboardMediaBlock(context: Context) : LinearLayout(context) {
    private val artwork = DashboardArtworkView(context)
    private val title = dashboardText(context, L.MEDIA_TITLE_TEXT, 600).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val subtitle = dashboardText(context, L.MEDIA_ARTIST_TEXT, 400, L.SECONDARY).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val progress = DashboardProgressView(context)
    private val badges = DashboardMediaBadges(context).apply { visibility = GONE }
    private var idle = false
    private var shown: DashboardMedia = DashboardMedia.Empty

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        addView(artwork, LayoutParams(L.ARTWORK_SIZE, L.ARTWORK_SIZE))
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.MEDIA_TITLE_GAP
        })
        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.MEDIA_ARTIST_GAP
        })
        addView(progress, LayoutParams(L.PROGRESS_WIDTH, L.PROGRESS_HEIGHT).apply { topMargin = L.PROGRESS_GAP })
        addView(badges, LayoutParams(LayoutParams.MATCH_PARENT, L.MEDIA_BADGE_HEIGHT).apply {
            topMargin = L.MEDIA_BADGE_ROW_GAP
        })
    }

    fun show(media: DashboardMedia) {
        styleFor(idle = media == DashboardMedia.Idle)
        when (media) {
            DashboardMedia.Empty -> show(null, NOW_PLAYING, null, null, null)
            DashboardMedia.Idle -> show(null, null, context.getString(R.string.custom_dashboard_media_empty_title),
                context.getString(R.string.custom_dashboard_media_empty_subtitle), null)
            is DashboardMedia.Track -> show(media.artwork, NOW_PLAYING, media.title, media.artist, media.progress)
            is DashboardMedia.Radio -> show(media.logo, R.drawable.art_radio_placeholder, media.title, media.subtitle, null)
        }
        showBadges(media, animated = media.javaClass == shown.javaClass)
        shown = media
    }

    private fun showBadges(media: DashboardMedia, animated: Boolean) {
        val playing = media as? DashboardMedia.Playing
        badges.visibility = if (playing == null) GONE else VISIBLE
        if (playing != null) badges.show(playing.paused, playing.muted, animated)
    }

    private fun styleFor(idle: Boolean) {
        if (this.idle == idle) return
        this.idle = idle
        title.setDashboardTextSize(if (idle) L.MEDIA_EMPTY_TITLE_TEXT else L.MEDIA_TITLE_TEXT)
        title.setTextColor(if (idle) L.MEDIA_EMPTY_TITLE else L.TEXT)
        subtitle.setDashboardTextSize(if (idle) L.MEDIA_EMPTY_SUBTITLE_TEXT else L.MEDIA_ARTIST_TEXT)
        subtitle.setTextColor(if (idle) L.TITLE_MUTED else L.SECONDARY)
        subtitle.layoutParams = (subtitle.layoutParams as LayoutParams).apply {
            topMargin = if (idle) L.MEDIA_EMPTY_SUBTITLE_GAP else L.MEDIA_ARTIST_GAP
        }
    }

    private fun show(image: Bitmap?, placeholder: Int?, title: String?, subtitle: String?, progress: Float?) {
        artwork.show(image, placeholder)
        this.title.showText(title)
        this.subtitle.showText(subtitle)
        this.progress.progress = progress
        this.progress.visibility = if (progress == null) GONE else VISIBLE
    }

    // The feed shows the block again every second for the progress alone; an unchanged text must not relayout it.
    private fun TextView.showText(value: String?) {
        if (!TextUtils.equals(text, value.orEmpty())) text = value
        visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
    }

    private companion object {
        val NOW_PLAYING = R.drawable.art_now_playing_placeholder
    }
}

/**
 * The rounded artwork with its shadow, or a placeholder drawable of the source when there is none. Without either it
 * is the empty tile: a faint bordered square with a note and no shadow.
 */
internal class DashboardArtworkView(context: Context) : View(context) {
    private val shadow = DashboardShadow(L.ARTWORK_RADIUS, L.ARTWORK_SHADOW_DY, L.ARTWORK_SHADOW_BLUR, L.ARTWORK_SHADOW)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val emptyTile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = L.MEDIA_EMPTY_TILE }
    private val emptyBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = L.MEDIA_EMPTY_BORDER
    }
    private val note = requireNotNull(context.getDrawable(R.drawable.ic_dashboard_music_note))
    private val placeholders = mutableMapOf<Int, Bitmap>()
    private var image: Bitmap? = null
    private var placeholder: Int? = R.drawable.art_now_playing_placeholder

    /** [image] stays its owner's: the view never recycles it. */
    fun show(image: Bitmap?, placeholder: Int?) {
        if (image === this.image && placeholder == this.placeholder) return
        this.image = image
        this.placeholder = placeholder
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val placeholder = placeholder
        if (image == null && placeholder == null) {
            drawEmpty(canvas)
            return
        }
        shadow.draw(canvas, width, height)
        val picture = image ?: placeholders.getOrPut(checkNotNull(placeholder)) {
            requireNotNull(context.getDrawable(placeholder)).toBitmap(L.ARTWORK_SIZE, L.ARTWORK_SIZE)
        }
        val crop = centerCrop(picture.width, picture.height, width, height)
        val scale = max(width / crop.width().toFloat(), height / crop.height().toFloat())
        paint.shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setTranslate(-crop.left.toFloat(), -crop.top.toFloat())
                postScale(scale, scale)
            })
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), L.ARTWORK_RADIUS, L.ARTWORK_RADIUS, paint)
    }

    private fun drawEmpty(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), L.ARTWORK_RADIUS, L.ARTWORK_RADIUS, emptyTile)
        val half = emptyBorder.strokeWidth / 2
        canvas.drawRoundRect(half, half, width - half, height - half, L.ARTWORK_RADIUS - half, L.ARTWORK_RADIUS - half,
            emptyBorder)
        val left = (width - L.MEDIA_EMPTY_GLYPH_SIZE) / 2
        val top = (height - L.MEDIA_EMPTY_GLYPH_SIZE) / 2
        note.setBounds(left, top, left + L.MEDIA_EMPTY_GLYPH_SIZE, top + L.MEDIA_EMPTY_GLYPH_SIZE)
        note.draw(canvas)
    }
}

/** A thin rounded bar filled to [progress] of its width. */
internal class DashboardProgressView(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = L.PROGRESS_TRACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = L.TEXT }

    var progress: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, track)
        val filled = width * (progress ?: return).coerceIn(0f, 1f)
        if (filled > 0f) canvas.drawRoundRect(0f, 0f, filled, height.toFloat(), radius, radius, fill)
    }
}

/**
 * Design v5's pause and sound-off badges in a row, centred and as wide as the badges show. The row keeps its height
 * while it shows no badge, so that the block does not move when one comes or goes.
 */
internal class DashboardMediaBadges(context: Context) : View(context) {
    private inner class Badge(val label: String, val glyph: Drawable, val glyphSize: Int, val color: Int,
        val background: Int) {
        val width = 2 * L.MEDIA_BADGE_PADDING + glyphSize + L.MEDIA_BADGE_GLYPH_GAP + text.measureText(label)
    }

    private val motion = DashboardMediaBadgeMotion()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = DashboardFont.typeface(context, 600)
        textSize = L.MEDIA_BADGE_TEXT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paused = Badge(context.getString(R.string.dashboard_media_paused),
        dashboardGlyph(context, R.drawable.ic_dashboard_media_paused, L.MEDIA_PAUSED), L.MEDIA_PAUSED_GLYPH_SIZE,
        L.MEDIA_PAUSED, L.MEDIA_PAUSED_BACKGROUND)
    private val muted = Badge(context.getString(R.string.dashboard_media_sound_off),
        dashboardGlyph(context, R.drawable.ic_dashboard_sound_off, L.MEDIA_SOUND_OFF), L.MEDIA_SOUND_OFF_GLYPH_SIZE,
        L.MEDIA_SOUND_OFF, L.MEDIA_SOUND_OFF_BACKGROUND)

    fun show(paused: Boolean, muted: Boolean, animated: Boolean) {
        val changed = if (animated && isAttachedToWindow && isShown) {
            motion.moveTo(paused, muted, SystemClock.uptimeMillis())
        } else {
            motion.jumpTo(paused, muted)
        }
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val frame = motion.at(now)
        val left = (width - frame.width(paused.width, muted.width)) / 2
        draw(canvas, muted, frame.muted, draw(canvas, paused, frame.paused, left) + frame.gap)
        if (motion.moving(now) && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** Returns the right edge of the badge's box. */
    private fun draw(canvas: Canvas, badge: Badge, shown: DashboardMediaBadgeMotion.Badge, left: Float): Float {
        val right = left + shown.width(badge.width)
        if (right <= left || shown.opacity <= 0f) return right
        val height = height.toFloat()
        val saved = canvas.save()
        canvas.clipRect(left, 0f, right, height)
        if (shown.opacity < 1f) canvas.saveLayerAlpha(left, 0f, right, height, (255 * shown.opacity).roundToInt())
        canvas.scale(shown.scale, shown.scale, left + badge.width / 2, height / 2)
        fill.color = badge.background
        canvas.drawRoundRect(left, 0f, left + badge.width, height, L.MEDIA_BADGE_RADIUS, L.MEDIA_BADGE_RADIUS, fill)
        val glyphLeft = left + L.MEDIA_BADGE_PADDING
        canvas.save()
        canvas.translate(glyphLeft, (height - badge.glyphSize) / 2)
        badge.glyph.setBounds(0, 0, badge.glyphSize, badge.glyphSize)
        badge.glyph.draw(canvas)
        canvas.restore()
        text.color = badge.color
        val metrics = text.fontMetrics
        canvas.drawText(badge.label, glyphLeft + badge.glyphSize + L.MEDIA_BADGE_GLYPH_GAP,
            height / 2 - (metrics.ascent + metrics.descent) / 2, text)
        canvas.restoreToCount(saved)
        return right
    }
}
