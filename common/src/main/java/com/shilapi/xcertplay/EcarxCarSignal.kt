package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * The latest speed and gear values with the time the speed came, from either ECARX source, and the
 * summary [EcarxWheelSpeed.status] gives of them.
 */
internal class EcarxSignalReadings {
    private var speed: Float? = null
    private var speedMillis = 0L
    private var gearCode: Int? = null
    private var gear: VehicleGear? = null
    private var events = 0

    @Synchronized
    fun speed(raw: Float, elapsedMillis: Long) {
        speed = raw
        speedMillis = elapsedMillis
        events++
    }

    @Synchronized
    fun gear(code: Int, gear: VehicleGear?) {
        gearCode = code
        this.gear = gear
        events++
    }

    /** The speed in m/s, or null when none came in the last [FRESH_MILLIS] or it is implausible. */
    @Synchronized
    fun metersPerSecond(nowMillis: Long): Double? {
        val raw = speed ?: return null
        if (nowMillis - speedMillis > FRESH_MILLIS) return null
        return EcarxWheelSpeed.metersPerSecond(raw)
    }

    @Synchronized
    fun gear(): VehicleGear? = gear

    /** The speed's age and value, the gear and the events since the previous call. */
    @Synchronized
    fun describe(nowMillis: Long): String {
        val age = speed?.let { "${nowMillis - speedMillis} speed=$it" } ?: "none"
        val gearText = gear?.letter?.toString() ?: gearCode?.let { "raw $it" } ?: "none"
        return "speedAgeMs=$age gear=$gearText events=$events".also { events = 0 }
    }

    @Synchronized
    fun clear() {
        speed = null
        gearCode = null
        gear = null
        events = 0
    }

    companion object {
        /** `car_signal` sends the speed about twice a second while the car stands, and only faster on the move. */
        const val FRESH_MILLIS = 1_500L

        /** `GearLvrIndcn`, the selector lever position. */
        fun leverGear(code: Int): VehicleGear? = when (code) {
            0 -> VehicleGear.PARK
            1 -> VehicleGear.REVERSE
            2 -> VehicleGear.NEUTRAL
            3 -> VehicleGear.DRIVE
            else -> null
        }
    }
}

/**
 * Speed and gear events from the `car_signal` manager of `ecarx.car`, reached by reflection, into [readings].
 * Firmware that refuses AdaptAPI to apps without an ECARX signature still serves `car_signal` to an ordinary
 * app. `VehSpdLgtA` is in m/s and has no sign, so reverse comes from the gear; it arrives about twice a second
 * while the car stands and 12-25 times a second on the move. `GearLvrIndcn` arrives at subscription and on
 * each change.
 *
 * [open], [subscribe] and [close] run on the polling thread; the service connection and the events come on others.
 */
internal class EcarxCarSignal(private val app: Context, private val readings: EcarxSignalReadings) {
    @Volatile private var car: Any? = null
    @Volatile private var connected = false
    @Volatile private var speedId: Int? = null
    @Volatile private var gearId: Int? = null
    private var manager: Any? = null
    private var callback: Any? = null
    private var failureLogged = false
    @Volatile private var state = "connecting"
    @Volatile private var lastError: String? = null

    /** connecting, subscribed or error with the exception's class, then the last `onErrorEvent` if any. */
    fun status(): String = state + lastError?.let { " lastError=$it" }.orEmpty()

    fun open() {
        val listener = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                state = "connecting"
            }
        }
        try {
            val type = Class.forName(CAR)
            val created = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, app, listener)
            car = created
            type.getMethod("connect").invoke(created)
        } catch (error: Throwable) {
            failed("ecarx.car unavailable", error)
        }
    }

    /** Subscribes once the service is connected, and again after it reconnects; retries a failed subscription. */
    fun subscribe() {
        val car = car ?: return
        if (!connected) {
            manager = null
            callback = null
            return
        }
        if (callback != null) return
        try {
            val manager = requireNotNull(car.javaClass.getMethod("getCarManager", String::class.java).invoke(car, "car_signal")) {
                "no car_signal"
            }
            val filterType = Class.forName(FILTER)
            val filter = filterType.getConstructor().newInstance()
            val speed = manager.javaClass.getField("SignalId_VehSpdLgtA").getInt(null)
            val gear = manager.javaClass.getField("SignalId_GearLvrIndcn").getInt(null)
            val add = filterType.getMethod("add", Int::class.javaObjectType)
            add.invoke(filter, speed)
            add.invoke(filter, gear)
            speedId = speed
            gearId = gear
            val callbackType = Class.forName(CALLBACK)
            val callback = Proxy.newProxyInstance(app.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "onChangeEvent" -> args?.firstOrNull()?.let(::changed)
                    "onErrorEvent" -> lastError = "onErrorEvent ${args?.contentToString()}"
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "DiPlay car_signal speed callback"
                }
                null
            }
            manager.javaClass.getMethod("registerCallback", callbackType, filterType).invoke(manager, callback, filter)
            this.manager = manager
            this.callback = callback
            state = "subscribed"
            failureLogged = false
            Log.i(TAG, "car_signal speed and gear subscribed")
        } catch (error: Throwable) {
            failed("car_signal subscription failed", error)
        }
    }

    fun close() {
        val car = car ?: return
        this.car = null
        val manager = manager
        val callback = callback
        if (manager != null && callback != null) {
            runCatching { manager.javaClass.getMethod("unregisterCallback", Class.forName(CALLBACK)).invoke(manager, callback) }
        }
        this.manager = null
        this.callback = null
        runCatching { car.javaClass.getMethod("disconnect").invoke(car) }
    }

    private fun changed(value: Any) {
        // An exception thrown here would end on the car service's callback thread.
        runCatching {
            val id = value.javaClass.getMethod("getPropertyId").invoke(value) as? Int
            val raw = value.javaClass.getMethod("getValue").invoke(value) as? Number ?: return
            when (id) {
                speedId -> readings.speed(raw.toFloat(), SystemClock.elapsedRealtime())
                gearId -> raw.toInt().let { readings.gear(it, EcarxSignalReadings.leverGear(it)) }
            }
        }
    }

    private fun failed(what: String, error: Throwable) {
        state = "error ${describe(error)}"
        if (!failureLogged) Log.w(TAG, what, error)
        failureLogged = true
    }

    private companion object {
        const val TAG = "DiPlay-EcarxSpeed"
        const val CAR = "ecarx.car.ECarXCar"
        const val FILTER = "ecarx.car.hardware.signal.SignalFilter"
        const val CALLBACK = "ecarx.car.hardware.signal.CarSignalManager\$CarSignalEventCallback"

        fun describe(error: Throwable): String =
            ((error as? InvocationTargetException)?.targetException ?: error).javaClass.simpleName
    }
}
