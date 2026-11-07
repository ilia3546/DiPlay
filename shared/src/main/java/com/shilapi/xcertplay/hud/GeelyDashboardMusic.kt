package com.shilapi.xcertplay.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.compat.Base64Compat
import com.shilapi.xcertplay.network.RootShell
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A track for the dashboard; [cover] is a JPEG ready for the message, or null for none. */
internal class DashboardTrack(val title: String, val artist: String, val album: String, val cover: ByteArray?,
    val durationMillis: Long? = null)

/**
 * The music card messages the Desay SV dashboard service sends to QNX (CabinLAN 0x1001): subtype 0
 * carries the track and its cover, subtype 1 the play state, subtype 6 the position and length and
 * subtype 129 whether the progress bar is shown.
 */
internal object GeelyDashboardMessages {
    // QNX draws its Bluetooth badge for this origin; the probe on the car showed DiPlay's own cover with it.
    private const val ORIGIN = 6
    const val TRACK = 0
    const val PLAY_STATE = 1
    const val PLAY_TIME = 6
    const val PLAY_BAR = 129

    /** Both in whole seconds, as JSON numbers. */
    fun playTime(currentSeconds: Long, totalSeconds: Long): String =
        "{\"CurrentPlayingTime\":$currentSeconds,\"TotalPlayTime\":$totalSeconds}"

    fun playBar(shown: Boolean): String = "{\"MusicPlayBarStatus\":${if (shown) 1 else 0}}"

    /** Field order, empty fields and base64 lines of 76 characters follow the stock service. */
    fun track(title: String, artist: String, album: String, cover: ByteArray): String = buildString {
        append("{\"Origin\":").append(ORIGIN).append(",\"SongIndex\":0")
        field("SongName", title)
        field("SongArtist", artist)
        field("SongAlbum", album)
        field("SongSchools", "")
        field("SongAges", "")
        field("IsChangeAlbumPic", "true")
        field("SongAlbumPicture", Base64Compat.encodeLines(cover, 76, "\n"))
        append('}')
    }

    fun playState(playing: Boolean): String = "{\"MusicPlayStatus\":\"${if (playing) "Play" else "Pause"}\"}"

    /** Appends `,"name":"value"` with [value] escaped as a JSON string. */
    internal fun StringBuilder.field(name: String, value: String) {
        append(",\"").append(name).append("\":\"")
        for (char in value) when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
        append('"')
    }
}

/**
 * What the dashboard shows. Sends a track only when its text or cover changed and the play state only
 * when it changed; [clear] sends one empty card, and only while a card of ours may be shown. A track with
 * a duration gets the progress bar and its position, every [tick] while playing when the second changed
 * and once at a pause. Not thread-safe: [DashboardMusicQueue] calls it from one worker.
 */
