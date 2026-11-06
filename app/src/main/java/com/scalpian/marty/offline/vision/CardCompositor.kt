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

// ---------------------------------------------------------------------------------------------
// Small OpenCV helpers (file-private)
// ---------------------------------------------------------------------------------------------

/** Collects every Mat created while compositing so they can all be released together. */
private class Scope {
    private val mats = ArrayList<Mat>()

    fun <T : Mat> T.keep(): T {
        mats.add(this)
        return this
    }

    fun close() = mats.forEach { it.release() }

    fun and(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.bitwise_and(a, b, o); return o }
    fun or(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.bitwise_or(a, b, o); return o }
    fun not(a: Mat): Mat { val o = Mat().keep(); Core.bitwise_not(a, o); return o }
    fun dil(a: Mat, n: Int): Mat { val o = Mat().keep(); Imgproc.dilate(a, o, ellipse(n).keep()); return o }
    fun lessThan(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.compare(a, b, o, Core.CMP_LT); return o }
    fun greaterThan(a: Mat, v: Double): Mat { val o = Mat().keep(); Core.compare(a, Scalar(v), o, Core.CMP_GT); return o }
    fun lessThan(a: Mat, v: Double): Mat { val o = Mat().keep(); Core.compare(a, Scalar(v), o, Core.CMP_LT); return o }
    fun scaled(a: Mat, k: Double): Mat { val o = Mat().keep(); a.convertTo(o, CvType.CV_32F, k); return o }

    /** 32-bit grey version of a float RGB image. */
    fun grayOf(rgbF: Mat): Mat {
        val p8 = Mat().keep()
        rgbF.convertTo(p8, CvType.CV_8UC3)
        val g8 = Mat().keep()
        Imgproc.cvtColor(p8, g8, Imgproc.COLOR_RGB2GRAY)
        val g = Mat().keep()
        g8.convertTo(g, CvType.CV_32F)
        return g
    }

    /**
     * Smooth estimate of the paper (colour AND shading) built only from pixels where mask8 is set
     * (normalised convolution), so print and fingers never leak into it.
     */
    fun paperModel(cardF: Mat, mask8: Mat, sigma: Double): Mat {
        val m = soft(mask8, 0.0).keep()
        val num = Mat().keep()
        Core.multiply(cardF, merge3(m).keep(), num)
        Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sigma)
        val den = Mat().keep()
        Imgproc.GaussianBlur(m, den, Size(0.0, 0.0), sigma)
        Core.max(den, Scalar(1e-3), den)
        val out = Mat().keep()
        Core.divide(num, merge3(den).keep(), out)
        return out
    }
}

private fun ellipse(n: Int): Mat =
    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(n.toDouble(), n.toDouble()))

/** 8-bit 0/255 mask -> float 0..1, optionally blurred. */
private fun soft(mask8: Mat, sigma: Double): Mat {
    val f = Mat()
    mask8.convertTo(f, CvType.CV_32F, 1.0 / 255.0)
    if (sigma > 0.0) Imgproc.GaussianBlur(f, f, Size(0.0, 0.0), sigma)
    return f
}

private fun merge3(m: Mat): Mat {
    val out = Mat()
    Core.merge(listOf(m, m, m), out)
    return out
}

private fun oneMinus(m: Mat): Mat {
    val ones = Mat(m.size(), m.type(), Scalar(1.0))
    val out = Mat()
    Core.subtract(ones, m, out)
    ones.release()
    return out
}

object CardCompositor {

    private const val RW = 500
    private const val RH = 700

