package com.scalpian.marty.offline.vision

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the card in a photo, trying several independent strategies and keeping the best-scoring
 * result:
 *   1. outlines from edge maps (two Canny settings) and from a brightness threshold
 *   2. "paper-seed" segmentation: grow the paper colour found near the middle of the photo, fill the
 *      pips, and fit four straight sides to the blob. Because the four sides are FITTED (not read off
 *      the outline), a corner hidden under a finger is still recovered by extending the other sides.
 * A candidate is scored on size, how much of its inside is paper-bright, and how well its border
 * follows real edges. Weak results return null, and the UI then offers the manual corner editor.
 */
object CardDetector {
    private const val WORK = 1200          // longest side used for detection
    private const val MIN_SCORE = 0.12

    fun detect(bitmap: Bitmap): Quad? {
        val sc = Scope()
        try {
            return with(sc) {
                val full = Mat().keep()
                Utils.bitmapToMat(bitmap, full)
                val k = min(1.0, WORK.toDouble() / max(full.cols(), full.rows()))
                val rgba = Mat().keep()
                if (k < 1.0) Imgproc.resize(full, rgba, Size(), k, k, Imgproc.INTER_AREA) else full.copyTo(rgba)
                val rgb = Mat().keep()
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                val w = rgb.cols()
                val h = rgb.rows()

                val gray = Mat().keep()
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
                val blur = Mat().keep()
                Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

                val rect3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).keep()
                val edges = Mat().keep()
                Imgproc.Canny(blur, edges, 40.0, 120.0)
                val edgesD = Mat().keep()
                Imgproc.dilate(edges, edgesD, rect3)

                val avg = Core.mean(blur).`val`[0]
                val edges2 = Mat().keep()
                Imgproc.Canny(blur, edges2, max(10.0, 0.66 * avg), min(250.0, 1.33 * avg) + 30.0)
                val edges2D = Mat().keep()
                Imgproc.dilate(edges2, edges2D, rect3)

                val otsu = Mat().keep()
                Imgproc.threshold(blur, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, ellipse(7).keep())

                val candidates = ArrayList<Quad>()
                candidates += contourCandidates(edgesD, w, h)
                candidates += contourCandidates(edges2D, w, h)
                candidates += contourCandidates(otsu, w, h)
                candidates += paperSeedCandidates(rgb, edges, w, h, true)
                candidates += paperSeedCandidates(rgb, edges, w, h, false)

                val edgesWide = dil(edges, 9)
                var best: Quad? = null
                var bestScore = -1.0
                for (q in candidates) {
                    val s = score(q, gray, edgesWide, w, h)
                    if (s > bestScore) {
                        bestScore = s
                        best = q
                    }
                }
                if (best == null || bestScore < MIN_SCORE) return@with null
                // back to the coordinates of the original photo
                val inv = (1.0 / k).toFloat()
                Quad(
                    PointF(best.tl.x * inv, best.tl.y * inv), PointF(best.tr.x * inv, best.tr.y * inv),
                    PointF(best.br.x * inv, best.br.y * inv), PointF(best.bl.x * inv, best.bl.y * inv)
                )
            }
        } finally {
            sc.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // candidate generation
    // ------------------------------------------------------------------------------------------

    private fun Scope.contourCandidates(binary: Mat, w: Int, h: Int): List<Quad> {
        val out = ArrayList<Quad>()
        val contours = ArrayList<MatOfPoint>()
        val hier = Mat().keep()
        Imgproc.findContours(binary.clone().keep(), contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        for (c in contours) {
            c.keep()
            val a = Imgproc.contourArea(c)
            if (a < 0.03 * w * h || a > 0.95 * w * h) continue
            val c2f = MatOfPoint2f(*c.toArray()).keep()
            val peri = Imgproc.arcLength(c2f, true)
            val ap = MatOfPoint2f().keep()
            Imgproc.approxPolyDP(c2f, ap, 0.02 * peri, true)
            if (ap.total() == 4L) {
                val pts = ap.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
                if (isConvex(pts)) out.add(Quad.ordered(pts))
            }
            fit4(hullPoints(c))?.let { out.add(it) }
        }
        return out
    }

    private fun Scope.paperSeedCandidates(rgb: Mat, edges: Mat, w: Int, h: Int, cutEdges: Boolean): List<Quad> {
        val out = ArrayList<Quad>()
        val lab = Mat().keep()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val chans = ArrayList<Mat>()
        Core.split(lab, chans)
        chans.forEach { it.keep() }
        val l8 = chans[0]
        val aF = Mat().keep()
        val bF = Mat().keep()
        chans[1].convertTo(aF, CvType.CV_32F, 1.0, -128.0)
        chans[2].convertTo(bF, CvType.CV_32F, 1.0, -128.0)
        val edgeCut = dil(edges, 5)
        val notEdgeCut = not(edgeCut)

        val seeds = listOf(0.5 to 0.5, 0.42 to 0.5, 0.58 to 0.5, 0.5 to 0.42, 0.5 to 0.58)
        for ((fx, fy) in seeds) {
            val cx = (fx * w).toInt()
            val cy = (fy * h).toInt()
            val r = (0.05 * min(w, h)).toInt()
            val x0 = max(cx - r, 0)
            val y0 = max(cy - r, 0)
            val rect = Rect(x0, y0, min(2 * r, w - x0), min(2 * r, h - y0))
            if (rect.width < 8 || rect.height < 8) continue
            val n = rect.width * rect.height
            val lb = ByteArray(n)
            val ab = ByteArray(n)
            val bb = ByteArray(n)
            chans[0].submat(rect).clone().keep().get(0, 0, lb)
            chans[1].submat(rect).clone().keep().get(0, 0, ab)
            chans[2].submat(rect).clone().keep().get(0, 0, bb)
            val ls = IntArray(n) { lb[it].toInt() and 0xFF }
            val sortedL = ls.sorted()
            val thr = sortedL[(0.45 * (n - 1)).toInt()]           // skip dark print inside the seed window
            val keepIdx = (0 until n).filter { ls[it] >= thr }
            if (keepIdx.isEmpty()) continue
            val seedL = median(keepIdx.map { ls[it].toDouble() })
            val seedA = median(keepIdx.map { (ab[it].toInt() and 0xFF) - 128.0 })
            val seedB = median(keepIdx.map { (bb[it].toInt() and 0xFF) - 128.0 })

            val da = plusVal(aF, -seedA)
            val db = plusVal(bF, -seedB)
            val d = magnitude(da, db)
            val nearColour = lessThan(d, 9.0)
            val lOk = Mat().keep()
            Core.inRange(l8, Scalar(0.72 * seedL), Scalar(1.25 * seedL), lOk)
            var m = and(nearColour, lOk)
            if (cutEdges) m = and(m, notEdgeCut)          // card border edges separate it from a similar background
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, ellipse(5).keep())
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, ellipse(9).keep())

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(m.clone().keep(), contours, Mat().keep(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            var best: MatOfPoint? = null
            var bestArea = 0.0
            for (c in contours) {
                c.keep()
                val ar = Imgproc.contourArea(c)
                val inside = Imgproc.pointPolygonTest(MatOfPoint2f(*c.toArray()).keep(), Point(cx.toDouble(), cy.toDouble()), false) >= 0
                if ((inside || ar > 0.05 * w * h) && ar > bestArea && ar < 0.95 * w * h) {
                    best = c
                    bestArea = ar
                }
            }
            val bc = best ?: continue
            var blob = Mat.zeros(h, w, CvType.CV_8UC1).keep()
            Imgproc.drawContours(blob, listOf(bc), -1, Scalar(255.0), -1)          // filled: pips are no longer holes
            if (cutEdges) blob = dil(blob, 5)                                         // give back the pixels removed with the edges
            val contours2 = ArrayList<MatOfPoint>()
            Imgproc.findContours(blob.clone().keep(), contours2, Mat().keep(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val biggest = contours2.maxByOrNull { Imgproc.contourArea(it) } ?: continue
            biggest.keep()
            fit4(hullPoints(biggest))?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // geometry
    // ------------------------------------------------------------------------------------------

    private fun Scope.hullPoints(cnt: MatOfPoint): List<Point> {
        val idx = MatOfInt().keep()
        Imgproc.convexHull(cnt, idx)
        val pts = cnt.toArray()
        return idx.toArray().map { pts[it] }
    }

    private class Line(val vx: Double, val vy: Double, val x0: Double, val y0: Double)

    private class Edge(val a: Point, val b: Point, val len: Double, val ang: Double)

    /**
     * Fits four straight sides to a convex outline, even if a corner is cut off by a finger: each
     * edge of the simplified outline is assigned to one of the card's four sides by its direction,
     * the sides' lines are fitted, and adjacent lines are intersected to get the corners.
     */
    private fun Scope.fit4(hull: List<Point>): Quad? {
        if (hull.size < 4) return null
        val hull2f = MatOfPoint2f(*hull.toTypedArray()).keep()
        val peri = Imgproc.arcLength(hull2f, true)
        val approx = MatOfPoint2f().keep()
        Imgproc.approxPolyDP(hull2f, approx, 0.008 * peri, true)
        val poly = approx.toArray()
        val n = poly.size
        if (n < 4) return null

        val edges = (0 until n).map { i ->
            val a = poly[i]
            val b = poly[(i + 1) % n]
            val ang = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
            Edge(a, b, hypot(b.x - a.x, b.y - a.y), ((ang % 360.0) + 360.0) % 360.0)
        }
        val th0 = edges.maxByOrNull { it.len }!!.ang
        val sides = Array(4) { ArrayList<Edge>() }
        for (e in edges) {
            val rel = (((e.ang - th0) % 360.0) + 360.0) % 360.0
            val k = (((rel + 45.0) % 360.0) / 90.0).toInt().coerceIn(0, 3)
            val off = ((rel + 45.0) % 90.0) - 45.0
            if (abs(off) <= 30.0 && e.len > 0.04 * peri) sides[k].add(e)
        }
        if (sides.any { it.isEmpty() }) return null

        val lines = sides.map { side ->
            val pts = ArrayList<Point>()
            for (e in side) {
                val m = max(3, (e.len / 10.0).toInt())
                for (i in 0 until m) {
                    val t = i / (m - 1.0)
                    pts.add(Point(e.a.x + (e.b.x - e.a.x) * t, e.a.y + (e.b.y - e.a.y) * t))
                }
            }
            val line = Mat().keep()
            Imgproc.fitLine(MatOfPoint2f(*pts.toTypedArray()).keep(), line, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
            Line(line.get(0, 0)[0], line.get(1, 0)[0], line.get(2, 0)[0], line.get(3, 0)[0])
        }
        val corners = (0 until 4).map { intersect(lines[it], lines[(it + 1) % 4]) ?: return null }
        return Quad.ordered(corners)
    }

    private fun intersect(l1: Line, l2: Line): PointF? {
        val det = l1.vx * (-l2.vy) - (-l2.vx) * l1.vy
        if (abs(det) < 1e-6) return null
        val bx = l2.x0 - l1.x0
        val by = l2.y0 - l1.y0
        // solve [vx1 -vx2; vy1 -vy2] * [t; s] = [bx; by]
        val t = (bx * (-l2.vy) - (-l2.vx) * by) / det
        return PointF((l1.x0 + t * l1.vx).toFloat(), (l1.y0 + t * l1.vy).toFloat())
    }

    private fun isConvex(p: List<PointF>): Boolean {
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    private fun polyArea(q: Quad): Double {
        val p = q.points
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2.0
    }

    private fun validQuad(q: Quad, w: Int, h: Int): Boolean {
        val p = q.points
        if (!isConvex(p)) return false
        val area = polyArea(q)
        if (area < 0.025 * w * h || area > 0.95 * w * h) return false
        if (p.any { it.x < -0.03f * w || it.x > 1.03f * w || it.y < -0.03f * h || it.y > 1.03f * h }) return false
        val ew = (Quad.dist(q.tl, q.tr) + Quad.dist(q.bl, q.br)) / 2f
        val eh = (Quad.dist(q.tl, q.bl) + Quad.dist(q.tr, q.br)) / 2f
        val ratio = min(ew, eh) / max(ew, eh)
        return ratio in 0.5f..0.9f
    }

    private fun Scope.score(q: Quad, gray: Mat, edgesWide: Mat, w: Int, h: Int): Double {
        if (!validQuad(q, w, h)) return -1.0
        val area = polyArea(q) / (w.toDouble() * h)
        val mask = Mat.zeros(h, w, CvType.CV_8UC1).keep()
        Imgproc.fillConvexPoly(
            mask,
            MatOfPoint(*q.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()).keep(),
            Scalar(255.0)
        )
        val inner = ero(mask, max(5, (0.04 * min(w, h)).toInt()))
        val n = Core.countNonZero(inner)
        if (n == 0) return -1.0
        val meanGray = Core.mean(gray, inner).`val`[0]
        val brightInside = Core.countNonZero(and(greaterThan(gray, 0.85 * meanGray), inner))
        val pf = brightInside.toDouble() / n
        if (pf < 0.5) return -1.0

        var ok = 0
        var tot = 0
        val p = q.points
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            for (s in 0 until 60) {
                val t = s / 59f
                val x = (a.x + (b.x - a.x) * t).toInt()
                val y = (a.y + (b.y - a.y) * t).toInt()
                if (x in 0 until w && y in 0 until h) {
                    tot++
                    if (edgesWide.get(y, x)[0] > 0) ok++
                }
            }
        }
        val es = ok.toDouble() / max(tot, 1)
        return sqrt(area) * pf * (0.25 + 0.75 * es)
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return s[s.size / 2]
    }
}
