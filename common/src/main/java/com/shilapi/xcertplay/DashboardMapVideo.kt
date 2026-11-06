package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import kotlin.math.max

/**
 * The CarPlay map inside the dashboard's map card: the cluster stream's texture cropped to the card, and a neutral
 * cover while it has no live picture.
 */
internal class DashboardMapVideo(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context)
    private val cover = View(context).apply { setBackgroundColor(L.MAP_EMPTY) }
    private var surface: Surface? = null
    private var live = false

    init {
        texture.surfaceTextureListener = this
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The texture keeps the last frame of a stopped stream, so it stays covered until the next frame. */
    fun streamStopped() {
        live = false
        cover.visibility = VISIBLE
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val stream = DashboardMapStream.config
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
    }

    private fun crop(width: Int, height: Int) {
        val stream = DashboardMapStream.config
        val (scaleX, scaleY) = centreCropScale(width, height, stream.widthPixels, stream.heightPixels)
        texture.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
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