internal class GeelyDashboardMusicOutput(
    private val send: (subtype: Int, json: String) -> Boolean,
    /** The cover for a track without one and for the empty card; the stock night cover is Desay's asset. */
    private val blankCover: () -> ByteArray,
    private val log: (String) -> Unit = {},
    /**
     * Sends a repeat of what is shown, the position and the progress bar; none of them may start su, so it
     * may refuse where [send] would not.
     */
    private val resend: (subtype: Int, json: String) -> Boolean = send,
    /** A card of ours may already be shown: a killed process sent it and never cleared it. */
    leftBehind: Boolean = false,
    /** Hears whether a card of ours may be shown, so the next process can tell [leftBehind]. */
    private val cardShown: (Boolean) -> Unit = {},
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private var shown = leftBehind
    private var barShown = leftBehind
    private var lastTrack: String? = null
    private var lastPlaying: Boolean? = null
    private var wantPlaying: Boolean? = null
    private var durationMillis = 0L
    private var elapsedMillis: Long? = null
    private var elapsedAt = 0L
    private var lastSecond: Long? = null

    val ticking: Boolean get() = lastTrack != null && durationMillis > 0 && wantPlaying == true

    fun show(track: DashboardTrack) {
        if (track.title.isBlank() && track.artist.isBlank()) {
            clear()
            return
        }
        val json = GeelyDashboardMessages.track(track.title.trim(), track.artist.trim(), track.album.trim(),
            track.cover ?: blankCover())
        val duration = track.durationMillis?.coerceAtLeast(0) ?: 0L
        if (json == lastTrack && duration == durationMillis) return
        // The stock service hides the bar before it sends the card and shows it after the first position.
        if (duration == 0L) showBar(false)
        if (json != lastTrack) {
            if (!send(GeelyDashboardMessages.TRACK, json)) return
            lastTrack = json
            if (!shown) {
                shown = true
                cardShown(true)
            }
            log("dashboard music track sent titleLength=${track.title.length} cover=${track.cover?.size ?: "blank"}")
            sendPlayState()
        }
        durationMillis = duration
        lastSecond = null
        sendPosition()
        if (duration > 0) showBar(true)
    }

    /** Remembered until a track is shown: QNX gets the play state of a card it has. */
    fun playing(playing: Boolean) {
        val paused = wantPlaying == true && !playing
        if (wantPlaying != playing) {
            elapsedMillis = elapsedMillis?.let { position() }
            elapsedAt = now()
        }
        wantPlaying = playing
        sendPlayState()
        if (paused) {
            lastSecond = null
            sendPosition()
        }
    }

    /** The iPhone's position, [elapsedMillis] at [atMillis] on the elapsed-realtime clock. */
    fun position(elapsedMillis: Long?, atMillis: Long) {
        this.elapsedMillis = elapsedMillis
        elapsedAt = atMillis
    }

    fun tick() = sendPosition()

    private fun sendPlayState() {
        val playing = wantPlaying ?: return
        if (lastTrack == null || lastPlaying == playing) return
        if (send(GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(playing))) lastPlaying = playing
    }

    private fun sendPosition() {
        if (lastTrack == null || durationMillis <= 0) return
        val second = position() / 1000
        if (second == lastSecond) return
        if (resend(GeelyDashboardMessages.PLAY_TIME, GeelyDashboardMessages.playTime(second, durationMillis / 1000))) {
            lastSecond = second
        }
    }

    private fun showBar(shown: Boolean) {
        if (barShown != shown) sendBar(shown)
    }

    private fun sendBar(shown: Boolean) {
        if (resend(GeelyDashboardMessages.PLAY_BAR, GeelyDashboardMessages.playBar(shown))) barShown = shown
    }

    private fun position(): Long {
        val elapsed = elapsedMillis ?: return 0L
        val moved = if (wantPlaying == true) (now() - elapsedAt).coerceAtLeast(0) else 0L
        val position = if (durationMillis > 0) (elapsed + moved).coerceAtMost(durationMillis) else elapsed + moved
        return position.coerceAtLeast(0)
    }

    /**
     * Sends the shown card, play state, position and progress bar again: the stock dashboard service overwrites
     * them on its own events.
     */
    fun reassert() {
        val track = lastTrack ?: return
        if (!resend(GeelyDashboardMessages.TRACK, track)) return
        lastPlaying?.let { resend(GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(it)) }
        lastSecond = null
        sendPosition()
        sendBar(durationMillis > 0)
    }

    fun clear() {
        elapsedMillis = null
        if (!shown) return
        showBar(false)
        if (send(GeelyDashboardMessages.TRACK, GeelyDashboardMessages.track("", "", "", blankCover()))) {
            shown = false
            lastTrack = null
            lastPlaying = null
            durationMillis = 0L
            lastSecond = null
            cardShown(false)
            log("dashboard music cleared")
        }
    }
}

/**
 * Applies the card DiPlay wants to the dashboard and the media widgets on one worker, in order. Each call
 * records what is wanted now and queues a sync that applies the latest wish, so a sync queued before a newer
 * show or stop cannot bring an older card back. Owns the covers it is given until it encodes or drops them.
 */
