package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.GeelyDashboardMessages.field
import com.shilapi.xcertplay.network.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The call widget messages the Desay SV dashboard service sends to QNX (CabinLAN 0x1003): subtype 2 carries the
 * call and its state, subtype 3 the talk time.
 */
internal object GeelyCallMessages {
    // QNX draws the Bluetooth phone's widget for this type; the probe on the car showed DiPlay's text with it.
    private const val PHONE_TYPE = 1
    const val CALL = 2
    const val DURATION = 3
    const val IDLE = 0
    const val INCOMING = 1
    const val DIALING = 2
    const val TALKING = 3
    const val ENDED = 4

    /** Field order and the empty picture follow the stock service. */
    fun call(name: String, number: String, state: Int): String = buildString {
        append("{\"PhoneType\":").append(PHONE_TYPE)
        field("Name", name)
        field("Number", number)
        append(",\"CallState\":").append(state)
        field("ContactPicture", "")
        append('}')
    }

    fun duration(seconds: Long): String = "{\"CallDuration\":$seconds}"

    /** A held call is shown as a call in progress, as on DiPlay's call card. */
    fun state(phase: CarPlayCallCard.Phase): Int = when (phase) {
        CarPlayCallCard.Phase.RINGING -> INCOMING
        CarPlayCallCard.Phase.DIALING -> DIALING
        CarPlayCallCard.Phase.ACTIVE -> TALKING
    }
}

/**
 * What the dashboard's call widget shows. Keeps the wanted call, or the wanted close, until it is delivered: sends
 * the call only when its state, name or number changed, the talk time every [tick] of a delivered call in progress
 * when the second changed, and on [clear] the end of the call and then the idle state, only while a widget of ours
 * may be shown. A message that was not sent leaves [undelivered] set until [retry] or a new wish delivers it; only a
 * retry after the longest wait may start su, so su starts at most once in that time. Not thread-safe:
 * [GeelyDashboardCall] calls it from one worker.
 */
internal class GeelyDashboardCallOutput(
    private val send: (subtype: Int, json: String) -> Boolean,
    /** Sends the talk time, which must not start su, so it may refuse where [send] would not. */
    private val resend: (subtype: Int, json: String) -> Boolean = send,
    /** A widget of ours may already be shown: a killed process sent it and never closed it. */
    leftBehind: Boolean = false,
    /** Hears whether a widget of ours may be shown, so the next process can tell [leftBehind]. */
    private val widgetShown: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
    /** The clock of [CarPlayCallCard.activeSinceMillis]. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Lets the transport try again a path it gave up on until its next session. */
    private val renewTransport: () -> Unit = {},
) {
    private var shown = leftBehind
    private var wanted: CarPlayCallCard? = null
    private var lastCall: String? = null
    private var lastName = ""
    private var lastNumber = ""
    private var endSent = false
    private var activeSince: Long? = null
    private var lastSecond: Long? = null

    private var retries = 0

    var undelivered = false
        private set(value) {
            field = value
            if (!value) retries = 0
        }

    val ticking: Boolean get() = activeSince != null

    /** How long to wait before the next [retry]. */
    val retryDelayMillis: Long get() = retryDelayMillis(retries)

    fun show(card: CarPlayCallCard?) {
        wanted = card
        deliver()
    }

    fun clear() = show(null)

    /** Sends again what was not delivered; does nothing once it was. */
    fun retry() {
        if (!undelivered) return
        val mayStartSu = retryDelayMillis == RETRY_DELAYS_MILLIS.last()
        retries++
        if (mayStartSu) renewTransport()
        deliver(if (mayStartSu) send else resend)
    }

    fun tick() {
        val since = activeSince ?: return
        val second = (now() - since).coerceAtLeast(0) / 1000
        if (second == lastSecond) return
        if (resend(GeelyCallMessages.DURATION, GeelyCallMessages.duration(second))) lastSecond = second
    }

    private fun deliver(via: (subtype: Int, json: String) -> Boolean = send) {
        val card = wanted ?: return close(via)
        val json = GeelyCallMessages.call(card.name, card.number, GeelyCallMessages.state(card.phase))
        if (json != lastCall) {
            // Marked before the message: a process killed right after it must still close the widget.
            if (!shown) {
                shown = true
                widgetShown(true)
            }
            if (!via(GeelyCallMessages.CALL, json)) {
                activeSince = null
                undelivered = true
                return
            }
            lastCall = json
            lastName = card.name
            lastNumber = card.number
            endSent = false
            lastSecond = null
            log("dashboard call ${card.phase} sent nameLength=${card.name.length} number=${card.number.isNotEmpty()}")
        }
        undelivered = false
        activeSince = card.activeSinceMillis.takeIf { card.phase == CarPlayCallCard.Phase.ACTIVE }
        tick()
    }

    private fun close(via: (subtype: Int, json: String) -> Boolean) {
        activeSince = null
        if (!shown) {
            undelivered = false
            return
        }
        if (!endSent) {
            if (!via(GeelyCallMessages.CALL, GeelyCallMessages.call(lastName, lastNumber, GeelyCallMessages.ENDED))) {
                undelivered = true
                return
            }
            endSent = true
            lastCall = null
        }
        if (!via(GeelyCallMessages.CALL, GeelyCallMessages.call(lastName, lastNumber, GeelyCallMessages.IDLE))) {
            undelivered = true
            return
        }
        shown = false
        endSent = false
        lastName = ""
        lastNumber = ""
        lastSecond = null
        undelivered = false
        widgetShown(false)
        log("dashboard call widget closed")
    }

    companion object {
        private val RETRY_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 5_000, 10_000)

        /** How long the [attempt]th retry in a row of an undelivered message waits, counting from 0. */
        fun retryDelayMillis(attempt: Int): Long = RETRY_DELAYS_MILLIS[attempt.coerceIn(0, RETRY_DELAYS_MILLIS.size - 1)]
    }
}

