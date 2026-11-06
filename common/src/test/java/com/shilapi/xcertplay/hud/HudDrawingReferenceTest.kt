package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The designer's reference for the HUD line (`hud/design-reference.json`): every sign's source paths and dots, every
 * lane arrow in its three roles, the numbered roundabout exits, and the layout of one line. The reference differs
 * from the HUD Renderer original only by `otherLane`, the style of lanes the driver should not take.
 */
@RunWith(RobolectricTestRunner::class) // org.json
@Config(sdk = [29], manifest = Config.NONE)
class HudDrawingReferenceTest {
    private val reference = JSONObject(javaClass.getResource("/hud/design-reference.json")!!.readText())

    private class Op(val type: String) {
        var data: String? = null
        var text: String? = null
        var x = 0f
        var y = 0f
        var size = 0f
        var width = 0f
        var opacity = 0f
        var tracking = 0f
        var color = 0
        var weight = 0
    }

    /** Records every call; a glyph is half an em wide, so text widths are exact. */
    private class Recording : HudCanvas {
        val ops = mutableListOf<Op>()
        private fun op(type: String) = Op(type).also { ops += it }
        override fun measure(value: String, size: Float, bold: Boolean) = measure(value, size, if (bold) 500 else 400, 0f)
        override fun measure(value: String, size: Float, weight: Int, tracking: Float) =
            value.codePointCount(0, value.length) * (size * .5f + tracking)
        override fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean) =
            text(value, x, baseline, size, color, if (bold) 500 else 400, 0f)
        override fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, weight: Int, tracking: Float) {
            if (value.isEmpty()) return
            op("text").also {
                it.text = value; it.x = x; it.y = baseline; it.size = size; it.color = color; it.weight = weight; it.tracking = tracking
            }
        }
        override fun line(points: FloatArray, width: Float, color: Int) = throw AssertionError("unexpected line")
        override fun circle(x: Float, y: Float, radius: Float, width: Float, color: Int) = throw AssertionError("unexpected circle")
        override fun path(path: HudPath, x: Double, y: Double, scale: Double, width: Double, color: Int, opacity: Double) {
            op("path").also {
                it.data = path.data; it.x = x.toFloat(); it.y = y.toFloat(); it.size = scale.toFloat()
                it.width = width.toFloat(); it.color = color; it.opacity = opacity.toFloat()
            }
        }
        override fun rule(x: Double, top: Double, bottom: Double, width: Double, color: Int, opacity: Double, dash: Double, gap: Double) {
            op("rule").also {
                it.x = x.toFloat(); it.y = top.toFloat(); it.size = bottom.toFloat(); it.width = width.toFloat()
                it.color = color; it.opacity = opacity.toFloat(); it.tracking = dash.toFloat()
            }
        }
        override fun dot(cx: Double, cy: Double, radius: Double, x: Double, y: Double, scale: Double, color: Int) {
            op("dot").also {
                it.x = (x + cx * scale).toFloat(); it.y = (y + cy * scale).toFloat(); it.size = (radius * scale).toFloat(); it.color = color
            }
        }
        override fun beginOpacity(opacity: Double) {
            op("begin").opacity = opacity.toFloat()
        }
        override fun endOpacity() {
            op("end")
        }
    }

    private fun close(actual: Float, expected: Double, label: String) =
        assertTrue("$label: $actual != $expected", abs(actual - expected) < .0002)

    private fun color(hex: String) = (0xff000000 or hex.removePrefix("#").toLong(16)).toInt()

    private fun parts(actual: Recording, expected: JSONArray, size: Float, stroke: Float, color: Int) {
        assertEquals("part count", expected.length(), actual.ops.size)
        val scale = size / 24
        for (i in 0 until expected.length()) {
            val part = expected.getJSONObject(i)
            val op = actual.ops[i]
            if (part.has("d")) {
                assertEquals("source path", "path" to part.getString("d"), op.type to op.data)
                close(op.x, 400.0 - size / 2, "icon left")
                close(op.y, 215.0 - size / 2, "icon top")
                close(op.size, scale.toDouble(), "source grid scale")
                close(op.width, (stroke / scale).toDouble(), "source stroke")
                close(op.opacity, if (part.has("dim")) .4 else 1.0, "source opacity")
            } else {
                val dot = part.getJSONArray("dot")
                assertEquals("filled dot", "dot", op.type)
                close(op.x, 400 - size / 2 + dot.getDouble(0) * scale, "dot x")
                close(op.y, 215 - size / 2 + dot.getDouble(1) * scale, "dot y")
                close(op.size, dot.getDouble(2) * scale, "dot radius")
            }
            assertEquals("source color", color, op.color)
        }
    }

    @Test fun theSignsAndLanesAreTheReferenceNamesInItsOrder() {
        val kinds = reference.getJSONArray("kinds")
        assertEquals((0 until kinds.length()).map { kinds.getJSONArray(it).getString(0) }, HudManeuver.entries.map { it.name })
        val directions = reference.getJSONArray("directions")
        assertEquals((0 until directions.length()).map { directions.getJSONArray(it).getString(0) },
            HudLaneDirection.entries.map { it.name.lowercase() })
    }

    @Test fun everySignNumberedExitAndLaneMatchesTheReferenceInEveryVariantAndScale() {
        val icons = reference.getJSONObject("icons")
        val lanes = reference.getJSONObject("lanes")
        val other = reference.getJSONObject("otherLane")
        val cyan = color(reference.getJSONObject("colors").getString("pri"))
        val muted = color(reference.getJSONObject("colors").getString("sec"))
        val variants = reference.getJSONObject("variants")
        for (variant in variants.keys()) for (factor in floatArrayOf(.5f, .65f, 1f)) {
            val tokens = variants.getJSONObject(variant)
            val size = tokens.getDouble("icon").toFloat() * factor
            val stroke = tokens.getDouble("stroke").toFloat() * factor
            for (kind in HudManeuver.entries) {
                val r = Recording()
                ManeuverIcon.draw(r, kind, 400.0, 215.0, size.toDouble(), stroke.toDouble(), cyan, 0)
                parts(r, icons.getJSONArray(kind.name), size, stroke, cyan)
            }
            for (exit in 1..19) {
                val r = Recording()
                ManeuverIcon.draw(r, HudManeuver.ROUNDABOUT_EXIT, 400.0, 215.0, size.toDouble(), stroke.toDouble(), cyan, exit)
                assertEquals("ring only with number", 3, r.ops.size)
                assertEquals("M21 10.5 A9 9 0 1 1 3 10.5 A9 9 0 1 1 21 10.5", r.ops[0].data)
                assertEquals("M12 22.5 V19.5", r.ops[1].data)
                val number = r.ops[2]
                assertEquals(exit.toString(), number.text)
                assertEquals(700, number.weight)
                close(number.size, (if (exit >= 10) 10.5 else 12.5) * size / 24, "ring font")
                close(number.tracking, if (exit >= 10) -.4 * size / 24 else 0.0, "ring tracking")
            }
            for (direction in HudLaneDirection.entries) for (role in 0..2) {
                val r = Recording()
                val laneSize = tokens.getDouble("lane").toFloat() * factor
                val lane = HudLane(listOf(direction), if (role == 2) direction else null, role != 0)
                HudDrawing.lane(r, lane, 400.0 - laneSize / 2, 215.0 - laneSize / 2, laneSize.toDouble())
                val source = lanes.getJSONArray(direction.name.lowercase())
                assertEquals("lane part count", source.length() + 2 + if (role != 0) 1 else 0, r.ops.size)
                close(r.ops[0].opacity, if (role == 0) other.getDouble("opacity") else 1.0, "group opacity")
                for (i in 0 until source.length()) {
                    val op = r.ops[i + 1]
                    assertEquals("lane source path", source.getJSONObject(i).getString("d"), op.data)
                    close(op.size, (laneSize / 24).toDouble(), "lane scale")
                    close(op.width, if (role == 0) other.getDouble("stroke") else 2.6, "lane stroke")
                    assertEquals("lane role color", when (role) {
                        2 -> cyan
                        1 -> muted
                        else -> color(other.getString("color"))
                    }, op.color)
                }
                if (role != 0) {
                    val underline = r.ops[r.ops.size - 2]
                    assertEquals("M8 26.5 H16", underline.data)
                    close(underline.width, 2.2, "underline stroke")
                }
            }
        }
    }

    @Test fun smoothQuadraticsAndArcsReplayAsTheirSourceDrew() {
        val quads = mutableListOf<FloatArray>()
        HudPath("M3 21 Q5.25 19.5 7.5 21 T12 21 T16.5 21 T21 21").replay(object : Sink() {
            override fun quad(cx: Float, cy: Float, x: Float, y: Float) {
                quads += floatArrayOf(cx, cy, x, y)
            }
        })
        assertEquals("waves retain quadratic curves", 4, quads.size)
        close(quads[1][0], 9.75, "smooth quadratic reflected x")
        close(quads[1][1], 22.5, "smooth quadratic reflected y")
        close(quads[2][1], 19.5, "second reflection")
        val arcs = mutableListOf<FloatArray>()
        HudPath("M7 20 V11 A5 5 0 0 1 17 11 V20").replay(object : Sink() {
            override fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) {
                arcs += floatArrayOf(cx, cy, radius, start, sweep)
            }
        })
        assertEquals("native circular arc", 1, arcs.size)
        close(arcs[0][0], 12.0, "arc center x")
        close(arcs[0][1], 11.0, "arc center y")
        close(arcs[0][2], 5.0, "arc radius")
        close(arcs[0][4], 180.0, "arc sweep")
    }

    private open class Sink : HudPath.Sink {
        override fun move(x: Float, y: Float) = Unit
        override fun line(x: Float, y: Float) = Unit
        override fun quad(cx: Float, cy: Float, x: Float, y: Float) = Unit
        override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = Unit
        override fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) = Unit
        override fun close() = Unit
    }

    private fun frame(
        meters: Int,
        lanes: List<LaneGuidanceLane> = emptyList(),
        remainingMeters: Long? = null,
        remainingSeconds: Long? = null,
    ) = HudFrame.from(CarPlayGlance.Snapshot(
        connected = true, maneuverType = 2, maneuverIndex = 0, distanceMeters = meters, road = "На демонстрационную улицу",
        remainingMeters = remainingMeters, remainingSeconds = remainingSeconds,
        lanes = lanes.takeIf { it.isNotEmpty() }?.let { CarPlayGlance.LaneStrip(0, it) },
    ), HudUnits("м", "км", "мин", ','))!!

    @Test fun theLineKeepsItsBaselinesSpacingAndTextRoles() {
        val frame = frame(300, listOf(LaneGuidanceLane(0, 2, 0, 90)), remainingMeters = 4200, remainingSeconds = 720)
        val r = Recording()
        HudDrawing.draw(r, frame, HudSettings(HudSettings.Variant.BALANCED, 215, 100, true, true))
        val text = r.ops.filter { it.type == "text" }
        assertEquals("number/unit/instruction and five trip spans", 8, text.size)
        close(text[0].x, 140.0, "number anchor")
        close(text[0].y, 235.59, "number baseline")
        close(text[1].y, text[0].y.toDouble(), "unit shared baseline")
        close(text[2].y, text[0].y.toDouble(), "instruction shared baseline")
        assertEquals(listOf(500, 400, 400), text.take(3).map { it.weight })
        close(text[3].x, 196.0, "trip follows lane on same row")
        close(text[3].y, 276.81, "trip baseline")
        assertEquals(listOf("12", "\u00a0мин", "\u00a0\u00a0·\u00a0\u00a0", "4,2", "\u00a0км"), text.drop(3).map { it.text })
        assertEquals(listOf(0xfff4fbff.toInt(), 0xffc8e2ec.toInt(), 0xfff4fbff.toInt()), listOf(text[3].color, text[4].color, text[6].color))
    }

    @Test fun aLabelIsCutWithAnEllipsisAndHiddenBelowOneEm() {
        val r = Recording()
        assertTrue(HudDrawing.fit(r, "x", 28f, false, 27f).isEmpty())
        val fit = HudDrawing.fit(r, "Очень длинная инструкция", 28f, false, 100f)
        assertTrue(fit.endsWith("…") && r.measure(fit, 28f, false) <= 100)
        assertEquals("Коротко", HudDrawing.fit(r, "Коротко", 28f, false, 200f))
    }

    @Test fun theDistanceNeverShrinks() {
        val big = Recording()
        val frame = frame(meters = 0).copy(number = "100\u202f000\u202f000", unit = "км")
        HudDrawing.draw(big, frame, HudSettings(HudSettings.Variant.BOLD, 215, 100, false, false))
        val number = big.ops.single { it.text == "100\u202f000\u202f000" }
        close(number.size, 68.0, "distance never shrinks")
    }

    @Test fun aTripThatDoesNotFitIsLeftOutAndChangesNothingElse() {
        val eight = (0 until 8).map { LaneGuidanceLane(it, 0, 0, null) }
        val extreme = frame(300, eight, remainingMeters = 100_000_000_000, remainingSeconds = Long.MAX_VALUE)
        val overflow = Recording()
        HudDrawing.draw(overflow, extreme, HudSettings(HudSettings.Variant.BOLD, 360, 100, true, true))
        assertEquals("primary text retained, trip omitted", 3, overflow.ops.count { it.type == "text" })
        assertEquals("all lanes retained", 8, overflow.ops.count { it.type == "begin" })
        val noTrip = Recording()
        HudDrawing.draw(noTrip, extreme, HudSettings(HudSettings.Variant.BOLD, 360, 100, true, false))
        assertEquals(noTrip.ops.size, overflow.ops.size)
        overflow.ops.zip(noTrip.ops).forEach { (actual, expected) ->
            assertEquals(expected.type, actual.type)
            close(actual.x, expected.x.toDouble(), "overflow keeps x")
            close(actual.y, expected.y.toDouble(), "overflow keeps y")
            close(actual.size, expected.size.toDouble(), "overflow keeps size")
        }
    }
}
