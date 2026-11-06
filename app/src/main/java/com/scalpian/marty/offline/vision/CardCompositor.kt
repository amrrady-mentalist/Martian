package com.scalpian.marty.offline.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import com.scalpian.marty.offline.data.PlayingCard
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object CardCompositor {

    private const val RW = 500
    private const val RH = 700

    /**
     * "Ink swap": edit the REAL card instead of pasting a synthetic one.
     *
     * Everything is decided by COLOUR relative to the card's own paper, measured in Lab space:
     *   - paper is neutral/blueish and bright;
     *   - black print is the paper's hue, just darker;
     *   - red print has a very high "a" (redness);
     *   - fingers and nails are warmer than paper (skin a/b), so they are recognised as fingers even
     *     when pale (a nail) or in shade, and are never touched.
     * Steps: flatten the card -> find and erase the old print (dilated so soft edges go too) -> fill with
     * a paper model built only from untouched paper (+ grain, + a soft contact shadow beside fingers)
     * -> print the new indices/pips as ink that multiplies the paper shading -> warp back and blend only
     * where something changed. Falls back to the old full-card paste if the card can't be analysed.
     */
    fun compositeInk(photo: Bitmap, quad: Quad, card: PlayingCard): Bitmap {
        val sc = Scope()
        try {
            return with(sc) {
                val src = Mat().keep()
                Utils.bitmapToMat(photo, src)
                val rgb = Mat().keep()
                Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

                // ---- 1. flatten the card ----
                val pts = orientedCorners(quad)
                val quadPts = MatOfPoint2f(*pts.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()).keep()
                val rectPts = MatOfPoint2f(
                    Point(0.0, 0.0), Point(RW.toDouble(), 0.0),
                    Point(RW.toDouble(), RH.toDouble()), Point(0.0, RH.toDouble())
                ).keep()
                val toRect = Imgproc.getPerspectiveTransform(quadPts, rectPts).keep()
                val cardRgb = Mat().keep()
                Imgproc.warpPerspective(rgb, cardRgb, toRect, Size(RW.toDouble(), RH.toDouble()), Imgproc.INTER_LINEAR)

                // valid area = rounded rectangle slightly inside the card (the edge is never edited)
                val valid = Mat.zeros(RH, RW, CvType.CV_8UC1).keep()
                val inset = 8
                val r = 26
                Imgproc.rectangle(valid, Point((inset + r).toDouble(), inset.toDouble()),
                    Point((RW - inset - r).toDouble(), (RH - inset).toDouble()), Scalar(255.0), -1)
                Imgproc.rectangle(valid, Point(inset.toDouble(), (inset + r).toDouble()),
                    Point((RW - inset).toDouble(), (RH - inset - r).toDouble()), Scalar(255.0), -1)
                for ((cx, cy) in listOf(
                    (inset + r) to (inset + r), (RW - inset - r) to (inset + r),
                    (inset + r) to (RH - inset - r), (RW - inset - r) to (RH - inset - r)
                )) Imgproc.circle(valid, Point(cx.toDouble(), cy.toDouble()), r, Scalar(255.0), -1)
                val validF = soft(valid, 0.0).keep()

                val cardF = Mat().keep()
                cardRgb.convertTo(cardF, CvType.CV_32FC3)
                val gray = Mat().keep()
                Imgproc.cvtColor(cardRgb, gray, Imgproc.COLOR_RGB2GRAY)
                val grayF = Mat().keep()
                gray.convertTo(grayF, CvType.CV_32F)

                // ---- 2. colour model of the paper (Lab) ----
                val lab = Mat().keep()
                Imgproc.cvtColor(cardRgb, lab, Imgproc.COLOR_RGB2Lab)
                val lc = ArrayList<Mat>()
                Core.split(lab, lc)
                lc.forEach { it.keep() }
                val lum = Mat().keep()
                val aF = Mat().keep()
                val bF = Mat().keep()
                lc[0].convertTo(lum, CvType.CV_32F)
                lc[1].convertTo(aF, CvType.CV_32F, 1.0, -128.0)
                lc[2].convertTo(bF, CvType.CV_32F, 1.0, -128.0)

                val lMean = Core.mean(lum, valid).`val`[0]
                val bright = and(greaterThan(lum, lMean), valid)
                if (Core.countNonZero(bright) < 5000) return@with composite(photo, quad, CardFaceRenderer.render(card))
                var a0 = Core.mean(aF, bright).`val`[0]
                var b0 = Core.mean(bF, bright).`val`[0]
                var dg = magnitude(plusVal(aF, -a0), plusVal(bF, -b0))
                val pl0 = and(bright, lessThan(dg, 6.0))
                if (Core.countNonZero(pl0) < 4000) return@with composite(photo, quad, CardFaceRenderer.render(card))
                a0 = Core.mean(aF, pl0).`val`[0]
                b0 = Core.mean(bF, pl0).`val`[0]
                val lRef = Core.mean(lum, pl0).`val`[0]
                dg = magnitude(plusVal(aF, -a0), plusVal(bF, -b0))
                val paperLike = and(and(greaterThan(lum, 0.8 * lRef), lessThan(dg, 6.0)), valid)

                val lab3 = Mat().keep()
                Core.merge(listOf(lum, aF, bF), lab3)
                val labModel = paperModel(lab3, paperLike, 40.0)
                val pc = ArrayList<Mat>()
                Core.split(labModel, pc)
                pc.forEach { it.keep() }
                val pL = pc[0]
                val pA = pc[1]
                val pB = pc[2]
                val dA = minus(aF, pA)                       // redness relative to the local paper
                val dLoc = magnitude(dA, minus(bF, pB))      // overall colour difference from the local paper

                // black print keeps the paper's hue, just darker: expected chroma = paper chroma * brightness ratio
                val pLs = Mat().keep()
                Core.max(pL, Scalar(1.0), pLs)
                val t = over(lum, pLs)
                Core.min(t, Scalar(1.05), t)
                val dDark = magnitude(minus(aF, times(pA, t)), minus(bF, times(pB, t)))

                // ---- classes ----
                val darkCore = and(and(lessThan(lum, scaled(pL, 0.30)), lessThan(dDark, 7.0)), valid)
                val redCore = and(greaterThan(dA, 28.0), valid)
                val neutralDark = and(and(lessThan(lum, scaled(pL, 0.60)), lessThan(dDark, 7.0)), valid)
                val redFringe = and(and(greaterThan(dA, 10.999), dil(redCore, 13)), valid)
                val inkLike = or(or(darkCore, redCore), or(neutralDark, redFringe))

                // fingers / nails / skin: clearly warmer than paper and not print. NEVER touched.
                val fingerRaw = and(and(greaterThan(dLoc, 7.5), not(inkLike)), valid)
                Imgproc.morphologyEx(fingerRaw, fingerRaw, Imgproc.MORPH_OPEN, ellipse(5).keep())
                val finger = and(fingerRaw, not(inkLike))

                // old print = ink-like pixels grown by ~7 px (covers anti-aliasing and JPEG halos), minus fingers
                val old = and(and(dil(inkLike, 15), not(finger)), valid)
                val oldF = soft(old, 2.0).keep()
                Core.multiply(oldF, validF, oldF)

                // ---- 3. fill: paper model built only from clean paper + contact shadow + grain ----
                val paper2 = and(
                    and(and(valid, lessThan(dLoc, 4.0)), not(lessThan(lum, scaled(pL, 0.85)))),
                    and(not(dil(old, 9)), not(dil(finger, 11)))
                )
                if (Core.countNonZero(paper2) < 3000) return@with composite(photo, quad, CardFaceRenderer.render(card))
                val p2 = paperModel(cardF, paper2, 25.0)

                val dist = Mat().keep()
                Imgproc.distanceTransform(not(finger), dist, Imgproc.DIST_L2, 3)
                val shade = Mat().keep()
                dist.convertTo(shade, CvType.CV_32F, -0.2, 0.0)                 // -d / 5
                Core.exp(shade, shade)
                shade.convertTo(shade, CvType.CV_32F, -0.14, 1.0)               // 1 - 0.14 * exp(-d / 5)
                val filled = Mat().keep()
                Core.multiply(p2, merge3(shade).keep(), filled)

                val blur3 = Mat().keep()
                Imgproc.GaussianBlur(grayF, blur3, Size(0.0, 0.0), 3.0)
                val grain = Mat().keep()
                Core.subtract(grayF, blur3, grain)
                val meanM = MatOfDouble().keep()
                val stdM = MatOfDouble().keep()
                Core.meanStdDev(grain, meanM, stdM, paper2)
                val noise = Mat(RH, RW, CvType.CV_32F).keep()
                Core.randn(noise, 0.0, stdM.toArray()[0] * 0.9)
                Core.add(filled, merge3(noise).keep(), filled)

                val diff = Mat().keep()
                Core.subtract(filled, cardF, diff)
                Core.multiply(diff, merge3(oldF).keep(), diff)
                val clean = Mat().keep()
                Core.add(cardF, diff, clean)

                // ---- 4. print the new card ----
                val inkBmp = CardFaceRenderer.renderInk(card, RW, RH)
                val inkRgba = Mat().keep()
                Utils.bitmapToMat(inkBmp, inkRgba)
                inkBmp.recycle()
                val inkChans = ArrayList<Mat>()
                Core.split(inkRgba, inkChans)
                inkChans.forEach { it.keep() }
                val a = soft(inkChans[3], 0.7).keep()
                Core.multiply(a, validF, a)
                val fingerSoft = soft(dil(finger, 5), 1.0).keep()
                Core.multiply(a, oneMinus(fingerSoft).keep(), a)                // never print on a finger

                val col = CardFaceRenderer.inkColor(card)
                val inked = Mat().keep()
                Core.multiply(
                    clean,
                    Scalar(Color.red(col) / 255.0, Color.green(col) / 255.0, Color.blue(col) / 255.0),
                    inked
                )
                val diff2 = Mat().keep()
                Core.subtract(inked, clean, diff2)
                Core.multiply(diff2, merge3(a).keep(), diff2)
                val finalRect = Mat().keep()
                Core.add(clean, diff2, finalRect)

                val changed = Mat().keep()
                Core.max(oldF, a, changed)

                // ---- 5. back into the photo, only where changed ----
                val flags = Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP
                val finalWarp = Mat().keep()
                val changedWarp = Mat().keep()
                Imgproc.warpPerspective(finalRect, finalWarp, toRect, rgb.size(), flags)
                Imgproc.warpPerspective(changed, changedWarp, toRect, rgb.size(), flags)
                Imgproc.GaussianBlur(changedWarp, changedWarp, Size(0.0, 0.0), 1.0)

                val rgbF = Mat().keep()
                rgb.convertTo(rgbF, CvType.CV_32FC3)
                val d3 = Mat().keep()
                Core.subtract(finalWarp, rgbF, d3)
                Core.multiply(d3, merge3(changedWarp).keep(), d3)
                val outF = Mat().keep()
                Core.add(rgbF, d3, outF)
                val out8 = Mat().keep()
                outF.convertTo(out8, CvType.CV_8UC3)
                val outRgba = Mat().keep()
                Imgproc.cvtColor(out8, outRgba, Imgproc.COLOR_RGB2RGBA)

                val result = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(outRgba, result)
                result
            }
        } finally {
            sc.close()
        }
    }

    /** Corner order so the card's short edge is "top" (a landscape card is turned 90 degrees). */
    private fun orientedCorners(q: Quad): List<PointF> {
        val w = (Quad.dist(q.tl, q.tr) + Quad.dist(q.bl, q.br)) / 2f
        val h = (Quad.dist(q.tl, q.bl) + Quad.dist(q.tr, q.br)) / 2f
        return if (w > h) listOf(q.bl, q.tl, q.tr, q.br) else listOf(q.tl, q.tr, q.br, q.bl)
    }

    /** Old method, kept as a fallback: warps a fully drawn card face over the whole card. */
    fun composite(photo: Bitmap, quad: Quad, face: Bitmap): Bitmap {
        val q = quad.points
        val widthEdge = (Quad.dist(q[0], q[1]) + Quad.dist(q[3], q[2])) / 2f
        val heightEdge = (Quad.dist(q[0], q[3]) + Quad.dist(q[1], q[2])) / 2f
        // The face is portrait; if the card lies landscape in the photo, turn the face too.
        val faceUsed = if (widthEdge > heightEdge) rotate90(face) else face

        val src = Mat()
        val rgb = Mat()
        val gray = Mat()
        val faceRgba = Mat()
        val warped = Mat()
        val cardMask = Mat()
        Utils.bitmapToMat(photo, src)
        Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        Utils.bitmapToMat(faceUsed, faceRgba)

        // --- geometry ---
        val grown = expand(q, 1.035f)
        val fw = faceUsed.width.toDouble()
        val fh = faceUsed.height.toDouble()
        val srcPts = MatOfPoint2f(Point(0.0, 0.0), Point(fw, 0.0), Point(fw, fh), Point(0.0, fh))
        val dstPts = MatOfPoint2f(*grown.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)

        // --- lighting: scale the face to the brightness of the card it replaces ---
        val quadPoly = MatOfPoint(*q.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        Mat.zeros(src.size(), CvType.CV_8UC1).copyTo(cardMask)
        Imgproc.fillConvexPoly(cardMask, quadPoly, Scalar(255.0))
        val meanPhoto = Core.mean(gray, cardMask).`val`[0]
        val faceGray = Mat()
        Imgproc.cvtColor(faceRgba, faceGray, Imgproc.COLOR_RGBA2GRAY)
        val meanFace = Core.mean(faceGray).`val`[0].coerceAtLeast(1.0)
        faceGray.release()
        val gain = (meanPhoto / meanFace).coerceIn(0.45, 1.15)

        Imgproc.warpPerspective(
            faceRgba, warped, transform, src.size(),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0)
        )

        // --- split warped face into colour + alpha ---
        val channels = ArrayList<Mat>()
        Core.split(warped, channels)               // R, G, B, A
        val alpha8 = channels[3]
        val fg8 = Mat()
        Core.merge(listOf(channels[0], channels[1], channels[2]), fg8)
        fg8.convertTo(fg8, -1, gain, 0.0)
        // Photos are never razor sharp: soften the synthetic face a touch.
        Imgproc.GaussianBlur(fg8, fg8, Size(0.0, 0.0), 0.8)

        // --- feathered alpha ---
        val alphaF = Mat()
        alpha8.convertTo(alphaF, CvType.CV_32F, 1.0 / 255.0)
        Imgproc.GaussianBlur(alphaF, alphaF, Size(5.0, 5.0), 0.0)
        val alpha3 = Mat()
        Core.merge(listOf(alphaF, alphaF, alphaF), alpha3)

        // --- blend: out = bg + (fg - bg) * alpha ---
        val fgF = Mat()
        val bgF = Mat()
        fg8.convertTo(fgF, CvType.CV_32FC3)
        rgb.convertTo(bgF, CvType.CV_32FC3)
        val diff = Mat()
        Core.subtract(fgF, bgF, diff)
        Core.multiply(diff, alpha3, diff)
        val outF = Mat()
        Core.add(bgF, diff, outF)
        val out8 = Mat()
        outF.convertTo(out8, CvType.CV_8UC3)
        val outRgba = Mat()
        Imgproc.cvtColor(out8, outRgba, Imgproc.COLOR_RGB2RGBA)

        val result = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, result)

        listOf(
            src, rgb, gray, faceRgba, warped, cardMask, srcPts, dstPts, transform, quadPoly,
            fg8, alphaF, alpha3, fgF, bgF, diff, outF, out8, outRgba
        ).forEach { it.release() }
        channels.forEach { it.release() }
        return result
    }

    private fun expand(q: List<PointF>, k: Float): List<PointF> {
        val cx = q.map { it.x }.average().toFloat()
        val cy = q.map { it.y }.average().toFloat()
        return q.map { PointF(cx + (it.x - cx) * k, cy + (it.y - cy) * k) }
    }

    private fun rotate90(b: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
