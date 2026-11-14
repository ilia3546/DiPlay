package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Observed
import com.shilapi.xcertplay.location.HeadUnitGnssFixPolicy.Send
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.atomic.AtomicInteger

/** Some receivers put HDOP into [Location.getAccuracy]; their real error in metres is only in the `GST` sentence. */
class HeadUnitGnssLocationProvider(
    context: Context,
    private val log: (String) -> Unit = {},
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : Iap2LocationProvider {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private var subscription: Subscription? = null
    @Volatile private var generation = 0

    @Volatile private var latestFix: Fix? = null
    @Volatile private var latestGga: Received<NmeaSentenceParser.Gga>? = null
    @Volatile private var latestGst: Received<NmeaSentenceParser.Gst>? = null
    @Volatile private var firstNmeaMillis: Long? = null
    @Volatile private var firstGstMillis: Long? = null
    private val fixes = AtomicInteger()
    private val nmeaMessages = AtomicInteger()

    private var startedMillis = 0L
    private var nmeaLogged = false
    private var gstMissingLogged = false
    private var gstAppearedLogged = false
    private var fallbackLogged = false
    private var encodeFailureLogged = false
    private var reportedGst: Received<NmeaSentenceParser.Gst>? = null
    private var report = HeadUnitGnssReport()

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (subscription != null) return true
        val current = ++generation
        resetState()
        val locationListener = locationListener(current)
        val nmeaListener = nmeaListener(current)
        var locationManager: LocationManager? = null
        var thread: HandlerThread? = null
        try {
            val manager = appContext.getSystemService(LocationManager::class.java)
                ?: throw IllegalStateException("no LocationManager")
            locationManager = manager
            if (LocationManager.GPS_PROVIDER !in manager.allProviders) {
                log("Head unit GPS: gps=unavailable nmea=not subscribed")
                return false
            }
            val started = HandlerThread("HeadUnitGnss").apply { start() }
            thread = started
            val looper = started.looper
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MILLIS, MIN_DISTANCE_METERS,
                locationListener, looper)
            var nmeaSubscribed = false
            val nmea = try {
                @Suppress("DEPRECATION")
                val added = manager.addNmeaListener(nmeaListener, Handler(looper))
                nmeaSubscribed = added
                if (added) "subscribed" else "refused"
            } catch (error: Exception) {
                failure(error)
            }
            log("Head unit GPS: gps=subscribed nmea=$nmea")
            subscription = Subscription(manager, started, locationListener, nmeaListener.takeIf { nmeaSubscribed })
            true
        } catch (error: Exception) {
            log("Head unit GPS: gps=${failure(error)} nmea=not subscribed")
            locationManager?.let { manager -> runCatching { manager.removeUpdates(locationListener) } }
            thread?.quitSafely()
            false
        }
    }

    override fun stop() {
        synchronized(stateLock) {
            val stopped = subscription ?: return
            subscription = null
            generation++
            runCatching { stopped.locationManager.removeUpdates(stopped.locationListener) }
            stopped.nmeaListener?.let { listener -> runCatching { stopped.locationManager.removeNmeaListener(listener) } }
            stopped.thread.quitSafely()
        }
    }

    override fun latestNmea(): String? {
        val now = elapsedRealtimeMillis()
        val fix = latestFix
        val gga = latestGga
        val gst = latestGst
        val gstObserved = gst?.observed(now, fix)
        val decision = HeadUnitGnssFixPolicy.decide(
            fixAgeMillis = fix?.let { now - it.receivedMillis },
            locationAccuracyMeters = fix?.accuracyMeters,
            locationSatellites = fix?.satellites,
            gga = gga?.observed(now, fix),
            gst = gstObserved,
        )
        val newGstOffset = if (gst !== reportedGst) gstObserved?.fixTimeOffsetMillis else null
        reportedGst = gst
        logNmeaState(now)
        report.record(now, decision, fix?.accuracyMeters, fix?.speedMetersPerSecond, newGstOffset)
        if (report.summaryDue(now)) {
            log(report.summary(now, fixes.getAndSet(0), nmeaMessages.getAndSet(0)))
        }
        if (decision !is Send || fix == null) return null
        return try {
            NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitudeDegrees,
                    longitudeDegrees = fix.longitudeDegrees,
                    altitudeMeters = fix.altitudeMeters,
                    bearingDegrees = fix.bearingDegrees,
                    speedMetersPerSecond = fix.speedMetersPerSecond,
                    accuracyMeters = decision.accuracyMeters,
                    timestampMillis = fix.timeMillis,
                    satellitesUsed = decision.quality.satellitesUsed,
                ),
            )
        } catch (error: Exception) {
            if (!encodeFailureLogged) {
                encodeFailureLogged = true
                log("Head unit GPS: could not encode the fix (${error.javaClass.simpleName})")
            }
            null
        }
    }

    private fun locationListener(current: Int) = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (current != generation || location.provider != LocationManager.GPS_PROVIDER) return
            latestFix = Fix.of(location, elapsedRealtimeMillis())
            fixes.incrementAndGet()
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    private fun nmeaListener(current: Int) = OnNmeaMessageListener { message, _ ->
        if (current != generation) return@OnNmeaMessageListener
        val now = elapsedRealtimeMillis()
        nmeaMessages.incrementAndGet()
        if (firstNmeaMillis == null) firstNmeaMillis = now
        for (sentence in NmeaSentenceParser.parseAll(message)) {
            when (sentence) {
                is NmeaSentenceParser.Gga -> latestGga = Received(sentence, now)
                is NmeaSentenceParser.Gst -> {
                    latestGst = Received(sentence, now)
                    if (firstGstMillis == null) firstGstMillis = now
                }
            }
        }
    }

    private fun logNmeaState(now: Long) {
        val firstNmea = firstNmeaMillis
        val firstGst = firstGstMillis
        if (!nmeaLogged && firstNmea != null && (firstGst != null || now - firstNmea >= GST_WAIT_MILLIS)) {
            nmeaLogged = true
            gstMissingLogged = firstGst == null
            log("Head unit GPS: NMEA received (GST=${if (firstGst != null) "yes" else "no"})")
        }
        if (gstMissingLogged && !gstAppearedLogged && firstGst != null) {
            gstAppearedLogged = true
            log("Head unit GPS: GST appeared after ${(firstGst - startedMillis) / 1000} s")
        }
        if (!fallbackLogged && firstNmea == null && now - startedMillis >= NMEA_WAIT_MILLIS) {
            fallbackLogged = true
            log("Head unit GPS: no NMEA in ${NMEA_WAIT_MILLIS / 1000} s, accuracy falls back to " +
                "max(${HeadUnitGnssFixPolicy.MIN_FALLBACK_ACCURACY_METERS.toInt()} m, Location.accuracy)")
        }
    }

    private fun resetState() {
        latestFix = null
        latestGga = null
        latestGst = null
        firstNmeaMillis = null
        firstGstMillis = null
        fixes.set(0)
        nmeaMessages.set(0)
        startedMillis = elapsedRealtimeMillis()
        nmeaLogged = false
        gstMissingLogged = false
        gstAppearedLogged = false
        fallbackLogged = false
        encodeFailureLogged = false
        reportedGst = null
        report = HeadUnitGnssReport()
    }

    private fun failure(error: Exception): String = "failed (${error.javaClass.simpleName}: ${error.message})"

    private class Subscription(
        val locationManager: LocationManager,
        val thread: HandlerThread,
        val locationListener: LocationListener,
        val nmeaListener: OnNmeaMessageListener?,
    )

    private class Received<T : NmeaSentenceParser.Sentence>(val sentence: T, val receivedMillis: Long) {
        fun observed(nowMillis: Long, fix: Fix?): Observed<T> {
            val sentenceTime = sentence.utcTimeOfDayMillis
            val offset = if (fix == null || sentenceTime == null) null
            else HeadUnitGnssFixPolicy.timeOfDayOffsetMillis(sentenceTime, fix.timeMillis)
            return Observed(sentence, nowMillis - receivedMillis, offset)
        }
    }

    private class Fix(
        val latitudeDegrees: Double,
        val longitudeDegrees: Double,
        val altitudeMeters: Double?,
        val bearingDegrees: Double?,
        val speedMetersPerSecond: Double?,
        val accuracyMeters: Double?,
        val satellites: Int?,
        val timeMillis: Long,
        val receivedMillis: Long,
    ) {
        companion object {
            fun of(location: Location, receivedMillis: Long) = Fix(
                latitudeDegrees = location.latitude,
                longitudeDegrees = location.longitude,
                altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                bearingDegrees = location.bearing.takeIf { location.hasBearing() }?.toDouble(),
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() }?.toDouble(),
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() }?.toDouble(),
                satellites = location.extras?.takeIf { it.containsKey(SATELLITES_EXTRA) }?.getInt(SATELLITES_EXTRA),
                timeMillis = location.time,
                receivedMillis = receivedMillis,
            )
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L
        const val MIN_DISTANCE_METERS = 0f
        const val NMEA_WAIT_MILLIS = 10_000L
        const val GST_WAIT_MILLIS = 2_000L
        const val SATELLITES_EXTRA = "satellites"
    }
}
