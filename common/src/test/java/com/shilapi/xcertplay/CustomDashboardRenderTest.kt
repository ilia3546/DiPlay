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
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallOutcome
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.File
import java.time.Duration
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun everyPresetRendersAroundAnEmptyMapCard() {
        for (preset in DashboardBackground.presets) {
            val frame = render("preset-${preset.name.lowercase()}") { showBackground(preset, 0) { null } }
            assertEquals("${preset.name} map card", Color.rgb(0x12, 0x18, 0x21), frame.getPixel(960, 390))
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
            assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE, View.GONE), media.childVisibility())
        }
        render("media-empty") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            media.show(DashboardMedia.Track("Hymn", "Charlotte de Witte", cover, 0.38f))
            media.show(DashboardMedia.Empty)
            assertEquals("nothing of the track stays", listOf(View.VISIBLE, View.GONE, View.GONE, View.GONE),
                media.childVisibility())
        }
    }

    private fun DashboardMediaBlock.childVisibility() = (0 until childCount).map { getChildAt(it).visibility }

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

    @Test fun theRouteCardsOverTheEmptyMap() {
        val frame = render("map-route") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, 350, "Садовое кольцо"),
                arrivalEpochSeconds = evening / 1000 + 18 * 60, remainingSeconds = 1_080, remainingMeters = 9_400),
                is24Hour = true, zone = moscow)
        }
        assertEquals("the maneuver tile", Color.rgb(0x2f, 0x7b, 0xff), frame.getPixel(MonjaroDashboardLayout.defaultMap.maneuverCard.left + 16, MonjaroDashboardLayout.defaultMap.maneuverCard.top + 30))
        assertEquals("the map below the cards", Color.rgb(0x12, 0x18, 0x21), frame.getPixel(960, 500))
    }

    @Test fun theDistanceFitsBesideTheArrivalOnTheSmallestMap() {
        for (meters in listOf(10, 530, 1_250, 12_400)) {
            var card: DashboardManeuverCard? = null
            render(if (meters == 530) "map-route-small" else null) {
                showBackground(DashboardBackground.OCEAN, 0) { null }
                map.resize(MonjaroDashboardLayout.MapGeometry(MonjaroDashboardLayout.mapWidths.first(), 304))
                map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, meters, "Расторопово"),
                    arrivalEpochSeconds = evening / 1000 + 18 * 60, remainingSeconds = 1_080, remainingMeters = 9_400),
                    is24Hour = true, zone = moscow)
                card = map.descendants<DashboardManeuverCard>().single()
            }
            val distance = card!!.descendants<TextView>().first()
            assertEquals("${distance.text} is cut", 0, distance.layout.getEllipsisCount(0))
        }
    }

    @Test fun aLongerDistanceIsLaidOutAgainRatherThanCut() {
        for (arrival in listOf(true, false)) for (roads in listOf(listOf("", ""), listOf("Садовое кольцо", ""),
            listOf("", "Садовое кольцо"), listOf("Садовое кольцо", "Садовое кольцо"))) {
            val view = MonjaroDashboardView(context)
            view.map.arrivalShown = arrival
            fun route(meters: Int, road: String, minutes: Long) = DashboardRoute(
                ClusterTurnGuidance.fromApple(2, 0, meters, road),
                arrivalEpochSeconds = evening / 1000 + minutes * 60, remainingSeconds = minutes * 60, remainingMeters = 9_400)
            // A window lays its views out again only when one of them asks for it, as here.
            fun frame() {
                if (view.isLayoutRequested || view.width == 0) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
            }
            view.map.showRoute(route(50, roads[0], 5), is24Hour = true, zone = moscow)
            frame()
            view.map.showRoute(route(2_600, roads[1], 115), is24Hour = true, zone = moscow)
            frame()
            val texts = view.map.descendants<TextView>().filter { it.isShown && it.text.isNotEmpty() && it.text.length < 9 }
            for (text in texts) assertEquals("${text.text} is cut, arrival=$arrival roads=$roads", 0,
                text.layout?.getEllipsisCount(0) ?: 0)
        }
    }

    @Test fun withoutTheArrivalTheManeuverGrowsToSixtyPercentOfTheMap() {
        var card: DashboardManeuverCard? = null
        render("map-route-alone") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            map.arrivalShown = false
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, 530, "Улица Сергея Эйзенштейна"),
                arrivalEpochSeconds = null, remainingSeconds = null, remainingMeters = null),
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
        val frame = render("map-parked", attached = true) {
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
        assertTrue(edge < 0x12 + 0x21)
    }

    @Test fun unknownValuesAreOneDash() {
        render("unknown-dash") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(null)
            trip.show(DashboardTrip())
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(1, 0, 1_250, ""), null, null, null),
                is24Hour = true, zone = moscow)
        }
    }

    /** [attached] puts the view in an activity's window first, so that its animations run. */
    private fun render(name: String?, attached: Boolean = false, setUp: MonjaroDashboardView.() -> Unit): Bitmap {
        val view = MonjaroDashboardView(context)
        if (attached) Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(view)
        view.apply {
            showTime(evening, is24Hour = true, zone = moscow)
            setUp()
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(1920 to 720, view.measuredWidth to view.measuredHeight)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        if (name != null) {
            File(directory, "$name.png").outputStream().use { assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        return frame
    }
}
