package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Wheel speed and gear from the ECARX car SDK (`ISensor`), for the iPhone's dead reckoning. The SDK is
 * in the head unit's boot classpath, so it is reached by reflection; an ordinary app reads these
 * sensors with no extra permission (Geely Monjaro, ECARX KX11, Android 9).
 *
 * The speed is polled rather than subscribed to: a listener only fires on a change, so a standing car
 * would send nothing, and the iPhone should hear 0 m/s at a red light. Its raw value is taken as m/s:
 * the Monjaro tweak app MConfig+ shows raw x 3.72 as the cluster's km/h, and clusters read about 3 %
 * over the true speed.
 *
 * Firmware that lets only ECARX-signed apps use AdaptAPI throws a SecurityException from `Car.create`. There
 * the speed and gear come from `ecarx.car` through [EcarxCarSignal] instead.
 */
internal object EcarxWheelSpeed : VehicleSpeedSource {
    private const val TAG = "DiPlay-EcarxSpeed"
    private const val CAR = "com.ecarx.xui.adaptapi.car.Car"
    private const val ICAR = "com.ecarx.xui.adaptapi.car.ICar"
    private const val SENSOR = "com.ecarx.xui.adaptapi.car.sensor.ISensor"
    private const val SENSOR_TYPE_CAR_SPEED = 1048832
    private const val SENSOR_TYPE_GEAR = 2097664
    private const val GEAR_PARK = 2097712
    private const val GEAR_REVERSE = 2097728
    private const val GEAR_NEUTRAL = 2097680
    private const val GEAR_DRIVE = 2097696
    private val GEAR_FORWARD = 2097665..2097674 // GEAR_FIRST..GEAR_TENTH, manual mode
    private const val MAX_METERS_PER_SECOND = 90.0
    private const val READ_MILLIS = 250L
    private const val GEAR_EVERY_READS = 4
    private const val MAX_SAMPLES = 40
    private const val MAX_GEAR_AGE_MILLIS = 2_000L

    /** One poll's speed and gear, null where unread; [elapsedMillis] is on the monotonic clock since boot. */
    data class Motion(val elapsedMillis: Long, val metersPerSecond: Double?, val gear: VehicleGear?)

    enum class Source(val label: String) { ADAPT_API("adaptapi"), CAR_SIGNAL("car_signal"), NONE("none") }

    /** One run of the polling thread; [signal] is opened and closed on that thread. */
    private class Polling(val executor: ScheduledExecutorService) {
        @Volatile var signal: EcarxCarSignal? = null
        var closed = false
    }

    private var context: Context? = null
    private var sensor: Any? = null
    @Volatile private var source: Source? = null
    @Volatile private var adaptApiState = "connecting"
    @Volatile private var adaptApiDenied = false
    internal val readings = EcarxSignalReadings()
    @Volatile private var polling: Polling? = null
    private var speedSourceStarted = false
    private var followers = 0
    @Volatile private var latest: Motion? = null
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null
    private var reads = 0
    private var firstSampleLogged = false
    private var failureLogged = false

    /**
     * No position for the iPhone: the Monjaro's head unit has no GPS receiver, and an Android network
     * fix would only mislead it. With this, only `$PASCD` is sent, beside the iPhone's own GPS.
     */
    object NoPosition : Iap2LocationProvider {
        override fun start(): Boolean = false

        override fun stop() = Unit

        override fun latestNmea(): String? = null
    }

    /** Whether the ECARX car SDK is on this head unit. */
    fun present(): Boolean = runCatching { Class.forName(CAR); Class.forName(SENSOR) }.isSuccess

    private fun firstSource(): Source = if (present()) Source.ADAPT_API else Source.NONE

    /** The source after AdaptAPI failed with [error]: still AdaptAPI unless the firmware refused this app. */
    internal fun sourceAfter(error: Throwable, carSignal: Boolean): Source = when {
        !deniedBySignature(error) -> Source.ADAPT_API
        carSignal -> Source.CAR_SIGNAL
        else -> Source.NONE
    }

