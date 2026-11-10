package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** The start-up's logos over the black cluster: Geely alone, then Geely, a divider and CarPlay side by side. */
internal class DashboardBootLogo(context: Context) : View(context) {
    private val geely = logo(context, R.drawable.dashboard_boot_geely)
    private val carPlay = logo(context, R.drawable.dashboard_boot_carplay)
    private val divider = Paint().apply { color = L.BOOT_DIVIDER }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = L.BOOT_LOGO
        textSize = L.BOOT_CARPLAY_TEXT
        typeface = DashboardFont.typeface(context, 500)
        letterSpacing = L.BOOT_CARPLAY_LETTER_SPACING / L.BOOT_CARPLAY_TEXT
    }
    private val textCentre = text.fontMetrics.let { (it.ascent + it.descent) / 2 }
    private var frame = DashboardBoot.finished

    init {
        visibility = GONE
    }

    fun show(frame: DashboardBoot.Frame) {
        this.frame = frame
        visibility = if (frame.logoShown) VISIBLE else GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val x = L.BOOT_LOGO_CENTRE_X.toFloat()
        val y = L.BOOT_LOGO_CENTRE_Y.toFloat()
        if (frame.geelyAlpha > 0f) {
            val left = (x + frame.geelyLeft).roundToInt()
            val top = L.BOOT_LOGO_CENTRE_Y - L.BOOT_GEELY_HEIGHT / 2
            geely.setBounds(left, top, left + L.BOOT_GEELY_WIDTH, top + L.BOOT_GEELY_HEIGHT)
            geely.alpha = alpha(frame.geelyAlpha)
            geely.draw(canvas)
        }
        if (frame.dividerAlpha > 0f && frame.dividerScale > 0f) {
            divider.alpha = alpha(frame.dividerAlpha) * Color.alpha(L.BOOT_DIVIDER) / 255
            val half = L.BOOT_DIVIDER_HEIGHT / 2f * frame.dividerScale
            canvas.drawRect(x - L.BOOT_DIVIDER_WIDTH / 2f, y - half, x + L.BOOT_DIVIDER_WIDTH / 2f, y + half, divider)
        }
        if (frame.carPlayAlpha > 0f) {
            val left = (x + frame.carPlayLeft).roundToInt()
            val top = L.BOOT_LOGO_CENTRE_Y - L.BOOT_CARPLAY_LOGO_SIZE / 2
            carPlay.setBounds(left, top, left + L.BOOT_CARPLAY_LOGO_SIZE, top + L.BOOT_CARPLAY_LOGO_SIZE)
            carPlay.alpha = alpha(frame.carPlayAlpha)
            carPlay.draw(canvas)
            text.alpha = alpha(frame.carPlayAlpha)
            canvas.drawText(CARPLAY, (left + L.BOOT_CARPLAY_LOGO_SIZE + L.BOOT_CARPLAY_TEXT_GAP).toFloat(),
                y - textCentre, text)
        }
    }

    private fun alpha(fraction: Float) = (fraction.coerceIn(0f, 1f) * 255).roundToInt()

    private companion object {
        const val CARPLAY = "CarPlay"

        fun logo(context: Context, id: Int): Drawable = requireNotNull(context.getDrawable(id)).mutate()
    }
}