internal class DashboardMusicQueue(
    private val execute: (Runnable) -> Unit,
    /** Runs a task on the same worker every period until the returned function is called. */
    private val repeat: (periodMillis: Long, Runnable) -> () -> Unit,
    private val encode: (Bitmap) -> ByteArray,
    private val output: () -> GeelyDashboardMusicOutput,
    private val widget: () -> GeelyMediaWidgetOutput,
    private val newSession: () -> Unit,
) {
    private sealed interface Wish {
        class Track(val title: String, val artist: String, val album: String, val durationMillis: Long?,
            val cover: Bitmap?) : Wish
        object Clear : Wish
    }

    private val wish = AtomicReference<Wish?>()
    private var stopRepeating: (() -> Unit)? = null
    private var stopProgress: (() -> Unit)? = null
    private var stopPosition: (() -> Unit)? = null

    /** A session with the option on began: the shown card is repeated until [stop]. */
    fun start() = execute(Runnable {
        newSession()
        stopRepeating?.invoke()
        stopProgress?.invoke()
        stopRepeating = repeat(REASSERT_MILLIS, Runnable {
            output().reassert()
            widget().reassert()
        })
        stopProgress = repeat(PROGRESS_MILLIS, Runnable { widget().tick() })
    })

    fun show(title: String, artist: String, album: String, durationMillis: Long?, cover: Bitmap?) =
        want(Wish.Track(title, artist, album, durationMillis, cover))

    fun playing(playing: Boolean) = execute(Runnable {
        output().playing(playing)
        widget().playing(playing)
        followPosition()
    })

    fun position(elapsedMillis: Long?, atMillis: Long) = execute(Runnable {
        output().position(elapsedMillis, atMillis)
        widget().position(elapsedMillis, atMillis)
    })

    fun stop() {
        want(Wish.Clear)
        execute(Runnable {
            stopRepeating?.invoke()
            stopRepeating = null
            stopProgress?.invoke()
            stopProgress = null
            stopPosition?.invoke()
            stopPosition = null
        })
    }

    /** Runs [done] on the worker once the work asked for before this call is done. */
    fun whenIdle(done: () -> Unit) = execute(Runnable(done))

    private fun want(next: Wish) {
        (wish.getAndSet(next) as? Wish.Track)?.cover?.recycle()
        execute(Runnable(::sync))
    }

    private fun sync() {
        when (val next = wish.getAndSet(null) ?: return) {
            is Wish.Track -> {
                val jpeg = next.cover?.let(encode)
                output().show(DashboardTrack(next.title, next.artist, next.album, jpeg, next.durationMillis))
                widget().show(next.title, next.artist, next.album, next.durationMillis, next.cover)
                next.cover?.recycle()
            }
            Wish.Clear -> {
                output().clear()
                widget().clear()
            }
        }
        followPosition()
    }

    private fun followPosition() {
        val ticking = output().ticking
        if (ticking && stopPosition == null) {
            stopPosition = repeat(POSITION_MILLIS, Runnable { output().tick() })
        } else if (!ticking) {
            stopPosition?.invoke()
            stopPosition = null
        }
    }

    companion object {
        const val REASSERT_MILLIS = 30_000L
        const val PROGRESS_MILLIS = 5_000L
        const val POSITION_MILLIS = 1_000L
    }
}

/**
 * The dashboard and the media widgets as the Monjaro A3 option drives them; every call returns at once and the
 * work runs in order.
 */
interface DashboardMusic {
    /** [mediaKey] gets the media widget's buttons as [com.shilapi.xcertplay.airplay.CarPlayMediaButton] indices. */
    fun start(context: Context, mediaKey: (index: Int) -> Unit)

    /** The cover is copied before this returns, so [artwork] stays the caller's to recycle. */
    fun show(context: Context, title: String?, artist: String?, album: String?, durationMillis: Long?, artwork: Bitmap?)

    fun playing(context: Context, playing: Boolean)

    /** The iPhone's position, [elapsedMillis] at [atMillis] on the elapsed-realtime clock. */
    fun position(context: Context, elapsedMillis: Long?, atMillis: Long)

    /** Sends one empty card if a card of ours is shown, gives the media widgets back and stops repeating. */
    fun stop(context: Context)

    /** Runs [done] on the dashboard worker once the work asked for before this call is done. */
    fun whenIdle(done: () -> Unit)
}

/**
 * The CarPlay track on the stock Monjaro A3 dashboard and media widgets. The first message of a process
 * clears a dashboard card that a killed process left; a repeat goes only through the app process, never
 * through su.
 */
object GeelyDashboardMusic : DashboardMusic {
    private const val TAG = "DiPlay-DashboardMusic"
    private const val COVER_SIZE = 300
    private const val COVER_QUALITY = 85
    private const val PREFS = "diplay_dashboard_music"
    private const val KEY_CARD_SHOWN = "card_shown"
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-dashboard-music").apply { isDaemon = true } }
    @Volatile private var app: Context? = null
    @Volatile private var mediaKey: (Int) -> Unit = {}
    private var link: Link? = null
    private var widget: GeelyMediaWidgetOutput? = null
    private var blank: ByteArray? = null
    private val widgetKeys = GeelyMediaWidgetKeys(press = { mediaKey(it) }, log = { Log.i(TAG, it) })

