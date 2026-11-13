package com.shilapi.xcertplay.hud

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneGlyphTest {
    private val all = HudLaneDirection.entries
    private val sharp = setOf(HudLaneDirection.SHARP_LEFT, HudLaneDirection.SHARP_RIGHT)

    private fun subsets(): List<List<HudLaneDirection>> =
        (1 until (1 shl all.size)).map { mask -> all.filter { mask and (1 shl it.ordinal) != 0 } }

    /** Every coordinate a path passes through or bends towards; arcs add their whole circle's box. */
    private fun points(path: HudPath): List<DoubleArray> {
        val points = mutableListOf<DoubleArray>()
        path.replay(object : HudPath.Sink {
            override fun move(x: Float, y: Float) { points += doubleArrayOf(x.toDouble(), y.toDouble()) }
            override fun line(x: Float, y: Float) { points += doubleArrayOf(x.toDouble(), y.toDouble()) }
            override fun quad(cx: Float, cy: Float, x: Float, y: Float) {
                points += doubleArrayOf(cx.toDouble(), cy.toDouble()); points += doubleArrayOf(x.toDouble(), y.toDouble())
            }
            override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
                points += doubleArrayOf(x.toDouble(), y.toDouble())
            }
            override fun arc(cx: Float, cy: Float, radius: Float, start: Float, sweep: Float) {
                points += doubleArrayOf((cx - radius).toDouble(), (cy - radius).toDouble())
                points += doubleArrayOf((cx + radius).toDouble(), (cy + radius).toDouble())
            }
            override fun close() = Unit
        })
        return points
    }

    /** A head's three corners: the control points of its rounded corners. */
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

    private fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    private fun inside(p: DoubleArray, t: List<DoubleArray>): Boolean {
        val d = (0..2).map { cross(t[it], t[(it + 1) % 3], p) }
        return d.all { it >= 0 } || d.all { it <= 0 }
    }

    private fun toSegment(p: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val t = (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)
    }

    /** Zero when the triangles overlap, else the shortest distance between them. */
    private fun gap(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.any { inside(it, b) } || b.any { inside(it, a) }) return 0.0
        fun edges(t: List<DoubleArray>) = (0..2).map { t[it] to t[(it + 1) % 3] }
        val ab = a.flatMap { p -> edges(b).map { (s, e) -> toSegment(p, s, e) } }
        val ba = b.flatMap { p -> edges(a).map { (s, e) -> toSegment(p, s, e) } }
        return (ab + ba).min()
    }

    @Test fun everyLaneHasABranchForEachOfItsDirectionsAndNoOther() {
        for (lane in subsets()) {
            val glyph = LaneGlyph.of(lane)
            for (direction in all) {
                if (direction in lane) assertNotNull("$lane $direction", glyph[direction.ordinal]) else assertNull("$lane $direction", glyph[direction.ordinal])
            }
        }
    }

    @Test fun everyBranchStartsAtTheBottomAndStaysOnTheGrid() {
        for (lane in subsets()) {
            for (branch in LaneGlyph.of(lane).filterNotNull()) {
                assertTrue("$lane starts at the bottom: ${branch[0].data}", branch[0].data.matches(Regex("M\\d+(\\.\\d+)? 22\\.5 .*")))
                for (p in branch.flatMap(::points)) assertTrue("$lane ${branch[0].data} at ${p.toList()}", p[0] in 0.0..24.0 && p[1] in 0.0..24.0)
            }
        }
    }

    private val left = setOf(HudLaneDirection.UTURN_LEFT, HudLaneDirection.LEFT, HudLaneDirection.SLIGHT_LEFT)
    private val right = setOf(HudLaneDirection.UTURN_RIGHT, HudLaneDirection.RIGHT, HudLaneDirection.SLIGHT_RIGHT)

    @Test fun noTwoHeadsMeetInALaneWithoutSharpTurnsAndAtMostTwoBranchesToASide() {
        val failures = mutableListOf<String>()
        for (lane in subsets().filter { lane -> lane.none(sharp::contains) && lane.count(left::contains) <= 2 && lane.count(right::contains) <= 2 }) {
            val glyph = LaneGlyph.of(lane)
            for (i in lane.indices) for (j in i + 1 until lane.size) {
                val gap = gap(corners(glyph[lane[i].ordinal]!![1]), corners(glyph[lane[j].ordinal]!![1]))
                if (gap < .5) failures += String.format(Locale.US, "%s: %s and %s %.2f apart", lane, lane[i], lane[j], gap)
            }
        }
        assertEquals("", failures.joinToString("\n"))
    }

    @Test fun aSharpTurnKeepsApartFromAUTurnOrASlightTurnToTheSameSide() {
        val failures = mutableListOf<String>()
        for ((sharp, other) in listOf(
            HudLaneDirection.SHARP_LEFT to HudLaneDirection.UTURN_LEFT, HudLaneDirection.SHARP_RIGHT to HudLaneDirection.UTURN_RIGHT,
            HudLaneDirection.SHARP_LEFT to HudLaneDirection.SLIGHT_LEFT, HudLaneDirection.SHARP_RIGHT to HudLaneDirection.SLIGHT_RIGHT,
        )) for (lane in listOf(listOf(sharp, other), listOf(sharp, other, HudLaneDirection.STRAIGHT))) {
            val glyph = LaneGlyph.of(lane)
            val gap = gap(corners(glyph[sharp.ordinal]!![1]), corners(glyph[other.ordinal]!![1]))
            if (gap < .5) failures += String.format(Locale.US, "%s %.2f apart", lane, gap)
        }
        assertEquals("", failures.joinToString("\n"))
    }

    @Test fun aTurnAndAUTurnToTheSameSideShareTheBendAndTheUTurnDropsBelowTheTurn() {
        val glyph = LaneGlyph.of(listOf(HudLaneDirection.UTURN_LEFT, HudLaneDirection.LEFT))
        val turn = glyph[HudLaneDirection.LEFT.ordinal]!!
        val uturn = glyph[HudLaneDirection.UTURN_LEFT.ordinal]!!
        val bend = turn[0].data.substringBefore(" H")
        assertTrue("${uturn[0].data} continues $bend", uturn[0].data.startsWith("$bend "))
        assertTrue(corners(uturn[1]).minOf { it[1] } > corners(turn[1]).maxOf { it[1] })
    }

    @Test fun aSingleDirectionIsCenteredOnTheGrid() {
        for (direction in all.filter { it != HudLaneDirection.UTURN_LEFT && it != HudLaneDirection.UTURN_RIGHT }) {
            assertTrue(LaneGlyph.of(listOf(direction))[direction.ordinal]!![0].data.startsWith("M12 22.5 "))
        }
    }

    /** Each stroke and fill as "kind color width", the width in grid units. */
    private fun drawn(lane: HudLane): List<String> {
        val ops = mutableListOf<String>()
        val svg = object : HudCanvas by Svg() {
            override fun path(path: HudPath, x: Double, y: Double, scale: Double, width: Double, color: Int, opacity: Double) {
                ops += String.format(Locale.US, "path %08X %.2f", color, width)
            }
            override fun fill(path: HudPath, x: Double, y: Double, scale: Double, color: Int) {
                ops += String.format(Locale.US, "fill %08X", color)
            }
            override fun beginOpacity(opacity: Double) = Unit
            override fun endOpacity() = Unit
        }
        HudDrawing.lane(svg, lane, 0.0, 0.0, 24.0)
        return ops
    }

    @Test fun aHighlightedBranchIsDrawnLastOverABlackRimAndTheOthersAreThinnerAndMuted() {
        val lane = listOf(HudLaneDirection.UTURN_LEFT, HudLaneDirection.LEFT)
        assertEquals(listOf(
            "path FFC8E2EC 2.56", "fill FFC8E2EC",
            "path FF000000 4.96", "path FF000000 2.40",
            "path FF54D5FF 2.56", "fill FF54D5FF",
        ), drawn(HudLane(lane, HudLaneDirection.LEFT, true)))
    }

    @Test fun aPreferredLaneWithoutAHighlightIsCyanWholeAndASingleArrowHasNoRim() {
        val lane = listOf(HudLaneDirection.LEFT, HudLaneDirection.STRAIGHT)
        assertEquals(List(2) { listOf("path FF54D5FF 2.56", "fill FF54D5FF") }.flatten(), drawn(HudLane(lane, null, true)))
        assertEquals(listOf("path FF54D5FF 3.20", "fill FF54D5FF"), drawn(HudLane(listOf(HudLaneDirection.LEFT), HudLaneDirection.LEFT, true)))
        assertEquals(listOf("path FFAEC6D0 2.24", "fill FFAEC6D0").let { it + it }, drawn(HudLane(lane, null, false)))
    }

    @Test fun aHighlightOutsideTheDirectionsJoinsTheLaneInsteadOfFailing() {
        val ops = drawn(HudLane(listOf(HudLaneDirection.STRAIGHT), HudLaneDirection.RIGHT, true))
        assertEquals(listOf("path FFC8E2EC 2.56", "fill FFC8E2EC", "path FF000000 4.96", "path FF000000 2.40", "path FF54D5FF 2.56", "fill FF54D5FF"), ops)
    }

    // The catalogue for a manual look: every lane of one, two and three directions in its roles, as SVG pages.

    private class Svg : HudCanvas {
        val body = StringBuilder()
        private fun color(value: Int) = String.format(Locale.US, "#%06X", value and 0xffffff)
        override fun measure(value: String, size: Float, bold: Boolean) = throw UnsupportedOperationException()
        override fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean) = throw UnsupportedOperationException()
        override fun measure(value: String, size: Float, weight: Int, tracking: Float) = throw UnsupportedOperationException()
        override fun text(value: String, x: Float, baseline: Float, size: Float, color: Int, weight: Int, tracking: Float) = throw UnsupportedOperationException()
        override fun line(points: FloatArray, width: Float, color: Int) = throw UnsupportedOperationException()
        override fun circle(x: Float, y: Float, radius: Float, width: Float, color: Int) = throw UnsupportedOperationException()
        override fun rule(x: Double, top: Double, bottom: Double, width: Double, color: Int, opacity: Double, dash: Double, gap: Double) = throw UnsupportedOperationException()
        override fun dot(cx: Double, cy: Double, radius: Double, x: Double, y: Double, scale: Double, color: Int) = throw UnsupportedOperationException()
        override fun path(path: HudPath, x: Double, y: Double, scale: Double, width: Double, color: Int, opacity: Double) {
            body.append(String.format(Locale.US, "<path transform=\"translate(%.3f %.3f) scale(%.4f)\" d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.3f\" stroke-opacity=\"%.3f\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n",
                x, y, scale, path.data, color(color), width, opacity))
        }
        override fun fill(path: HudPath, x: Double, y: Double, scale: Double, color: Int) {
            body.append(String.format(Locale.US, "<path transform=\"translate(%.3f %.3f) scale(%.4f)\" d=\"%s\" fill=\"%s\"/>\n", x, y, scale, path.data, color(color)))
        }
        override fun beginOpacity(opacity: Double) { body.append(String.format(Locale.US, "<g opacity=\"%.3f\">\n", opacity)) }
        override fun endOpacity() { body.append("</g>\n") }
    }

    private val names = mapOf(
        HudLaneDirection.UTURN_LEFT to "разворот влево", HudLaneDirection.SHARP_LEFT to "резко влево", HudLaneDirection.LEFT to "влево",
        HudLaneDirection.SLIGHT_LEFT to "плавно влево", HudLaneDirection.STRAIGHT to "прямо", HudLaneDirection.SLIGHT_RIGHT to "плавно вправо",
        HudLaneDirection.RIGHT to "вправо", HudLaneDirection.SHARP_RIGHT to "резко вправо", HudLaneDirection.UTURN_RIGHT to "разворот вправо",
    )

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")

    /** One A4 landscape page: a title and a grid of groups, each a caption over a lane drawn in several roles. */
    private fun page(title: String, groups: List<Pair<String, List<HudLane>>>, columns: Int, size: Double): String {
        val width = 842.0; val height = 595.0; val margin = 24.0
        val groupWidth = (width - 2 * margin) / columns
        val rowHeight = size * 1.5 + 22
        val out = StringBuilder()
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${width.toInt()}pt\" height=\"${height.toInt()}pt\" viewBox=\"0 0 $width $height\">\n")
        out.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
        out.append("<text x=\"$margin\" y=\"${margin + 6}\" font-family=\"Helvetica\" font-size=\"13\" font-weight=\"bold\" fill=\"#111\">${escape(title)}</text>\n")
        groups.forEachIndexed { index, (caption, lanes) ->
            val gx = margin + (index % columns) * groupWidth
            val gy = margin + 22 + (index / columns) * rowHeight
            out.append(String.format(Locale.US, "<text x=\"%.1f\" y=\"%.1f\" font-family=\"Helvetica\" font-size=\"8\" fill=\"#333\">%s</text>\n", gx + 4, gy + 9, escape(caption)))
            val pitch = size * 1.3
            out.append(String.format(Locale.US, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"4\" fill=\"#000\"/>\n",
                gx + 4, gy + 13, pitch * lanes.size + size * .2, size * 1.25))
            lanes.forEachIndexed { i, lane ->
                val svg = Svg()
                HudDrawing.lane(svg, lane, gx + 4 + size * .25 + i * pitch, gy + 13 + size * .08, size)
                out.append(svg.body)
            }
        }
        out.append("</svg>\n")
        return out.toString()
    }

    /** The lane as another lane, then as the preferred lane with each of its directions highlighted in turn. */
    private fun roles(lane: List<HudLaneDirection>) =
        listOf(HudLane(lane, null, false)) + lane.map { HudLane(lane, it, true) }

    @Test fun theCatalogueIsWrittenAsSvgPages() {
        val directory = File("build/reports/lane-glyphs").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val caption = { lane: List<HudLaneDirection> -> lane.joinToString(" + ") { names.getValue(it) } }
        val pages = mutableListOf<String>()
        pages += page("Одно направление: другая полоса, рекомендуемая полоса", all.map { caption(listOf(it)) to roles(listOf(it)) }, 5, 44.0)
        val pairs = subsets().filter { it.size == 2 }
        pairs.chunked(18).forEachIndexed { i, chunk ->
            pages += page("Два направления (${i + 1}/${(pairs.size + 17) / 18}): другая полоса, затем подсвечено каждое направление",
                chunk.map { caption(it) to roles(it) }, 3, 44.0)
        }
        val triples = subsets().filter { it.size == 3 }
        triples.chunked(18).forEachIndexed { i, chunk ->
            pages += page("Три направления (${i + 1}/${(triples.size + 17) / 18}): другая полоса, затем подсвечено каждое направление",
                chunk.map { caption(it) to roles(it) }, 3, 38.0)
        }
        val many = listOf(
            listOf(HudLaneDirection.UTURN_LEFT, HudLaneDirection.LEFT, HudLaneDirection.STRAIGHT, HudLaneDirection.RIGHT),
            listOf(HudLaneDirection.LEFT, HudLaneDirection.STRAIGHT, HudLaneDirection.RIGHT, HudLaneDirection.UTURN_RIGHT),
            listOf(HudLaneDirection.UTURN_LEFT, HudLaneDirection.LEFT, HudLaneDirection.SLIGHT_LEFT, HudLaneDirection.STRAIGHT),
            listOf(HudLaneDirection.SLIGHT_LEFT, HudLaneDirection.STRAIGHT, HudLaneDirection.SLIGHT_RIGHT, HudLaneDirection.RIGHT),
            listOf(HudLaneDirection.LEFT, HudLaneDirection.SLIGHT_LEFT, HudLaneDirection.STRAIGHT, HudLaneDirection.SLIGHT_RIGHT, HudLaneDirection.RIGHT),
            listOf(HudLaneDirection.UTURN_LEFT, HudLaneDirection.LEFT, HudLaneDirection.STRAIGHT, HudLaneDirection.RIGHT, HudLaneDirection.UTURN_RIGHT),
        )
        pages += page("Четыре и пять направлений", many.map { caption(it) to roles(it) }, 2, 44.0)
        pages.forEachIndexed { i, svg -> File(directory, String.format(Locale.US, "page-%02d.svg", i + 1)).writeText(svg) }
        assertEquals(1 + 2 + 5 + 1, pages.size)
    }
}
