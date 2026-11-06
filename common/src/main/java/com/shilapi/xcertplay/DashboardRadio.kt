package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The station of the firmware's radio service, read from its `com.ts.radio.model.RadioLocalInfo`. */
internal data class RadioStation(
    val frequencyId: Int = -1,
    val frequencyName: String = "",
    val psName: String = "",
    val stationLogoPath: String = "",
    val radioText: String = "",
    val titleText: String = "",
    val artistText: String = "",
    val playing: Boolean = false,
) {
    /** The station name, or else its frequency with the band, as the firmware's cluster shows it. */
    val title: String
        get() = psName.trim().ifEmpty { frequency() }

    val subtitle: String?
        get() = radioText.trim().ifEmpty {
            listOf(titleText, artistText).map(String::trim).filter(String::isNotEmpty).joinToString(" · ")
        }.ifEmpty { null }

    private fun frequency(): String {
        val name = frequencyName.trim()
        return when (frequencyId) {
            in FM_RANGE -> "FM ${name.ifEmpty { "%.1f".format(Locale.ROOT, frequencyId / 100.0) }} MHz"
            in AM_RANGE -> "AM ${name.ifEmpty { frequencyId.toString() }} kHz"
            in DAB_RANGE -> "DAB ${name.ifEmpty { "%.3f".format(Locale.ROOT, frequencyId / 1000.0) }} MHz"
            else -> name
        }
    }

    companion object {
        private val FM_RANGE = 8750..10800
        private val AM_RANGE = 522..1629
        private val DAB_RANGE = 174928..239200

        /**
         * Reads a nullable `RadioLocalInfo` as AIDL writes it: a presence int, then the fields in the order of its
         * `writeToParcel`. Null when absent, cut short or unreadable.
         */
        fun read(parcel: Parcel): RadioStation? = runCatching {
            val fields = Fields(parcel)
            if (fields.int() == 0) return@runCatching null
            fields.string() // slideShowPath
            fields.int() // ptyCode
            fields.string() // ptyName
            val frequencyId = fields.int()
            repeat(4) { fields.int() } // ensembleId, serviceId, componentId, status
            fields.string() // serviceName
            val frequencyName = fields.string()
            repeat(2) { fields.string() } // componentName, ensembleName
            val psName = fields.string()
            val stationLogoPath = fields.string()
            val radioText = fields.string()
            val titleText = fields.string()
            fields.string() // albumText
            val artistText = fields.string()
            fields.int() // signalStrength
            fields.string() // version
            repeat(4) { fields.int() } // taType, isLink, isFavorite, isOnSignal
            val playing = fields.int() == 1
            repeat(2) { fields.int() } // isEon, isTrafficProgram
            RadioStation(frequencyId, frequencyName, psName, stationLogoPath, radioText, titleText, artistText, playing)
        }.getOrNull()
    }

    private class Fields(private val parcel: Parcel) {
        fun int(): Int {
            check(parcel.dataAvail() >= 4)
            return parcel.readInt()
        }

        fun string(): String {
            check(parcel.dataAvail() >= 4)
            return parcel.readString().orEmpty()
        }
    }
}

/**
 * DiPlay's `com.ts.radio.service.IRadioAppListener`. Every call of that interface is one-way; this binder takes any
 * code without throwing, and passes the station and the play state on as they come, on the binder thread.
 */
internal class RadioListener(
    private val station: (RadioStation?) -> Unit,
    private val playing: (Boolean) -> Unit,
) : Binder() {
    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        if (code !in FIRST_CALL..LAST_CALL) return runCatching { super.onTransact(code, data, reply, flags) }.getOrDefault(false)
        runCatching {
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                ON_UPDATE_RADIO_INFO_CHANGED -> station(RadioStation.read(data))
                ON_PLAY -> playing(true)
                ON_PAUSE -> playing(false)
            }
        }.onFailure { Log.w(TAG, "radio listener call $code not read", it) }
        reply?.writeNoException()
        return true
    }

    companion object {
        const val DESCRIPTOR = "com.ts.radio.service.IRadioAppListener"
        private const val TAG = "DiPlay-CustomDashboard"
        private const val FIRST_CALL = 1
        private const val ON_UPDATE_RADIO_INFO_CHANGED = 3
        private const val ON_PLAY = 4
        private const val ON_PAUSE = 5
        private const val LAST_CALL = 13
    }
}

/**
 * Counts the listener's calls of one kind, so that an answer to a request sent before the latest call does not
 * replace what that call brought. Any thread.
 */
internal class RadioEvents {
    private val heard = AtomicLong()

    fun heard() {
        heard.incrementAndGet()
    }

    /** Before a request: the returned check is true while no call came since. */
    fun mark(): () -> Boolean {
        val seen = heard.get()
        return { heard.get() == seen }
    }
}

