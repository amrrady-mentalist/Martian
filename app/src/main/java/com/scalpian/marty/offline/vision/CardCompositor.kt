package com.scalpian.marty.offline.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import com.scalpian.marty.offline.data.PlayingCard

/**
 * Replaces the card inside [quad] with [face], warped into the same perspective.
 *
 * Steps: warp the flat face onto a slightly enlarged quad (so none of the old card peeks
 * out), match its brightness and softness to the photo, feather the edge, alpha-blend.
 */
object CardCompositor {

    private const val RW = 500
    private const val RH = 700

    private class Scope {
        val mats = ArrayList<Mat>()
        fun <T : Mat> T.t(): T { mats.add(this); return this }
        fun close() = mats.forEach { it.release() }
    }

    /**
     * "Ink swap": instead of pasting a synthetic card over the photo, edit the REAL card.
     *  1. Flatten the card (perspective) so everything below happens in a plain 500x700 rectangle.
     *  2. Find the print (anything clearly darker than the estimated paper, plus strong red) and
     *     paint it out with the paper colour/shading estimated from the card itself.
     *  3. Print the new card's indices and pips on top, as ink that multiplies the paper shading.
     *  4. Warp back and blend only where something changed. Fingers (skin-coloured pixels inside the
     *     card) are never touched, so a thumb on the card stays in front, and the card's own edge,
     *     shadow, gloss and the photo's sharpness are kept.
     * Falls back to the old full-card paste if the card can't be analysed.
     */
    fun compositeInk(photo: Bitmap, quad: Quad, card: PlayingCard): Bitmap {
        val sc = Scope()
        try {
            return with(sc) {
                val src = Mat().t()
                Utils.bitmapToMat(photo, src)
                val rgb = Mat().t()
                Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)

                // ---- 1. flatten the card ----
                val pts = orientedCorners(quad)
                val quadPts = MatOfPoint2f(*pts.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()).t()
                val rectPts = MatOfPoint2f(
                    Point(0.0, 0.0), Point(RW.toDouble(), 0.0),
                    Point(RW.toDouble(), RH.toDouble()), Point(0.0, RH.toDouble())
                ).t()
                val toRect = Imgproc.getPerspectiveTransform(quadPts, rectPts).t()
                val cardRgb = Mat().t()
                Imgproc.warpPerspective(rgb, cardRgb, toRect, Size(RW.toDouble(), RH.toDouble()), Imgproc.INTER_LINEAR)

                // valid area = rounded rectangle slightly inside the card (never edit the edge)
                val valid = Mat.zeros(RH, RW, CvType.CV_8UC1).t()
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
                val validF = soft(valid, 0.0).t()

                // ---- fingers: skin-coloured blobs inside the card ----
                val ycc = Mat().t()
                val hsv = Mat().t()
                Imgproc.cvtColor(cardRgb, ycc, Imgproc.COLOR_RGB2YCrCb)
                Imgproc.cvtColor(cardRgb, hsv, Imgproc.COLOR_RGB2HSV)
                val skinA = Mat().t()
                val skinB = Mat().t()
                Core.inRange(ycc, Scalar(0.0, 136.0, 77.0), Scalar(255.0, 173.0, 125.0), skinA)
                Core.inRange(hsv, Scalar(0.0, 45.0, 0.0), Scalar(179.0, 255.0, 255.0), skinB)
                val skinCore = Mat().t()
                Core.bitwise_and(skinA, skinB, skinCore)
                Imgproc.morphologyEx(skinCore, skinCore, Imgproc.MORPH_OPEN, ellipse(9).t())
                val skinD = Mat().t()
                Imgproc.dilate(skinCore, skinD, ellipse(5).t())
                val skinF = soft(skinD, 1.0).t()
                val skinCoreF = soft(skinCore, 0.8).t()

                // ---- 2. estimate the paper (colour + shading) without the print ----
                val gray = Mat().t()
                Imgproc.cvtColor(cardRgb, gray, Imgproc.COLOR_RGB2GRAY)
                val notSkin = Mat().t()
                Core.bitwise_not(skinD, notSkin)
                val ok = Mat().t()
                Core.bitwise_and(valid, notSkin, ok)
                if (Core.countNonZero(ok) < 20000) return composite(photo, quad, CardFaceRenderer.render(card))

                val m0 = Core.mean(gray, ok).`val`[0]
                val above = Mat().t()
                Core.compare(gray, Scalar(m0), above, Core.CMP_GT)
                val seed = Mat().t()
                Core.bitwise_and(above, ok, seed)
                if (Core.countNonZero(seed) < 5000) return composite(photo, quad, CardFaceRenderer.render(card))
                val ref = Core.mean(gray, seed).`val`[0]
                val bright = Mat().t()
                Core.compare(gray, Scalar(0.85 * ref), bright, Core.CMP_GT)
                val paper0 = Mat().t()
                Core.bitwise_and(bright, ok, paper0)

                val cardF = Mat().t()
                cardRgb.convertTo(cardF, CvType.CV_32FC3)
                val maskF = soft(paper0, 0.0).t()
                val mask3 = merge3(maskF).t()
                val num = Mat().t()
                Core.multiply(cardF, mask3, num)
                Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), 35.0)
                val den = Mat().t()
                Imgproc.GaussianBlur(maskF, den, Size(0.0, 0.0), 35.0)
                Core.max(den, Scalar(1e-3), den)
                val paper = Mat().t()                       // P: smooth paper estimate, no print
                Core.divide(num, merge3(den).t(), paper)

