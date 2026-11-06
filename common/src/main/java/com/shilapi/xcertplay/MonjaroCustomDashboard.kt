package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.EngineeringMode
import com.shilapi.xcertplay.hud.GeelyEngineeringMode
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the custom dashboard window draws: a view for a 1920x720 frame in the cluster display's pixels. */
internal fun interface MonjaroDashboardContent {
    fun create(context: Context): View
}

/** The custom dashboard window on the cluster display, as [CustomDashboardState] drives it. Main thread. */
internal interface DashboardWindow {
    /** The overlay permission is granted and exactly one cluster display is present. */
    fun available(): Boolean

    /** Reports each change that may change [available] to [changed], until [stopWatching]. */
    fun startWatching(changed: () -> Unit)

    fun stopWatching()

    /** Adds the window, or returns false; [firstFrame] runs on the main thread once its first frame is committed. */
    fun show(firstFrame: () -> Unit): Boolean

    fun hide()
}

/**
 * The custom dashboard during a CarPlay session: the window on the cluster display, and engineering mode once the
 * window drew its first frame. The option is read once per session, by [sessionStarting] while its AirPlay
 * configuration is built, so the cluster stream and the window agree; turning it off, losing the overlay
 * permission or the display, and [terminate] take the dashboard away at once, while a session end waits
 * [SESSION_END_GRACE_MILLIS] so that a reconnect does not leave and enter the mode again. Main thread only.
 */
internal class CustomDashboardState(
    private val enabled: () -> Boolean,
    private val window: DashboardWindow,
    private val enter: (current: () -> Boolean) -> Unit,
    private val exit: () -> Unit,
    private val sessionStarted: () -> Unit,
    private val postDelayed: (delayMillis: Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var connected = false
    private var sessionActive = false

    /** Whether the session being configured or running has the custom dashboard. */
    var sessionEnabled = false
        private set

    /** Whether the session's cluster stream was sized for the dashboard; turning the option off keeps it so. */
    var sessionStream = false
        private set
    private var terminating = false
    private var watching = false
    private var generation = 0L
    private var shownGeneration: Long? = null
    private var drawnGeneration: Long? = null
    @Volatile private var enterableGeneration: Long? = null
    private val sessionEnd = Runnable {
        sessionActive = false
        update()
    }

    fun sessionStarting(): Boolean {
        sessionEnabled = enabled()
        sessionStream = sessionEnabled
        update()
        return sessionEnabled
    }

    fun connectedChanged(next: Boolean) {
        if (next == connected) return
        connected = next
        if (next) {
            cancel(sessionEnd)
            sessionActive = true
            if (sessionEnabled) sessionStarted()
            update()
        } else if (sessionActive) {
            postDelayed(SESSION_END_GRACE_MILLIS, sessionEnd)
        }
    }

    fun settingChanged() {
        if (!sessionEnabled || enabled()) return
        sessionEnabled = false
        update()
    }

    fun terminate() {
        terminating = true
        cancel(sessionEnd)
        update()
    }

    private fun update() {
        val wanted = !terminating && sessionEnabled && sessionActive
        if (wanted != watching) {
            watching = wanted
            if (wanted) window.startWatching(::update) else window.stopWatching()
        }
        if (!wanted || !window.available()) {
            enterableGeneration = null
            if (shownGeneration != null) {
                exit()
                window.hide()
                shownGeneration = null
                drawnGeneration = null
            }
            return
        }
        if (shownGeneration == null) {
            val next = ++generation
            shownGeneration = next
            if (!window.show { firstFrame(next) }) {
                shownGeneration = null
                log("custom dashboard window not shown; engineering mode stays off")
                return
            }
        }
        val drawn = drawnGeneration ?: return
        if (drawn != shownGeneration) return
        enterableGeneration = drawn
        enter { enterableGeneration == drawn }
    }

    private fun firstFrame(shown: Long) {
        if (shown != shownGeneration) return
        drawnGeneration = shown
        update()
    }

    companion object {
        const val SESSION_END_GRACE_MILLIS = 5_000L
    }
}

/** The custom dashboard of the Monjaro A3 card, following every CarPlay session of the process. */
internal object MonjaroCustomDashboard {
    private const val TAG = "DiPlay-CustomDashboard"
    private val main = Handler(Looper.getMainLooper())
    internal var mode: EngineeringMode = GeelyEngineeringMode
    @Volatile var content: MonjaroDashboardContent = MonjaroDashboardContent(MonjaroDashboardSources::create)
    @Volatile private var app: Context? = null
    private var state: CustomDashboardState? = null
    private val settingsObservers = CopyOnWriteArraySet<() -> Unit>()

    /** At process start, on the main thread, before any activity, receiver or service runs. */
    fun install(context: Context) {
        if (state != null) return
        val app = context.applicationContext
        this.app = app
        mode.releaseStale(app)
        val created = CustomDashboardState(
            enabled = { MonjaroA3Options.load(app).customDashboard },
            window = MonjaroDashboardWindow(app, { content }) { Log.i(TAG, it) },
            enter = { current -> mode.enter(app, current) },
            exit = { mode.exit(app) },
            sessionStarted = { mode.sessionStarted(app) },
            postDelayed = { delayMillis, task -> main.postDelayed(task, delayMillis) },
            cancel = main::removeCallbacks,
            log = { Log.i(TAG, it) },
        )
        state = created
        CarPlayGlance.observe { snapshot -> main.post { created.connectedChanged(snapshot.connected) } }
    }

    fun releaseStale(context: Context) = mode.releaseStale(context.applicationContext)

    /** Main thread, while the next session's AirPlay configuration is built: whether it has the custom dashboard. */
    fun sessionStarting(): Boolean = state?.sessionStarting() ?: false

    /** Main thread: whether the running session has the custom dashboard, until the option is turned off. */
    val sessionEnabled: Boolean get() = state?.sessionEnabled ?: false

    /** Main thread: whether the running session's cluster stream was sized for the dashboard. */
    val sessionStream: Boolean get() = state?.sessionStream ?: false

    fun settingChanged() {
        main.post {
            state?.settingChanged()
            settingsObservers.forEach { it() }
        }
    }

    /** Calls [observer] on the main thread after each [settingChanged], until the returned function runs. */
    fun observeSettings(observer: () -> Unit): () -> Unit {
        settingsObservers.add(observer)
        return { settingsObservers.remove(observer) }
    }

    /** The process is about to be killed. */
    fun terminate() {
        main.post { state?.terminate() }
    }

    /** From the uncaught-exception handler; blocks. */
    fun exitNow() {
        app?.let(mode::exitNow)
    }

    fun whenIdle(done: () -> Unit) {
        main.post { mode.whenIdle(done) }
    }
}

/**
 * Waits off the main thread, up to one deadline, for the dashboard music and the custom dashboard work asked for
 * so far, such as the empty music card and the engineering-mode exit at shutdown.
 */
internal fun awaitMonjaroDashboards(timeoutMillis: Long): Boolean {
    val idle = CountDownLatch(2)
    MonjaroDashboardMusic.whenIdle(idle::countDown)
    MonjaroCustomDashboard.whenIdle(idle::countDown)
    return try {
        idle.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
