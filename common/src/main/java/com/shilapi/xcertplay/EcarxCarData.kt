package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The outside temperature in degrees Celsius, or null while the signal is missing, timed out or implausible. */
internal fun outsideTemperature(value: Any?, timeout: Any?): Float? {
    val celsius = (value as? Number)?.toFloat() ?: return null
    val timedOut = when (timeout) {
        is Number -> timeout.toInt() != 0
        is Boolean -> timeout
        else -> true
    }
    if (timedOut || !celsius.isFinite() || celsius !in -60f..80f) return null
    return celsius
}

/**
 * The public-attribute getters of `ecarx.car` that may hold trip data, by manager and method name. Only a
 * parameterless `getPA_` read qualifies, so no setter, reset or clear is ever called.
 */
internal object EcarxTripFields {
    private val WORDS = listOf("Travel", "Trip", "DstTrvld", "FuCns")

    fun candidate(manager: String, method: String, parameters: Int): Boolean =
        parameters == 0 && method.startsWith("getPA_") && WORDS.any { it in manager || it in method }
}

/** The values of named fields that differ from the ones seen last time. */
internal class ChangedValues {
    private val last = HashMap<String, String>()

    fun changes(values: Map<String, String>): Map<String, String> = values.filter { (name, value) -> last.put(name, value) != value }
}

/**
 * The head unit's `ecarx.car` service, in the boot classpath of ECARX firmware and reached by reflection: the
 * outside temperature for the custom dashboard, and trip fields logged raw until a drive shows which ones are
 * right. One connection per process, open while the dashboard is shown; both are read every [POLL_MILLIS].
 */
internal object EcarxCarData {
    private const val TAG = "DiPlay-EcarxCar"
    const val POLL_MILLIS = 30_000L
    private val main = Handler(Looper.getMainLooper())
    private var connection: Connection? = null
    private val tripValues = ChangedValues()
    private var tripFieldsLogged = false

    /** Main thread; [temperature] runs on the main thread after each read. */
    fun start(context: Context, temperature: (Float?) -> Unit) {
        stop()
        connection = Connection(context.applicationContext, temperature).also(Connection::open)
    }

    /** Main thread. */
    fun stop() {
        connection?.close()
        connection = null
    }

    private class TripField(val name: String, val manager: Any, val getter: Method)

    private class Connection(private val app: Context, private val temperature: (Float?) -> Unit) {
        private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-ecarx-car").apply { isDaemon = true } }
        @Volatile private var closed = false
        @Volatile private var car: Any? = null
        private var polling: ScheduledFuture<*>? = null
        private var tripFields: List<TripField>? = null
        private val failing = HashSet<String>()

        fun open() {
            val listener = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (closed) return
                    Log.i(TAG, "ecarx.car connected")
                    polling?.cancel(false)
                    polling = worker.scheduleWithFixedDelay(::poll, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.i(TAG, "ecarx.car disconnected")
                    polling?.cancel(false)
                    report(null)
                }
            }
            try {
                val created = EcarxCar.create(app, listener)
                car = created
                EcarxCar.connect(created)
            } catch (error: Throwable) {
                Log.i(TAG, "ecarx.car unavailable (${EcarxCar.describe(error)})")
                report(null)
            }
        }

        fun close() {
            closed = true
            polling?.cancel(false)
            worker.shutdownNow()
            val open = car ?: return
            car = null
            EcarxCar.disconnect(open)
        }

        private fun poll() {
            if (closed) return
            val car = car ?: return
            report(read("outside temperature") {
                val signal = EcarxCar.manager(car, "car_signal")
                outsideTemperature(signal.javaClass.getMethod("getAmbTRawAmbTVal").invoke(signal),
                    signal.javaClass.getMethod("getAmbTRawTimeout").invoke(signal))
            })
            read("trip fields") { logTrip(car) }
        }

        private fun logTrip(car: Any) {
            val fields = tripFields ?: findTripFields(EcarxCar.manager(car, "car_publicattribute")).also { tripFields = it }
            val values = fields.associate { field ->
                field.name to try {
                    val value = field.getter.invoke(field.manager)
                    "${raw(value?.let { it.javaClass.getMethod("getData").invoke(it) })} " +
                        "status=${raw(value?.let { it.javaClass.getMethod("getStatus").invoke(it) })}"
                } catch (error: Throwable) {
                    "error ${EcarxCar.describe(error)}"
                }
            }
            val changes = synchronized(EcarxCarData) {
                if (!tripFieldsLogged) {
                    tripFieldsLogged = true
                    Log.i(TAG, "trip candidates (${fields.size}): ${fields.joinToString { it.name }}")
                }
                tripValues.changes(values)
            }
            if (changes.isNotEmpty()) Log.i(TAG, "trip ${changes.entries.joinToString { "${it.key}=${it.value}" }}")
        }

        private fun findTripFields(attributes: Any): List<TripField> =
            attributes.javaClass.methods
                .filter { it.parameterTypes.size == 0 && it.name.startsWith("getECarXCar") && it.name.endsWith("Manager") }
                .sortedBy(Method::getName)
                .flatMap { getter ->
                    val managerName = getter.name.removePrefix("getECarXCar")
                    val manager = runCatching { getter.invoke(attributes) }.getOrNull() ?: return@flatMap emptyList()
                    manager.javaClass.methods
                        .filter { EcarxTripFields.candidate(managerName, it.name, it.parameterTypes.size) }
                        .sortedBy(Method::getName)
                        .map { TripField("$managerName.${it.name.removePrefix("getPA_")}", manager, it) }
                }

        private fun <T> read(what: String, block: () -> T?): T? = try {
            block().also { failing.remove(what) }
        } catch (error: Throwable) {
            if (failing.add(what)) Log.w(TAG, "$what not read (${EcarxCar.describe(error)})")
            null
        }

        private fun report(celsius: Float?) {
            main.post { if (!closed) temperature(celsius) }
        }
    }

    private fun raw(value: Any?): String = when (value) {
        is IntArray -> value.contentToString()
        is FloatArray -> value.contentToString()
        is LongArray -> value.contentToString()
        is Array<*> -> value.contentToString()
        else -> value.toString()
    }
}
