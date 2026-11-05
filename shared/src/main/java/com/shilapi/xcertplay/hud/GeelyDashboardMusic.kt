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
 * when it changed; [clear] sends one empty card, and only after something was shown. Not thread-safe:
 * [GeelyDashboardMusic] calls it from one worker.
 */
internal class GeelyDashboardMusicOutput(
    private val send: (subtype: Int, json: String) -> Boolean,
    /** The cover for a track without one and for the empty card; the stock night cover is Desay's asset. */
    private val blankCover: () -> ByteArray,
    private val log: (String) -> Unit = {},
) {
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

    fun clear() {
        if (lastTrack == null) return
        if (send(GeelyDashboardMessages.TRACK, GeelyDashboardMessages.track("", "", "", blankCover()))) {
            lastTrack = null
            lastPlaying = null
            log("dashboard music cleared")
        }
    }
}

/** The CarPlay track on the stock Monjaro A3 dashboard; every call returns at once and the work runs in order. */
object GeelyDashboardMusic {
    private const val TAG = "DiPlay-DashboardMusic"
    private const val COVER_SIZE = 300
    private const val COVER_QUALITY = 85
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-dashboard-music").apply { isDaemon = true } }
    private val pendingTrack = AtomicReference<PendingTrack?>()
    private var output: GeelyDashboardMusicOutput? = null
    private var blank: ByteArray? = null

    private class PendingTrack(val title: String, val artist: String, val album: String, val cover: Bitmap?)

    /** The cover is copied before this returns, so [artwork] stays the caller's to recycle. */
    fun show(context: Context, title: String?, artist: String?, album: String?, artwork: Bitmap?) {
        val cover = artwork?.takeUnless { it.isRecycled }?.let { runCatching { fitted(it) }.getOrNull() }
        pendingTrack.getAndSet(PendingTrack(title.orEmpty(), artist.orEmpty(), album.orEmpty(), cover))?.cover?.recycle()
        val app = context.applicationContext
        worker.execute {
            val track = pendingTrack.getAndSet(null) ?: return@execute
            val jpeg = track.cover?.let { bitmap ->
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it) }
                    .toByteArray().also { bitmap.recycle() }
            }
            output(app).show(DashboardTrack(track.title, track.artist, track.album, jpeg))
        }
    }

    fun playing(context: Context, playing: Boolean) {
        val app = context.applicationContext
        worker.execute { output(app).playing(playing) }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        pendingTrack.getAndSet(null)?.cover?.recycle()
        worker.execute { output(app).clear() }
    }

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

    private fun output(app: Context): GeelyDashboardMusicOutput = output ?: run {
        val apk = app.applicationInfo.sourceDir.orEmpty()
        val sender = GeelyCabinLan.Sender(
            direct = GeelyCabinLan::sendDirect,
            root = { msgType, subtype, json ->
                GeelyCabinLan.rootSent(RootShell.runWithInput(GeelyCabinLan.rootCommand(apk, msgType, subtype),
                    json.toByteArray(Charsets.UTF_8)))
            },
            log = { Log.i(TAG, it) },
        )
        GeelyDashboardMusicOutput(
            send = { subtype, json -> sender.send(GeelyCabinLan.MUSIC, subtype, json) },
            blankCover = ::blankCover,
            log = { Log.i(TAG, it) },
        ).also { output = it }
    }
}
