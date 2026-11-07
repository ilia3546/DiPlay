package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The stock cluster's current trip (its trip B), as the firmware's QNX side keeps it in the PPS object
 * `/tmp/pps/spi_service/a001`: `data:json:{"len":54,"data":[...]}` with the distance as LE32 tenths of a km at 36,
 * the driving time as LE16 hours at 28 and a minute byte at 30, and the average as LE16 tenths of l/100 km at 32.
 * All-ones fields are the firmware's "not available".
 */
internal object QnxTripData {
    private val DATA = Regex(""""data"\s*:\s*\[([0-9,\s]*)]""")

    fun parse(text: String): DashboardTrip? {
        val bytes = DATA.find(text)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?: return null
        if (bytes.size < 40) return null
        fun u16(at: Int) = bytes[at] or (bytes[at + 1] shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val distance = u32(36).takeIf { it != 0xFFFF_FFFFL }?.let { it / 10.0 }
        val hours = u16(28).takeIf { it != 0xFFFF }
        val minutes = bytes[30].takeIf { it != 0xFF && it < 60 }
        val average = u16(32).takeIf { it != 0xFFFF }?.let { it / 10.0 }
        return DashboardTrip(
            distanceKm = distance,
            drivingMinutes = if (hours != null && minutes != null) hours * 60 + minutes else null,
            litresPer100Km = average,
        )
    }
}

/** A root login to the firmware's QNX shell over its telnet port, reduced to running one read-only command at a time. */
internal class QnxShell(private val socket: Socket) {
    class LoginRefused : IOException("QNX login refused")

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    fun login(password: String) {
        readUntil(LOGIN_TIMEOUT_MILLIS) { it.endsWith("login:") || it.endsWith("login: ") }
        send("root")
        readUntil(LOGIN_TIMEOUT_MILLIS) { it.contains("assword:") }
        send(password)
        val answer = run("echo ok", LOGIN_TIMEOUT_MILLIS) { it.contains("incorrect", ignoreCase = true) }
        if (answer == null) throw LoginRefused()
    }

    /** The command's output, or null when [refused] matched first; the marker's echo carries quotes, its output none. */
    fun run(command: String, timeoutMillis: Long, refused: (String) -> Boolean = { false }): String? {
        send("$command; echo ${MARKER_HEAD}\"\"$MARKER_TAIL")
        var result: String? = null
        readUntil(timeoutMillis) { text ->
            when {
                refused(text) -> true
                text.contains(MARKER_HEAD + MARKER_TAIL) -> {
                    result = text.substringBefore(MARKER_HEAD + MARKER_TAIL)
                    true
                }
                else -> false
            }
        }
        return result
    }

    fun close() {
        runCatching { send("exit") }
        runCatching { socket.close() }
    }

    private fun send(line: String) {
        output.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Reads, answering the server's option offers with a refusal, until [done] holds for the text so far. */
    private fun readUntil(timeoutMillis: Long, done: (String) -> Boolean) {
        val text = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (true) {
            if (SystemClock.elapsedRealtime() > deadline) throw SocketTimeoutException("QNX shell did not answer")
            val byte = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (byte < 0) throw IOException("QNX shell closed the connection")
            if (byte == IAC) {
                answerOption() ?: continue
                text.write(IAC)
            } else if (byte != 0) {
                text.write(byte)
            }
            if (done(text.toString(Charsets.ISO_8859_1.name()))) return
        }
    }

    /** Answers an IAC sequence; returns [IAC] for an escaped data byte, else null. */
    private fun answerOption(): Int? {
        when (val command = input.read()) {
            IAC -> return IAC
            DO, DONT -> refuse(WONT, input.read())
            WILL, WONT -> refuse(DONT, input.read())
            SB -> while (true) {
                val next = input.read()
                if (next < 0) throw IOException("QNX shell closed the connection")
                if (next == IAC && input.read() == SE) break
            }
            else -> if (command < 0) throw IOException("QNX shell closed the connection")
        }
        return null
    }

    private fun refuse(answer: Int, option: Int) {
        if (option < 0) throw IOException("QNX shell closed the connection")
        output.write(byteArrayOf(IAC.toByte(), answer.toByte(), option.toByte()))
        output.flush()
    }

    companion object {
        const val HOST = "192.168.8.1"
        const val PORT = 3333
        private const val LOGIN_TIMEOUT_MILLIS = 6_000L
        private const val MARKER_HEAD = "__DIPLAY_"
        private const val MARKER_TAIL = "DONE__"
        private const val IAC = 255
        private const val DONT = 254
        private const val DO = 253
        private const val WONT = 252
        private const val WILL = 251
        private const val SB = 250
        private const val SE = 240

        fun connect(timeoutMillis: Int): QnxShell {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(HOST, PORT), timeoutMillis)
                socket.soTimeout = 1_000
                socket.tcpNoDelay = true
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw error
            }
            return QnxShell(socket)
        }
    }
}