    /** AdaptAPI's signature check throws a SecurityException; through reflection it is the cause of the error. */
    internal fun deniedBySignature(error: Throwable): Boolean =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).any { it is SecurityException }

    fun attach(appContext: Context) = apply { context = appContext.applicationContext }

    @Synchronized
    override fun start() {
        speedSourceStarted = true
        samples.clear()
        startPolling()
    }

    override fun stop() {
        synchronized(this) { speedSourceStarted = false }
        stopWhenUnused()
    }

    /** Polls for [motion] until the returned function runs, with the same reads as the iPhone's wheel speed. */
    fun follow(appContext: Context): () -> Unit {
        synchronized(this) {
            attach(appContext)
            followers++
            startPolling()
        }
        var followed = true
        return {
            synchronized(this) {
                if (followed) followers--
                followed = false
            }
            stopWhenUnused()
        }
    }

    /** The latest poll while [follow] or [start] keeps polling. */
    fun motion(): Motion? = latest

    /**
     * Whether the latest poll read P, or null when no poll is recent or `car_signal` is not subscribed:
     * its gear only arrives on a change, so one missed while reconnecting would stay stale.
     */
    fun parked(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean? {
        val run = polling ?: return null
        if (source == Source.CAR_SIGNAL && run.signal?.subscribed() != true) return null
        return parked(latest, nowMillis)
    }

    internal fun parked(motion: Motion?, nowMillis: Long): Boolean? {
        if (motion == null || nowMillis - motion.elapsedMillis !in 0..MAX_GEAR_AGE_MILLIS) return null
        return motion.gear?.let { it == VehicleGear.PARK }
    }

    @Synchronized
    private fun startPolling() {
        if (polling != null) return
        gear = null
        reads = 0
        firstSampleLogged = false
        latest = null
        readings.clear()
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-speed").apply { isDaemon = true }
        }
        polling = Polling(executor).also { run ->
            executor.scheduleWithFixedDelay({ poll(run) }, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
        }
        Log.i(TAG, "wheel speed started")
    }

    private fun stopWhenUnused() {
        val running = synchronized(this) {
            if (speedSourceStarted || followers > 0) null else polling.also { polling = null }
        } ?: return
        running.executor.execute {
            running.closed = true
            running.signal?.close()
            running.signal = null
        }
        running.executor.shutdown()
        Log.i(TAG, "wheel speed stopped")
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    internal fun gear(event: Int): VehicleGear? = when (event) {
        GEAR_PARK -> VehicleGear.PARK
        GEAR_REVERSE -> VehicleGear.REVERSE
        GEAR_NEUTRAL -> VehicleGear.NEUTRAL
        GEAR_DRIVE, in GEAR_FORWARD -> VehicleGear.DRIVE
        else -> null
    }

    internal fun metersPerSecond(raw: Float): Double? =
        raw.toDouble().takeIf { it.isFinite() && it in 0.0..MAX_METERS_PER_SECOND }

    /** Where the speed comes from, how that source is doing, and how old its last values are. */
    override fun status(): String {
        val source = source ?: firstSource()
        val state = when (source) {
            Source.ADAPT_API -> adaptApiState
            Source.CAR_SIGNAL -> "${polling?.signal?.status() ?: "connecting"} adaptapi=denied"
            Source.NONE -> if (adaptApiDenied) "denied" else "unavailable"
        }
        return "ecarx=${source.label} $state ${readings.describe(SystemClock.elapsedRealtime())}"
    }

    override fun comparison(): String? = readings.comparison()

    private fun poll(run: Polling) {
        if (run.closed) return
        val source = source ?: firstSource().also { source = it }
        when (source) {
            Source.ADAPT_API -> pollAdaptApi()
            Source.CAR_SIGNAL -> pollCarSignal(run)
            Source.NONE -> Unit
        }
    }

    private fun pollAdaptApi() {
        val sensor = sensor() ?: return
        if (reads++ % GEAR_EVERY_READS == 0) {
            val event = runCatching { call(sensor, "getSensorEvent", SENSOR_TYPE_GEAR) as Int }.getOrNull()
            val next = event?.let(::gear)
            event?.let { readings.gear(it, next, SystemClock.elapsedRealtime()) }
            val changed = synchronized(this) { (gear != next).also { gear = next } }
            if (changed) Log.i(TAG, "gear $next (event $event)")
        }
        val before = SystemClock.elapsedRealtime()
        val raw = runCatching { call(sensor, "getSensorLatestValue", SENSOR_TYPE_CAR_SPEED) as Float }.getOrNull()
        val speed = raw?.let(::metersPerSecond)
        val now = SystemClock.elapsedRealtime()
        raw?.let { readings.speed(it, now) }
        latest = Motion(now, speed, synchronized(this) { gear })
        if (raw == null || speed == null) return
        record(VehicleSpeedSample((before + now) / 2, speed))
    }

    private fun pollCarSignal(run: Polling) {
        val signal = run.signal ?: EcarxCarSignal(context ?: return, readings).also { run.signal = it }
        val now = SystemClock.elapsedRealtime()
        signal.poll(now)
        val (gearChanged, speed) = readSignal(now)
        if (gearChanged) Log.i(TAG, "gear ${readings.gear()} (car_signal)")
        if (speed != null) record(speed)
    }

    /** Takes the latest `car_signal` values as this poll's motion; whether the gear changed, and the speed sample. */
    internal fun readSignal(nowMillis: Long): Pair<Boolean, VehicleSpeedSample?> {
        val speed = readings.metersPerSecond(nowMillis)
        val next = readings.gear()
        val changed = synchronized(this) { (gear != next).also { gear = next } }
        latest = Motion(nowMillis, speed, next)
        return changed to speed?.let { VehicleSpeedSample(nowMillis, it) }
    }

    private fun record(sample: VehicleSpeedSample) {
        if (keep(sample)) Log.i(TAG, "first wheel speed ${"%.1f".format(sample.metersPerSecond * 3.6)} km/h")
    }

    /** Adds [sample] for [drain]; whether it is the first since polling started. */
    @Synchronized
    internal fun keep(sample: VehicleSpeedSample): Boolean {
        if (samples.size == MAX_SAMPLES) samples.removeAt(0)
        samples += sample
        return !firstSampleLogged.also { firstSampleLogged = true }
    }

    /**
     * The ECARX sensor manager, once it reports the speed sensor as supported. An unsupported sensor
     * may read 0, and a false 0 would tell the iPhone the car stands while its GPS sees it moving.
     */
    private fun sensor(): Any? = sensor ?: runCatching {
        val app = context ?: return null
        val car = Class.forName(CAR).getMethod("create", Context::class.java).invoke(null, app)
        // Through the public interface: the implementing class may not be accessible to reflection.
        val manager = Class.forName(ICAR).getMethod("getSensorManager").invoke(car)!!
        val status = call(manager, "isSensorSupported", SENSOR_TYPE_CAR_SPEED)?.toString()
        check(status != null && status != "notavailable" && status != "error") { "speed sensor $status" }
        Log.i(TAG, "ECARX speed sensor $status")
        manager
    }.onSuccess {
        sensor = it
        adaptApiState = "reading"
    }.onFailure {
        adaptApiState = "error ${EcarxCar.describe(it)}"
        val next = sourceAfter(it, EcarxCar.present())
        if (next != Source.ADAPT_API) {
            adaptApiDenied = true
            source = next
            Log.w(TAG, "ECARX AdaptAPI refused this app, wheel speed from ${next.label}", it)
            return null
        }
        // The ECARX framework can come up after DiPlay at boot, so this retries; say it once.
        if (!failureLogged) Log.w(TAG, "ECARX sensors unavailable, retrying", it)
        failureLogged = true
    }.getOrNull()

    private fun call(sensor: Any, method: String, type: Int): Any? =
        Class.forName(SENSOR).getMethod(method, Int::class.javaPrimitiveType).invoke(sensor, type)
}
