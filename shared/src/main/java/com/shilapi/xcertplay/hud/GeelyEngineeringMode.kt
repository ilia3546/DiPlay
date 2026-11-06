package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.network.GeelyHeadUnit
import com.shilapi.xcertplay.network.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The engineering-mode switch of the Desay SV head unit's QNX side (CabinLAN 0x1005, subtype 0x10). On page 0
 * QNX shows Android's cluster display and keeps drawing its speed, warning lights and bottom line over it.
 */
internal object GeelyEngineeringModeMessages {
    fun switch(enter: Boolean): String =
        "{\"EnterOrExitEngineeringMode\":${if (enter) 1 else 0},\"EngineeringModePage\":0}"
}

/** DiPlay's on-disk record that it may have left QNX in engineering mode. */
internal interface EngineeringModeClaim {
    val held: Boolean

    /** Returns whether the record reached the disk. */
    fun set(held: Boolean): Boolean
}

/** What [EngineeringModeLink.releaseStale] does with the claim a previous run may have left. */
internal enum class StaleEngineeringModeAction {
    /** No claim, or this process holds the mode on purpose. */
    NONE,
    /** Not a head unit that has the mode: forget the claim without sending anything. */
    FORGET,
    /** A run that was killed in the mode left it on: leave it. */
    EXIT,
}

/**
 * The start-up decision for an engineering mode a killed run may have left on. An exit while QNX is not in the
 * mode makes it send itself key 0x62, so only DiPlay's own claim leads to an exit.
 */
internal fun staleEngineeringModeAction(heldInProcess: Boolean, claimed: Boolean, supported: Boolean):
    StaleEngineeringModeAction = when {
    heldInProcess || !claimed -> StaleEngineeringModeAction.NONE
    !supported -> StaleEngineeringModeAction.FORGET
    else -> StaleEngineeringModeAction.EXIT
}

/**
 * Enters and leaves engineering mode on one worker. Each call records what is wanted now and queues a sync that
 * applies the latest wish, so an enter queued before a newer exit cannot bring the mode back. The claim is on disk
 * before the enter message leaves and is cleared only once an exit was sent. An exit goes around the
 * [GeelyCabinLan.Sender] back-off: a lost exit leaves the driver without the stock dashboard.
 */
internal class EngineeringModeLink(
    private val execute: (Runnable) -> Unit,
    /** Runs a task on the same worker after the delay. */
    private val schedule: (delayMillis: Long, Runnable) -> Unit,
    private val sendEnter: (json: String) -> Boolean,
    /** Gives the root helper up to [rootTimeoutMillis] once the app's own process was refused. */
    private val sendExit: (json: String, rootTimeoutMillis: Long) -> Boolean,
    private val claim: EngineeringModeClaim,
    private val supported: () -> Boolean,
    private val newSession: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private sealed interface Wish {
        class Enter(val current: () -> Boolean) : Wish
        object Exit : Wish
    }

    private val wish = AtomicReference<Wish?>()
    private var heldInProcess = false
    private var entered = false
    private var exitRound = 0L

    /** Enters unless [current], asked on the worker right before the claim, says the wish is out of date. */
    fun enter(current: () -> Boolean) = want(Wish.Enter(current))

    fun exit() = want(Wish.Exit)

    fun sessionStarted() = execute(Runnable(newSession))

    fun releaseStale() = execute(Runnable {
        when (staleEngineeringModeAction(heldInProcess, claim.held, supported())) {
            StaleEngineeringModeAction.NONE -> Unit
            StaleEngineeringModeAction.FORGET -> claim.set(false)
            StaleEngineeringModeAction.EXIT -> {
                log("engineering mode left on by a previous run; leaving it")
                leave()
            }
        }
    })

    /**
     * On the caller's thread: for a crashing process, whose worker may never run again. The root helper gets
     * [CRASH_EXIT_ROOT_TIMEOUT_MILLIS], not the time a first root prompt needs, so the crash is not held up.
     */
    fun exitNow() {
        if (!supported() || !claim.held) return
        if (sendExit(GeelyEngineeringModeMessages.switch(false), CRASH_EXIT_ROOT_TIMEOUT_MILLIS)) claim.set(false)
    }

    /** Runs [done] on the worker once the work asked for before this call is done. */
    fun whenIdle(done: () -> Unit) = execute(Runnable(done))

    private fun want(next: Wish) {
        wish.set(next)
        execute(Runnable(::sync))
    }

    private fun sync() {
        when (val next = wish.getAndSet(null) ?: return) {
            is Wish.Enter -> enterNow(next.current)
            Wish.Exit -> leave()
        }
    }

    private fun enterNow(current: () -> Boolean) {
        if (!supported() || entered || !current()) return
        exitRound += 1
        if (!claim.set(true)) {
            log("engineering mode claim could not be saved; not entering")
            return
        }
        heldInProcess = true
        entered = sendEnter(GeelyEngineeringModeMessages.switch(true))
        log(if (entered) "engineering mode entered" else "engineering mode enter failed")
    }

    private fun leave() {
        exitRound += 1
        tryLeave(exitRound, attempt = 0)
    }

    private fun tryLeave(round: Long, attempt: Int) {
        if (round != exitRound) return
        heldInProcess = false
        entered = false
        if (!supported() || !claim.held) return
        if (sendExit(GeelyEngineeringModeMessages.switch(false), RootShell.DEFAULT_TIMEOUT_MILLIS)) {
            claim.set(false)
            log("engineering mode left")
            return
        }
        log("engineering mode exit failed (attempt ${attempt + 1})")
        if (attempt < EXIT_RETRIES) {
            schedule(EXIT_RETRY_MILLIS, Runnable { tryLeave(round, attempt + 1) })
        }
    }

    companion object {
        const val EXIT_RETRIES = 3
        const val EXIT_RETRY_MILLIS = 2_000L
        const val CRASH_EXIT_ROOT_TIMEOUT_MILLIS = 3_000L
    }
}

