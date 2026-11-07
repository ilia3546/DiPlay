package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallOutcome
import com.shilapi.xcertplay.hud.CarPlayCallSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The call the dashboard shows in place of the media block. */
internal sealed interface DashboardCallShown {
    /** [label] is the iPhone's label for the number, empty when it sent none. */
    data class Live(val card: CarPlayCallCard, val label: String, val muted: Boolean) : DashboardCallShown

    data class Ended(val outcome: CarPlayCallOutcome) : DashboardCallShown

    companion object {
        const val ENDED_MILLIS = 3_500L

        /**
         * Null leaves the media block: the option does not act ([MonjaroDashboardCall.active]), there is no call, or
         * the last call ended [ENDED_MILLIS] or more before [nowMillis], which is wall-clock time like
         * [CarPlayCallOutcome.endedAtMillis].
         */
        fun of(active: Boolean, calls: CarPlayCallSnapshot, nowMillis: Long): DashboardCallShown? {
            if (!active) return null
            calls.card?.let { return Live(it, calls.label, calls.muted == true) }
            val outcome = calls.lastOutcome ?: return null
            return if (nowMillis - outcome.endedAtMillis in 0 until ENDED_MILLIS) Ended(outcome) else null
        }
    }
}

/** The call block's words that come from the call. */
internal object DashboardCallText {
    private val number = Regex("[+0-9\\s()\\-]+")
    private val space = Regex("\\s+")

    /** A name that is only a phone number, as the iPhone sends for a caller who is not a contact. */
    fun isNumber(name: String): Boolean = name.matches(number) && name.any { it in '0'..'9' }

    /** The first letters of the first two words; null for no name or a number, which the avatar shows as a phone. */
    fun initials(name: String, locale: Locale = Locale.getDefault()): String? {
        if (isNumber(name)) return null
        val letters = name.trim().split(space).mapNotNull { word ->
            word.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst().takeIf { it.isPresent }?.asInt
        }.take(2)
        if (letters.isEmpty()) return null
        return letters.joinToString("") { String(Character.toChars(it)) }.uppercase(locale)
    }

    /** Under a ringing call's name: the iPhone's label for the number, else the number unless the name is one. */
    fun ringingDetail(card: CarPlayCallCard, label: String): String? = when {
        label.isNotBlank() -> label
        card.number.isNotBlank() && !isNumber(card.name) -> card.number
        else -> null
    }

    fun talkSeconds(card: CarPlayCallCard, nowMillis: Long): Long =
        card.activeSinceMillis?.let { ((nowMillis - it) / 1_000).coerceAtLeast(0) } ?: 0
}

/**
 * The call card of design v3 in the media block's column: the avatar, the caller, the call's time, and under them
 * what the wheel's previous and next keys do now.
 */
