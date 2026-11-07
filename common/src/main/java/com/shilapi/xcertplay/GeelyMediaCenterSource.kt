package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.Executors

/**
 * Tells the firmware's media centre that CarPlay is the car's audio source. The media centre maps an audio focus
 * holder it does not know, such as DiPlay, to its radio and hands each steering-wheel key to the radio too; with
 * CarPlay as its source it leaves short presses alone. The radio takes the source back when it plays again.
 * Its CarPlay player only acts on the firmware's own CarPlay, which is not connected while DiPlay runs.
 */
internal object GeelyMediaCenterSource {
    private const val TAG = "DiPlay-MediaCenter"
    private const val PACKAGE = "com.geely.mediacenterservice"
    private const val ACTION = "android.intent.action.GEELY_MEDIACENTER_SERVICE"
    private const val DESCRIPTOR = "com.geely.lib.oneosapi.mediacenter.IMediaCenter"
    private const val REQUEST_AUDIO_SOURCE = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val AUDIO_SOURCE_CARPLAY = 7
    private const val APP_SOURCE_UNKNOWN = 0

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "DiPlay-MediaCenter").apply { isDaemon = true } }

    /** May run on any thread. */
    fun claimForCarPlay(context: Context) {
        val app = context.applicationContext
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                worker.execute {
                    Log.i(TAG, "media centre source set to CarPlay: ${request(service)}")
                    runCatching { app.unbindService(this) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            app.bindService(Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "media centre bind refused", it) }.getOrDefault(false)
        if (!bound) {
            Log.i(TAG, "media centre not found; its source is left alone")
            runCatching { app.unbindService(connection) }
        }
    }

    private fun request(service: IBinder): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(AUDIO_SOURCE_CARPLAY)
            data.writeInt(APP_SOURCE_UNKNOWN)
            if (!service.transact(REQUEST_AUDIO_SOURCE, data, reply, 0)) return "not handled"
            reply.readException()
            "ok"
        } catch (error: Exception) {
            "failed (${error.javaClass.simpleName})"
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
