package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
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

/**
 * The firmware's two-byte `/dev/shmem/etm_flag`: the engineering mode (2 while it shows Android's cluster display)
 * and the flag that shows the stock bottom line (RPM, odometer, fuel, coolant, ECO, gear), which some other
 * cluster apps clear. [ENSURE_COMMAND] sets that flag back to 1 only in the engineering mode with it at 0, checked
 * and written in one command on the QNX side, then prints the pair.
 */
internal object QnxEtmFlag {
    private const val FILE = "/dev/shmem/etm_flag"
    private val PAIR = Regex("""(?m)^0000000 ([0-9a-fA-F]{2}) ([0-9a-fA-F]{2})""")

    const val READ_COMMAND = "hd $FILE"
    const val ENSURE_COMMAND = "[ -f $FILE ] && [ \"\$(wc -c < $FILE)\" -eq 2 ] && " +
        "case \"\$(hd $FILE)\" in *\" 02 00 \"*) awk 'BEGIN { printf \"%c\", 1 }' | " +
        "dd of=$FILE bs=1 seek=1 count=1 conv=notrunc 2>/dev/null && echo ETM_\"\"WROTE ;; esac; hd $FILE"
    private val WROTE = Regex("""(?m)^ETM_WROTE""")

    /** Whether the command wrote; its echo carries the marker with quotes, so only the shell's output counts. */
    fun wrote(text: String) = WROTE.containsMatchIn(text)

    /** The pair the shell printed last, so the echoed command never counts. */
    fun parse(text: String): Pair<Int, Int>? =
        PAIR.findAll(text).lastOrNull()?.let { it.groupValues[1].toInt(16) to it.groupValues[2].toInt(16) }
}

/**
 * A root login to the firmware's QNX shell over its telnet port, reduced to reading a file at a time: it sends
 * the user name, the password and the read commands, nothing else. Not thread-safe; one worker owns it.
 */
internal class QnxShell(private val socket: Socket) {
    /** [answer] is the tail of what QNX said, which holds no password: QNX does not echo it. */
    class LoginRefused(val answer: String) : IOException("QNX login refused")

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private var loggedIn = false

    fun login(password: String, stillWanted: () -> Boolean) {
        readUntil(LOGIN_TIMEOUT_MILLIS) { it.trimEnd().endsWith("login:") }
        if (!stillWanted()) throw IOException("QNX login no longer wanted")
        send("root")
        // The firmware asks "Password for root@localhost:".
        readUntil(LOGIN_TIMEOUT_MILLIS) { it.contains("password", ignoreCase = true) && it.trimEnd().endsWith(":") }
        if (!stillWanted()) throw IOException("QNX login no longer wanted")
        send(password)
        // Once the password is out, anything but the shell's prompt counts against it, silence included.
        val answer = try {
            readUntil(LOGIN_TIMEOUT_MILLIS) {
                it.endsWith(PROMPT) || it.contains("incorrect", ignoreCase = true) || it.trimEnd().endsWith("login:")
            }
        } catch (_: SocketTimeoutException) {
            throw LoginRefused("no answer")
        }
        if (!answer.endsWith(PROMPT)) throw LoginRefused(answer.takeLast(120))
        loggedIn = true
    }

    /** What [command] printed, read up to the shell's next prompt. */
    fun read(command: String, timeoutMillis: Long): String {
        send(command)
        return readUntil(timeoutMillis) { it.contains('\n') && it.endsWith(PROMPT) }
    }

    fun close() {
        if (loggedIn) runCatching { send("exit") }
        runCatching { socket.close() }
    }

