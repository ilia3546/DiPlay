package com.shilapi.xcertplay.hud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/**
 * The broadcasts of the stock music widgets on the driver and passenger screens (`com.geely.mediawidget`).
 * Its service listens for [UPDATE], [PROGRESS] and [CLEAR] without a permission; its play, pause, previous and
 * next buttons send the implicit key broadcasts, which nothing on the head unit receives.
 */
internal object GeelyMediaWidgetMessages {
    const val PACKAGE = "com.geely.mediawidget"
    const val UPDATE = "gmc.ecarx.xsf.mediacenter.update"
    const val PROGRESS = "gmc.ecarx.xsf.mediacenter.progress"
    /** The widgets draw the stock media center's source again. */
    const val CLEAR = "gmc.ecarx.xsf.mediacenter.clear"
    const val KEY_PLAY = "gmc.audio_play"
    const val KEY_PAUSE = "gmc.audio_pause"
    const val KEY_PREVIOUS = "gmc.audio_pre"
    const val KEY_NEXT = "gmc.audio_next"
    val KEYS = listOf(KEY_PLAY, KEY_PAUSE, KEY_PREVIOUS, KEY_NEXT)

    const val EXTRA_TITLE = "music.title"
    const val EXTRA_SUBTITLE = "music.subtitle"
    const val EXTRA_APP = "music.app"
    /** The package a tap on the widget opens. */
    const val EXTRA_APP_PACKAGE = "music.app.full"
    const val EXTRA_PLAYING = "music.is_playing"
    const val EXTRA_IMAGE = "music.image"
    const val EXTRA_DURATION = "music.duration"
    const val EXTRA_PROGRESS = "music.progress"

    /** The widget drops an update whose title, subtitle, app or package is null, so none of them is. */
    fun update(title: String, subtitle: String, app: String, appPackage: String, playing: Boolean, cover: Bitmap?,
        durationMillis: Long, progressMillis: Long): Intent = Intent(UPDATE).setPackage(PACKAGE)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_SUBTITLE, subtitle)
        .putExtra(EXTRA_APP, app)
        .putExtra(EXTRA_APP_PACKAGE, appPackage)
        .putExtra(EXTRA_PLAYING, playing)
        .putExtra(EXTRA_DURATION, durationMillis)
        .putExtra(EXTRA_PROGRESS, progressMillis)
        .apply { cover?.let { putExtra(EXTRA_IMAGE, it) } }

    fun progress(durationMillis: Long, progressMillis: Long): Intent = Intent(PROGRESS).setPackage(PACKAGE)
        .putExtra(EXTRA_DURATION, durationMillis)
        .putExtra(EXTRA_PROGRESS, progressMillis)

    fun clear(): Intent = Intent(CLEAR).setPackage(PACKAGE)

    /** The widget names the command itself from the play state it was last sent. */
    fun mediaButton(action: String?): Int? = when (action) {
        KEY_PLAY -> CarPlayMediaButton.PLAY
        KEY_PAUSE -> CarPlayMediaButton.PAUSE
        KEY_PREVIOUS -> CarPlayMediaButton.PREVIOUS
        KEY_NEXT -> CarPlayMediaButton.NEXT
        else -> null
    }
}

/**
 * What the stock music widgets show. Sends the whole card for each track, play state change and repeat, and the
 * position alone every [tick] while playing; [clear] gives the widgets back to the media center, and only while
 * a card of ours may be shown. [owned] hears when DiPlay starts and stops owning the widgets. Not thread-safe:
 * [DashboardMusicQueue] calls it from one worker.
 */
