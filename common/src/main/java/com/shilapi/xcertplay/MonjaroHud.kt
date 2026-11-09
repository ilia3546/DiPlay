package com.shilapi.xcertplay

import android.app.AppOpsManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.AndroidHudCanvas
import com.shilapi.xcertplay.hud.CarPlayCalls
import com.shilapi.xcertplay.hud.HudCall
import com.shilapi.xcertplay.hud.HudCallWords
import com.shilapi.xcertplay.hud.HudDrawing
import com.shilapi.xcertplay.hud.HudFrame
import com.shilapi.xcertplay.hud.HudSettings
import com.shilapi.xcertplay.hud.HudUnits

/** The HUD line window on the head-up display, as [HudState] drives it. Main thread. */
internal interface HudWindow {
    /**
     * The overlay permission is granted, exactly one head-up display is present, and the shown window, if any, is on
     * it.
     */
    fun available(): Boolean

    /** Reports each change that may change [available] to [changed], until [stopWatching]. */
    fun startWatching(changed: () -> Unit)

    fun stopWatching()

    /** Adds the window, or returns false. */
    fun show(): Boolean

    fun hide()

    /** Draws [frame] and [call] with [settings] in the shown window; null draws nothing. */
    fun draw(frame: HudFrame?, call: HudCall?, settings: HudSettings)
}

/**
 * The HUD line during a CarPlay session: the window is wanted while the option is on and a session is connected,
 * or while there is a [call] to show, and shown while the overlay permission and the head-up display are there too.
 * It stays for the whole session, empty without a route, and redraws on every glance or call change. It also reads
 * both once a second while it is shown or the iPhone has a call ([calling]): guidance that expires without another
 * frame leaves the HUD, a call's time runs, and a call that the HUD may show only from some moment on, such as the
 * AirPlay session going live, appears without a change of its own. Main thread only.
 */
internal class HudState(
    private val options: () -> MonjaroA3Options,
    private val window: HudWindow,
    private val glance: () -> CarPlayGlance.Snapshot,
    private val units: () -> HudUnits,
    private val call: () -> HudCall? = { null },
    private val calling: () -> Boolean = { false },
    private val postDelayed: (delayMillis: Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var current: MonjaroA3Options? = null
    private var settings: HudSettings? = null
    private var snapshot = CarPlayGlance.Snapshot()
    private var shownCall: HudCall? = null
    private var lineWanted = false
    private var watching = false
    private var shown = false
    private var ticking = false
    private var callDrawn = false
    private var drawn: Triple<HudFrame?, HudCall?, HudSettings>? = null
    private var loggedManeuver: Triple<Long, Int?, Int?>? = null
    private val tick = Runnable {
        ticking = false
        glanceChanged()
    }

    /** Reads [glance] now: a snapshot posted from another thread may be older than one posted after it. */
    fun glanceChanged() {
        val next = glance()
        val sessionChanged = next.connected != snapshot.connected || next.session != snapshot.session
        snapshot = next
        refresh(sessionChanged)
    }

    /** Reads [call] now, as [glanceChanged] reads the glance. */
    fun callChanged() = refresh(sessionChanged = false)

    fun settingChanged() {
        load()
        update()
    }

    private fun refresh(sessionChanged: Boolean) {
        val hadCall = shownCall != null
        shownCall = call()
        if (sessionChanged || hadCall != (shownCall != null)) place() else redraw()
        keepTicking()
    }

    private fun update() {
        shownCall = call()
        place()
        keepTicking()
    }

    private fun load(): MonjaroA3Options = options().also {
        current = it
        settings = it.hudSettings
    }

    private fun place() {
        val options = current ?: load()
        lineWanted = options.hudManeuvers && snapshot.connected
        val wanted = lineWanted || shownCall != null
        if (wanted != watching) {
            watching = wanted
            if (wanted) window.startWatching(::update) else window.stopWatching()
        }
        if (!wanted || !window.available()) {
            if (shown) {
                window.hide()
                shown = false
                drawn = null
                logCall(false)
                // Shows a new window on a head-up display that came back under a new id.
                if (wanted) place()
            }
            return
        }
        if (!shown) {
            if (!window.show()) {
                log("HUD window not shown")
                return
            }
            shown = true
        }
        redraw()
    }

    private fun keepTicking() {
        val wanted = shown || calling()
        if (wanted == ticking) return
        ticking = wanted
        if (wanted) postDelayed(TICK_MILLIS, tick) else cancel(tick)
    }

    private fun redraw() {
        if (!shown) return
        val frame = if (lineWanted) HudFrame.from(snapshot, units()) else null
        val maneuver = Triple(snapshot.session, snapshot.maneuverIndex, snapshot.maneuverType)
        if (frame != null && maneuver != loggedManeuver) {
            loggedManeuver = maneuver
            log("maneuver index=${snapshot.maneuverIndex} type=${snapshot.maneuverType} sign=${frame.kind}" +
                if (frame.exitNumber > 0) " exit=${frame.exitNumber}" else "")
        }
        val next = Triple(frame, shownCall, settings ?: return)
        logCall(next.second != null)
        if (next == drawn) return
        drawn = next
        window.draw(next.first, next.second, next.third)
    }

    private fun logCall(drawing: Boolean) {
        if (drawing == callDrawn) return
        callDrawn = drawing
        log(if (drawing) "HUD call shown" else "HUD call hidden")
    }

    companion object {
        const val TICK_MILLIS = 1_000L
    }
}

/** The Android display that firmware shows on the head-up display: physical display 132, 800x480. */
internal object MonjaroHudDisplay :
    PhysicalDisplay(physicalId = 132, width = HudDrawing.WIDTH, height = HudDrawing.HEIGHT)

/** Draws one [HudFrame] in the HUD's 800x480 pixels, scaled to fit the view. */
internal class HudLineView(context: Context) : View(context) {
    private var frame: HudFrame? = null
    private var call: HudCall? = null
    private var settings: HudSettings? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(frame: HudFrame?, call: HudCall?, settings: HudSettings) {
        this.frame = frame
        this.call = call
        this.settings = settings
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame == null && call == null) return
        val settings = settings ?: return
        val scale = minOf(width / HudDrawing.WIDTH.toFloat(), height / HudDrawing.HEIGHT.toFloat())
        canvas.save()
        canvas.translate((width - HudDrawing.WIDTH * scale) / 2, (height - HudDrawing.HEIGHT * scale) / 2)
        canvas.scale(scale, scale)
        HudDrawing.draw(AndroidHudCanvas(canvas), frame, call, settings)
        canvas.restore()
    }
}