                // ---- old print mask ----
                val paper8 = Mat().t()
                paper.convertTo(paper8, CvType.CV_8UC3)
                val paperGray8 = Mat().t()
                Imgproc.cvtColor(paper8, paperGray8, Imgproc.COLOR_RGB2GRAY)
                val grayF = Mat().t()
                val paperGrayF = Mat().t()
                gray.convertTo(grayF, CvType.CV_32F)
                paperGray8.convertTo(paperGrayF, CvType.CV_32F, 0.88)
                val darker = Mat().t()
                Core.compare(grayF, paperGrayF, darker, Core.CMP_LT)
                val old = Mat().t()
                Core.bitwise_and(darker, ok, old)

                // strong red print is erased even right beside a finger
                val chans = ArrayList<Mat>()
                Core.split(cardRgb, chans)
                chans.forEach { it.t() }
                val r16 = Mat().t()
                val g16 = Mat().t()
                val b16 = Mat().t()
                chans[0].convertTo(r16, CvType.CV_16S)
                chans[1].convertTo(g16, CvType.CV_16S)
                chans[2].convertTo(b16, CvType.CV_16S)
                val rg = Mat().t()
                val rb = Mat().t()
                Core.subtract(r16, g16, rg)
                Core.subtract(r16, b16, rb)
                val redA = Mat().t()
                val redB = Mat().t()
                Core.compare(rg, Scalar(95.0), redA, Core.CMP_GT)
                Core.compare(rb, Scalar(75.0), redB, Core.CMP_GT)
                val red = Mat().t()
                Core.bitwise_and(redA, redB, red)
                Core.bitwise_and(red, valid, red)
                Core.bitwise_or(old, red, old)
                Imgproc.dilate(old, old, ellipse(7).t())

                val oldF = soft(old, 1.5).t()
                Core.multiply(oldF, validF, oldF)
                Core.multiply(oldF, oneMinus(skinCoreF).t(), oldF)

                // paint the old print out: clean = card + (paper - card) * oldF
                val diff = Mat().t()
                Core.subtract(paper, cardF, diff)
                Core.multiply(diff, merge3(oldF).t(), diff)
                val clean = Mat().t()
                Core.add(cardF, diff, clean)

                // ---- 3. print the new card ----
                val inkBmp = CardFaceRenderer.renderInk(card, RW, RH)
                val inkRgba = Mat().t()
                Utils.bitmapToMat(inkBmp, inkRgba)
                inkBmp.recycle()
                val inkChans = ArrayList<Mat>()
                Core.split(inkRgba, inkChans)
                inkChans.forEach { it.t() }
                val a = soft(inkChans[3], 0.7).t()
                Core.multiply(a, validF, a)
                Core.multiply(a, oneMinus(skinF).t(), a)     // never print on a finger

                val col = CardFaceRenderer.inkColor(card)
                val inked = Mat().t()
                Core.multiply(
                    clean,
                    Scalar(Color.red(col) / 255.0, Color.green(col) / 255.0, Color.blue(col) / 255.0),
                    inked
                )
                val diff2 = Mat().t()
                Core.subtract(inked, clean, diff2)
                Core.multiply(diff2, merge3(a).t(), diff2)
                val finalRect = Mat().t()
                Core.add(clean, diff2, finalRect)

                // where anything changed (old print erased or new print added)
                val changed = Mat().t()
                Core.max(oldF, a, changed)

                // ---- 4. back into the photo, only where changed ----
                val flags = Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP
                val finalWarp = Mat().t()
                val changedWarp = Mat().t()
                Imgproc.warpPerspective(finalRect, finalWarp, toRect, rgb.size(), flags)
                Imgproc.warpPerspective(changed, changedWarp, toRect, rgb.size(), flags)
                Imgproc.GaussianBlur(changedWarp, changedWarp, Size(0.0, 0.0), 1.0)

                val rgbF = Mat().t()
                rgb.convertTo(rgbF, CvType.CV_32FC3)
                val d3 = Mat().t()
                Core.subtract(finalWarp, rgbF, d3)
                Core.multiply(d3, merge3(changedWarp).t(), d3)
                val outF = Mat().t()
                Core.add(rgbF, d3, outF)
                val out8 = Mat().t()
                outF.convertTo(out8, CvType.CV_8UC3)
                val outRgba = Mat().t()
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
