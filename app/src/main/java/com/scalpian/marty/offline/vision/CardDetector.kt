package com.scalpian.marty.offline.vision

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the largest card-shaped quadrilateral in a photo.
 *
 * Pass 1 follows edges (works for a card on a similar-brightness surface); pass 2 falls back
 * to a brightness threshold (works for a white card on a darker table). If both fail the UI
 * shows the manual corner editor instead.
 */
object CardDetector {

    fun detect(bitmap: Bitmap): Quad? {
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            val imgArea = bitmap.width.toDouble() * bitmap.height

            Imgproc.Canny(gray, edges, 40.0, 120.0)
            val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edges, edges, k3)
            k3.release()
            var best = findCardQuad(edges, imgArea)

            if (best == null) {
                val bin = Mat()
                Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
                Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, k5)
                k5.release()
                best = findCardQuad(bin, imgArea)
                bin.release()
            }
            return best
        } finally {
            rgba.release()
            gray.release()
            edges.release()
        }
    }

    private fun findCardQuad(binary: Mat, imgArea: Double): Quad? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val work = binary.clone()
        Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        work.release()
        hierarchy.release()

        var best: Quad? = null
        var bestArea = 0.0
        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)
            if (area < imgArea * 0.03 || area > imgArea * 0.92 || area <= bestArea) {
                cnt.release()
                continue
            }
            val c2f = MatOfPoint2f(*cnt.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val cvPts = approx.toArray()
                val asInt = MatOfPoint(*cvPts)
                val convex = Imgproc.isContourConvex(asInt)
                asInt.release()
                if (convex) {
                    val quad = Quad.ordered(cvPts.map { PointF(it.x.toFloat(), it.y.toFloat()) })
                    val w = (Quad.dist(quad.tl, quad.tr) + Quad.dist(quad.bl, quad.br)) / 2f
                    val h = (Quad.dist(quad.tl, quad.bl) + Quad.dist(quad.tr, quad.br)) / 2f
                    val ratio = min(w, h) / max(w, h)
                    // A real card is ~0.714; allow for perspective.
                    if (ratio in 0.50f..0.88f) {
                        best = quad
                        bestArea = area
                    }
                }
            }
            c2f.release()
            approx.release()
            cnt.release()
        }
        return best
    }
}
