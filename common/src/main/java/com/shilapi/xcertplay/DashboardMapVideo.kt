package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import kotlin.math.hypot
import kotlin.math.max

/**
 * The CarPlay map inside the dashboard's map card: the cluster stream's texture cropped to the card, a neutral
 * cover while it has no live picture, and the parked look, a blurred copy of the picture dimmed toward the centre,
 * where the firmware draws the car over it.
 */
internal class DashboardMapVideo(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val blur = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0f
    }
    private val cover = View(context).apply { setBackgroundColor(L.MAP_EMPTY) }
    private val dim = View(context).apply {
        background = CentreDim()
        alpha = 0f
    }
    private var surface: Surface? = null
    private var live = false
    private var parked = false
    private val capture = object : Runnable {
        override fun run() {
            captureBlur()
            if (parked && live) postDelayed(this, L.PARKED_BLUR_REFRESH_MILLIS)
        }
    }

    init {
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(blur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The texture keeps the last frame of a stopped stream, so it stays covered until the next frame. */
    fun streamStopped() {
        live = false
        removeCallbacks(capture)
        cover.visibility = VISIBLE
        blur.setImageDrawable(null)
    }

    fun showParked(next: Boolean) {
        if (next == parked) return
        parked = next
        removeCallbacks(capture)
        if (next) capture.run()
        val alpha = if (next) 1f else 0f
        blur.animate().alpha(alpha).setDuration(L.PARKED_FADE_MILLIS)
        dim.animate().alpha(alpha).setDuration(L.PARKED_FADE_MILLIS)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val stream = DashboardMapStream.requested
        texture.setDefaultBufferSize(stream.widthPixels, stream.heightPixels)
        crop(width, height)
        val next = Surface(texture)
        surface = next
        DashboardMapStream.attach(next)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop(width, height)

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surface?.let {
            DashboardMapStream.detach(it)
            it.release()
        }
        surface = null
        streamStopped()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (live) return
        live = true
        cover.visibility = GONE
        if (parked) {
            removeCallbacks(capture)
            capture.run()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(capture)
        super.onDetachedFromWindow()
    }

    private fun crop(width: Int, height: Int) {
        val stream = DashboardMapStream.requested
        val (scaleX, scaleY) = centreCropScale(width, height, stream.widthPixels, stream.heightPixels)
        texture.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }

    private fun captureBlur() {
        if (!live || width == 0 || height == 0) return
        val frame = texture.getBitmap(max(1, width / L.PARKED_BLUR_DOWNSCALE), max(1, height / L.PARKED_BLUR_DOWNSCALE))
            ?: return
        blur.setImageDrawable(BitmapDrawable(resources, frame).apply { isFilterBitmap = true })
    }

    private class CentreDim : Drawable() {
        private val paint = Paint()

        override fun onBoundsChange(bounds: Rect) {
            val radius = max(1f, hypot(bounds.width() / 2f, bounds.height() / 2f))
            paint.shader = RadialGradient(bounds.exactCenterX(), bounds.exactCenterY(), radius,
                L.PARKED_DIM_CENTRE, L.PARKED_DIM_EDGE, Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}

/**
 * The scale, on top of TextureView's stretch to the view, that shows a [bufferWidth]x[bufferHeight] picture in a
 * [viewWidth]x[viewHeight] view at one scale for both axes, filling the view and cropping the rest at the centre.
 */
internal fun centreCropScale(viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int): Pair<Float, Float> {
    if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return 1f to 1f
    val scale = max(viewWidth.toFloat() / bufferWidth, viewHeight.toFloat() / bufferHeight)
    return scale * bufferWidth / viewWidth to scale * bufferHeight / viewHeight
}
