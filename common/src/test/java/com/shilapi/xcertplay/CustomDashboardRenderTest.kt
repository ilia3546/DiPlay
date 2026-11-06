package com.shilapi.xcertplay

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
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.io.File
import java.time.Duration
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun theRouteCardsOverTheEmptyMap() {
        val frame = render("map-route") {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            map.showRoute(DashboardRoute(ClusterTurnGuidance.fromApple(2, 0, 350, "Садовое кольцо"),
                arrivalEpochSeconds = evening / 1000 + 18 * 60, remainingSeconds = 1_080, remainingMeters = 9_400),
                is24Hour = true, zone = moscow)
        }
        assertEquals("the maneuver tile", Color.rgb(0x2f, 0x7b, 0xff), frame.getPixel(642, 248))
        assertEquals("the map below the cards", Color.rgb(0x12, 0x18, 0x21), frame.getPixel(960, 500))
    }

    @Test fun theParkedMapIsDimmedTowardTheCentre() {
        val frame = render("map-parked", attached = true) {
            showBackground(DashboardBackground.OCEAN, 0) { null }
            showTemperature(6.4f)
            map.video.showParked(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(MonjaroDashboardLayout.PARKED_FADE_MILLIS * 2))
        }
        val centre = Color.red(frame.getPixel(960, 390)) + Color.blue(frame.getPixel(960, 390))
        val edge = Color.red(frame.getPixel(660, 560)) + Color.blue(frame.getPixel(660, 560))
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