internal class DashboardCallBlock(context: Context) : LinearLayout(context) {
    val avatar = DashboardCallAvatar(context)
    private val caption = dashboardText(context, L.CALL_CAPTION_TEXT, 600, L.CALL_CAPTION).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        letterSpacing = L.CALL_CAPTION_LETTER_SPACING / L.CALL_CAPTION_TEXT
        isAllCaps = true
        setText(R.string.dashboard_call_incoming)
    }
    private val name = dashboardText(context, L.CALL_NAME_TEXT, 600).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val detail = dashboardText(context, L.CALL_DETAIL_TEXT, 400, L.SECONDARY).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val timer = dashboardText(context, L.CALL_TIMER_TEXT, 600, L.CALL_GREEN).apply {
        fontFeatureSettings = "tnum"
    }
    private val muted = dashboardText(context, L.CALL_MUTED_TEXT, 600, L.CALL_MUTED).apply {
        setShadowLayer(0f, 0f, 0f, 0)
        background = GradientDrawable().apply {
            setColor(L.CALL_MUTED_BACKGROUND)
            cornerRadius = L.CALL_MUTED_RADIUS
        }
        setPadding(L.CALL_MUTED_PADDING_HORIZONTAL, L.CALL_MUTED_PADDING_VERTICAL, L.CALL_MUTED_PADDING_HORIZONTAL,
            L.CALL_MUTED_PADDING_VERTICAL)
        setText(R.string.dashboard_call_muted)
    }
    private val timerRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(timer)
        addView(muted, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = L.CALL_MUTED_GAP
        })
    }
    private val outcome = dashboardText(context, L.CALL_OUTCOME_TEXT, 400, L.CALL_OUTCOME).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val previousKey = DashboardCallButton(context)
    private val nextKey = DashboardCallButton(context)
    private val buttons = LinearLayout(context).apply {
        orientation = HORIZONTAL
        clipChildren = false
        addView(previousKey)
        addView(nextKey, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = L.CALL_BUTTON_SPACING
        })
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        addView(avatar, LayoutParams(L.CALL_AVATAR_SIZE, L.CALL_AVATAR_SIZE))
        addView(caption, fullWidth(L.CALL_CAPTION_GAP))
        addView(name, fullWidth(L.CALL_NAME_GAP))
        addView(detail, fullWidth(L.CALL_DETAIL_GAP))
        addView(timerRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.CALL_TIMER_GAP
        })
        addView(outcome, fullWidth(L.CALL_OUTCOME_GAP))
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.CALL_BUTTONS_GAP
        })
    }

    /** [nowMillis] is wall-clock time, which the call's connect time uses. */
    fun show(call: DashboardCallShown, nowMillis: Long) {
        when (call) {
            is DashboardCallShown.Live -> when (call.card.phase) {
                CarPlayCallCard.Phase.RINGING -> showRinging(call)
                CarPlayCallCard.Phase.DIALING, CarPlayCallCard.Phase.ACTIVE -> showTalking(call, nowMillis)
            }
            is DashboardCallShown.Ended -> showEnded(call.outcome)
        }
    }

    private fun showRinging(call: DashboardCallShown.Live) {
        avatar.show(DashboardCallAvatar.Look.RINGING, DashboardCallText.initials(call.card.name))
        arrange(top = 0, nameGap = L.CALL_RINGING_NAME_GAP, buttonsGap = L.CALL_RINGING_BUTTONS_GAP)
        caption.visibility = VISIBLE
        name.showText(call.card.name, L.TEXT)
        detail.showText(DashboardCallText.ringingDetail(call.card, call.label))
        timerRow.visibility = GONE
        outcome.visibility = GONE
        buttons.visibility = VISIBLE
        previousKey.show(DashboardCallButton.Action.DECLINE)
        nextKey.show(DashboardCallButton.Action.ANSWER)
    }

    private fun showTalking(call: DashboardCallShown.Live, nowMillis: Long) {
        avatar.show(DashboardCallAvatar.Look.TALKING, DashboardCallText.initials(call.card.name))
        arrange(top = 0, nameGap = L.CALL_NAME_GAP, buttonsGap = L.CALL_BUTTONS_GAP)
        caption.visibility = GONE
        name.showText(call.card.name, L.TEXT)
        detail.visibility = GONE
        timerRow.visibility = VISIBLE
        timer.showText(if (call.card.phase == CarPlayCallCard.Phase.ACTIVE) {
            DashboardFormat.callDuration(DashboardCallText.talkSeconds(call.card, nowMillis))
        } else {
            context.getString(R.string.dashboard_call_calling)
        })
        muted.visibility = if (call.muted) VISIBLE else GONE
        outcome.visibility = GONE
        buttons.visibility = VISIBLE
        previousKey.show(
            if (call.muted) DashboardCallButton.Action.MICROPHONE_OFF else DashboardCallButton.Action.MICROPHONE,
        )
        nextKey.show(DashboardCallButton.Action.END)
    }

    private fun showEnded(ended: CarPlayCallOutcome) {
        avatar.show(DashboardCallAvatar.Look.ENDED, DashboardCallText.initials(ended.name))
        arrange(top = L.CALL_ENDED_TOP, nameGap = L.CALL_NAME_GAP, buttonsGap = L.CALL_BUTTONS_GAP)
        caption.visibility = GONE
        name.showText(ended.name, L.CALL_ENDED_NAME)
        detail.visibility = GONE
        timerRow.visibility = GONE
        outcome.showText(when (ended.kind) {
            CarPlayCallOutcome.Kind.DECLINED -> context.getString(R.string.dashboard_call_declined)
            CarPlayCallOutcome.Kind.ENDED ->
                context.getString(R.string.dashboard_call_ended, DashboardFormat.callDuration(ended.talkSeconds))
        })
        buttons.visibility = GONE
    }

    private fun arrange(top: Int, nameGap: Int, buttonsGap: Int) {
        if (paddingTop != top) setPadding(0, top, 0, 0)
        name.setTopMargin(nameGap)
        buttons.setTopMargin(buttonsGap)
    }

    private fun fullWidth(gap: Int) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = gap
    }

    private fun View.setTopMargin(gap: Int) {
        val params = layoutParams as LayoutParams
        if (params.topMargin == gap) return
        params.topMargin = gap
        layoutParams = params
    }

    // The feed shows the block again every second for the timer alone; an unchanged text must not relayout it.
    private fun TextView.showText(value: String?, color: Int = currentTextColor) {
        if (!TextUtils.equals(text, value.orEmpty())) text = value
        if (currentTextColor != color) setTextColor(color)
        visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
    }
}

/**
 * The caller's circle: initials, or a phone without them. Ringing, a green ring pulses out of it while it is in a
 * window and visible; talking, a green ring frames it; ended, it is dimmed with a red hang-up badge.
 */
