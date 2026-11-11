package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import com.shilapi.xcertplay.network.GeelyHeadUnit

/**
 * The steering wheel's dashboard view key, for firmware whose input service ([GeelyWheelKeys]) does not pass
 * that key to apps. The Desay SV VDBus key service (`com.desaysv.ivi.vds.sms`) is exported without a permission
 * check and reports each wheel key press and release to every subscriber without consuming it, so the car still
 * switches its own cluster view on a short press; a hold of [HOLD_MILLIS] hides or shows the custom dashboard.
 *
 * The VDBus classes are on the head unit's boot class path only, so the event is built by reflection; where
 * they or the service are missing, nothing is subscribed.
 */
internal object DesayWheelKeys {
    private const val TAG = "DiPlay-DesayWheel"
    private const val SMS_PACKAGE = "com.desaysv.ivi.vds.sms"
    private const val SMS_CLASS = "com.desaysv.ivi.vds.sms.service.SmsService"
    private const val BUS_DESCRIPTOR = "com.desaysv.ivi.vdb.IVDBus"
    private const val NOTIFY_DESCRIPTOR = "com.desaysv.ivi.vdb.IVDBusNotify"
    private const val EVENT_CLASS = "com.desaysv.ivi.vdb.event.VDEvent"
    // IVDBus.subscribeCustomizedEvent and unsubscribeCustomizedEvent, IVDBusNotify.onVDBusNotify.
    private const val TRANSACTION_SUBSCRIBE = IBinder.FIRST_CALL_TRANSACTION + 3
    private const val TRANSACTION_UNSUBSCRIBE = IBinder.FIRST_CALL_TRANSACTION + 4
    private const val TRANSACTION_NOTIFY = IBinder.FIRST_CALL_TRANSACTION
    private const val ID_SMS_KEY_EVENT = 65537
    private const val PAYLOAD_TYPE = "type"
    private const val PAYLOAD_ACTION = "action"
    internal const val KEY_DASHBOARD_VIEW = 17
    internal const val ACTION_PRESS = 0
    internal const val ACTION_RELEASE = 3

    private const val RETRY_MILLIS = 3_000L
    private const val MAX_SUBSCRIBE_FAILURES = 3
    private const val HOLD_MILLIS = 1_500L

    private val handler = Handler(Looper.getMainLooper())
    private val hold = KeyHoldToggle(HOLD_MILLIS, { delay, task -> handler.postDelayed(task, delay) }) {
        Log.i(TAG, "dashboard view key held long: toggling the custom dashboard")
        MonjaroCustomDashboard.toggleHidden()
    }
    private val events: VdEvents? by lazy {
        runCatching { VdEvents(Class.forName(EVENT_CLASS)) }
            .onFailure { Log.i(TAG, "no VDBus event class: $it") }
            .getOrNull()
    }
    private var appContext: Context? = null
    private var bound = false
    private var bus: IBinder? = null
    private var notify: Notify? = null
    private var subscribeFailures = 0

    /** Called with the CarPlay session's start; safe to call repeatedly. */
    fun start(context: Context) {
        if (!GeelyHeadUnit.detected) return
        synchronized(this) {
            appContext = context.applicationContext
            subscribeFailures = 0
        }
        refresh()
    }

    /** Subscribes while the custom dashboard and its wheel toggle are on, unsubscribes otherwise; does nothing outside a session. */
    fun refresh() {
        val context = synchronized(this) { appContext } ?: return
        val options = MonjaroA3Options.load(context)
        if (!options.customDashboard || !options.dashboardWheelToggle) {
            release(context)
            return
        }
        if (events == null) return
        synchronized(this) { if (!bound) bindLocked() }
    }

    /** Unsubscribes and unbinds when the CarPlay session ends. */
    fun stop() {
        val context = synchronized(this) { appContext.also { appContext = null } }
        release(context)
        handler.removeCallbacksAndMessages(null)
    }

