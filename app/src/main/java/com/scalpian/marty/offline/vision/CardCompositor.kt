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
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.min

/** [result] is the edited photo; [overlay] (preview only) shows what is erased (red) and protected (green). */
class CompositeOutput(val result: Bitmap, val overlay: Bitmap?)

object CardCompositor {

    private const val RW = 500
    private const val RH = 700
    private const val MG = 15                    // margin around the card in the flattened image
    private const val CW = RW + 2 * MG
    private const val CH = RH + 2 * MG

    fun compositeInk(photo: Bitmap, quad: Quad, card: PlayingCard): Bitmap = compositeCore(photo, quad, card, false).result

    fun compositePreview(photo: Bitmap, quad: Quad, card: PlayingCard): CompositeOutput = compositeCore(photo, quad, card, true)

    /**
     * "Ink swap": edit the REAL card instead of pasting a synthetic one.
     *
     * Decisions are made by STRUCTURE first and colour second, relative to the card's own paper:
     *   - the flattened card gets a margin, so print never touches the border but fingers always do;
     *   - paper brightness (with its glare and shading) is modelled from the bright paper pixels only;
     *   - blobs darker than that model are classified: a big blob attached to the card edge is a finger /
     *     hand, a compact blob inside is print (this works under pink glare, blur, any colour cast);
     *   - colour is only used to rescue print that touches a finger, to find pale nails, and to catch the
     *     pinkish rim of red print;
     *   - old print is grown to cover its blur halo and filled by inpainting from the surrounding paper
     *     (finger and outside-the-card pixels are replaced by the paper model first so nothing leaks in);
     *   - the new print is multiplied onto the paper and warped back; only changed pixels are blended.
     * Falls back to the old full-card paste if the card can't be analysed.
     */
    private fun compositeCore(photo: Bitmap, quad: Quad, card: PlayingCard, wantOverlay: Boolean): CompositeOutput {
        val sc = Scope()
        try {
            return with(sc) {
                val src = Mat().keep()
                Utils.bitmapToMat(photo, src)
                val rgb = Mat().keep()
                Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
                fun fallback() = CompositeOutput(composite(photo, quad, CardFaceRenderer.render(card)), null)

                // ---- 1. flatten the card (with a margin) ----
                val pts = orientedCorners(quad)
                val quadPts = MatOfPoint2f(*pts.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()).keep()
                val rectPts = MatOfPoint2f(
                    Point(MG.toDouble(), MG.toDouble()), Point((MG + RW).toDouble(), MG.toDouble()),
                    Point((MG + RW).toDouble(), (MG + RH).toDouble()), Point(MG.toDouble(), (MG + RH).toDouble())
                ).keep()
                val toRect = Imgproc.getPerspectiveTransform(quadPts, rectPts).keep()
                val cardRgb = Mat().keep()
                Imgproc.warpPerspective(rgb, cardRgb, toRect, Size(CW.toDouble(), CH.toDouble()), Imgproc.INTER_LINEAR)

                fun roundedRect(inset: Int): Mat {
                    val m = Mat.zeros(CH, CW, CvType.CV_8UC1).keep()
                    val r = 22
                    Imgproc.rectangle(m, Point((inset + r).toDouble(), inset.toDouble()),
                        Point((CW - inset - r).toDouble(), (CH - inset).toDouble()), Scalar(255.0), -1)
                    Imgproc.rectangle(m, Point(inset.toDouble(), (inset + r).toDouble()),
                        Point((CW - inset).toDouble(), (CH - inset - r).toDouble()), Scalar(255.0), -1)
                    for ((cx, cy) in listOf(
                        (inset + r) to (inset + r), (CW - inset - r) to (inset + r),
                        (inset + r) to (CH - inset - r), (CW - inset - r) to (CH - inset - r)
                    )) Imgproc.circle(m, Point(cx.toDouble(), cy.toDouble()), r, Scalar(255.0), -1)
                    return m
                }
                val valid = roundedRect(MG + 3)            // inside the card: used for paper statistics
                val validOut = roundedRect(MG - 8)         // card + a few px beyond the snapped edge: where print may be erased
                val validOutF = soft(validOut, 0.0).keep()

                val cardF = Mat().keep()
                cardRgb.convertTo(cardF, CvType.CV_32FC3)
                val gray = Mat().keep()
                Imgproc.cvtColor(cardRgb, gray, Imgproc.COLOR_RGB2GRAY)
                val grayF = Mat().keep()
                gray.convertTo(grayF, CvType.CV_32F)

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

                // ---- 2. paper brightness model (no colour needed) ----
                val lMean = Core.mean(lum, valid).`val`[0]
                val m0 = and(greaterThan(lum, lMean), valid)
                if (Core.countNonZero(m0) < 5000) return@with fallback()
                val pl0 = paperModel(lum, m0, 40.0)
                val m1 = and(and(valid, not(lessThan(lum, scaled(pl0, 0.9)))), lessThan(lum, scaled(pl0, 1.2)))
                if (Core.countNonZero(m1) < 4000) return@with fallback()
                val pl = paperModel(lum, m1, 25.0)
                val pa = paperModel(aF, m1, 40.0)
                val pb = paperModel(bF, m1, 40.0)
                val dA = minus(aF, pa)                       // redness relative to the local paper
                val dLoc = magnitude(dA, minus(bF, pb))      // colour difference from the local paper

                // ---- 3. dark blobs, classified by STRUCTURE ----
                val core = and(vo(validOut), lessThan(lum, scaled(pl, 0.87)))
                Imgproc.morphologyEx(core, core, Imgproc.MORPH_CLOSE, ellipse(3).keep())
                val labels = Mat().keep()
                val stats = Mat().keep()
                val cents = Mat().keep()
                val n = Imgproc.connectedComponentsWithStats(core, labels, stats, cents, 8, CvType.CV_32S)
                val statData = IntArray(n * 5)
                stats.get(0, 0, statData)
                var finger = Mat.zeros(CH, CW, CvType.CV_8UC1).keep()
                var inkc = Mat.zeros(CH, CW, CvType.CV_8UC1).keep()
                val band = MG + 12                           // within ~12 px of the card's own edge
                var processed = 0
                for (i in 1 until n) {
                    val x = statData[i * 5]
                    val y = statData[i * 5 + 1]
                    val bw = statData[i * 5 + 2]
                    val bh = statData[i * 5 + 3]
                    val area = statData[i * 5 + 4]
                    if (area < 4) continue
                    val touches = x <= band || y <= band || x + bw >= CW - band || y + bh >= CH - band
                    val target = when {
                        touches && area >= 2500 -> finger                // big region attached to the card edge = finger / hand / edge line
                        area > 0.10 * RW * RH -> continue                // card-sized dark region (shadow?): leave alone
                        else -> inkc                                     // compact blob = print
                    }
                    if (++processed > 1500) break
                    val rect = Rect(x, y, bw, bh)
                    val cm = Mat().keep()
                    Core.compare(labels.submat(rect), Scalar(i.toDouble()), cm, Core.CMP_EQ)
                    val roi = target.submat(rect)
                    Core.bitwise_or(roi, cm, roi)
                }

                // ---- 4. colour: rescue print merged into a finger blob, find pale nails ----
                val pLs = Mat().keep()
                Core.max(pl, Scalar(1.0), pLs)
                val tRatio = over(lum, pLs)
                Core.min(tRatio, Scalar(1.05), tRatio)
                val dDark = magnitude(minus(aF, times(pa, tRatio)), minus(bF, times(pb, tRatio)))
                val vOutM = vo(validOut)
                val blackInk = and(and(lessThan(lum, scaled(pl, 0.55)), lessThan(dDark, 9.0)), vOutM)
                val redInk = and(greaterThan(dA, 28.0), vOutM)
                val rescue = and(finger, or(blackInk, redInk))
                inkc = or(inkc, rescue)
                finger = and(finger, not(rescue))
                // the pinkish rim around red print is print, not skin
                val redBlob = and(inkc, dil(redInk, 5))
                val halo = and(and(greaterThan(dA, 6.0), vOutM), dil(redBlob, 7))
                inkc = or(inkc, halo)
                finger = and(finger, not(halo))

                // pale nails: warmer than the local paper, same brightness, touching a finger
                var warm = and(and(greaterThan(dLoc, 7.5), not(lessThan(lum, scaled(pl, 0.80)))), vOutM)
                Imgproc.morphologyEx(warm, warm, Imgproc.MORPH_OPEN, ellipse(5).keep())
                warm = and(warm, not(dil(inkc, 9)))          // the pinkish rim of a red pip is print, not a nail
                val near = dil(finger, 15)
                val labels2 = Mat().keep()
                val stats2 = Mat().keep()
                val cents2 = Mat().keep()
                val n2 = Imgproc.connectedComponentsWithStats(warm, labels2, stats2, cents2, 8, CvType.CV_32S)
                val statData2 = IntArray(n2 * 5)
                stats2.get(0, 0, statData2)
                val nail = Mat.zeros(CH, CW, CvType.CV_8UC1).keep()
                for (i in 1 until min(n2, 400)) {
                    val rect = Rect(statData2[i * 5], statData2[i * 5 + 1], statData2[i * 5 + 2], statData2[i * 5 + 3])
                    val cm = Mat().keep()
                    Core.compare(labels2.submat(rect), Scalar(i.toDouble()), cm, Core.CMP_EQ)
                    if (Core.countNonZero(and(cm, near.submat(rect))) > 0) {
                        val roi = nail.submat(rect)
                        Core.bitwise_or(roi, cm, roi)
                    }
                }
                val prot = or(finger, nail)

                // ---- 5. old print: cores plus their soft fringe (hysteresis), grown to cover the blur halo ----
                val weak = and(and(vOutM, lessThan(lum, scaled(pl, 0.972))), not(prot))
                var ink = or(and(dil(inkc, 31), weak), inkc)
                ink = dil(ink, 17)
                ink = and(and(ink, not(dil(prot, 5))), vOutM)
                val inkF = soft(ink, 0.0).keep()
                val oldF = soft(ink, 5.0).keep()
                Core.max(oldF, inkF, oldF)                   // full strength inside the mask, feather only outside it
                Core.multiply(oldF, validOutF, oldF)

                // ---- 6. fill ----
                val cleanPaper = and(
                    and(and(valid, not(lessThan(lum, scaled(pl, 0.9)))), lessThan(lum, scaled(pl, 1.2))),
                    and(not(dil(ink, 9)), not(dil(prot, 11)))
                )
                if (Core.countNonZero(cleanPaper) < 3000) return@with fallback()
                val p2 = paperModel(cardF, cleanPaper, 25.0)

                val blur3 = Mat().keep()
                Imgproc.GaussianBlur(grayF, blur3, Size(0.0, 0.0), 3.0)
                val grain = minus(grayF, blur3)
                val meanM = MatOfDouble().keep()
                val stdM = MatOfDouble().keep()
                Core.meanStdDev(grain, meanM, stdM, cleanPaper)
                val noise = Mat(CH, CW, CvType.CV_32F).keep()
                Core.randn(noise, 0.0, stdM.toArray()[0] * 0.9)

                val notProt = not(prot)
                val dist = Mat().keep()
                Imgproc.distanceTransform(notProt, dist, Imgproc.DIST_L2, 3)
                val shade = Mat().keep()
                dist.convertTo(shade, CvType.CV_32F, -0.2, 0.0)                 // -d / 5
                Core.exp(shade, shade)
                shade.convertTo(shade, CvType.CV_32F, -0.14, 1.0)               // 1 - 0.14 * exp(-d / 5)

                // finger pixels and everything outside the card get the paper model first, so inpainting never
                // copies skin or background colours into the card
                val temp = cardF.clone().keep()
                p2.copyTo(temp, or(dil(prot, 7), not(valid)))
                val temp8 = Mat().keep()
                temp.convertTo(temp8, CvType.CV_8UC3)
                val inkMask = dil(ink, 3)
                val inp8 = Mat().keep()
                Photo.inpaint(temp8, inkMask, inp8, 6.0, Photo.INPAINT_TELEA)
                val inp = Mat().keep()
                inp8.convertTo(inp, CvType.CV_32FC3)
                Imgproc.GaussianBlur(inp, inp, Size(0.0, 0.0), 5.0)             // smooth: no trace of the old silhouette
                Core.multiply(inp, merge3(shade).keep(), inp)
                Core.add(inp, merge3(noise).keep(), inp)                        // the paper's own grain

                val diff = minus(inp, cardF)
                Core.multiply(diff, merge3(oldF).keep(), diff)
                val clean = Mat().keep()
                Core.add(cardF, diff, clean)

                // ---- 7. print the new card ----
                val inkBmp = CardFaceRenderer.renderInk(card, RW, RH)
                val inkRgba = Mat().keep()
                Utils.bitmapToMat(inkBmp, inkRgba)
                inkBmp.recycle()
                val inkChans = ArrayList<Mat>()
                Core.split(inkRgba, inkChans)
                inkChans.forEach { it.keep() }
                // the 500x700 ink layer sits inside the margin
                val alphaFull = Mat.zeros(CH, CW, CvType.CV_8UC1).keep()
                inkChans[3].copyTo(alphaFull.submat(Rect(MG, MG, RW, RH)))
                val a = soft(alphaFull, 0.7).keep()
                Core.multiply(a, validOutF, a)
                val protSoft = soft(dil(prot, 5), 1.0).keep()
                Core.multiply(a, oneMinus(protSoft).keep(), a)                  // never print on a finger or nail

                val col = CardFaceRenderer.inkColor(card)
                val inked = Mat().keep()
                Core.multiply(
                    clean,
                    Scalar(Color.red(col) / 255.0, Color.green(col) / 255.0, Color.blue(col) / 255.0),
                    inked
                )
                val diff2 = minus(inked, clean)
                Core.multiply(diff2, merge3(a).keep(), diff2)
                val finalRect = Mat().keep()
                Core.add(clean, diff2, finalRect)

                val changed = Mat().keep()
                Core.max(oldF, a, changed)

                // ---- 8. back into the photo, only where changed ----
                val flags = Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP
                val finalWarp = Mat().keep()
                val changedWarp = Mat().keep()
                Imgproc.warpPerspective(finalRect, finalWarp, toRect, rgb.size(), flags)
                Imgproc.warpPerspective(changed, changedWarp, toRect, rgb.size(), flags)
                Imgproc.GaussianBlur(changedWarp, changedWarp, Size(0.0, 0.0), 1.0)

                val rgbF = Mat().keep()
                rgb.convertTo(rgbF, CvType.CV_32FC3)
                val d3 = minus(finalWarp, rgbF)
                Core.multiply(d3, merge3(changedWarp).keep(), d3)
                val outF = Mat().keep()
                Core.add(rgbF, d3, outF)
                val out8 = Mat().keep()
                outF.convertTo(out8, CvType.CV_8UC3)
                val outRgba = Mat().keep()
                Imgproc.cvtColor(out8, outRgba, Imgproc.COLOR_RGB2RGBA)
                val result = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(outRgba, result)

                var overlayBmp: Bitmap? = null
                if (wantOverlay) {
                    // red = old print that will be erased, green = fingers / nails that are protected (8-bit only: cheap on memory)
                    val eraseWarp = Mat().keep()
                    val protWarp = Mat().keep()
                    Imgproc.warpPerspective(oldF, eraseWarp, toRect, rgb.size(), flags)
                    Imgproc.warpPerspective(soft(prot, 1.0).keep(), protWarp, toRect, rgb.size(), flags)
                    val eraseM = greaterThan(eraseWarp, 0.35)
                    val protM = greaterThan(protWarp, 0.35)
                    val tinted = rgb.clone().keep()
                    tinted.setTo(Scalar(255.0, 50.0, 50.0), eraseM)
                    tinted.setTo(Scalar(40.0, 220.0, 90.0), protM)
                    val mixed = Mat().keep()
                    Core.addWeighted(rgb, 0.45, tinted, 0.55, 0.0, mixed)
                    val ovRgba = Mat().keep()
                    Imgproc.cvtColor(mixed, ovRgba, Imgproc.COLOR_RGB2RGBA)
                    val ob = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(ovRgba, ob)
                    overlayBmp = ob
                }
                CompositeOutput(result, overlayBmp)
            }
        } finally {
            sc.close()
        }
    }

    private fun vo(m: Mat): Mat = m          // (readability) the "outer valid" mask is used directly as a 0/255 mask

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
