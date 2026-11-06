package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.host.R
import kotlin.math.max

/** What the dashboard's media block shows. */
internal sealed interface DashboardMedia {
    /** Nothing is known to play, so nothing from an earlier source stays on screen. */
    object Empty : DashboardMedia

    /** [progress] runs from 0 to 1, and is null for a track of unknown length. */
    data class Track(val title: String, val artist: String?, val artwork: Bitmap?, val progress: Float?) : DashboardMedia

    data class Radio(val title: String, val subtitle: String?, val logo: Bitmap?) : DashboardMedia
}

/** The right block of design v2 A: the artwork, the title, the artist and the progress of what plays. */
internal class DashboardMediaBlock(context: Context) : LinearLayout(context) {
    private val artwork = DashboardArtworkView(context)
    private val title = dashboardText(context, L.MEDIA_TITLE_TEXT, 600).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val subtitle = dashboardText(context, L.MEDIA_ARTIST_TEXT, 400, L.SECONDARY).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val progress = DashboardProgressView(context)

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
    }

    fun show(media: DashboardMedia) {
        when (media) {
            DashboardMedia.Empty -> show(null, null, null, null)
            is DashboardMedia.Track -> show(media.artwork, media.title, media.artist, media.progress)
            is DashboardMedia.Radio -> show(media.logo, media.title, media.subtitle, null)
        }
    }

    private fun show(image: Bitmap?, title: String?, subtitle: String?, progress: Float?) {
        artwork.image = image
        this.title.text = title
        this.title.visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
        this.subtitle.text = subtitle
        this.subtitle.visibility = if (subtitle.isNullOrEmpty()) GONE else VISIBLE
        this.progress.progress = progress
        this.progress.visibility = if (progress == null) GONE else VISIBLE
    }
}

/** The rounded artwork with its shadow, or the app's now-playing placeholder when there is none. */
internal class DashboardArtworkView(context: Context) : View(context) {
    private val shadow = DashboardShadow(L.ARTWORK_RADIUS, L.ARTWORK_SHADOW_DY, L.ARTWORK_SHADOW_BLUR, L.ARTWORK_SHADOW)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder by lazy {
        requireNotNull(context.getDrawable(R.drawable.art_now_playing_placeholder)).toBitmap(L.ARTWORK_SIZE, L.ARTWORK_SIZE)
    }

    var image: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        shadow.draw(canvas, width, height)
        val picture = image ?: placeholder
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
