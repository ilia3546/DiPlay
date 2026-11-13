package com.shilapi.xcertplay.hud

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundaboutTest {
    /** The tip of a head: the corner its first rounded corner bends round. */
    private fun tip(head: HudPath): DoubleArray {
        var tip: DoubleArray? = null
        head.replay(object : HudPath.Sink {
            override fun move(x: Float, y: Float) = Unit
            override fun line(x: Float, y: Float) = Unit
            override fun quad(cx: Float, cy: Float, x: Float, y: Float) { if (tip == null) tip = doubleArrayOf(cx.toDouble(), cy.toDouble()) }
            override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = Unit
            override fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) = Unit
            override fun close() = Unit
        })
        return tip!!
    }

    @Test fun theFirstThreeExitsGoRoundCounterClockwiseToTheSideAheadAndTheOtherSide() {
        val right = tip(Roundabout.of(1, false)[1])
        val ahead = tip(Roundabout.of(2, false)[1])
        val left = tip(Roundabout.of(3, false)[1])
        assertTrue(right.toList().toString(), right[0] > 20 && right[1] == 12.5)
        assertTrue(ahead.toList().toString(), ahead[0] == 12.0 && ahead[1] < 4)
        assertTrue(left.toList().toString(), left[0] < 4 && left[1] == 12.5)
        for (exit in 1..3) {
            val way = Roundabout.of(exit, false)[0].data
            assertTrue(way, way.startsWith("M12 22.5 V17.5 A5 5 0 0 0 17 12.5"))
            assertEquals("a quarter turn per exit: $way", exit, Regex(" A").findAll(way).count())
        }
    }

    /** Every corner of a head's triangle: the control points of its rounded corners. */
    private fun corners(head: HudPath): List<DoubleArray> {
        val corners = mutableListOf<DoubleArray>()
        head.replay(object : HudPath.Sink {
            override fun move(x: Float, y: Float) = Unit
            override fun line(x: Float, y: Float) = Unit
            override fun quad(cx: Float, cy: Float, x: Float, y: Float) { corners += doubleArrayOf(cx.toDouble(), cy.toDouble()) }
            override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = Unit
            override fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) = Unit
            override fun close() = Unit
        })
        return corners
    }

    @Test fun anExitHeadClearsTheRingByMoreThanItsRimAtTheBoldestStroke() {
        val halfStroke = 24.0 * 6 / 56 / 2
        for (clockwise in listOf(false, true)) for (exit in 1..3) for (corner in corners(Roundabout.of(exit, clockwise)[1])) {
            val gap = Math.hypot(corner[0] - 12, corner[1] - 12.5) - 5 - halfStroke
            assertTrue("exit $exit clockwise $clockwise ${corner.toList()} only $gap from the ring", gap > Roundabout.RIM + .2)
        }
    }

    @Test fun whereTrafficKeepsLeftTheWayRoundIsMirrored() {
        for (exit in 1..3) {
            val right = tip(Roundabout.of(exit, false)[1])
            val left = tip(Roundabout.of(exit, true)[1])
            assertEquals(24 - right[0], left[0], 1e-6)
            assertEquals(right[1], left[1], 1e-6)
            assertTrue(Roundabout.of(exit, true)[0].data.contains(" A5 5 0 0 1 "))
        }
    }

    @Test fun laterExitsAreThreeArrowsChasingRoundInTheDirectionOfTraffic() {
        for (exit in 4..19) assertEquals(6, Roundabout.of(exit, false).size)
        for (clockwise in listOf(false, true)) {
            val sign = Roundabout.of(4, clockwise)
            for (i in 0..2) {
                assertTrue(sign[2 * i].data, sign[2 * i].data.contains(" 0 0 ${if (clockwise) 1 else 0} "))
                val at = tip(sign[2 * i + 1])
                assertEquals("tip back on the circle", 7.5, Math.hypot(at[0] - 12, at[1] - 12), .05)
            }
        }
    }

    // The catalogue for a manual look: every sign on the head-up display and on the dashboard card, as SVG pages.

    private class Svg : HudCanvas {
        val body = StringBuilder()
        private fun rgb(value: Int) = String.format(Locale.US, "#%06X", value and 0xffffff)
        private fun alpha(value: Int) = (value ushr 24) / 255.0
        override fun measure(value: String, size: Float, bold: Boolean) = throw UnsupportedOperationException()
        override fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean) = throw UnsupportedOperationException()
        override fun measure(value: String, size: Float, weight: Int, tracking: Float) = throw UnsupportedOperationException()
        override fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, weight: Int, tracking: Float) = throw UnsupportedOperationException()
        override fun line(points: FloatArray, width: Float, color: Int) = throw UnsupportedOperationException()
        override fun circle(x: Float, y: Float, radius: Float, width: Float, color: Int) = throw UnsupportedOperationException()
        override fun rule(x: Double, top: Double, bottom: Double, width: Double, color: Int, opacity: Double, dash: Double, gap: Double) = throw UnsupportedOperationException()
        override fun dot(cx: Double, cy: Double, radius: Double, x: Double, y: Double, scale: Double, color: Int) {
            body.append(String.format(Locale.US, "<circle cx=\"%.3f\" cy=\"%.3f\" r=\"%.3f\" fill=\"%s\" fill-opacity=\"%.3f\"/>\n",
                x + cx * scale, y + cy * scale, radius * scale, rgb(color), alpha(color)))
        }
        override fun path(path: HudPath, x: Double, y: Double, scale: Double, width: Double, color: Int, opacity: Double) {
            body.append(String.format(Locale.US, "<path transform=\"translate(%.3f %.3f) scale(%.4f)\" d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.3f\" stroke-opacity=\"%.3f\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n",
                x, y, scale, path.data, rgb(color), width, opacity * alpha(color)))
        }
        override fun fill(path: HudPath, x: Double, y: Double, scale: Double, color: Int) {
            body.append(String.format(Locale.US, "<path transform=\"translate(%.3f %.3f) scale(%.4f)\" d=\"%s\" fill=\"%s\" fill-opacity=\"%.3f\"/>\n",
                x, y, scale, path.data, rgb(color), alpha(color)))
        }
        override fun beginOpacity(opacity: Double) { body.append(String.format(Locale.US, "<g opacity=\"%.3f\">\n", opacity)) }
        override fun endOpacity() { body.append("</g>\n") }
    }

    private class Sign(val caption: String, val kind: HudManeuver, val exit: Int = 0, val clockwise: Boolean = false)

    private val names = mapOf(
        HudManeuver.UNKNOWN to "неизвестно", HudManeuver.NO_TURN to "без поворота", HudManeuver.STRAIGHT to "прямо",
        HudManeuver.LEFT to "влево", HudManeuver.RIGHT to "вправо", HudManeuver.SLIGHT_LEFT to "плавно влево",
        HudManeuver.SLIGHT_RIGHT to "плавно вправо", HudManeuver.SHARP_LEFT to "резко влево", HudManeuver.SHARP_RIGHT to "резко вправо",
        HudManeuver.UTURN to "разворот", HudManeuver.KEEP_LEFT to "держаться левее", HudManeuver.KEEP_RIGHT to "держаться правее",
        HudManeuver.EXIT_LEFT to "съезд влево", HudManeuver.EXIT_RIGHT to "съезд вправо", HudManeuver.ROUNDABOUT_ENTER to "въезд на круг",
        HudManeuver.ROUNDABOUT_EXIT to "съезд с круга", HudManeuver.FERRY_ENTER to "на паром", HudManeuver.FERRY_EXIT to "с парома",
        HudManeuver.ARRIVE to "прибытие",
    )

    private fun signs(): List<Sign> = HudManeuver.entries.map { Sign(names.getValue(it), it) } +
        listOf(1, 2, 3, 4, 5, 10, 19).map { Sign("круг, съезд $it", HudManeuver.ROUNDABOUT_EXIT, it) } +
        listOf(1, 2, 3, 4).map { Sign("левостороннее, съезд $it", HudManeuver.ROUNDABOUT_EXIT, it, true) }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")

    /** One A4 landscape page of tiles, each a sign drawn as [draw] does over a caption; a cell is a group named by its caption. */
    private fun page(title: String, tile: Int, draw: (Svg, Sign, Double, Double) -> Unit): String {
        val width = 842.0; val height = 595.0; val margin = 24.0; val columns = 8
        val cell = (width - 2 * margin) / columns
        val out = StringBuilder()
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${width.toInt()}pt\" height=\"${height.toInt()}pt\" viewBox=\"0 0 $width $height\">\n")
        out.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
        out.append("<text x=\"$margin\" y=\"${margin + 6}\" font-family=\"Helvetica\" font-size=\"13\" font-weight=\"bold\" fill=\"#111\">${escape(title)}</text>\n")
        signs().forEachIndexed { index, sign ->
            val gx = margin + (index % columns) * cell
            val gy = margin + 22 + (index / columns) * 112.0
            out.append(String.format(Locale.US, "<g data-caption=\"%s\" transform=\"translate(%.1f %.1f)\">\n", escape(sign.caption), gx, gy))
            out.append(String.format(Locale.US, "<rect x=\"6\" y=\"0\" width=\"84\" height=\"84\" rx=\"10\" fill=\"%s\"/>\n", String.format(Locale.US, "#%06X", tile and 0xffffff)))
            val svg = Svg()
            draw(svg, sign, 48.0, 42.0)
            out.append(svg.body)
            out.append(String.format(Locale.US, "<text x=\"48\" y=\"98\" text-anchor=\"middle\" font-family=\"Helvetica\" font-size=\"8\" fill=\"#333\">%s</text>\n</g>\n", escape(sign.caption)))
        }
        out.append("</svg>\n")
        return out.toString()
    }

    @Test fun theCatalogueIsWrittenAsSvgPages() {
        val directory = File("build/reports/maneuver-icons").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val hud = page("HUD: знак манёвра 56 px, линия 6 px, голубой на чёрном; кольцо бледное, путь с чёрной обводкой", 0xff000000.toInt()) { svg, sign, x, y ->
            ManeuverIcon.draw(svg, sign.kind, x, y, 64.0, 64.0 * 6 / 56, 0xff54d5ff.toInt(), sign.exit, sign.clockwise, 0xff000000.toInt())
        }
        val dashboard = page("Приборка: карточка манёвра, белый на синей плитке; кольцо полупрозрачное, обводка цветом плитки", 0xff2f7bff.toInt()) { svg, sign, x, y ->
            ManeuverIcon.draw(svg, sign.kind, x, y, 64.0, 64.0 * 6 / 56, 0xffffffff.toInt(), sign.exit, sign.clockwise, 0xff2f7bff.toInt())
        }
        File(directory, "page-01.svg").writeText(hud)
        File(directory, "page-02.svg").writeText(dashboard)
        assertEquals(2, directory.listFiles()!!.size)
    }
}