    private fun send(line: String) {
        // Enter as a telnet client sends it in character mode; CR LF reads as two lines there.
        output.write("$line\r\u0000".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Reads, refusing the server's option offers, until [done] holds for the text so far; returns that text. */
    private fun readUntil(timeoutMillis: Long, done: (String) -> Boolean): String {
        val text = ByteArrayOutputStream()
        val deadline = System.nanoTime() / 1_000_000 + timeoutMillis
        while (true) {
            when (val byte = next(deadline)) {
                IAC -> when (next(deadline)) {
                    IAC -> text.write(IAC)
                    DO, DONT -> refuse(WONT, next(deadline))
                    WILL, WONT -> refuse(DONT, next(deadline))
                    SB -> while (!(next(deadline) == IAC && next(deadline) == SE)) Unit
                    else -> Unit
                }
                0 -> Unit
                else -> text.write(byte)
            }
            val sofar = text.toString(Charsets.ISO_8859_1.name())
            if (done(sofar)) return sofar
        }
    }

    /** The next byte before [deadline]; the socket's short timeout only lets the deadline and interrupts be seen. */
    private fun next(deadline: Long): Int {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("QNX read interrupted")
            if (System.nanoTime() / 1_000_000 > deadline) throw SocketTimeoutException("QNX shell did not answer")
            val byte = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (byte < 0) throw IOException("QNX shell closed the connection")
            return byte
        }
    }

    private fun refuse(answer: Int, option: Int) {
        output.write(byteArrayOf(IAC.toByte(), answer.toByte(), option.toByte()))
        output.flush()
    }

    companion object {
        const val HOST = "192.168.8.1"
        const val PORT = 3333
        private const val PROMPT = "# "
        private const val LOGIN_TIMEOUT_MILLIS = 8_000L
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
                socket.soTimeout = 500
                socket.tcpNoDelay = true
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw error
            }
            return QnxShell(socket)
        }
    }
}

/**
 * The QNX root password the user entered for trip data, kept out of the settings backup and never logged, and how
 * many times in a row QNX refused it: after [MAX_REFUSALS] the reader waits for a new password, across windows.
 */
internal object QnxTripCredentials {
    private const val PREFS = "diplay_qnx"
    private const val KEY_PASSWORD = "root_password"
    private const val KEY_REFUSALS = "refusals"
    const val MAX_REFUSALS = 3

    fun load(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun save(context: Context, password: String?) {
        prefs(context).edit().apply {
            if (password.isNullOrEmpty()) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, password)
            remove(KEY_REFUSALS)
        }.apply()
    }

    fun refusals(context: Context): Int = prefs(context).getInt(KEY_REFUSALS, 0)

    fun refused(context: Context) {
        prefs(context).edit().putInt(KEY_REFUSALS, refusals(context) + 1).commit()
    }

