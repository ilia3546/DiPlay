package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallOutcome
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.hud.HudManeuver
import java.io.File
import java.time.Duration
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The custom dashboard window at 1920x720 for every background, written to build/reports/custom-dashboard-render. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "ru")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomDashboardRenderTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = File("build/reports/custom-dashboard-render").apply { mkdirs() }
    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val evening = 1_762_460_160_000L // 23:16 in Moscow

    @After fun clean() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DashboardPhoto.file(context).delete()
    }

    @Test fun everyPresetRendersWithoutTheMapUntilItHasAPicture() {
        for (preset in DashboardBackground.presets) {
            val frame = render("preset-${preset.name.lowercase()}") { showBackground(preset, 0) { null } }
            assertNotEquals("${preset.name} map card", MAP_EMPTY, frame.getPixel(960, 390))
            frame.recycle()
        }
    }

    @Test fun blackStaysBlackUnderTheVignette() {
        val frame = render(null) { showBackground(DashboardBackground.BLACK, 0) { null } }
        assertEquals(Color.BLACK, frame.getPixel(60, 660))
    }

    @Test fun dimmingDarkensTheWallpaper() {
        val plain = render(null) { showBackground(DashboardBackground.OCEAN, 0) { null } }
        val dimmed = render("ocean-dimmed-40") { showBackground(DashboardBackground.OCEAN, 40) { null } }
        val bright = Color.blue(plain.getPixel(960, 660))
        assertTrue(bright > 0)
        assertTrue(Color.blue(dimmed.getPixel(960, 660)) < bright)
    }

    @Test fun aSavedPhotoFillsTheDashboard() {
        val source = File(context.cacheDir, "landscape.png")
        val picture = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, 1800f, intArrayOf(Color.rgb(250, 170, 90), Color.rgb(60, 90, 160),
                    Color.rgb(20, 60, 40)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
            drawCircle(1700f, 700f, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 240, 200) })
        }
        source.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        picture.recycle()
        assertTrue(DashboardPhoto.save(context, Uri.fromFile(source)))
        val saved = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(DashboardPhoto.file(context).path, saved)
        assertEquals(1920 to 720, saved.outWidth to saved.outHeight)
        assertEquals("image/jpeg", saved.outMimeType)
        AirPlayPersistence.saveDashboardBackground(context, DashboardBackground.CUSTOM_PHOTO)
        val dimming = AirPlayPersistence.loadDashboardDimmingPercent(context)
        assertEquals(20, dimming)
        render("photo-dimmed-20") { showBackground(DashboardBackground.CUSTOM_PHOTO, dimming) { DashboardPhoto.load(context) } }
    }

    @Test fun theTemperatureAndAPlayingTrack() {
        render("ocean-temperature-6") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
        }
        render("ocean-track") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f))
        }
    }

    @Test fun theMediaBlockOfEachSource() {
        val cover = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, 600f, 600f, Color.rgb(240, 90, 120), Color.rgb(60, 40, 160),
                    Shader.TileMode.CLAMP)
            })
        }
        render("media-carplay") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", cover, 0.38f))
        }
        assertFalse("the dashboard does not own CarPlay's cover", cover.isRecycled)
        val station = RadioStation(frequencyId = 10620, frequencyName = "106.2", psName = "Europa Plus",
            titleText = "Houdini", artistText = "Dua Lipa")
        render("media-radio") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Radio(station.title, station.subtitle, null))
            assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE, View.GONE, View.VISIBLE),
                media.childVisibility())
        }
        render("media-empty") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", cover, 0.38f))
            media.show(DashboardMedia.Empty)
            assertEquals("nothing of the track stays", listOf(View.VISIBLE, View.GONE, View.GONE, View.GONE, View.GONE),
                media.childVisibility())
        }
        render("media-idle") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", cover, 0.38f))
            media.show(DashboardMedia.Idle)
            assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE, View.GONE, View.GONE), media.childVisibility())
            assertEquals(listOf("Не воспроизводится", "Выберите музыку на iPhone"),
                media.descendants<TextView>().map { it.text.toString() })
        }
        render("media-untitled") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", cover, 0.38f))
            media.show(DashboardMedia.Untitled)
            assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE, View.GONE, View.GONE), media.childVisibility())
            assertEquals(listOf("Звук с iPhone", "Приложение не передаёт, что играет"),
                media.descendants<TextView>().map { it.text.toString() })
        }
    }

    @Test fun withoutArtworkEveryBlockShowsTheEmptyTile() {
        val tile = MonjaroDashboardLayout.media.let { it.left + (it.width - MonjaroDashboardLayout.ARTWORK_SIZE) / 2 }
        val top = MonjaroDashboardLayout.media.top
        for (shown in listOf(DashboardMedia.Empty, DashboardMedia.Untitled,
            DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f),
            DashboardMedia.Radio("Europa Plus", "106.2", null))) {
            val frame = render(null) {
                showBackground(DashboardBackground.BLACK, 0) { null }
                media.show(shown)
            }
            assertEquals("$shown", Color.rgb(15, 15, 15), frame.getPixel(tile + 20, top + 20))
            assertEquals("$shown has no shadow", Color.BLACK, frame.getPixel(tile + 100, top + 208))
        }
    }

    @Test fun theIdleMediaBlockIsAFaintTileWithANote() {
        val frame = render("media-idle-black") {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f))
            media.show(DashboardMedia.Idle)
        }
        val tile = MonjaroDashboardLayout.media.let { it.left + (it.width - MonjaroDashboardLayout.ARTWORK_SIZE) / 2 }
        val top = MonjaroDashboardLayout.media.top
        assertEquals("6 % white over black", Color.rgb(15, 15, 15), frame.getPixel(tile + 20, top + 20))
        assertEquals("no shadow under the tile", Color.BLACK, frame.getPixel(tile + 100, top + 208))
        val note = (top + 40..top + 160).maxOf { y -> Color.red(frame.getPixel(tile + 100 - 6, y)) }
        assertTrue("the note's stem", note > 60)
        val track = render(null) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(DashboardMedia.Idle)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f))
        }
        val plain = render(null) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f))
        }
        assertTrue("a track looks as before after the idle block", track.sameAs(plain))
    }

    private fun DashboardMediaBlock.childVisibility() = (0 until childCount).map { getChildAt(it).visibility }

    @Test fun theMediaBadgesOfEachState() {
        val track = DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f)
        val radio = DashboardMedia.Radio("Europa Plus", "Dua Lipa – Houdini", null)
        for ((name, media) in listOf("media-paused" to track.copy(paused = true), "media-muted" to track.copy(muted = true),
            "media-paused-muted" to track.copy(paused = true, muted = true), "media-radio-paused" to radio.copy(paused = true))) {
            render(name) {
                showBackground(DashboardBackground.OCEAN, 0) { null }
                showTemperature(6.4f)
                this.media.show(media)
            }
        }
        val (paused, pausedRow) = badged(track.copy(paused = true))
        val pausedRuns = paused.badgeRuns(pausedRow)
        assertEquals(1, pausedRuns.size)
        assertCentred(pausedRuns)
        assertEquals("13 % white over black", Color.rgb(33, 33, 33), paused.getPixel(pausedRuns[0].first + 3, paused.rowMiddle(pausedRow)))
        val (muted, mutedRow) = badged(track.copy(muted = true))
        val mutedRuns = muted.badgeRuns(mutedRow)
        assertEquals(1, mutedRuns.size)
        assertCentred(mutedRuns)
        assertEquals("15 % orange over black", Color.rgb(38, 24, 1), muted.getPixel(mutedRuns[0].first + 3, muted.rowMiddle(mutedRow)))
        val (both, bothRow) = badged(track.copy(paused = true, muted = true))
        val bothRuns = both.badgeRuns(bothRow)
        assertEquals(2, bothRuns.size)
        assertCentred(bothRuns)
        assertEquals(pausedRuns[0].count(), bothRuns[0].count())
        assertEquals(mutedRuns[0].count(), bothRuns[1].count())
        assertEquals("the gap", 8, bothRuns[1].first - bothRuns[0].last - 1)
        val bottom = MonjaroDashboardLayout.media.top + bothRow.top + bothRow.height
        assertTrue("the row ends at $bottom, above the firmware's bottom row", bottom <= FIRMWARE_BOTTOM_ROW_TOP)
        val (station, stationRow) = badged(radio.copy(paused = true))
        assertEquals(1, station.badgeRuns(stationRow).size)
        assertTrue("without the progress the row follows the subtitle", stationRow.top < bothRow.top)
    }

    @Test fun withoutBadgesTheBlockLooksAsBefore() {
        val track = DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f)
        val (plain, row) = badged(track)
        assertTrue("the row is kept", row.visibility == View.VISIBLE && row.height == MonjaroDashboardLayout.MEDIA_BADGE_HEIGHT)
        assertEquals(emptyList<IntRange>(), plain.badgeRuns(row))
        val unrowed = render(null) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(track)
            media.descendants<DashboardMediaBadges>().single().visibility = View.GONE
        }
        assertTrue(plain.sameAs(unrowed))
        val (unpaused, _) = badged(track, before = track.copy(paused = true, muted = true))
        assertTrue("the badges leave nothing behind", plain.sameAs(unpaused))
    }

    @Test fun theBadgesMoveOnlyWithinTheSameMedia() {
        val track = DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f)
        val (one, oneRow) = badged(track.copy(paused = true))
        val (two, twoRow) = badged(track.copy(paused = true, muted = true))
        val oneWidth = one.badgeRuns(oneRow).let { it.last().last - it.first().first }
        val twoWidth = two.badgeRuns(twoRow).let { it.last().last - it.first().first }
        val looper = shadowOf(Looper.getMainLooper())
        var row: DashboardMediaBadges? = null
        val appearing = render("media-muted-appearing", attached = true) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(track.copy(paused = true))
            media.show(track.copy(paused = true, muted = true))
            // Later the badge is already as wide as it gets: its max-width passes its width early.
            looper.idleFor(Duration.ofMillis(50))
            row = media.descendants<DashboardMediaBadges>().single()
        }
        val appearingRuns = appearing.badgeRuns(row!!)
        assertCentred(appearingRuns)
        val appearingWidth = appearingRuns.last().last - appearingRuns.first().first
        assertTrue("$oneWidth < $appearingWidth < $twoWidth", appearingWidth in oneWidth + 1 until twoWidth)
        val hiding = render("media-badges-hiding", attached = true) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(track.copy(paused = true, muted = true))
            media.show(track)
            looper.idleFor(Duration.ofMillis(100))
            row = media.descendants<DashboardMediaBadges>().single()
        }
        val hidingRuns = hiding.badgeRuns(row!!)
        assertTrue(hidingRuns.isNotEmpty())
        assertTrue(hidingRuns.last().last - hidingRuns.first().first < twoWidth)
        val starting = render(null, attached = true) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(track)
            media.show(track.copy(paused = true))
            row = media.descendants<DashboardMediaBadges>().single()
        }
        assertEquals("a new pause starts from nothing", emptyList<IntRange>(), starting.badgeRuns(row!!))
        val settled = render(null, attached = true) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            media.show(track.copy(paused = true))
            looper.idleFor(Duration.ofMillis(MonjaroDashboardLayout.MEDIA_BADGE_RESIZE_MILLIS))
            row = media.descendants<DashboardMediaBadges>().single()
        }
        assertTrue("then it ends as drawn at once", settled.sameAs(one))
        for (before in listOf(DashboardMedia.Idle, DashboardMedia.Empty, DashboardMedia.Radio("Europa Plus", null, null))) {
            val switched = render(null, attached = true) {
                showBackground(DashboardBackground.BLACK, 0) { null }
                media.show(before)
                media.show(track.copy(paused = true))
                row = media.descendants<DashboardMediaBadges>().single()
            }
            assertEquals("after $before", one.badgeRuns(oneRow), switched.badgeRuns(row!!))
        }
    }

    @Test fun anUnchangedTickDoesNotDrawTheBadgesAgain() {
        val track = DashboardMedia.Track("Hymn", "Charlotte de Witte", null, 0.38f, paused = true)
        for (attached in listOf(false, true)) {
            var view: MonjaroDashboardView? = null
            render(null, attached = attached) {
                showBackground(DashboardBackground.BLACK, 0) { null }
                media.show(track.copy(paused = false))
                media.show(track)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MonjaroDashboardLayout.MEDIA_BADGE_RESIZE_MILLIS))
                view = this
            }
            val media = view!!.media
            val row = media.descendants<DashboardMediaBadges>().single()
            assertFalse("attached=$attached drawn", row.isDirty)
            media.show(track.copy(progress = 0.4f))
            assertFalse("attached=$attached: the same badges a second later", row.isDirty)
            media.show(track.copy(muted = true))
            assertTrue("attached=$attached: a new badge", row.isDirty)
        }
    }

    /** A black dashboard with [media] shown after [before], drawn outside a window, and its badge row. */
    private fun badged(media: DashboardMedia, before: DashboardMedia? = null): Pair<Bitmap, DashboardMediaBadges> {
        var row: DashboardMediaBadges? = null
        val frame = render(null) {
            showBackground(DashboardBackground.BLACK, 0) { null }
            before?.let(this.media::show)
            this.media.show(media)
            row = this.media.descendants<DashboardMediaBadges>().single()
        }
        return frame to row!!
    }

    private fun Bitmap.rowMiddle(row: DashboardMediaBadges) = MonjaroDashboardLayout.media.top + row.top + row.height / 2

    /** The x ranges drawn across the middle of the badge row, on a black dashboard. */
    private fun Bitmap.badgeRuns(row: DashboardMediaBadges): List<IntRange> {
        val box = MonjaroDashboardLayout.media
        val y = rowMiddle(row)
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (x in box.left..box.left + box.width) {
            val drawn = x < box.left + box.width && getPixel(x, y) != Color.BLACK
            if (drawn && start < 0) start = x
            if (!drawn && start >= 0) {
                runs += start until x
                start = -1
            }
        }
        return runs
    }

    private fun assertCentred(runs: List<IntRange>) {
        val centre = MonjaroDashboardLayout.media.let { it.left + it.width / 2f }
        val middle = (runs.first().first + runs.last().last + 1) / 2f
        assertEquals("centred, $runs", centre, middle, 1f)
    }

    @Test fun theCallBlockInEachState() {
        val name = "Анна Смирнова"
        val number = "+7 999 123-45-67"
        val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, name, number = number)
        val active = ringing.copy(phase = CarPlayCallCard.Phase.ACTIVE, activeSinceMillis = evening - 42_000)
        val pulsed = render("call-incoming", attached = true) {
            showCall(DashboardCallShown.Live(ringing, "Мобильный", muted = false))
            assertTrue(call.avatar.pulsing)
            // Robolectric runs a repeating animation to its end within one idle, so the frame is set directly.
            (DashboardCallAvatar::class.java.getDeclaredField("pulse").apply { isAccessible = true }
                .get(call.avatar) as ValueAnimator).currentPlayTime = 500
        }
        val (centreX, centreY) = MonjaroDashboardLayout.call.let { it.left + it.width / 2 to it.top + 75 }
        assertTrue("the pulse ring outside the avatar", (78..110).any { radius ->
            val pixel = pulsed.getPixel(centreX - radius, centreY)
            Color.green(pixel) > Color.red(pixel) + 0x40 && Color.green(pixel) > Color.blue(pixel)
        })
        render("call-incoming-number") {
            showCall(DashboardCallShown.Live(ringing.copy(name = number), "", muted = false))
        }
        render("call-talking") { showCall(DashboardCallShown.Live(active, "", muted = false)) }
        render("call-talking-muted") {
            showCall(DashboardCallShown.Live(active.copy(activeSinceMillis = evening - 3_725_000), "", muted = true))
        }
        render("call-dialing") {
            showCall(DashboardCallShown.Live(ringing.copy(phase = CarPlayCallCard.Phase.DIALING), "", muted = false))
        }
        render("call-ended") {
            showCall(DashboardCallShown.Ended(CarPlayCallOutcome(CarPlayCallOutcome.Kind.ENDED, name, number, 134, evening)))
        }
        render("call-declined") {
            showCall(DashboardCallShown.Ended(CarPlayCallOutcome(CarPlayCallOutcome.Kind.DECLINED, name, number, 0, evening)))
        }
    }

    @Test fun thePulseRunsOnlyWhileTheCallBlockIsShown() {
        val view = MonjaroDashboardView(context)
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(view)
        view.showCall(DashboardCallShown.Live(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Анна"), "", muted = false))
        assertTrue(view.call.avatar.pulsing)
        view.call.visibility = View.GONE
        assertFalse("hidden", view.call.avatar.pulsing)
        view.call.visibility = View.VISIBLE
        assertTrue(view.call.avatar.pulsing)
        view.call.show(DashboardCallShown.Live(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Анна", evening), "", false),
            evening)
        assertFalse("answered", view.call.avatar.pulsing)
        view.call.show(DashboardCallShown.Live(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Анна"), "", false), evening)
        assertTrue(view.call.avatar.pulsing)
        (view.parent as ViewGroup).removeView(view)
        assertFalse("detached", view.call.avatar.pulsing)
    }

    private fun MonjaroDashboardView.showCall(shown: DashboardCallShown) {
        showBackground(DashboardBackground.OCEAN, 0) { null }
        showTemperature(6.4f)
        media.visibility = View.GONE
        call.visibility = View.VISIBLE
        call.show(shown, evening)
    }

    @Test fun theRouteCardsOverTheMap() {
        val frame = render("map-route", playing = true) {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, 350, "Садовое кольцо"),
                arrivalEpochSeconds = evening / 1000 + 18 * 60, remainingSeconds = 1_080, remainingMeters = 9_400, sign = HudManeuver.RIGHT, exitNumber = 0),
                is24Hour = true, zone = moscow)
        }
        assertEquals("the maneuver tile", Color.rgb(0x2f, 0x7b, 0xff), frame.getPixel(MonjaroDashboardLayout.defaultMap.maneuverCard.left + 16, MonjaroDashboardLayout.defaultMap.maneuverCard.top + 30))
        assertEquals("the map below the cards", MAP_PICTURE, frame.getPixel(960, 500))
    }

    @Test fun theDistanceFitsBesideTheArrivalOnTheSmallestMap() {
        for (meters in listOf(10, 530, 1_250, 12_400)) {
            var card: DashboardManeuverCard? = null
            render(if (meters == 530) "map-route-small" else null, playing = true) {
                showBackground(DashboardBackground.OCEAN, 0) { null }
                map.resize(MonjaroDashboardLayout.defaultMap)
                map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, meters, "Расторопово"),
                    arrivalEpochSeconds = evening / 1000 + 18 * 60, remainingSeconds = 1_080, remainingMeters = 9_400, sign = HudManeuver.RIGHT, exitNumber = 0),
                    is24Hour = true, zone = moscow)
                card = map.descendants<DashboardManeuverCard>().single()
            }
            val distance = card!!.descendants<TextView>().first()
            assertEquals("${distance.text} is cut", 0, distance.layout.getEllipsisCount(0))
        }
    }

    @Test fun aLongerDistanceIsLaidOutAgainRatherThanCut() {
        val roadPairs = listOf(listOf("", ""), listOf("Садовое кольцо", ""), listOf("", "Садовое кольцо"),
            listOf("Садовое кольцо", "Садовое кольцо"), listOf("Съезд 1", "Съезд 1"), listOf("", "Съезд 1"))
        val distancePairs = listOf(50 to 2_600, 1_100 to 1_200, 50 to 630)
        for (arrival in listOf(true, false)) for (roads in roadPairs) for ((before, after) in distancePairs) {
            val view = MonjaroDashboardView(context)
            view.map.arrivalShown = arrival
            fun route(meters: Int, road: String) = DashboardRoute(
                ClusterTurnGuidance.fromApple(2, 0, meters, road),
                arrivalEpochSeconds = evening / 1000 + 300, remainingSeconds = 300, remainingMeters = 9_400, sign = HudManeuver.RIGHT, exitNumber = 0)
            // A window lays its views out again only when one of them asks for it, as here.
            fun frame() {
                if (view.isLayoutRequested || view.width == 0) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            view.map.showRoute(route(before, roads[0]), is24Hour = true, zone = moscow)
            frame()
            view.map.showRoute(route(after, roads[1]), is24Hour = true, zone = moscow)
            frame()
            val card = view.map.descendants<DashboardManeuverCard>().single()
            val (distance, road) = card.descendants<TextView>()
            val texts = if (road.visibility == View.VISIBLE && card.width < card.maxWidth) listOf(distance, road)
            else listOf(distance)
            for (text in texts) {
                val room = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                assertTrue("${text.text} is cut to $room px, arrival=$arrival roads=$roads $before→$after m",
                    text.paint.measureText(text.text.toString()) <= room + 0.5f)
            }
        }
    }

    @Test fun withoutTheArrivalTheManeuverGrowsToSixtyPercentOfTheMap() {
        var card: DashboardManeuverCard? = null
        render("map-route-alone", playing = true) {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            map.arrivalShown = false
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, 530, "Улица Сергея Эйзенштейна"),
                arrivalEpochSeconds = null, remainingSeconds = null, remainingMeters = null, sign = HudManeuver.RIGHT, exitNumber = 0),
                is24Hour = true, zone = moscow)
            card = map.descendants<DashboardManeuverCard>().single()
        }
        val map = MonjaroDashboardLayout.defaultMap
        assertTrue(card!!.width > map.maneuverCard.width)
        assertTrue(card!!.width <= map.width * 60 / 100)
    }

    private inline fun <reified T : View> View.descendants(): List<T> = tree().filterIsInstance<T>()

    private fun View.tree(): List<View> =
        listOf(this) + if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).tree() } else emptyList()

    @Test fun theParkedMapIsDimmedTowardTheCentre() {
        val frame = render("map-parked", playing = true) {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            map.video.showParked(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MonjaroDashboardLayout.PARKED_FADE_MILLIS * 2))
        }
        val box = MonjaroDashboardLayout.defaultMap.map
        val (centreX, centreY) = box.left + box.width / 2 to box.top + (box.height ?: 0) / 2
        val (edgeX, edgeY) = box.left + 20 to box.top + (box.height ?: 0) - 20
        val centre = Color.red(frame.getPixel(centreX, centreY)) + Color.blue(frame.getPixel(centreX, centreY))
        val edge = Color.red(frame.getPixel(edgeX, edgeY)) + Color.blue(frame.getPixel(edgeX, edgeY))
        assertTrue(centre < edge)
        assertTrue(edge < Color.red(MAP_PICTURE) + Color.blue(MAP_PICTURE))
    }

    @Test fun unknownValuesAreOneDash() {
        render("unknown-dash", playing = true) {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(null)
            trip.show(DashboardTrip())
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(1, 0, 1_250, ""), null, null, null, HudManeuver.LEFT, 0),
                is24Hour = true, zone = moscow)
        }
    }

    @Test fun withoutAPictureTheMapAndTheIconBackdropAreNotDrawn() {
        val bare = render(null, attached = true) {
            map.visibility = View.GONE
            descendants<DashboardIconBackdrop>().single().visibility = View.GONE
        }
        val stopped = render("map-hidden", attached = true) { idle() }
        assertTrue("no stream", stopped.sameAs(bare))
        val flat = render(null, attached = true) {
            map.video.readFrame = { paintFlat(it) }
            map.video.onSurfaceTextureUpdated(SurfaceTexture(0))
            idleFor(MonjaroDashboardLayout.MAP_BLANK_CHECK_MILLIS * 3)
            assertTrue(map.video.live)
            assertFalse(map.video.hasPicture)
        }
        assertTrue("frames of one flat colour", flat.sameAs(bare))
    }

    @Test fun withAPictureTheMapAndTheIconBackdropShow() {
        lateinit var reveal: View
        lateinit var backdrop: View
        val shown = render("map-playing", playing = true) {
            idle()
            reveal = map.parent as View
            backdrop = descendants<DashboardIconBackdrop>().single()
        }
        for (view in listOf(reveal, backdrop)) {
            assertEquals(1f, view.alpha)
            assertEquals(1f, view.scaleX)
        }
        val box = MonjaroDashboardLayout.defaultMap.map
        assertEquals(MAP_PICTURE, shown.getPixel(box.left + box.width / 2, box.top + (box.height ?: 0) / 2))
        val without = render(null, playing = true) { descendants<DashboardIconBackdrop>().single().visibility = View.GONE }
        val pad = MonjaroDashboardLayout.iconBackdrop.let { it.left + it.width / 2 to it.top + (it.height ?: 0) / 2 }
        assertNotEquals("the backdrop", without.getPixel(pad.first, pad.second), shown.getPixel(pad.first, pad.second))
    }

    @Test fun theMapAppearsAndHidesFromWhereItIs() {
        val view = attached()
        val reveal = view.map.parent as View
        val backdrop = view.descendants<DashboardIconBackdrop>().single()
        assertEquals(0f, reveal.alpha)
        assertEquals(MonjaroDashboardLayout.MAP_REVEAL_SCALE, reveal.scaleX)
        assertEquals(0f, backdrop.alpha)
        assertEquals(MonjaroDashboardLayout.ICON_BACKDROP_REVEAL_SCALE, backdrop.scaleX)
        view.map.video.readFrame = { paintMap(it) }
        view.map.video.onSurfaceTextureUpdated(SurfaceTexture(0))
        idle()
        assertTrue(view.map.video.hasPicture)
        for (shown in listOf(reveal, backdrop)) {
            assertEquals(1f, shown.alpha)
            assertEquals(1f, shown.scaleX)
            assertEquals(1f, shown.scaleY)
        }
        view.revealAt(MonjaroDashboardLayout.MAP_REVEAL_FADE_MILLIS / 2)
        assertTrue(reveal.alpha in 0.05f..0.95f)
        assertTrue(reveal.scaleX in MonjaroDashboardLayout.MAP_REVEAL_SCALE + 0.005f..0.995f)
        assertEquals(reveal.scaleX, reveal.scaleY)
        assertEquals(reveal.alpha, backdrop.alpha)
        assertTrue(backdrop.scaleX in MonjaroDashboardLayout.ICON_BACKDROP_REVEAL_SCALE + 0.005f..0.995f)
        view.map.video.keepFrame()
        view.showTime(evening, is24Hour = true, zone = moscow)
        snapshot(view, "map-reveal-middle")
        val (alpha, scale) = reveal.alpha to reveal.scaleX
        val backdropScale = backdrop.scaleX
        view.map.video.streamStopped()
        assertEquals("the hiding starts where the showing was", alpha, reveal.alpha)
        assertEquals(scale, reveal.scaleX)
        assertEquals(backdropScale, backdrop.scaleX)
        view.revealAt(50)
        assertTrue(reveal.alpha < alpha && reveal.alpha > 0f)
        assertTrue(reveal.scaleX < scale && reveal.scaleX > MonjaroDashboardLayout.MAP_REVEAL_SCALE)
        assertTrue(backdrop.scaleX < backdropScale)
        idle()
        assertEquals(0f, reveal.alpha)
        assertEquals(MonjaroDashboardLayout.MAP_REVEAL_SCALE, reveal.scaleX)
        assertEquals(0f, backdrop.alpha)
        assertEquals(MonjaroDashboardLayout.ICON_BACKDROP_REVEAL_SCALE, backdrop.scaleX)
    }

    /**
     * Robolectric runs an animation to its end within one idle, so a moment of the appearance is set directly. A seek
     * of an animation that has no frame yet would also become the start of the next one, as a running one's does not.
     */
    private fun MonjaroDashboardView.revealAt(millis: Long) {
        for (name in listOf("mapFade", "mapGrow")) {
            val animator = MonjaroDashboardView::class.java.getDeclaredField(name).apply { isAccessible = true }
                .get(this) as ValueAnimator
            animator.currentPlayTime = millis
            ValueAnimator::class.java.getDeclaredField("mSeekFraction").apply { isAccessible = true }.setFloat(animator, -1f)
        }
    }

    @Test fun aFlatFrameHidesThePlayingMapAndAPictureBringsItBack() {
        val view = attached()
        val reveal = view.map.parent as View
        view.map.video.readFrame = { paintMap(it) }
        view.map.video.onSurfaceTextureUpdated(SurfaceTexture(0))
        assertTrue(view.map.video.live)
        assertFalse("not before the first probe", view.map.video.hasPicture)
        idle()
        assertTrue(view.map.video.hasPicture)
        assertEquals(1f, reveal.alpha)
        view.map.video.readFrame = { paintFlat(it) }
        idleFor(MonjaroDashboardLayout.MAP_BLANK_CHECK_MILLIS)
        assertTrue(view.map.video.live)
        assertFalse(view.map.video.hasPicture)
        assertEquals(0f, reveal.alpha)
        view.map.video.readFrame = { paintMap(it) }
        idleFor(MonjaroDashboardLayout.MAP_BLANK_CHECK_MILLIS)
        assertTrue(view.map.video.hasPicture)
        assertEquals(1f, reveal.alpha)
    }

    @Test fun enteringAWindowShowsTheMapAtOnce() {
        val view = attached()
        val reveal = view.map.parent as View
        view.map.video.readFrame = { paintMap(it) }
        view.map.video.onSurfaceTextureUpdated(SurfaceTexture(0))
        idle()
        view.revealAt(MonjaroDashboardLayout.MAP_REVEAL_FADE_MILLIS / 2)
        assertTrue(reveal.alpha < 1f)
        (view.parent as ViewGroup).removeView(view)
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(view)
        assertEquals(1f, reveal.alpha)
        assertEquals(1f, reveal.scaleX)
    }

    @Test fun theIconBackdropShowsTheWallpaperAndMapUnderItBlurred() {
        val box = MonjaroDashboardLayout.iconBackdrop
        val left = box.left + 12
        val right = box.left + box.width - 8
        val y = box.top + (box.height ?: 0) / 2
        for (width in listOf(600, 456)) {
            // The backdrop copies what is under it in a task of its own, outside drawing.
            val frame = render("icon-backdrop-neon-$width", playing = true) {
                showBackground(DashboardBackground.NEON, 0) { null }
                showMap(MonjaroDashboardLayout.MapGeometry(width, MonjaroDashboardLayout.MAP_HEIGHT))
                idle()
            }
            val off = render(null) {
                showBackground(DashboardBackground.NEON, 0) { null }
                showMap(MonjaroDashboardLayout.MapGeometry(width, MonjaroDashboardLayout.MAP_HEIGHT))
                descendants<DashboardIconBackdrop>().single().visibility = View.GONE
            }
            val pink = frame.getPixel(left, y)
            assertTrue("$width: the neon's pink shows through, ${Integer.toHexString(pink)}", Color.red(pink) > Color.green(pink))
            assertTrue("$width: tinted darker than the wallpaper", Color.red(pink) < Color.red(off.getPixel(left, y)))
            val over = frame.getPixel(right, y)
            if (width == 600) {
                assertTrue("600: the map shows through, ${Integer.toHexString(over)}", Color.red(over) <= Color.green(over))
            } else {
                assertTrue("456: only the wallpaper, ${Integer.toHexString(over)}", Color.red(over) > Color.green(over))
            }
            assertEquals("$width: outside it the dashboard is as without it", off.getPixel(box.left - 60, y),
                frame.getPixel(box.left - 60, y))
        }
    }

    @Test fun theIconBackdropFadesInWithTheMapAtTheStartUpButDoesNotGrow() {
        val view = attached()
        view.map.video.readFrame = { paintMap(it) }
        view.map.video.onSurfaceTextureUpdated(SurfaceTexture(0))
        idle()
        val frame = DashboardBoot.at(3_600)
        view.showBoot(frame)
        val backdrop = view.descendants<DashboardIconBackdrop>().single()
        assertEquals(view, backdrop.parent)
        assertEquals(frame.mapAlpha, backdrop.alpha)
        assertEquals(1f, backdrop.scaleX)
        assertTrue(frame.mapScale < 1f)
        view.showBoot(DashboardBoot.finished)
        assertEquals(1f, backdrop.alpha)
    }

    @Test fun theStartUpEndsOnTheDashboardAsItWasBefore() {
        val booted = render("boot-finished", attached = true) {
            showBackground(DashboardBackground.NEON, 0) { null }
            showTemperature(6.4f)
        }
        val plain = render(null) {
            showBackground(DashboardBackground.NEON, 0) { null }
            showTemperature(6.4f)
        }
        assertTrue(booted.sameAs(plain))
    }

    @Test fun theStartUpShowsGeelyBesideCarPlayOnBlack() {
        val start = render("boot-start") {
            showBackground(DashboardBackground.NEON, 0) { null }
            showBoot(DashboardBoot.at(0))
        }
        assertEquals(Color.BLACK, start.getPixel(960, 440))
        assertEquals(Color.BLACK, start.getPixel(1570, 300))
        assertTrue("the clock stays", (160..240).any { x -> (30..70).any { y -> Color.red(start.getPixel(x, y)) > 0xC0 } })
        val lockup = render("boot-lockup") {
            showBackground(DashboardBackground.NEON, 0) { null }
            showBoot(DashboardBoot.at(2_800))
        }
        assertEquals("the divider", Color.rgb(0x8C, 0x8C, 0x8C), lockup.getPixel(960, 388))
        assertTrue("the Geely emblem", (580..720).any { x -> Color.red(lockup.getPixel(x, 388)) > 0xC0 })
        assertTrue("the CarPlay logo", Color.red(lockup.getPixel(960 + 46 + 4, 388 - 25)) > 0xC0)
        render("boot-map") {
            showBackground(DashboardBackground.NEON, 0) { null }
            showBoot(DashboardBoot.at(3_900))
        }
    }

    /** A dashboard in an activity's window after its start-up, so that its animations and the map's probe run. */
    private fun attached(): MonjaroDashboardView {
        val view = MonjaroDashboardView(context)
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(view)
        idleFor(DashboardBoot.DURATION_MILLIS + 100)
        return view
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    /** A map picture of [MAP_PICTURE] with a road near its left edge. */
    private fun paintMap(frame: Bitmap) {
        frame.eraseColor(MAP_PICTURE)
        Canvas(frame).drawRect(frame.width * 0.1f, 0f, frame.width * 0.25f, frame.height.toFloat(),
            Paint().apply { color = Color.rgb(0x4a, 0x9b, 0xff) })
    }

    private fun paintFlat(frame: Bitmap) = frame.eraseColor(Color.rgb(0x1a, 0x1a, 0x1a))

    /**
     * [attached] puts the view in an activity's window first and plays its start-up, so that its animations run;
     * [playing] also plays a map with a picture there.
     */
    private fun render(
        name: String?,
        attached: Boolean = false,
        playing: Boolean = false,
        setUp: MonjaroDashboardView.() -> Unit,
    ): Bitmap {
        val view = if (attached || playing) attached() else MonjaroDashboardView(context)
        view.apply {
            showTime(evening, is24Hour = true, zone = moscow)
            if (playing) {
                map.video.readFrame = { paintMap(it) }
                map.video.onSurfaceTextureUpdated(SurfaceTexture(0))
                idle()
                map.video.keepFrame()
            }
            setUp()
        }
        return snapshot(view, name)
    }

    private fun snapshot(view: MonjaroDashboardView, name: String?): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(1920 to 720, view.measuredWidth to view.measuredHeight)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // The window under the dashboard is black.
        view.draw(Canvas(frame).apply { drawColor(Color.BLACK) })
        if (name != null) {
            File(directory, "$name.png").outputStream().use { assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        return frame
    }

    private companion object {
        val MAP_EMPTY = Color.rgb(0x12, 0x18, 0x21)
        val MAP_PICTURE = Color.rgb(0x24, 0x34, 0x44)

        /** The top of the firmware's fuel, temperature and gear readouts at the bottom of design v2. */
        const val FIRMWARE_BOTTOM_ROW_TOP = 636
    }
}