/** The stock dashboard's call widget as the Monjaro A3 option drives it; every call returns at once and the work runs in order. */
interface DashboardCall {
    /** A session with the option on began. */
    fun start(context: Context)

    /** Null closes the widget. What fails to send is sent again until it is delivered or another wish replaces it. */
    fun show(context: Context, card: CarPlayCallCard?)

    /** Closes the widget if one of ours is shown, like a null [show], and stops the talk time. */
    fun stop(context: Context)

    /** At process start: closes a widget that a killed process left shown. */
    fun clearLeftBehind(context: Context)

    /** Runs [done] on the call worker once the work asked for before this call is done. */
    fun whenIdle(done: () -> Unit)
}

/**
 * The CarPlay call in the stock Monjaro A3 dashboard and HUD call widget. The first message of a process closes a
 * widget that a killed process left; the talk time goes only through the app process, never through su.
 */
object GeelyDashboardCall : DashboardCall {
    private const val TAG = "DiPlay-DashboardCall"
    private const val PREFS = "diplay_dashboard_call"
    private const val KEY_WIDGET_SHOWN = "widget_shown"
    private const val DURATION_MILLIS = 1_000L
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-dashboard-call").apply { isDaemon = true } }
    @Volatile private var app: Context? = null
    private var link: Link? = null
    private var duration: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null

    private class Link(val sender: GeelyCabinLan.Sender, val output: GeelyDashboardCallOutput)

    override fun start(context: Context) {
        app = context.applicationContext
        worker.execute { link().sender.newSession() }
    }

    override fun show(context: Context, card: CarPlayCallCard?) {
        app = context.applicationContext
        worker.execute {
            link().output.show(card)
            follow()
        }
    }

    override fun stop(context: Context) {
        app = context.applicationContext
        worker.execute {
            link().output.clear()
            follow()
        }
    }

    override fun clearLeftBehind(context: Context) {
        val app = context.applicationContext
        this.app = app
        worker.execute {
            if (link != null || !prefs(app).getBoolean(KEY_WIDGET_SHOWN, false)) return@execute
            Log.i(TAG, "a call widget left by the previous process is closed at start")
            link()
            follow()
        }
    }

    override fun whenIdle(done: () -> Unit) = worker.execute(done)

    private fun follow() {
        followDuration()
        followRetry()
    }

    private fun followRetry() {
        val output = link?.output ?: return
        if (!output.undelivered) {
            retry?.cancel(false)
            retry = null
            return
        }
        if (retry != null) return
        retry = worker.schedule({
            retry = null
            output.retry()
            follow()
        }, output.retryDelayMillis, TimeUnit.MILLISECONDS)
    }

    private fun followDuration() {
        val ticking = link?.output?.ticking == true
        if (ticking && duration == null) {
            duration = worker.scheduleWithFixedDelay({ link?.output?.tick() }, DURATION_MILLIS, DURATION_MILLIS,
                TimeUnit.MILLISECONDS)
        } else if (!ticking) {
            duration?.cancel(false)
            duration = null
        }
    }

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun link(): Link = link ?: run {
        val app = checkNotNull(app)
        val apk = app.applicationInfo.sourceDir.orEmpty()
        val prefs = prefs(app)
        val sender = GeelyCabinLan.Sender(
            direct = GeelyCabinLan::sendDirect,
            root = { msgType, subtype, json ->
                GeelyCabinLan.rootFailure(RootShell.runWithInput(GeelyCabinLan.rootCommand(apk, msgType, subtype),
                    json.toByteArray(Charsets.UTF_8)))
            },
            log = { Log.i(TAG, it) },
        )
        val output = GeelyDashboardCallOutput(
            send = { subtype, json -> sender.send(GeelyCabinLan.PHONE, subtype, json) },
            resend = { subtype, json -> sender.sendWithoutRoot(GeelyCabinLan.PHONE, subtype, json) },
            leftBehind = prefs.getBoolean(KEY_WIDGET_SHOWN, false),
            // A commit, not an apply: the process may be killed right after the message.
            widgetShown = { prefs.edit().putBoolean(KEY_WIDGET_SHOWN, it).commit() },
            log = { Log.i(TAG, it) },
            renewTransport = sender::newSession,
        )
        output.clear()
        Link(sender, output).also { link = it }
    }
}