/** The HUD line as a transparent overlay on the head-up display, neither focusable nor touchable. */
internal class MonjaroHudWindow(
    private val context: Context,
    private val log: (String) -> Unit,
) : HudWindow {
    private val main = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var view: HudLineView? = null
    private var shownOn: Int? = null
    private var windows: WindowManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var opListener: AppOpsManager.OnOpChangedListener? = null
    private var reported: String? = null

    override fun available(): Boolean {
        val permitted = Settings.canDrawOverlays(context)
        val all = displays?.displays.orEmpty().map(PhysicalDisplay::describe)
        val choice = MonjaroHudDisplay.choose(all)
        val report = "HUD overlay=${if (permitted) "allowed" else "missing"} ${choice.reason}; displays=${all.joinToString()}"
        if (report != reported) {
            reported = report
            log(report)
        }
        return MonjaroHudDisplay.usable(permitted, choice.displayId, shownOn)
    }

    override fun startWatching(changed: () -> Unit) {
        stopWatching()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = changed()
            override fun onDisplayRemoved(displayId: Int) = changed()
            override fun onDisplayChanged(displayId: Int) = changed()
        }
        displays?.registerDisplayListener(listener, main)
        displayListener = listener
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return
        val op = AppOpsManager.OnOpChangedListener { _, packageName ->
            if (packageName == context.packageName) main.post(changed)
        }
        ops.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, op)
        opListener = op
    }

    override fun stopWatching() {
        displayListener?.let { displays?.unregisterDisplayListener(it) }
        displayListener = null
        opListener?.let { context.getSystemService(AppOpsManager::class.java)?.stopWatchingMode(it) }
        opListener = null
    }

    override fun show(): Boolean {
        hide()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log("HUD needs Android 11 window contexts")
            return false
        }
        val displayId = MonjaroHudDisplay.choose(displays?.displays.orEmpty().map(PhysicalDisplay::describe))
            .displayId ?: return false
        val display = displays?.getDisplay(displayId) ?: return false
        return add(display)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun add(display: Display): Boolean {
        val windowContext = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windows = windowContext.getSystemService(WindowManager::class.java) ?: return false
        val line = HudLineView(windowContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DiPlay HUD"
        }
        try {
            windows.addView(line, params)
        } catch (error: RuntimeException) {
            log("HUD window refused on display ${display.displayId} (${error.javaClass.simpleName})")
            return false
        }
        view = line
        shownOn = display.displayId
        this.windows = windows
        log("HUD window added on display ${display.displayId}")
        return true
    }

    override fun hide() {
        val line = view ?: return
        view = null
        shownOn = null
        runCatching { windows?.removeViewImmediate(line) }
        windows = null
        log("HUD window removed")
    }

    override fun draw(frame: HudFrame?, call: HudCall?, settings: HudSettings) {
        view?.show(frame, call, settings)
    }
}

/** The HUD line of the Monjaro A3 card, following every CarPlay session of the process. */
internal object MonjaroHud {
    private const val TAG = "DiPlay-Hud"
    private val main = Handler(Looper.getMainLooper())
    private var state: HudState? = null

    /** At process start, on the main thread, before any activity, receiver or service runs. */
    fun install(context: Context) {
        if (state != null) return
        val app = context.applicationContext
        val created = HudState(
            options = { MonjaroA3Options.load(app) },
            window = MonjaroHudWindow(app) { Log.i(TAG, it) },
            glance = CarPlayGlance::snapshot,
            units = { HudUnits.of(AppLocale.wrap(app)) },
            call = {
                HudCall.of(
                    calls = CarPlayCalls.current(),
                    engineeringMode = MonjaroCustomDashboard.engineeringModeWanted,
                    callsActive = { MonjaroDashboardCall.active(app) },
                    nowMillis = System.currentTimeMillis(),
                    words = { HudCallWords.of(AppLocale.wrap(app)) },
                )
            },
            calling = { CarPlayCalls.current().card != null },
            postDelayed = { delayMillis, task -> main.postDelayed(task, delayMillis) },
            cancel = main::removeCallbacks,
            log = { Log.i(TAG, it) },
        )
        state = created
        CarPlayGlance.observe { main.post(created::glanceChanged) }
        CarPlayCalls.observe { main.post(created::callChanged) }
        MonjaroCustomDashboard.observeSettings(created::callChanged)
    }

    fun settingChanged() {
        main.post { state?.settingChanged() }
    }

    /** After a change to whether the calls option acts. */
    fun callsChanged() {
        main.post { state?.callChanged() }
    }
}
