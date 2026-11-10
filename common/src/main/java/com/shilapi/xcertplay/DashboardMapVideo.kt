package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
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
 * The CarPlay map inside the dashboard's map card: the cluster stream's texture cropped to the card, the skeleton
 * while it has no live picture or the picture is one flat colour, and the parked look, a blurred copy of the
 * picture dimmed toward the centre, where the firmware draws the car over it. In a software canvas, where the
 * texture draws nothing, it draws the frame that [keepFrame] kept last, cropped and rounded as on screen.
 */
internal class DashboardMapVideo(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val blur = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 0f
    }
    private val skeleton = DashboardMapSkeleton(context)
    private val dim = View(context).apply {
        background = CentreDim()
        alpha = 0f
    }
    private var surface: Surface? = null

    /** Whether the texture shows a frame of the running stream. */
    var live = false
        private set
    var liveChanged: (() -> Unit)? = null
    private var still: Bitmap? = null
    private val stillPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stillBox = RectF()
    private val rounded = Path()
    private var parked = false
    private var skeletonShown = true
    private val probe by lazy {
        Bitmap.createBitmap(L.MAP_BLANK_PROBE_WIDTH, L.MAP_BLANK_PROBE_HEIGHT, Bitmap.Config.ARGB_8888)
    }
    private val probePixels = IntArray(L.MAP_BLANK_PROBE_WIDTH * L.MAP_BLANK_PROBE_HEIGHT)
    private val watchBlank = object : Runnable {
        override fun run() {
            if (!live) return
            texture.getBitmap(probe)
            probe.getPixels(probePixels, 0, probe.width, 0, 0, probe.width, probe.height)
            showSkeleton(looksBlank(probePixels, L.MAP_BLANK_SPREAD))
            postDelayed(this, L.MAP_BLANK_CHECK_MILLIS)
        }
    }
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
        addView(skeleton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The texture keeps the last frame of a stopped stream, so it stays covered until the next frame. */
    fun streamStopped() {
        val was = live
        live = false
        removeCallbacks(capture)
        removeCallbacks(watchBlank)
        skeletonShown = true
        skeleton.animate().cancel()
        skeleton.alpha = 1f
        skeleton.visibility = VISIBLE
        blur.setImageDrawable(null)
        if (was) liveChanged?.invoke()
    }

    /** Keeps the live picture for software drawing; it waits for the GPU, so it must not run while drawing. */
    fun keepFrame() {
        if (!live) return
        val stream = DashboardMapStream.requested
        val width = max(1, stream.widthPixels / L.MAP_STILL_DOWNSCALE)
        val height = max(1, stream.heightPixels / L.MAP_STILL_DOWNSCALE)
        val bitmap = still?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                still?.recycle()
                still = it
            }
        texture.getBitmap(bitmap)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val radius = L.MAP_RADIUS - L.MAP_BORDER
        rounded.rewind()
        rounded.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated) return super.dispatchDraw(canvas)
        // The card clips to its outline only in hardware drawing.
        canvas.save()
        canvas.clipPath(rounded)
        still?.takeIf { live }?.let { frame ->
            // The texture's transform does not reach getBitmap, so the frame is cropped here as crop() shows it.
            stillBox.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(frame, centerCrop(frame.width, frame.height, width, height), stillBox, stillPaint)
        }
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    // An attached TextureView clears its area in a software canvas, which would show through at the rounded edge.
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean =
        if (child === texture && !canvas.isHardwareAccelerated) false else super.drawChild(canvas, child, drawingTime)

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
        // This runs while the texture draws, where getBitmap would wait for the GPU.
        removeCallbacks(watchBlank)
        post(watchBlank)
        liveChanged?.invoke()
        if (parked) {
            removeCallbacks(capture)
            capture.run()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (live) post(watchBlank)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(capture)
        removeCallbacks(watchBlank)
        super.onDetachedFromWindow()
    }

    private fun showSkeleton(shown: Boolean) {
        if (shown == skeletonShown) return
        skeletonShown = shown
        skeleton.animate().cancel()
        if (shown) {
            skeleton.visibility = VISIBLE
            skeleton.animate().alpha(1f).setDuration(L.SKELETON_RETURN_MILLIS)
        } else {
            skeleton.animate().alpha(0f).setDuration(L.SKELETON_FADE_MILLIS)
                .withEndAction { skeleton.visibility = GONE }
        }
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

/** Whether every channel of the ARGB [pixels] spreads by at most [spread], as in a frame of one flat colour. */
internal fun looksBlank(pixels: IntArray, spread: Int): Boolean {
    if (pixels.isEmpty()) return false
    for (shift in intArrayOf(0, 8, 16)) {
        var low = 255
        var high = 0
        for (pixel in pixels) {
            val channel = (pixel shr shift) and 0xFF
            if (channel < low) low = channel
            if (channel > high) high = channel
        }
        if (high - low > spread) return false
    }
    return true
}