    private class Link(val sender: GeelyCabinLan.Sender, val output: GeelyDashboardMusicOutput)

    private val queue = DashboardMusicQueue(
        execute = worker::execute,
        repeat = { periodMillis, task ->
            val future = worker.scheduleWithFixedDelay(task, periodMillis, periodMillis, TimeUnit.MILLISECONDS)
            ({ future.cancel(false) })
        },
        encode = { bitmap ->
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) }.toByteArray()
        },
        output = { link().output },
        widget = ::mediaWidget,
        newSession = { link().sender.newSession() },
    )

    override fun start(context: Context, mediaKey: (index: Int) -> Unit) {
        app = context.applicationContext
        this.mediaKey = mediaKey
        queue.start()
    }

    override fun show(context: Context, title: String?, artist: String?, album: String?, durationMillis: Long?,
        artwork: Bitmap?) {
        app = context.applicationContext
        val cover = artwork?.takeUnless { it.isRecycled }?.let { runCatching { fitted(it) }.getOrNull() }
        queue.show(title.orEmpty(), artist.orEmpty(), album.orEmpty(), durationMillis, cover)
    }

    override fun playing(context: Context, playing: Boolean) {
        app = context.applicationContext
        queue.playing(playing)
    }

    override fun position(context: Context, elapsedMillis: Long?, atMillis: Long) {
        app = context.applicationContext
        queue.position(elapsedMillis, atMillis)
    }

    override fun stop(context: Context) {
        app = context.applicationContext
        queue.stop()
    }

    override fun whenIdle(done: () -> Unit) = queue.whenIdle(done)

    /** Scales [artwork] into a black 300x300 square, keeping its aspect ratio. */
    internal fun fitted(artwork: Bitmap): Bitmap {
        val square = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(COVER_SIZE.toFloat() / artwork.width, COVER_SIZE.toFloat() / artwork.height)
        val width = artwork.width * scale
        val height = artwork.height * scale
        val left = (COVER_SIZE - width) / 2
        val top = (COVER_SIZE - height) / 2
        canvas.drawBitmap(artwork, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        return square
    }

    private fun blankCover(): ByteArray = blank ?: run {
        val square = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) }.toByteArray()
            .also { square.recycle(); blank = it }
    }

    private fun link(): Link = link ?: run {
        val app = checkNotNull(app)
        val apk = app.applicationInfo.sourceDir.orEmpty()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sender = GeelyCabinLan.Sender(
            direct = GeelyCabinLan::sendDirect,
            root = { msgType, subtype, json ->
                GeelyCabinLan.rootFailure(RootShell.runWithInput(GeelyCabinLan.rootCommand(apk, msgType, subtype),
                    json.toByteArray(Charsets.UTF_8)))
            },
            log = { Log.i(TAG, it) },
        )
        val output = GeelyDashboardMusicOutput(
            send = { subtype, json -> sender.send(GeelyCabinLan.MUSIC, subtype, json) },
            blankCover = ::blankCover,
            log = { Log.i(TAG, it) },
            resend = { subtype, json -> sender.sendWithoutRoot(GeelyCabinLan.MUSIC, subtype, json) },
            leftBehind = prefs.getBoolean(KEY_CARD_SHOWN, false),
            // A commit, not an apply: the process may be killed right after the message.
            cardShown = { prefs.edit().putBoolean(KEY_CARD_SHOWN, it).commit() },
        )
        output.clear()
        Link(sender, output).also { link = it }
    }

    private fun mediaWidget(): GeelyMediaWidgetOutput = widget ?: run {
        val app = checkNotNull(app)
        GeelyMediaWidgetOutput(
            send = { intent -> runCatching { app.sendBroadcast(intent) }.isSuccess },
            appName = app.applicationInfo.loadLabel(app.packageManager).toString(),
            appPackage = app.packageName,
            owned = { owned -> if (owned) widgetKeys.register(app) else widgetKeys.unregister() },
            log = { Log.i(TAG, it) },
        ).also { widget = it }
    }
}
