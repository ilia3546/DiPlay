package com.shilapi.xcertplay.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The HUD line drawn by Android's canvas, 800x480 on black as the HUD shows it, for a look at the real glyphs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, qualifiers = "ru")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudRenderTest {
    private val route = CarPlayGlance.Snapshot(connected = true, maneuverIndex = 0)
    private val full = HudSettings(HudSettings.Variant.BALANCED, HudSettings.DEFAULT_Y, 100, true, true)

    private fun lanes(vararg lanes: LaneGuidanceLane) = CarPlayGlance.LaneStrip(0, lanes.toList())

    @Test fun examplesRenderAs800x480Pngs() {
        val examples = listOf(
            Triple("right-350m", route.copy(maneuverType = 2, distanceMeters = 350, road = "Садовое кольцо",
                remainingSeconds = 720, remainingMeters = 4200), full),
            Triple("right-350m-default", route.copy(maneuverType = 2, distanceMeters = 350, road = "Садовое кольцо",
                remainingSeconds = 720, remainingMeters = 4200),
                HudSettings(HudSettings.Variant.BALANCED, HudSettings.DEFAULT_Y, HudSettings.DEFAULT_SCALE, true, true)),
            Triple("roundabout-exit-3", route.copy(maneuverType = 30, distanceMeters = 1200,
                road = "Ленинградский проспект", remainingSeconds = 1500, remainingMeters = 18_400), full),
            Triple("lanes-highlighted", route.copy(maneuverType = 2, distanceMeters = 600, road = "МКАД",
                remainingSeconds = 300, remainingMeters = 3100, lanes = lanes(
                    LaneGuidanceLane(0, 0, -90, null), LaneGuidanceLane(1, 0, 0, null),
                    LaneGuidanceLane(2, 2, 0, 90), LaneGuidanceLane(3, 2, 90, null),
                )), full),
            Triple("long-label", route.copy(maneuverType = 49, distanceMeters = 800,
                road = "Третье транспортное кольцо, съезд на Большую Дорогомиловскую улицу", remainingSeconds = 2400,
                remainingMeters = 27_000), full),
            Triple("trip-does-not-fit", route.copy(maneuverType = 1, distanceMeters = 12_500, road = "Дмитровское шоссе",
                remainingSeconds = 7500, remainingMeters = 148_000,
                lanes = lanes(*Array(8) { LaneGuidanceLane(it, if (it < 2) 2 else 0, if (it < 2) -90 else 0, null) })),
                HudSettings(HudSettings.Variant.BOLD, HudSettings.DEFAULT_Y, 100, true, true)),
            Triple("neutral-sign", route.copy(maneuverType = 8, distanceMeters = 500, road = "Тверская улица"), full),
            Triple("ferry", route.copy(maneuverType = 15, distanceMeters = 2500, road = "Паромная переправа",
                remainingSeconds = 3600, remainingMeters = 41_000), full),
        )
        for ((name, glance, settings) in examples) {
            render(name, HudFrame.from(glance, HudUnits.of(RuntimeEnvironment.getApplication()))!!, null, settings)
        }
    }

    @Test fun callsRenderAs800x480Pngs() {
        val words = HudCallWords.of(RuntimeEnvironment.getApplication())
        val turn = HudFrame.from(route.copy(maneuverType = 2, distanceMeters = 350, road = "Садовое кольцо",
            remainingSeconds = 720, remainingMeters = 4200, lanes = lanes(
                LaneGuidanceLane(0, 0, -90, null), LaneGuidanceLane(1, 0, 0, null),
                LaneGuidanceLane(2, 2, 0, 90), LaneGuidanceLane(3, 2, 90, null),
            )), HudUnits.of(RuntimeEnvironment.getApplication()))!!
        val ringing = HudCall("Анна Петрова", "")
        val talkingMuted = HudCall("00:05", words.muted)
        val long = HudCall("Константин Константинопольский, ООО «Международные грузоперевозки»", words.muted)
        val examples = listOf(
            Triple("call-ringing", null to ringing, full),
            Triple("call-incoming-default", null to HudCall(words.incoming, ""),
                HudSettings(HudSettings.Variant.BALANCED, HudSettings.DEFAULT_Y, HudSettings.DEFAULT_SCALE, true, true)),
            Triple("call-talking-muted", null to talkingMuted, full),
            Triple("call-long-name", null to long, full),
            Triple("call-route", turn to talkingMuted, full),
            Triple("call-route-long-name", turn to long, full),
            Triple("call-route-compact", turn to ringing, HudSettings(HudSettings.Variant.COMPACT, HudSettings.DEFAULT_Y, 100, true, true)),
            Triple("call-route-bold", turn to talkingMuted, HudSettings(HudSettings.Variant.BOLD, HudSettings.DEFAULT_Y, 100, true, true)),
            Triple("call-bold", null to talkingMuted, HudSettings(HudSettings.Variant.BOLD, HudSettings.DEFAULT_Y, 100, true, true)),
        )
        for ((name, shown, settings) in examples) render(name, shown.first, shown.second, settings)
    }

    private fun render(name: String, frame: HudFrame?, call: HudCall?, settings: HudSettings) {
        val directory = File("build/reports/hud-render").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(HudDrawing.WIDTH, HudDrawing.HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            HudDrawing.draw(AndroidHudCanvas(canvas), frame, call, settings)
            val pixels = IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
            assertTrue("$name draws something", pixels.any { it != Color.BLACK })
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