    fun accepted(context: Context) {
        if (refusals(context) != 0) prefs(context).edit().remove(KEY_REFUSALS).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Reads [QnxTripData] every few seconds over one [QnxShell] login while the dashboard shows, and hands each
 * reading, or null once it is lost, to the listener on the main thread. Each start is a run of its own: a stopped
 * run's connection, results and retries never reach a newer one.
 */
internal class QnxTripReader(context: Context, private val listener: (DashboardTrip?) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private inner class Run(val password: String) : Runnable {
        @Volatile var shell: QnxShell? = null
        // Across this run's logins, so a reconnect does not reset the limits.
        var etmWrites = 0
        var etmWrittenAt = Long.MIN_VALUE / 2
        val thread = Thread(this, "DiPlay-QnxTrip").apply { isDaemon = true }
        val current get() = active === this

        fun deliver(trip: DashboardTrip?) = main.post { if (current) listener(trip) }

        override fun run() {
            var failures = 0
            var last: DashboardTrip? = null
            while (current) {
                if (QnxTripCredentials.refusals(app) >= QnxTripCredentials.MAX_REFUSALS) {
                    Log.w(TAG, "trip data from QNX: the password was refused; waiting for a new one")
                    deliver(null)
                    return
                }
                try {
                    val open = QnxShell.connect(CONNECT_TIMEOUT_MILLIS)
                    shell = open
                    try {
                        open.login(password) { current }
                        QnxTripCredentials.accepted(app)
                        Log.i(TAG, "trip data from QNX: logged in")
                        var etm: Pair<Int, Int>? = null
                        while (current) {
                            if (MonjaroA3Options.load(app).dashboardBottomLine) {
                                val now = System.nanoTime() / 1_000_000
                                val mayWrite = etmWrites < MAX_ETM_WRITES && now - etmWrittenAt >= ETM_WRITE_GAP_MILLIS
                                val answer = open.read(
                                    if (mayWrite) QnxEtmFlag.ENSURE_COMMAND else QnxEtmFlag.READ_COMMAND,
                                    READ_TIMEOUT_MILLIS)
                                val pair = QnxEtmFlag.parse(answer)
                                if (mayWrite && QnxEtmFlag.wrote(answer)) {
                                    etmWrites++
                                    etmWrittenAt = now
                                    Log.i(TAG, "cluster flags: the stock bottom line was hidden, set it again " +
                                        "($etmWrites of $MAX_ETM_WRITES), now ${format(pair)}")
                                } else if (pair != etm) {
                                    Log.i(TAG, "cluster flags ${format(etm)} -> ${format(pair)}" +
                                        if (pair == ETM_HIDDEN && etmWrites >= MAX_ETM_WRITES) "; no more writes" else "")
                                }
                                etm = pair
                            }
                            val trip = QnxTripData.parse(open.read("cat $PPS_OBJECT", READ_TIMEOUT_MILLIS))
                            failures = 0
                            if (trip != last) {
                                Log.i(TAG, "trip data from QNX: $trip")
                                last = trip
                                deliver(trip)
                            }
                            Thread.sleep(POLL_MILLIS)
                        }
                    } finally {
                        shell = null
                        open.close()
                    }
                } catch (refused: QnxShell.LoginRefused) {
                    QnxTripCredentials.refused(app)
                    failures++
                    Log.w(TAG, "trip data from QNX: login refused, QNX said: " + printable(refused.answer))
                } catch (_: InterruptedException) {
                    return
                } catch (error: IOException) {
                    if (!current) return
                    failures++
                    Log.w(TAG, "trip data from QNX lost: ${error.javaClass.simpleName}: ${error.message}")
                }
                if (last != null) {
                    last = null
                    deliver(null)
                }
                try {
                    Thread.sleep(RETRY_MILLIS[(failures - 1).coerceIn(RETRY_MILLIS.indices)])
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    @Volatile private var active: Run? = null
    private var off = false

    /** Starts, or restarts after a password change; main thread. */
    fun start() {
        val password = QnxTripCredentials.load(app)
        if (active != null && active?.password == password) return
        stop()
        if (password == null) {
            if (!off) Log.i(TAG, "trip data from QNX off: no password")
            off = true
            return
        }
        off = false
        Run(password).also {
            active = it
            it.thread.start()
        }
    }

    /** Main thread. */
    fun stop() {
        val stopped = active ?: return
        active = null
        stopped.thread.interrupt()
        stopped.shell?.let { open -> Thread { open.close() }.start() }
        listener(null)
    }

    private companion object {
        const val TAG = "DiPlay-QnxTrip"
        const val PPS_OBJECT = "/tmp/pps/spi_service/a001"
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 4_000L
        const val POLL_MILLIS = 5_000L
        val RETRY_MILLIS = longArrayOf(10_000L, 30_000L, 60_000L)

        const val MAX_ETM_WRITES = 5
        const val ETM_WRITE_GAP_MILLIS = 30_000L
        val ETM_HIDDEN = 2 to 0

        fun printable(text: String) = text.map { if (it in ' '..'~') it else '.' }.joinToString("")

        fun format(pair: Pair<Int, Int>?) = pair?.let { "%02x %02x".format(it.first, it.second) } ?: "unknown"
    }
}