internal class DashboardCallAvatar(context: Context) : View(context) {
    enum class Look { RINGING, TALKING, ENDED }

    private val shadow = DashboardShadow(L.CALL_AVATAR_SIZE / 2f, L.CALL_AVATAR_SHADOW_DY, L.CALL_AVATAR_SHADOW_BLUR,
        L.CALL_AVATAR_SHADOW)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = L.CALL_RING_WIDTH
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = DashboardFont.typeface(context, 600)
        setShadowLayer(cssBlurRadius(L.TEXT_SHADOW_BLUR), 0f, L.TEXT_SHADOW_DY, L.TEXT_SHADOW)
    }
    private val phone = dashboardGlyph(context, R.drawable.ic_dashboard_call, L.TEXT)
    private val hangUp = dashboardGlyph(context, R.drawable.ic_dashboard_call_end, L.TEXT)
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = L.CALL_PULSE_MILLIS
        repeatCount = ValueAnimator.INFINITE
        interpolator = PathInterpolator(0f, 0f, 0.58f, 1f) // CSS ease-out
        addUpdateListener { invalidate() }
    }
    private var look = Look.RINGING
    private var initials: String? = null
    private var attached = false
    private var visibleToUser = false
    private var gradient: Triple<Float, Int, LinearGradient>? = null

    val pulsing: Boolean get() = pulse.isStarted

    fun show(look: Look, initials: String?) {
        if (look == this.look && initials == this.initials) return
        this.look = look
        this.initials = initials
        updatePulse()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updatePulse()
    }

    override fun onDetachedFromWindow() {
        attached = false
        updatePulse()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        updatePulse()
    }

    private fun updatePulse() {
        val run = look == Look.RINGING && attached && visibleToUser
        if (run && !pulse.isStarted) pulse.start()
        if (!run && pulse.isStarted) pulse.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val centre = width / 2f
        when (look) {
            Look.RINGING -> {
                if (pulse.isRunning) drawPulse(canvas, centre, pulse.animatedValue as Float)
                shadow.draw(canvas, width, height)
                drawFace(canvas, centre, centre, L.CALL_AVATAR_TOP, L.CALL_AVATAR_BOTTOM, L.CALL_INITIALS_TEXT, L.TEXT)
            }
            Look.TALKING -> {
                ring.color = L.CALL_GREEN
                canvas.drawCircle(centre, centre, centre - L.CALL_RING_WIDTH / 2, ring)
                drawFace(canvas, centre, centre - L.CALL_RING_WIDTH - L.CALL_RING_INSET, L.CALL_AVATAR_TOP,
                    L.CALL_AVATAR_BOTTOM, L.CALL_TALKING_INITIALS_TEXT, L.TEXT)
            }
            Look.ENDED -> {
                drawFace(canvas, centre, centre, L.CALL_ENDED_AVATAR_TOP, L.CALL_ENDED_AVATAR_BOTTOM,
                    L.CALL_INITIALS_TEXT, L.CALL_ENDED_INITIALS)
                drawBadge(canvas)
            }
        }
    }

    private fun drawPulse(canvas: Canvas, centre: Float, fraction: Float) {
        val scale = 1 + (L.CALL_PULSE_SCALE - 1) * fraction
        ring.color = L.CALL_GREEN
        ring.alpha = (255 * L.CALL_PULSE_ALPHA * (1 - fraction)).roundToInt()
        canvas.save()
        canvas.scale(scale, scale, centre, centre)
        canvas.drawCircle(centre, centre, centre - L.CALL_RING_WIDTH / 2, ring)
        canvas.restore()
    }

    private fun drawFace(canvas: Canvas, centre: Float, radius: Float, top: Int, bottom: Int, textSize: Float,
        textColor: Int) {
        face.shader = gradient(centre, radius, top, bottom)
        canvas.drawCircle(centre, centre, radius, face)
        val initials = initials
        if (initials == null) {
            phone.setTint(textColor)
            phone.drawCentred(canvas, centre, centre, L.CALL_AVATAR_GLYPH_SIZE)
            return
        }
        letters.textSize = textSize
        letters.color = textColor
        val metrics = letters.fontMetrics
        canvas.drawText(initials, centre, centre - (metrics.ascent + metrics.descent) / 2, letters)
    }

    private fun drawBadge(canvas: Canvas) {
        val inner = L.CALL_BADGE_SIZE / 2f
        val outer = inner + L.CALL_BADGE_BORDER
        val x = width - outer
        val y = height - outer
        badge.color = L.CALL_BADGE_BORDER_COLOR
        canvas.drawCircle(x, y, outer, badge)
        badge.color = L.CALL_RED
        canvas.drawCircle(x, y, inner, badge)
        hangUp.drawCentred(canvas, x, y, L.CALL_BADGE_GLYPH_SIZE)
    }

    private fun gradient(centre: Float, radius: Float, top: Int, bottom: Int): LinearGradient {
        gradient?.let { (r, from, shader) -> if (r == radius && from == top) return shader }
        return cssGradient(centre, radius, L.CALL_AVATAR_GRADIENT_DEGREES, top, bottom).also {
            gradient = Triple(radius, top, it)
        }
    }
}

