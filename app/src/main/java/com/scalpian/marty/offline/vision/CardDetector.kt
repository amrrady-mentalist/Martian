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
 * Finds the card in a photo.
 *
 * 1. Several independent strategies each propose candidate quadrilaterals: outlines from two edge maps
 *    and a brightness threshold, plus "paper seeds" - the paper colour found near the middle of the
 *    photo is grown into a blob (using colour, brightness, SMOOTHNESS, and optionally the edge map to
 *    keep it from leaking into a similar-coloured wall), the pips are filled, and four straight sides are
 *    FITTED to the blob. Fitted sides recover a corner hidden under a finger.
 * 2. Every candidate is scored: size, how much of its inside is paper-bright, how well its border follows
 *    edges, and - important - every one of its four sides must sit on a real brightness step.
 * 3. The winner's sides are snapped onto the true card edge (refineQuad), so a slightly-off quad is
 *    corrected before anything is edited.
 * Weak results return null and the UI offers the manual corner editor.
 */
object CardDetector {
    private const val WORK = 1200          // longest side used for detection
    private const val MIN_SCORE = 0.11

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
                val gBlurF = Mat().keep()
                Imgproc.GaussianBlur(gray, gBlurF, Size(0.0, 0.0), 1.5)
                gBlurF.convertTo(gBlurF, CvType.CV_32F)
                val gData = FloatArray(w * h)
                gBlurF.get(0, 0, gData)

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
                candidates += paperSeedCandidates(rgb, edges, w, h)

                val edgesWide = dil(edges, 9)
                val wideData = ByteArray(w * h)
                edgesWide.get(0, 0, wideData)

                var best: Quad? = null
                var bestScore = -1.0
                for (q in candidates) {
                    val s = score(q, gray, gData, wideData, w, h)
                    if (s > bestScore) {
                        bestScore = s
                        best = q
                    }
                }
                if (best == null || bestScore < MIN_SCORE) return@with null

