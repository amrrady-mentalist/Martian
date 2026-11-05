package com.scalpian.marty.offline.vision

import android.graphics.Bitmap
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

/**
 * Replaces the card inside [quad] with [face], warped into the same perspective.
 *
 * Steps: warp the flat face onto a slightly enlarged quad (so none of the old card peeks
 * out), match its brightness and softness to the photo, feather the edge, alpha-blend.
 */
object CardCompositor {

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
