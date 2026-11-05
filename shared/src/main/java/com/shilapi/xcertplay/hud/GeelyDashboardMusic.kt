package com.shilapi.xcertplay.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.shilapi.xcertplay.compat.Base64Compat
import com.shilapi.xcertplay.network.RootShell
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A track for the dashboard; [cover] is a JPEG ready for the message, or null for none. */
internal class DashboardTrack(val title: String, val artist: String, val album: String, val cover: ByteArray?)

/**
 * The music card messages the Desay SV dashboard service sends to QNX (CabinLAN 0x1001): subtype 0
 * carries the track and its cover, subtype 1 the play state.
 */
internal object GeelyDashboardMessages {
    // QNX draws its Bluetooth badge for this origin; the probe on the car showed DiPlay's own cover with it.
    private const val ORIGIN = 6
    const val TRACK = 0
    const val PLAY_STATE = 1

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

    private fun StringBuilder.field(name: String, value: String) {
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
 * when it changed; [clear] sends one empty card, and only while a card of ours may be shown. Not
 * thread-safe: [DashboardMusicQueue] calls it from one worker.
 */
internal class GeelyDashboardMusicOutput(
    private val send: (subtype: Int, json: String) -> Boolean,
    /** The cover for a track without one and for the empty card; the stock night cover is Desay's asset. */
    private val blankCover: () -> ByteArray,
    private val log: (String) -> Unit = {},
    /** Sends a repeat of what is shown; a repeat must not start su, so it may refuse where [send] would not. */
    private val resend: (subtype: Int, json: String) -> Boolean = send,
    /** A card of ours may already be shown: a killed process sent it and never cleared it. */
    leftBehind: Boolean = false,
    /** Hears whether a card of ours may be shown, so the next process can tell [leftBehind]. */
    private val cardShown: (Boolean) -> Unit = {},
) {
    private var shown = leftBehind
    private var lastTrack: String? = null
    private var lastPlaying: Boolean? = null
    private var wantPlaying: Boolean? = null

    fun show(track: DashboardTrack) {
        if (track.title.isBlank() && track.artist.isBlank()) {
            clear()
            return
        }
        val json = GeelyDashboardMessages.track(track.title.trim(), track.artist.trim(), track.album.trim(),
            track.cover ?: blankCover())
        if (json == lastTrack) return
        if (send(GeelyDashboardMessages.TRACK, json)) {
            lastTrack = json
            if (!shown) {
                shown = true
                cardShown(true)
            }
            log("dashboard music track sent titleLength=${track.title.length} cover=${track.cover?.size ?: "blank"}")
            sendPlayState()
        }
    }

    /** Remembered until a track is shown: QNX gets the play state of a card it has. */
    fun playing(playing: Boolean) {
        wantPlaying = playing
        sendPlayState()
    }

    private fun sendPlayState() {
        val playing = wantPlaying ?: return
        if (lastTrack == null || lastPlaying == playing) return
        if (send(GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(playing))) lastPlaying = playing
    }

    /** Sends the shown card and play state again: the stock dashboard service overwrites them on its own events. */
    fun reassert() {
        val track = lastTrack ?: return
        if (!resend(GeelyDashboardMessages.TRACK, track)) return
        lastPlaying?.let { resend(GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(it)) }
    }

    fun clear() {
        if (!shown) return
        if (send(GeelyDashboardMessages.TRACK, GeelyDashboardMessages.track("", "", "", blankCover()))) {
            shown = false
            lastTrack = null
            lastPlaying = null
            cardShown(false)
            log("dashboard music cleared")
        }
    }
}

/**
 * Applies the card DiPlay wants on one worker, in order. Each call records what is wanted now and queues
 * a sync that applies the latest wish, so a sync queued before a newer show or stop cannot bring an older
 * card back. Owns the covers it is given until it encodes or drops them.
 */
internal class DashboardMusicQueue(
    private val execute: (Runnable) -> Unit,
    /** Runs a task on the same worker every period until the returned function is called. */
    private val repeat: (periodMillis: Long, Runnable) -> () -> Unit,
    private val encode: (Bitmap) -> ByteArray,
    private val output: () -> GeelyDashboardMusicOutput,
    private val newSession: () -> Unit,
) {
    private sealed interface Wish {
        class Track(val title: String, val artist: String, val album: String, val cover: Bitmap?) : Wish
        object Clear : Wish
    }

    private val wish = AtomicReference<Wish?>()
    private var stopRepeating: (() -> Unit)? = null

    /** A session with the option on began: the shown card is repeated until [stop]. */
    fun start() = execute(Runnable {
        newSession()
        stopRepeating?.invoke()
        stopRepeating = repeat(REASSERT_MILLIS, Runnable { output().reassert() })
    })

    fun show(title: String, artist: String, album: String, cover: Bitmap?) = want(Wish.Track(title, artist, album, cover))

    fun playing(playing: Boolean) = execute(Runnable { output().playing(playing) })

    fun stop() {
        want(Wish.Clear)
        execute(Runnable {
            stopRepeating?.invoke()
            stopRepeating = null
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
                val jpeg = next.cover?.let { bitmap -> encode(bitmap).also { bitmap.recycle() } }
                output().show(DashboardTrack(next.title, next.artist, next.album, jpeg))
            }
            Wish.Clear -> output().clear()
        }
    }

    companion object {
        const val REASSERT_MILLIS = 30_000L
    }
}

/** The dashboard as the Monjaro A3 option drives it; every call returns at once and the work runs in order. */
interface DashboardMusic {
    fun start(context: Context)

    /** The cover is copied before this returns, so [artwork] stays the caller's to recycle. */
    fun show(context: Context, title: String?, artist: String?, album: String?, artwork: Bitmap?)

    fun playing(context: Context, playing: Boolean)

    /** Sends one empty card if a card of ours is shown and stops repeating it. */
    fun stop(context: Context)

    /** Runs [done] on the dashboard worker once the work asked for before this call is done. */
    fun whenIdle(done: () -> Unit)
}

/**
 * The CarPlay track on the stock Monjaro A3 dashboard. The first message of a process clears a card that
 * a killed process left; a repeat goes only through the app process, never through su.
 */
object GeelyDashboardMusic : DashboardMusic {
    private const val TAG = "DiPlay-DashboardMusic"
    private const val COVER_SIZE = 300
    private const val COVER_QUALITY = 85
    private const val PREFS = "diplay_dashboard_music"
    private const val KEY_CARD_SHOWN = "card_shown"
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-dashboard-music").apply { isDaemon = true } }
    @Volatile private var app: Context? = null
    private var link: Link? = null
    private var blank: ByteArray? = null

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
        newSession = { link().sender.newSession() },
    )

    override fun start(context: Context) {
        app = context.applicationContext
        queue.start()
    }

    override fun show(context: Context, title: String?, artist: String?, album: String?, artwork: Bitmap?) {
        app = context.applicationContext
        val cover = artwork?.takeUnless { it.isRecycled }?.let { runCatching { fitted(it) }.getOrNull() }
        queue.show(title.orEmpty(), artist.orEmpty(), album.orEmpty(), cover)
    }

    override fun playing(context: Context, playing: Boolean) {
        app = context.applicationContext
        queue.playing(playing)
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
}