/** The QNX root password the user entered for trip data; kept out of the settings backup and never logged. */
internal object QnxTripCredentials {
    private const val PREFS = "diplay_qnx"
    private const val KEY_PASSWORD = "root_password"

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun save(context: Context, password: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (password.isNullOrEmpty()) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, password)
        }.apply()
    }
}

/**
 * Reads [QnxTripData] every few seconds over one [QnxShell] login while the dashboard shows, and hands each
 * reading, or null once it is lost, to the listener on the main thread. A refused password stops it until the
 * password changes, so a wrong one is not tried over and over.
 */
internal class QnxTripReader(context: Context, private val listener: (DashboardTrip?) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var shell: QnxShell? = null
    private var password: String? = null

    /** Starts, or restarts after a password change; main thread. */
    fun start() {
        val next = QnxTripCredentials.load(app)
        if (worker != null && next == password) return
        stop()
        password = next
        if (next == null) {
            Log.i(TAG, "trip data from QNX off: no password")
            return
        }
        worker = Thread({ loop(next) }, "DiPlay-QnxTrip").apply {
            isDaemon = true
            start()
        }
    }

    /** Main thread. */
    fun stop() {
        val running = worker ?: return
        worker = null
        running.interrupt()
        shell?.let { open -> Thread { open.close() }.start() }
        main.post { listener(null) }
    }

    private fun loop(password: String) {
        var failures = 0
        var last: DashboardTrip? = null
        while (worker === Thread.currentThread()) {
            try {
                val open = QnxShell.connect(CONNECT_TIMEOUT_MILLIS)
                shell = open
                open.login(password)
                Log.i(TAG, "trip data from QNX: logged in")
                failures = 0
                while (worker === Thread.currentThread()) {
                    val text = open.run("cat $PPS_OBJECT", READ_TIMEOUT_MILLIS)
                    val trip = text?.let(QnxTripData::parse)
                    if (trip != last) {
                        Log.i(TAG, "trip data from QNX: $trip")
                        last = trip
                        main.post { if (worker != null) listener(trip) }
                    }
                    Thread.sleep(POLL_MILLIS)
                }
            } catch (_: QnxShell.LoginRefused) {
                Log.w(TAG, "trip data from QNX: the password was refused; waiting for a new one")
                main.post { if (worker != null) listener(null) }
                return
            } catch (_: InterruptedException) {
                return
            } catch (error: IOException) {
                failures++
                Log.w(TAG, "trip data from QNX lost: ${error.javaClass.simpleName}: ${error.message}")
                if (last != null) {
                    last = null
                    main.post { if (worker != null) listener(null) }
                }
            } finally {
                shell?.let { open -> runCatching { open.close() } }
                shell = null
            }
            try {
                Thread.sleep(RETRY_MILLIS[(failures - 1).coerceIn(RETRY_MILLIS.indices)])
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private companion object {
        const val TAG = "DiPlay-QnxTrip"
        const val PPS_OBJECT = "/tmp/pps/spi_service/a001"
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 4_000L
        const val POLL_MILLIS = 5_000L
        val RETRY_MILLIS = longArrayOf(10_000L, 30_000L, 60_000L)
    }
}