internal class GeelyMediaWidgetOutput(
    private val send: (Intent) -> Boolean,
    private val appName: String,
    private val appPackage: String,
    private val owned: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Card(val title: String, val subtitle: String, val durationMillis: Long, val cover: Bitmap?)

    private var card: Card? = null
    private var shown = false
    private var playing = false
    private var elapsedMillis: Long? = null
    private var elapsedAt = 0L

    /** Keeps its own small copy of [cover], so the caller may recycle it. */
    fun show(title: String, artist: String, album: String, durationMillis: Long?, cover: Bitmap?) {
        if (title.isBlank() && artist.isBlank()) {
            clear()
            return
        }
        card?.cover?.recycle()
        val subtitle = artist.trim().ifEmpty { album.trim() }.ifEmpty { " " }
        val copy = cover?.takeUnless { it.isRecycled }?.let { runCatching { it.copy(Bitmap.Config.RGB_565, false) }.getOrNull() }
        card = Card(title.trim(), subtitle, durationMillis?.coerceAtLeast(0) ?: 0L, copy)
        if (sendCard()) log("media widget track sent titleLength=${title.length} cover=${copy != null}")
    }

    fun playing(playing: Boolean) {
        if (this.playing == playing) return
        elapsedMillis = position()
        elapsedAt = now()
        this.playing = playing
        if (shown) sendCard()
    }

    /** The iPhone's position, [elapsedMillis] at [atMillis] on the elapsed-realtime clock. */
    fun position(elapsedMillis: Long?, atMillis: Long) {
        this.elapsedMillis = elapsedMillis
        elapsedAt = atMillis
    }

    fun tick() {
        val duration = card?.durationMillis ?: return
        if (!shown || !playing || duration <= 0) return
        send(GeelyMediaWidgetMessages.progress(duration, position() ?: 0L))
    }

    /** Sends the shown card again: the media center redraws the widgets on its own events. */
    fun reassert() {
        if (shown) sendCard()
    }

    fun clear() {
        card?.cover?.recycle()
        card = null
        elapsedMillis = null
        if (!shown) return
        if (send(GeelyMediaWidgetMessages.clear())) {
            shown = false
            owned(false)
            log("media widget cleared")
        }
    }

    private fun position(): Long? {
        val elapsed = elapsedMillis ?: return null
        val moved = if (playing) (now() - elapsedAt).coerceAtLeast(0) else 0L
        val duration = card?.durationMillis ?: 0L
        return (elapsed + moved).let { if (duration > 0) it.coerceAtMost(duration) else it }.coerceAtLeast(0)
    }

    private fun sendCard(): Boolean {
        val shownCard = card ?: return false
        val sent = send(GeelyMediaWidgetMessages.update(shownCard.title, shownCard.subtitle, appName, appPackage,
            playing, shownCard.cover, shownCard.durationMillis, position() ?: 0L))
        if (sent && !shown) {
            shown = true
            owned(true)
        }
        return sent
    }
}

/**
 * The widget buttons as CarPlay presses while DiPlay owns the widgets. The widget sends them as implicit
 * broadcasts from its own package, so the receiver is exported. [register] and [unregister] run on one worker;
 * the broadcasts arrive on the main thread.
 */
internal class GeelyMediaWidgetKeys(
    private val press: (index: Int) -> Unit,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onKey(intent.action)
    }
    private var registeredIn: Context? = null
    private var lastKey: String? = null
    private var lastKeyAt = 0L

    fun register(context: Context) {
        if (registeredIn != null) return
        val filter = IntentFilter().apply { GeelyMediaWidgetMessages.KEYS.forEach(::addAction) }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
            .onSuccess { registeredIn = context; log("media widget keys registered") }
            .onFailure { log("media widget keys unavailable ${it.javaClass.simpleName}") }
    }

    fun unregister() {
        val context = registeredIn ?: return
        runCatching { context.unregisterReceiver(receiver) }
        registeredIn = null
        log("media widget keys unregistered")
    }

    internal fun onKey(action: String?) {
        val index = GeelyMediaWidgetMessages.mediaButton(action) ?: return
        val at = now()
        if (action == lastKey && at - lastKeyAt < REPEAT_MILLIS) {
            log("media widget key $action repeat ignored")
            return
        }
        lastKey = action
        lastKeyAt = at
        press(index)
    }

    companion object {
        const val REPEAT_MILLIS = 300L
    }
}