    @Synchronized
    private fun release(context: Context?) {
        handler.removeCallbacks(rebind)
        handler.removeCallbacks(resubscribe)
        hold.cancel()
        val service = bus
        val callback = notify
        // Only a live subscription reads [events]: off this firmware the class lookup must not run at all.
        val events = if (service != null && callback != null) events else null
        if (service != null && callback != null && events != null) {
            runCatching { call(service, TRANSACTION_UNSUBSCRIBE, events.keyEvent(subscribePayload()), callback) }
                .onSuccess { Log.i(TAG, "unsubscribed from key $KEY_DASHBOARD_VIEW") }
                .onFailure { Log.w(TAG, "unsubscribe failed", it) }
        }
        bus = null
        notify = null
        if (bound && context != null) runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun bindLocked() {
        val context = appContext ?: return
        val intent = Intent().setComponent(ComponentName(SMS_PACKAGE, SMS_CLASS))
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        Log.i(TAG, "bind desay key service requested=$bound")
    }

    private val rebind = Runnable {
        synchronized(this@DesayWheelKeys) {
            if (appContext != null && bound && bus == null) {
                bound = false
                bindLocked()
            }
        }
    }

    /** After a failed subscribe the binding is dropped, so [refresh] binds and subscribes again. */
    private val resubscribe = Runnable { refresh() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val events = events ?: return
            synchronized(this@DesayWheelKeys) {
                if (appContext == null || !bound || service == null) return
                val callback = Notify(events)
                runCatching { call(service, TRANSACTION_SUBSCRIBE, events.keyEvent(subscribePayload()), callback) }
                    .onSuccess {
                        bus = service
                        notify = callback
                        subscribeFailures = 0
                        Log.i(TAG, "subscribed to key $KEY_DASHBOARD_VIEW")
                    }
                    .onFailure { error ->
                        subscribeFailures++
                        Log.w(TAG, "subscribe failed ($subscribeFailures of $MAX_SUBSCRIBE_FAILURES)", error)
                        appContext?.let { context -> runCatching { context.unbindService(this) } }
                        bound = false
                        if (subscribeFailures < MAX_SUBSCRIBE_FAILURES) handler.postDelayed(resubscribe, RETRY_MILLIS)
                    }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val retry = synchronized(this@DesayWheelKeys) {
                bus = null
                notify = null
                hold.cancel()
                appContext != null
            }
            Log.w(TAG, "desay key service disconnected")
            if (retry) {
                // The car service is persistent; rebind shortly after an unexpected drop.
                handler.removeCallbacks(rebind)
                handler.postDelayed(rebind, RETRY_MILLIS)
            }
        }
    }

    @Synchronized
    private fun onKey(source: Notify, type: Int, action: Int) {
        // A late callback of a subscription already dropped by [release] must not arm the toggle.
        if (notify !== source || type != KEY_DASHBOARD_VIEW) return
        when (action) {
            ACTION_PRESS -> {
                Log.i(TAG, "dashboard view key pressed")
                hold.pressed()
            }
            ACTION_RELEASE -> {
                Log.i(TAG, "dashboard view key released")
                hold.released()
            }
            else -> Log.i(TAG, "dashboard view key action=$action")
        }
    }

    /** Asks the key service for [KEY_DASHBOARD_VIEW] only. */
    internal fun subscribePayload() = Bundle().apply { putIntArray(PAYLOAD_TYPE, intArrayOf(KEY_DASHBOARD_VIEW)) }

    /** The key code and action of a key event's payload; null when it has either missing. */
    internal fun keyOf(payload: Bundle?): Pair<Int, Int>? {
        if (payload == null || !payload.containsKey(PAYLOAD_TYPE) || !payload.containsKey(PAYLOAD_ACTION)) return null
        return payload.getInt(PAYLOAD_TYPE) to payload.getInt(PAYLOAD_ACTION)
    }

    private fun call(service: IBinder, code: Int, event: Parcelable, callback: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(BUS_DESCRIPTOR)
            data.writeInt(1)
            event.writeToParcel(data, 0)
            data.writeStrongBinder(callback)
            check(service.transact(code, data, reply, 0)) { "transaction $code not handled" }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private class VdEvents(type: Class<*>) {
        private val constructor = type.getConstructor(Int::class.javaPrimitiveType)
        private val setPayload = type.getMethod("setPayload", Bundle::class.java)
        private val getPayload = type.getMethod("getPayload")
        private val creator = type.getField("CREATOR").get(null) as Parcelable.Creator<*>

        fun keyEvent(payload: Bundle): Parcelable =
            (constructor.newInstance(ID_SMS_KEY_EVENT) as Parcelable).also { setPayload.invoke(it, payload) }

        fun payload(data: Parcel): Bundle? = getPayload.invoke(creator.createFromParcel(data)) as? Bundle
    }

    private class Notify(private val events: VdEvents) : Binder() {
        init {
            attachInterface(null, NOTIFY_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_NOTIFY) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(NOTIFY_DESCRIPTOR)
            val payload = if (data.readInt() != 0) {
                runCatching { events.payload(data) }.onFailure { Log.w(TAG, "unreadable key event", it) }.getOrNull()
            } else null
            reply?.writeNoException()
            keyOf(payload)?.let { (type, action) -> handler.post { onKey(this, type, action) } }
            return true
        }
    }
}
