package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DashboardRadioTest {
    /** A `RadioLocalInfo` as the radio service writes it, field by field, cut after [fields] fields when given. */
    private fun info(
        frequencyId: Int = 10620,
        frequencyName: String = "106.2",
        psName: String = "Europa Plus",
        logo: String = "/data/radio/logo.png",
        radioText: String = "",
        titleText: String = "Houdini",
        artistText: String = "Dua Lipa",
        playing: Boolean = true,
        fields: Int = Int.MAX_VALUE,
    ): Parcel {
        val written = listOf<Parcel.() -> Unit>(
            { writeString("/data/radio/slide.png") }, { writeInt(10) }, { writeString("Pop") }, { writeInt(frequencyId) },
            { writeInt(-1) }, { writeInt(-1) }, { writeInt(-1) }, { writeInt(1) }, { writeString("") },
            { writeString(frequencyName) }, { writeString("") }, { writeString("") }, { writeString(psName) },
            { writeString(logo) }, { writeString(radioText) }, { writeString(titleText) }, { writeString("Album") },
            { writeString(artistText) }, { writeInt(5) }, { writeString("1.0") }, { writeInt(-1) }, { writeInt(0) },
            { writeInt(1) }, { writeInt(1) }, { writeInt(if (playing) 1 else 0) }, { writeInt(0) }, { writeInt(0) },
        )
        assertEquals(27, written.size)
        return Parcel.obtain().apply {
            writeInt(1)
            written.take(fields).forEach { it() }
            setDataPosition(0)
        }
    }

    @Test fun aStationIsReadInTheOrderOfItsFields() {
        val station = RadioStation.read(info())!!
        assertEquals(RadioStation(10620, "106.2", "Europa Plus", "/data/radio/logo.png", "", "Houdini", "Dua Lipa", true),
            station)
        assertFalse(RadioStation.read(info(playing = false))!!.playing)
    }

    @Test fun anAbsentOrCutShortStationIsNull() {
        assertNull(RadioStation.read(Parcel.obtain().apply {
            writeInt(0)
            setDataPosition(0)
        }))
        assertNull(RadioStation.read(Parcel.obtain()))
        for (fields in listOf(0, 1, 13, 26)) assertNull("$fields fields", RadioStation.read(info(fields = fields)))
    }

    @Test fun theTitleIsTheStationNameOrItsFrequency() {
        assertEquals("Europa Plus", RadioStation(psName = " Europa Plus ").title)
        assertEquals("FM 106.2 MHz", RadioStation(frequencyId = 10620, frequencyName = "106.2").title)
        assertEquals("FM 87.5 MHz", RadioStation(frequencyId = 8750).title)
        assertEquals("AM 1026 kHz", RadioStation(frequencyId = 1026).title)
        assertEquals("DAB 227.360 MHz", RadioStation(frequencyId = 227360).title)
        assertEquals("12C", RadioStation(frequencyName = "12C").title)
    }

    @Test fun theSubtitleIsTheRadioTextOrTheSong() {
        assertEquals("Live from the studio", RadioStation(radioText = "Live from the studio", titleText = "Houdini").subtitle)
        assertEquals("Houdini · Dua Lipa", RadioStation(titleText = "Houdini", artistText = "Dua Lipa").subtitle)
        assertEquals("Dua Lipa", RadioStation(artistText = "Dua Lipa").subtitle)
        assertNull(RadioStation(radioText = " ").subtitle)
    }

    @Test fun theListenerTakesAnyCallWithoutThrowing() {
        val stations = mutableListOf<RadioStation?>()
        val playing = mutableListOf<Boolean>()
        val listener = RadioListener(stations::add, playing::add)

        fun call(code: Int, descriptor: String? = RadioListener.DESCRIPTOR, reply: Parcel? = null,
            body: Parcel.() -> Unit = {}): Boolean {
            val data = Parcel.obtain()
            descriptor?.let(data::writeInterfaceToken)
            data.body()
            data.setDataPosition(0)
            return listener.transact(code, data, reply, if (reply == null) IBinder.FLAG_ONEWAY else 0)
        }

        assertTrue(call(3) { appendFrom(info(), 0, info().dataSize()) })
        assertEquals("Europa Plus", stations.single()?.psName)
        assertTrue(call(3) { writeInt(0) })
        assertNull(stations.last())
        assertTrue(call(3) { writeInt(1); writeString("cut") })
        assertNull("a cut-short station is no station", stations.last())
        assertTrue(call(4))
        assertTrue(call(5))
        assertEquals(listOf(true, false), playing)
        for (code in listOf(1, 2, 6, 7, 8, 9, 10, 11, 12, 13)) assertTrue("call $code", call(code) { writeInt(0) })
        assertTrue("another interface's call", call(4, descriptor = "com.example.Other"))
        assertEquals(listOf(true, false), playing)
        assertFalse("an unknown call", call(99))
        val reply = Parcel.obtain()
        assertTrue(call(5, reply = reply))
        reply.setDataPosition(0)
        reply.readException()

        val descriptor = Parcel.obtain()
        assertTrue(listener.transact(IBinder.INTERFACE_TRANSACTION, Parcel.obtain(), descriptor, 0))
        descriptor.setDataPosition(0)
        assertEquals(RadioListener.DESCRIPTOR, descriptor.readString())
    }

    @Test fun anAnswerAskedForBeforeTheLatestCallIsStale() {
        val stations = RadioEvents()
        val plays = RadioEvents()
        val listener = RadioListener({ stations.heard() }, { plays.heard() })
        val stationFresh = stations.mark()
        val playingFresh = plays.mark()
        val pause = Parcel.obtain().apply {
            writeInterfaceToken(RadioListener.DESCRIPTOR)
            setDataPosition(0)
        }
        assertTrue(listener.transact(5, pause, null, IBinder.FLAG_ONEWAY))
        assertTrue("no station call came", stationFresh())
        assertFalse("the pause came after the request", playingFresh())
        assertTrue("a later request", plays.mark()())
    }
}