                val snapped = refineQuad(rgb, best)
                // back to the coordinates of the original photo
                val inv = (1.0 / k).toFloat()
                Quad(
                    PointF(snapped.tl.x * inv, snapped.tl.y * inv), PointF(snapped.tr.x * inv, snapped.tr.y * inv),
                    PointF(snapped.br.x * inv, snapped.br.y * inv), PointF(snapped.bl.x * inv, snapped.bl.y * inv)
                )
            }
        } finally {
            sc.close()
        }
    }

    /** Public for the editor: snap the sides of a hand-placed quad onto the real card edge. */
    fun snap(bitmap: Bitmap, quad: Quad): Quad {
        val sc = Scope()
        try {
            return with(sc) {
                val full = Mat().keep()
                Utils.bitmapToMat(bitmap, full)
                val rgb = Mat().keep()
                Imgproc.cvtColor(full, rgb, Imgproc.COLOR_RGBA2RGB)
                refineQuad(rgb, quad)
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

    private fun Scope.paperSeedCandidates(rgb: Mat, edges: Mat, w: Int, h: Int): List<Quad> {
        val out = ArrayList<Quad>()
        val lab = Mat().keep()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val chans = ArrayList<Mat>()
        Core.split(lab, chans)
        chans.forEach { it.keep() }
        val l8 = chans[0]
        val lF = Mat().keep()
        val aF = Mat().keep()
        val bF = Mat().keep()
        l8.convertTo(lF, CvType.CV_32F)
        chans[1].convertTo(aF, CvType.CV_32F, 1.0, -128.0)
        chans[2].convertTo(bF, CvType.CV_32F, 1.0, -128.0)

        // local texture: paper is smooth, shirts / beards / carpets are not
        val mean9 = Mat().keep()
        Imgproc.blur(lF, mean9, Size(9.0, 9.0))
        val sq = times(lF, lF)
        val meanSq = Mat().keep()
        Imgproc.blur(sq, meanSq, Size(9.0, 9.0))
        val var9 = minus(meanSq, times(mean9, mean9))
        Core.max(var9, Scalar(0.0), var9)
        val sd = Mat().keep()
        Core.sqrt(var9, sd)
        val smoothMask = lessThan(sd, 7.5)

        val notEdgeCut = not(dil(edges, 5))
        val seeds = listOf(0.5 to 0.5, 0.42 to 0.5, 0.58 to 0.5, 0.5 to 0.42, 0.5 to 0.58, 0.5 to 0.65, 0.4 to 0.6, 0.6 to 0.6)
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

            val d = magnitude(plusVal(aF, -seedA), plusVal(bF, -seedB))
            val lOk = Mat().keep()
            Core.inRange(l8, Scalar(0.75 * seedL), Scalar(1.22 * seedL), lOk)
            val base = and(lessThan(d, 9.0), lOk)

            for (smooth in listOf(false, true)) {
                for (cut in listOf(false, true)) {
                    for (openK in listOf(5, 21)) {
                        var m = base
                        if (smooth) m = and(m, smoothMask)
                        if (cut) m = and(m, notEdgeCut)
                        val mm = Mat().keep()
                        Imgproc.morphologyEx(m, mm, Imgproc.MORPH_OPEN, ellipse(openK).keep())
                        Imgproc.morphologyEx(mm, mm, Imgproc.MORPH_CLOSE, ellipse(11).keep())

                        val contours = ArrayList<MatOfPoint>()
                        Imgproc.findContours(mm.clone().keep(), contours, Mat().keep(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        var bestC: MatOfPoint? = null
                        var bestArea = 0.0
                        for (c in contours) {
                            c.keep()
                            val ar = Imgproc.contourArea(c)
                            val inside = Imgproc.pointPolygonTest(
                                MatOfPoint2f(*c.toArray()).keep(), Point(cx.toDouble(), cy.toDouble()), false
                            ) >= 0
                            if ((inside || ar > 0.05 * w * h) && ar > bestArea && ar < 0.95 * w * h) {
                                bestC = c
                                bestArea = ar
                            }
                        }
                        val bc = bestC ?: continue
                        var blob = Mat.zeros(h, w, CvType.CV_8UC1).keep()
                        Imgproc.drawContours(blob, listOf(bc), -1, Scalar(255.0), -1)        // filled: pips are no longer holes
                        if (cut) blob = dil(blob, 5)                                          // give back the pixels removed with the edges
                        val contours2 = ArrayList<MatOfPoint>()
                        Imgproc.findContours(blob.clone().keep(), contours2, Mat().keep(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        val biggest = contours2.maxByOrNull { Imgproc.contourArea(it) } ?: continue
                        biggest.keep()
                        fit4(hullPoints(biggest))?.let { out.add(it) }
                    }
                }
            }
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

    /** Fraction of each side where the brightness changes across it (inside vs outside strips). */
    private fun sideContrast(q: Quad, g: FloatArray, w: Int, h: Int): DoubleArray {
        val p = q.points
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        val res = DoubleArray(4)
        for (i in 0 until 4) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val tx = b.x - a.x
            val ty = b.y - a.y
            val len = hypot(tx, ty)
            if (len < 1f) return DoubleArray(4)
            var nx = ty / len
            var ny = -tx / len
            val mx = (a.x + b.x) / 2f
            val my = (a.y + b.y) / 2f
            if (nx * (cx - mx) + ny * (cy - my) < 0) { nx = -nx; ny = -ny }       // inward normal
            var ok = 0
            var tot = 0
            for (s in 0 until 40) {
                val t = 0.08f + 0.84f * s / 39f
                val px = a.x + tx * t
                val py = a.y + ty * t
                var inSum = 0f
                var outSum = 0f
                var cnt = 0
                for (off in intArrayOf(5, 9, 13)) {
                    val ix = (px + nx * off).toInt()
                    val iy = (py + ny * off).toInt()
                    val ox = (px - nx * off).toInt()
                    val oy = (py - ny * off).toInt()
                    if (ix in 0 until w && iy in 0 until h && ox in 0 until w && oy in 0 until h) {
                        inSum += g[iy * w + ix]
                        outSum += g[oy * w + ox]
                        cnt++
                    }
                }
                if (cnt < 2) continue
                tot++
                if (abs(inSum / cnt - outSum / cnt) > 8f) ok++
            }
            res[i] = if (tot >= 8) ok.toDouble() / tot else 0.0
        }
        return res
    }

    private fun Scope.score(q: Quad, gray: Mat, gData: FloatArray, wideData: ByteArray, w: Int, h: Int): Double {
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

        val contrast = sideContrast(q, gData, w, h)
        if (contrast.minOrNull()!! < 0.30) return -1.0         // every side must sit on a real brightness step

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
                    if (wideData[y * w + x].toInt() != 0) ok++
                }
            }
        }
        val es = ok.toDouble() / max(tot, 1)
        return sqrt(area) * pf * (0.25 + 0.75 * es) * (0.6 + 0.4 * contrast.average())
    }

    // ------------------------------------------------------------------------------------------
    // edge snapping
    // ------------------------------------------------------------------------------------------

    /**
     * Flattens the card with a 10% margin and moves each side to the strongest brightness step near it
     * (preferring the one closest to where the side already is, so it cannot jump to a far background edge).
     */
    private fun Scope.refineQuad(rgb: Mat, q: Quad, iters: Int = 2): Quad {
        val rw = 500
        val rh = 700
        val margin = 0.10
        val pw = (rw * (1 + 2 * margin)).toInt()
        val ph = (rh * (1 + 2 * margin)).toInt()
        val ox = (rw * margin).toInt()
        val oy = (rh * margin).toInt()
        val k = 10                                   // width of the strips compared on each side of a step
        var cur = q
        repeat(iters) {
            val wd = (Quad.dist(cur.tl, cur.tr) + Quad.dist(cur.bl, cur.br)) / 2f
            val ht = (Quad.dist(cur.tl, cur.bl) + Quad.dist(cur.tr, cur.br)) / 2f
            val idx = if (wd > ht) intArrayOf(3, 0, 1, 2) else intArrayOf(0, 1, 2, 3)
            val all = cur.points
            val src = MatOfPoint2f(*idx.map { Point(all[it].x.toDouble(), all[it].y.toDouble()) }.toTypedArray()).keep()
            val dst = MatOfPoint2f(
                Point(ox.toDouble(), oy.toDouble()), Point((ox + rw).toDouble(), oy.toDouble()),
                Point((ox + rw).toDouble(), (oy + rh).toDouble()), Point(ox.toDouble(), (oy + rh).toDouble())
            ).keep()
            val t = Imgproc.getPerspectiveTransform(src, dst).keep()
            val img = Mat().keep()
            Imgproc.warpPerspective(rgb, img, t, Size(pw.toDouble(), ph.toDouble()), Imgproc.INTER_LINEAR)
            val lab = Mat().keep()
            Imgproc.cvtColor(img, lab, Imgproc.COLOR_RGB2Lab)
            val lc = ArrayList<Mat>()
            Core.split(lab, lc)
            lc.forEach { it.keep() }
            val lf = Mat().keep()
            lc[0].convertTo(lf, CvType.CV_32F)
            Imgproc.GaussianBlur(lf, lf, Size(0.0, 0.0), 1.5)
            val data = FloatArray(pw * ph)
            lf.get(0, 0, data)

            fun step(profiles: List<FloatArray>, lo: Int, hi: Int, sign: Int, nominal: Int): Double? {
                val found = ArrayList<Int>()
                for (p in profiles) {
                    val pre = FloatArray(p.size + 1)
                    for (i in p.indices) pre[i + 1] = pre[i] + p[i]
                    fun mean(a: Int, b: Int): Float = (pre[b] - pre[a]) / max(b - a, 1)
                    val st = FloatArray(hi - lo)
                    var mx = -1e9f
                    for (i in lo until hi) {
                        val s = sign * (mean(i, min(i + k, p.size)) - mean(max(i - k, 0), i))
                        st[i - lo] = s
                        if (s > mx) mx = s
                    }
                    if (mx <= 6f) continue
                    var bestI = -1
                    var bestD = Int.MAX_VALUE
                    for (j in st.indices) {
                        if (st[j] >= 0.6f * mx && abs(lo + j - nominal) < bestD) {
                            bestD = abs(lo + j - nominal)
                            bestI = lo + j
                        }
                    }
                    if (bestI >= 0) found.add(bestI)
                }
                if (found.size < max(1, (0.25 * profiles.size).toInt())) return null
                found.sort()
                return found[found.size / 2].toDouble()
            }

            val y0 = oy + (0.2 * rh).toInt()
            val y1 = oy + (0.8 * rh).toInt()
            val x0 = ox + (0.2 * rw).toInt()
            val x1 = ox + (0.8 * rw).toInt()
            val rows = (y0 until y1 step 4).map { y -> FloatArray(pw) { x -> data[y * pw + x] } }
            val cols = (x0 until x1 step 4).map { x -> FloatArray(ph) { y -> data[y * pw + x] } }
            val sx = (0.08 * rw).toInt()
            val sy = (0.08 * rh).toInt()
            val left = step(rows, max(ox - sx, k), ox + sx, +1, ox)
            val right = step(rows, ox + rw - sx, min(ox + rw + sx, pw - k), -1, ox + rw)
            val top = step(cols, max(oy - sy, k), oy + sy, +1, oy)
            val bot = step(cols, oy + rh - sy, min(oy + rh + sy, ph - k), -1, oy + rh)
            val xl = left ?: ox.toDouble()
            val xr = right ?: (ox + rw).toDouble()
            val yt = top ?: oy.toDouble()
            val yb = bot ?: (oy + rh).toDouble()

            val corners = MatOfPoint2f(Point(xl, yt), Point(xr, yt), Point(xr, yb), Point(xl, yb)).keep()
            val back = Mat().keep()
            Core.perspectiveTransform(corners, back, t.inv().keep())
            val bp = MatOfPoint2f(back).keep().toArray()
            val np = Array(4) { all[it] }
            for (j in 0 until 4) np[idx[j]] = PointF(bp[j].x.toFloat(), bp[j].y.toFloat())
            cur = Quad(np[0], np[1], np[2], np[3])
        }
        return cur
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return s[s.size / 2]
    }
}
