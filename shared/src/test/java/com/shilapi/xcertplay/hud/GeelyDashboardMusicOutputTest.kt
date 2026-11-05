package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.compat.Base64Compat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyDashboardMusicOutputTest {
    private val sent = mutableListOf<Pair<Int, String>>()
    private var accept = true
    private val blank = byteArrayOf(9, 9, 9)
    private var clock = 0L
    private val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; accept },
        blankCover = { blank }, now = { clock })

    private val messages = mutableListOf<Triple<String, Int, String>>()
    private var acceptApp = true
    private val progress = GeelyDashboardMusicOutput(
        send = { subtype, json -> messages += Triple("root", subtype, json); true },
        blankCover = { blank },
        resend = { subtype, json -> messages += Triple("app", subtype, json); acceptApp },
        now = { clock },
    )
    private val song = GeelyDashboardMessages.track("Song", "Artist", "", byteArrayOf(1))

    private fun track(title: String, durationMillis: Long?) =
        DashboardTrack(title, "Artist", "", byteArrayOf(1), durationMillis)

    private fun time(current: Long, total: Long) =
        Triple("app", GeelyDashboardMessages.PLAY_TIME, GeelyDashboardMessages.playTime(current, total))

    private fun bar(shown: Boolean) =
        Triple("app", GeelyDashboardMessages.PLAY_BAR, GeelyDashboardMessages.playBar(shown))

    private fun card(path: String, json: String) = Triple(path, GeelyDashboardMessages.TRACK, json)

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
            resend = { subtype, json -> resent += subtype to json; true }, now = { clock })
        output.reassert()
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.playing(true)
        output.reassert()
        assertEquals(sent + (GeelyDashboardMessages.PLAY_BAR to GeelyDashboardMessages.playBar(false)), resent)
        output.clear()
        resent.clear()
        output.reassert()
        assertEquals(emptyList<Pair<Int, String>>(), resent)
    }

    @Test fun aCardLeftByAKilledProcessIsClearedAndTheJournalFollowsTheCard() {
        val journal = mutableListOf<Boolean>()
        val output = GeelyDashboardMusicOutput(send = { subtype, json -> sent += subtype to json; accept }, blankCover = { blank },
            leftBehind = true, cardShown = journal::add, now = { clock })
        output.clear()
        assertEquals(listOf(GeelyDashboardMessages.PLAY_BAR to GeelyDashboardMessages.playBar(false),
            GeelyDashboardMessages.TRACK to GeelyDashboardMessages.track("", "", "", blank)), sent)
        output.show(DashboardTrack("Song", "Artist", "", byteArrayOf(1)))
        output.show(DashboardTrack("Next", "Artist", "", byteArrayOf(1)))
        accept = false
        output.clear()
        accept = true
        output.clear()
        assertEquals(listOf(false, true, false), journal)
    }

    @Test fun buildsThePositionAndProgressBarMessagesWithNumbers() {
        assertEquals("{\"CurrentPlayingTime\":35,\"TotalPlayTime\":200}", GeelyDashboardMessages.playTime(35, 200))
        assertEquals("{\"MusicPlayBarStatus\":1}", GeelyDashboardMessages.playBar(true))
        assertEquals("{\"MusicPlayBarStatus\":0}", GeelyDashboardMessages.playBar(false))
    }

    @Test fun aTrackWithADurationGetsItsPositionInSecondsAndThenTheBar() {
        progress.playing(true)
        progress.position(30_900L, 0L)
        progress.show(track("Song", 200_999L))
        assertEquals(listOf(
            card("root", song),
            Triple("root", GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(true)),
            time(30, 200), bar(true)), messages)
        assertTrue(progress.ticking)
    }

    @Test fun theBarIsSentOnlyWhenItChangesAndHiddenBeforeACardWithoutDuration() {
        progress.show(track("A", 100_000L))
        progress.show(track("B", 120_000L))
        progress.show(track("C", null))
        progress.show(track("D", 0L))
        assertEquals(listOf(bar(true), bar(false)), messages.filter { it.second == GeelyDashboardMessages.PLAY_BAR })
        val hide = messages.indexOf(bar(false))
        assertEquals(GeelyDashboardMessages.track("C", "Artist", "", byteArrayOf(1)), messages[hide + 1].third)
        assertFalse(messages.any { it.second == GeelyDashboardMessages.PLAY_TIME &&
            it.third.endsWith("\"TotalPlayTime\":0}") })
    }

    @Test fun aNewDurationForTheSameCardSendsThePositionAndBarAtOnce() {
        progress.show(track("Song", null))
        messages.clear()
        progress.show(track("Song", 90_000L))
        progress.show(track("Song", 90_000L))
        assertEquals(listOf(time(0, 90), bar(true)), messages)
    }

    @Test fun thePositionGoesWhenTheSecondChangesAndOnlyWhilePlaying() {
        progress.position(0L, 0L)
        progress.playing(true)
        progress.show(track("Song", 200_000L))
        messages.clear()
        clock = 400L
        progress.tick()
        clock = 1_000L
        progress.tick()
        progress.tick()
        clock = 2_300L
        progress.tick()
        assertEquals(listOf(time(1, 200), time(2, 200)), messages)
    }

    @Test fun aPauseSendsTheCurrentPositionOnceAndStopsTheTicks() {
        progress.position(10_000L, 0L)
        progress.playing(true)
        progress.show(track("Song", 200_000L))
        messages.clear()
        clock = 1_500L
        progress.playing(false)
        assertFalse(progress.ticking)
        clock = 9_000L
        progress.tick()
        progress.playing(false)
        assertEquals(listOf(Triple("root", GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(false)),
            time(11, 200)), messages)
        clock = 10_000L
        progress.playing(true)
        clock = 11_000L
        progress.tick()
        assertEquals(time(12, 200), messages.last())
    }

    @Test fun thePositionAndBarNeverGoThroughRoot() {
        acceptApp = false
        progress.playing(true)
        progress.show(track("Song", 200_000L))
        clock = 1_000L
        progress.tick()
        progress.playing(false)
        progress.reassert()
        progress.clear()
        val root = messages.filter { it.first == "root" }.map { it.second }.toSet()
        assertEquals(setOf(GeelyDashboardMessages.TRACK, GeelyDashboardMessages.PLAY_STATE), root)
        assertEquals(setOf(GeelyDashboardMessages.PLAY_TIME, GeelyDashboardMessages.PLAY_BAR),
            messages.filter { it.first == "app" }.map { it.second }.toSet() - GeelyDashboardMessages.TRACK)
    }

    @Test fun aRepeatSendsThePositionAndBarAfterTheCard() {
        progress.position(0L, 0L)
        progress.playing(true)
        progress.show(track("Song", 200_000L))
        messages.clear()
        clock = 3_200L
        progress.reassert()
        assertEquals(listOf(
            card("app", song),
            Triple("app", GeelyDashboardMessages.PLAY_STATE, GeelyDashboardMessages.playState(true)),
            time(3, 200), bar(true)), messages)
    }

    @Test fun aClearHidesTheBarBeforeTheEmptyCard() {
        progress.show(track("Song", 200_000L))
        messages.clear()
        progress.clear()
        assertEquals(listOf(bar(false), card("root", GeelyDashboardMessages.track("", "", "", blank))), messages)
        assertFalse(progress.ticking)
    }

    @Test fun aClearOfACardWithoutBarSendsOnlyTheEmptyCard() {
        progress.show(track("Song", null))
        messages.clear()
        progress.clear()
        assertEquals(listOf(GeelyDashboardMessages.TRACK), messages.map { it.second })
    }
}
