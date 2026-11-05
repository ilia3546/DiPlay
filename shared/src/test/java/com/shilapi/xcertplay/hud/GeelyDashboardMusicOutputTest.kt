package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.compat.Base64Compat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyDashboardMusicOutputTest {
    private val sent = mutableListOf<Pair<Int, String>>()
    private var accept = true
    private val blank = byteArrayOf(9, 9, 9)
    private val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; accept },
        blankCover = { blank })

    @Test fun buildsTheStockTrackMessage() {
        val cover = ByteArray(120) { it.toByte() }
        val json = GeelyDashboardMessages.track("Song", "Artist", "", cover)
        val picture = Base64Compat.encodeLines(cover, 76, "\n").replace("\n", "\\n")
        assertEquals("{\"Origin\":6,\"SongIndex\":0,\"SongName\":\"Song\",\"SongArtist\":\"Artist\",\"SongAlbum\":\"\"," +
            "\"SongSchools\":\"\",\"SongAges\":\"\",\"IsChangeAlbumPic\":\"true\",\"SongAlbumPicture\":\"$picture\"}", json)
        assertTrue(picture.split("\\n").dropLast(1).all { it.length == 76 })
    }

    @Test fun escapesQuotesBackslashesAndControlCharacters() {
        val json = GeelyDashboardMessages.track("a\"b\\c\nd\u0001", "Тест", "x", ByteArray(3))
        assertTrue(json, json.contains("\"SongName\":\"a\\\"b\\\\c\\nd\\u0001\""))
        assertTrue(json, json.contains("\"SongArtist\":\"Тест\""))
    }

    @Test fun buildsThePlayStateMessage() {
        assertEquals("{\"MusicPlayStatus\":\"Play\"}", GeelyDashboardMessages.playState(true))
        assertEquals("{\"MusicPlayStatus\":\"Pause\"}", GeelyDashboardMessages.playState(false))
    }

    @Test fun sendsATrackOnlyWhenItsTextOrCoverChanges() {
        output.show(DashboardTrack("Song", "Artist", "Album", byteArrayOf(1)))
        output.show(DashboardTrack("Song ", "Artist", "Album", byteArrayOf(1)))
        output.show(DashboardTrack("Song", "Artist", "Album", byteArrayOf(2)))
        output.show(DashboardTrack("Next", "Artist", "Album", byteArrayOf(2)))
        assertEquals(3, sent.size)
        assertTrue(sent.all { it.first == GeelyDashboardMessages.TRACK })
    }

    @Test fun aTrackWithoutCoverGetsTheBlankCover() {
        output.show(DashboardTrack("Song", "", "", null))
        assertEquals(GeelyDashboardMessages.track("Song", "", "", blank), sent.single().second)
    }

    @Test fun clearSendsOneEmptyCardAndOnlyAfterSomethingWasShown() {
        output.clear()
        assertEquals(emptyList<Pair<Int, String>>(), sent)
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.clear()
        output.clear()
        output.show(DashboardTrack("", "", "", null))
        assertEquals(listOf(GeelyDashboardMessages.TRACK to GeelyDashboardMessages.track("", "", "", blank)), sent.drop(1))
    }

    @Test fun anEmptyTrackClearsTheCard() {
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.show(DashboardTrack(" ", "", "Album", null))
        assertEquals(GeelyDashboardMessages.track("", "", "", blank), sent.last().second)
    }

    @Test fun thePlayStateWaitsForATrackAndIsSentOnChangeOnly() {
        output.playing(true)
        assertEquals(emptyList<Pair<Int, String>>(), sent)
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.playing(true)
        output.playing(false)
        assertEquals(listOf(GeelyDashboardMessages.TRACK, GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.PLAY_STATE),
            sent.map { it.first })
        assertEquals(listOf(GeelyDashboardMessages.playState(true), GeelyDashboardMessages.playState(false)),
            sent.drop(1).map { it.second })
    }

    @Test fun aRefusedTrackIsSentAgainNextTime() {
        accept = false
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.clear()
        accept = true
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        assertEquals(2, sent.size)
    }

    @Test fun aRepeatSendsTheShownCardAndPlayStateThroughResend() {
        val resent = mutableListOf<Pair<Int, String>>()
        val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; true }, blankCover = { blank },
            resend = { subtype, json -> resent += subtype to json; true })
        output.reassert()
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.playing(true)
        output.reassert()
        assertEquals(sent, resent)
        output.clear()
        resent.clear()
        output.reassert()
        assertEquals(emptyList<Pair<Int, String>>(), resent)
    }

    @Test fun aCardLeftByAKilledProcessIsClearedAndTheJournalFollowsTheCard() {
        val journal = mutableListOf<Boolean>()
        val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; accept }, blankCover = { blank },
            leftBehind = true, cardShown = journal::add)
        output.clear()
        assertEquals(listOf(GeelyDashboardMessages.TRACK to GeelyDashboardMessages.track("", "", "", blank)), sent)
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.show(DashboardTrack("Next", "Artist", "", byteArrayOf(1)))
        accept = false
        output.clear()
        accept = true
        output.clear()
        assertEquals(listOf(false, true, false), journal)
    }
}