    /**
     * "Ink swap": edit the REAL card instead of pasting a synthetic one.
     *  1. Flatten the card (perspective) into a plain 500x700 rectangle.
     *  2. Find the old print: anything darker than the estimated paper, strong red, and anything
     *     near-black or clearly off-paper right beside a finger.
     *  3. Fill it with a clean paper model (colour + shading, built only from untouched paper), plus
     *     matching grain and a soft contact shadow next to fingers. Nothing is copied from fingers.
     *  4. Print the new indices/pips as ink that multiplies the paper shading.
     *  5. Warp back and blend only where something changed, so fingers, the card edge, its shadow and
     *     the photo's sharpness stay original.
     * Falls back to the old full-card paste if the card can't be analysed.
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

                val gray = Mat().keep()
                Imgproc.cvtColor(cardRgb, gray, Imgproc.COLOR_RGB2GRAY)
                val grayF = Mat().keep()
                gray.convertTo(grayF, CvType.CV_32F)
                val grayS = Mat().keep()
                Imgproc.GaussianBlur(grayF, grayS, Size(0.0, 0.0), 1.2)
                val cardF = Mat().keep()
                cardRgb.convertTo(cardF, CvType.CV_32FC3)

                // ---- fingers: bright, skin-coloured blobs ----
                val ycc = Mat().keep()
                val hsv = Mat().keep()
                Imgproc.cvtColor(cardRgb, ycc, Imgproc.COLOR_RGB2YCrCb)
                Imgproc.cvtColor(cardRgb, hsv, Imgproc.COLOR_RGB2HSV)
                val skinA = Mat().keep()
                val skinB = Mat().keep()
                Core.inRange(ycc, Scalar(0.0, 133.0, 77.0), Scalar(255.0, 175.0, 127.0), skinA)
                Core.inRange(hsv, Scalar(0.0, 40.0, 0.0), Scalar(179.0, 255.0, 255.0), skinB)
                val loose = and(skinA, skinB)                                  // skin-coloured, any brightness
                val strict = and(loose, greaterThan(gray, 88.0))               // real finger: skin-coloured AND bright
                Imgproc.morphologyEx(strict, strict, Imgproc.MORPH_OPEN, ellipse(9).keep())
                val notStrict = not(strict)
                val guard = dil(strict, 3)
                val protect = or(guard, and(loose, dil(strict, 9)))
                val skinF = soft(dil(strict, 5), 1.0).keep()

                // ---- 2. paper model, in two passes ----
                val mask0 = and(valid, not(dil(strict, 9)))
                if (Core.countNonZero(mask0) < 20000) return@with composite(photo, quad, CardFaceRenderer.render(card))
                val m0 = Core.mean(gray, mask0).`val`[0]
                val seed = and(greaterThan(gray, m0), mask0)
                if (Core.countNonZero(seed) < 5000) return@with composite(photo, quad, CardFaceRenderer.render(card))
                val ref = Core.mean(gray, seed).`val`[0]
                val paperMask = and(greaterThan(gray, 0.85 * ref), mask0)
                val p1 = paperModel(cardF, paperMask, 25.0)
                val ink1 = and(lessThan(grayS, scaled(grayOf(p1), 0.90)), mask0)
                // second pass: paper = not near any print (15 px) and not near a finger
                val paperMask2 = and(mask0, not(dil(ink1, 15)))
                val p2 = paperModel(cardF, paperMask2, 25.0)
                val pg2 = grayOf(p2)

                // ---- old print ----
                val ink = and(lessThan(grayS, scaled(pg2, 0.91)), valid)
                Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, ellipse(3).keep())

                val chans = ArrayList<Mat>()
                Core.split(cardRgb, chans)
                chans.forEach { it.keep() }
                val r16 = Mat().keep()
                val g16 = Mat().keep()
                val b16 = Mat().keep()
                chans[0].convertTo(r16, CvType.CV_16S)
                chans[1].convertTo(g16, CvType.CV_16S)
                chans[2].convertTo(b16, CvType.CV_16S)
                val rg = Mat().keep()
                val rb = Mat().keep()
                Core.subtract(r16, g16, rg)
                Core.subtract(r16, b16, rb)
                val red = and(and(greaterThan(rg, 85.0), greaterThan(rb, 65.0)), valid)

                val dark = and(lessThan(grayS, scaled(pg2, 0.45)), valid)
                val dark2 = and(lessThan(gray, 55.0), valid)
                val ringBand = and(dil(strict, 15), notStrict)
                val ringDark = and(and(lessThan(grayS, scaled(pg2, 0.55)), ringBand), valid)
                val diffP = Mat().keep()
                Core.absdiff(cardF, p2, diffP)
                val dch = ArrayList<Mat>()
                Core.split(diffP, dch)
                dch.forEach { it.keep() }
                val dmax = Mat().keep()
                Core.max(dch[0], dch[1], dmax)
                Core.max(dmax, dch[2], dmax)
                val dev = and(greaterThan(dmax, 30.0), and(dil(strict, 17), notStrict))

                // ordinary print: stay clear of the finger edge
                val oldA = and(and(dil(and(and(ink, not(protect)), valid), 11), not(guard)), valid)
                // pure red / near-black / off-paper beside a finger: erase right up to the finger
                val seedB = and(and(or(or(red, dark), or(or(dark2, ringDark), dev)), notStrict), valid)
                val oldB = and(and(dil(seedB, 7), notStrict), valid)
                val old = or(oldA, oldB)
                val oldF = soft(old, 2.0).keep()
                Core.multiply(oldF, validF, oldF)

                // ---- 3. fill: paper model + contact shadow + grain ----
                val dist = Mat().keep()
                Imgproc.distanceTransform(notStrict, dist, Imgproc.DIST_L2, 3)
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
                Core.meanStdDev(grain, meanM, stdM, paperMask2)
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
                Core.multiply(a, oneMinus(skinF).keep(), a)                     // never print on a finger

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