/** The engineering mode as the custom dashboard drives it; every call but [exitNow] returns at once. */
interface EngineeringMode {
    /** Enters unless [current], asked on the mode's worker, says the wish is out of date by then. */
    fun enter(context: Context, current: () -> Boolean)

    fun exit(context: Context)

    fun sessionStarted(context: Context)

    /** Leaves a mode that a killed run left on. */
    fun releaseStale(context: Context)

    /** Leaves the mode on the caller's thread, for a crashing process. */
    fun exitNow(context: Context)

    /** Runs [done] on the mode's worker once the work asked for before this call is done. */
    fun whenIdle(done: () -> Unit)
}

/** The Monjaro A3 engineering mode over CabinLAN; sends nothing on any other head unit. */
object GeelyEngineeringMode : EngineeringMode {
    private const val TAG = "DiPlay-EngineeringMode"
    private const val PREFS = "diplay_engineering_mode"
    private const val KEY_CLAIMED = "claimed"
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-engineering-mode").apply { isDaemon = true } }
    @Volatile private var link: EngineeringModeLink? = null

    override fun enter(context: Context, current: () -> Boolean) = link(context).enter(current)

    override fun exit(context: Context) = link(context).exit()

    override fun sessionStarted(context: Context) = link(context).sessionStarted()

    override fun releaseStale(context: Context) = link(context).releaseStale()

    override fun exitNow(context: Context) = link(context).exitNow()

    override fun whenIdle(done: () -> Unit) {
        link?.whenIdle(done) ?: done()
    }

    private fun link(context: Context): EngineeringModeLink = link ?: synchronized(this) {
        link ?: create(context.applicationContext).also { link = it }
    }

    private fun create(app: Context): EngineeringModeLink {
        val apk = app.applicationInfo.sourceDir.orEmpty()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = { json: String, timeoutMillis: Long ->
            GeelyCabinLan.rootFailure(RootShell.runWithInput(
                GeelyCabinLan.rootCommand(apk, GeelyCabinLan.ENGINEERING_MODE, GeelyCabinLan.ENGINEERING_MODE_SWITCH),
                json.toByteArray(Charsets.UTF_8), timeoutMillis))
        }
        val sender = GeelyCabinLan.Sender(
            direct = GeelyCabinLan::sendDirect,
            root = { _, _, json -> root(json, RootShell.DEFAULT_TIMEOUT_MILLIS) },
            log = { Log.i(TAG, it) },
        )
        return EngineeringModeLink(
            execute = worker::execute,
            schedule = { delayMillis, task -> worker.schedule(task, delayMillis, TimeUnit.MILLISECONDS) },
            sendEnter = { json ->
                sender.send(GeelyCabinLan.ENGINEERING_MODE, GeelyCabinLan.ENGINEERING_MODE_SWITCH, json)
            },
            sendExit = { json, rootTimeoutMillis -> sendAroundBackOff(json) { root(it, rootTimeoutMillis) } },
            claim = object : EngineeringModeClaim {
                override val held: Boolean get() = prefs.getBoolean(KEY_CLAIMED, false)

                // A commit, not an apply: the process may be killed right after the message.
                override fun set(held: Boolean): Boolean = prefs.edit().putBoolean(KEY_CLAIMED, held).commit()
            },
            supported = { GeelyHeadUnit.monjaroA3 },
            newSession = sender::newSession,
            log = { Log.i(TAG, it) },
        )
    }

    private fun sendAroundBackOff(json: String, root: (String) -> String?): Boolean {
        try {
            GeelyCabinLan.sendDirect(GeelyCabinLan.ENGINEERING_MODE, GeelyCabinLan.ENGINEERING_MODE_SWITCH, json)
            return true
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            Log.i(TAG, "engineering mode exit refused in the app process (${cause.javaClass.simpleName})")
        }
        val failure = root(json) ?: return true
        Log.w(TAG, "engineering mode exit root helper failed ($failure)")
        return false
    }
}