/** A hint of what a wheel key does in the call: the round key with its glyph and the label. It is never touched. */
internal class DashboardCallButton(context: Context) : LinearLayout(context) {
    enum class Action(
        val label: Int,
        val glyph: Int,
        val glyphSize: Int,
        val fill: Int,
        val glyphColor: Int,
        val bordered: Boolean,
        val shadow: Int?,
    ) {
        DECLINE(R.string.dashboard_call_decline, R.drawable.ic_dashboard_call_end, L.CALL_END_GLYPH_SIZE, L.CALL_RED,
            L.TEXT, false, L.CALL_RED_SHADOW),
        ANSWER(R.string.dashboard_call_answer, R.drawable.ic_dashboard_call, L.CALL_ANSWER_GLYPH_SIZE, L.CALL_GREEN,
            L.TEXT, false, L.CALL_GREEN_SHADOW),
        MICROPHONE(R.string.dashboard_call_microphone, R.drawable.ic_dashboard_microphone, L.CALL_MICROPHONE_GLYPH_SIZE,
            L.CALL_BUTTON_IDLE, L.TEXT, true, null),
        MICROPHONE_OFF(R.string.dashboard_call_microphone, R.drawable.ic_dashboard_microphone_off,
            L.CALL_MICROPHONE_GLYPH_SIZE, L.TEXT, L.CALL_MUTED_GLYPH, true, null),
        END(R.string.dashboard_call_end, R.drawable.ic_dashboard_call_end, L.CALL_END_GLYPH_SIZE, L.CALL_RED, L.TEXT,
            false, L.CALL_RED_SHADOW),
    }

    private val key = DashboardCallKey(context)
    private val label = dashboardText(context, L.CALL_BUTTON_LABEL_TEXT, 400, L.SECONDARY).apply {
        setShadowLayer(0f, 0f, 0f, 0)
    }
    var action: Action? = null
        private set

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        addView(key, LayoutParams(L.CALL_BUTTON_SIZE, L.CALL_BUTTON_SIZE))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = L.CALL_BUTTON_LABEL_GAP
        })
    }

    fun show(action: Action) {
        if (action == this.action) return
        this.action = action
        key.show(action)
        label.setText(action.label)
    }
}

private class DashboardCallKey(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = L.CALL_BUTTON_BORDER
        color = L.CALL_BUTTON_BORDER_COLOR
    }
    private var shadow: DashboardShadow? = null
    private var glyph: Drawable? = null
    private var glyphSize = 0
    private var bordered = false

    fun show(action: DashboardCallButton.Action) {
        fill.color = action.fill
        bordered = action.bordered
        shadow = action.shadow?.let {
            DashboardShadow(L.CALL_BUTTON_SIZE / 2f, L.CALL_BUTTON_SHADOW_DY, L.CALL_BUTTON_SHADOW_BLUR, it)
        }
        glyph = dashboardGlyph(context, action.glyph, action.glyphColor)
        glyphSize = action.glyphSize
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        shadow?.draw(canvas, width, height)
        val radius = width / 2f
        canvas.drawCircle(radius, radius, radius, fill)
        if (bordered) canvas.drawCircle(radius, radius, radius - L.CALL_BUTTON_BORDER / 2, border)
        glyph?.drawCentred(canvas, radius, radius, glyphSize)
    }
}

private fun dashboardGlyph(context: Context, res: Int, color: Int): Drawable =
    requireNotNull(ContextCompat.getDrawable(context, res)).mutate().apply { setTint(color) }

private fun Drawable.drawCentred(canvas: Canvas, x: Float, y: Float, size: Int) {
    val left = (x - size / 2f).roundToInt()
    val top = (y - size / 2f).roundToInt()
    setBounds(left, top, left + size, top + size)
    draw(canvas)
}

/** A CSS `linear-gradient(<degrees>deg, …)` over the square around a circle of [radius] at ([centre], [centre]). */
private fun cssGradient(centre: Float, radius: Float, degrees: Double, from: Int, to: Int): LinearGradient {
    val angle = Math.toRadians(degrees)
    val dx = sin(angle).toFloat()
    val dy = -cos(angle).toFloat()
    val half = radius * (abs(dx) + abs(dy))
    return LinearGradient(centre - dx * half, centre - dy * half, centre + dx * half, centre + dy * half, from, to,
        Shader.TileMode.CLAMP)
}