/**
 * The firmware radio's station and play state, through its exported `com.ts.radio.service.IRadioAppManager`.
 * [connect] binds without starting the service, so that DiPlay never brings up another app's radio. Main thread;
 * [changed] runs on it after each update.
 */
internal class DashboardRadio(context: Context, private val changed: () -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var service: IBinder? = null
    private var listener: RadioListener? = null
    private var generation = 0L
    private var logoPath = ""

    var station: RadioStation? = null
        private set
    var logo: Bitmap? = null
        private set

    /** The service's own play state, or null until it answered. */
    var servicePlaying: Boolean? = null
        private set
    private var wanted = false

    fun connect() {
        if (wanted) return
        wanted = true
        val current = ++generation
        val component = runCatching { app.packageManager.queryIntentServices(Intent(ACTION), 0) }
            .getOrDefault(emptyList())
            .mapNotNull { it.serviceInfo }
            .firstOrNull { it.packageName == PACKAGE }
            ?.let { ComponentName(it.packageName, it.name) }
        if (component == null) {
            Log.i(TAG, "radio service not found")
            return
        }
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) = connected(current, binder)
            override fun onServiceDisconnected(name: ComponentName) = lost(current)
        }
        val bound = runCatching { app.bindService(Intent(ACTION).setComponent(component), next, 0) }
            .onFailure { Log.w(TAG, "radio service bind refused", it) }
            .getOrDefault(false)
        Log.i(TAG, "radio service bind=$bound")
        if (bound) connection = next else runCatching { app.unbindService(next) }
    }

    fun disconnect() {
        if (!wanted) return
        wanted = false
        generation++
        val binder = service
        val registered = listener
        if (binder != null && registered != null) worker.execute { call(binder, UNREGISTER_LISTENER, { it.writeStrongBinder(registered) }) {} }
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        service = null
        listener = null
        station = null
        servicePlaying = null
        logoPath = ""
        logo = null
    }

    private fun connected(current: Long, binder: IBinder) {
        if (current != generation) return
        service = binder
        val stations = RadioEvents()
        val plays = RadioEvents()
        val next = RadioListener(
            station = { station ->
                stations.heard()
                main.post { if (current == generation) showStation(station) }
            },
            playing = { playing ->
                plays.heard()
                main.post { if (current == generation) showPlaying(playing) }
            },
        )
        listener = next
        worker.execute {
            val registered = call(binder, REGISTER_LISTENER, { it.writeStrongBinder(next) }) {} != null
            val stationFresh = stations.mark()
            val station = call(binder, GET_CURRENT_RADIO_INFO) { RadioStation.read(it) }
            val playingFresh = plays.mark()
            val playing = call(binder, IS_PLAY) { it.readInt() != 0 }
            Log.i(TAG, "radio service registered=$registered playing=$playing station=${station != null}")
            main.post {
                if (current != generation) return@post
                if (stationFresh()) showStation(station)
                if (playingFresh()) showPlaying(playing)
            }
        }
    }

    private fun lost(current: Long) {
        if (current != generation) return
        Log.i(TAG, "radio service disconnected")
        service = null
        listener = null
        station = null
        servicePlaying = null
        changed()
    }

    private fun showStation(next: RadioStation?) {
        station = next
        val path = next?.stationLogoPath.orEmpty()
        if (path != logoPath) {
            logoPath = path
            logo = null
            if (path.isNotEmpty()) worker.execute {
                val decoded = decodeLogo(path)
                main.post {
                    if (logoPath != path || !wanted) return@post
                    logo = decoded
                    changed()
                }
            }
        }
        changed()
    }

    private fun showPlaying(playing: Boolean?) {
        servicePlaying = playing
        changed()
    }

    companion object {
        const val ACTION = "com.ts.radio.intent.action.SERVICE"
        private const val PACKAGE = "com.tencent.wecarflow"
        private const val DESCRIPTOR = "com.ts.radio.service.IRadioAppManager"
        private const val REGISTER_LISTENER = 1
        private const val UNREGISTER_LISTENER = 2
        private const val IS_PLAY = 13
        private const val GET_CURRENT_RADIO_INFO = 14
        private const val LOGO_SIZE = 200
        private const val TAG = "DiPlay-CustomDashboard"
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-dashboard-radio").apply { isDaemon = true }
        }

        /** One synchronous call of the radio service; null when it failed or the service threw. */
        private fun <T> call(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                write(data)
                if (!binder.transact(code, data, reply, 0)) return null
                reply.readException()
                read(reply)
            } catch (e: Exception) {
                Log.w(TAG, "radio service call $code failed", e)
                null
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        internal fun decodeLogo(path: String): Bitmap? = runCatching {
            val file = File(path).takeIf { it.canRead() } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= LOGO_SIZE && bounds.outHeight / (sample * 2) >= LOGO_SIZE) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
