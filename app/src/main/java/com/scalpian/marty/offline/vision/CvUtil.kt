package com.scalpian.marty.offline.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Collects every Mat created during one detection / compositing run so they can all be released
 * together. NOTE: the tracking helper is called keep(), not t(): Mat already has a built-in t()
 * (transpose) which would silently win over an extension function.
 */
internal class Scope {
    private val mats = ArrayList<Mat>()

    fun <T : Mat> T.keep(): T {
        mats.add(this)
        return this
    }

    fun close() = mats.forEach { it.release() }

    // ---- masks (8-bit 0/255) ----
    fun and(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.bitwise_and(a, b, o); return o }
    fun or(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.bitwise_or(a, b, o); return o }
    fun not(a: Mat): Mat { val o = Mat().keep(); Core.bitwise_not(a, o); return o }
    fun dil(a: Mat, n: Int): Mat { val o = Mat().keep(); Imgproc.dilate(a, o, ellipse(n).keep()); return o }
    fun ero(a: Mat, n: Int): Mat { val o = Mat().keep(); Imgproc.erode(a, o, ellipse(n).keep()); return o }
    fun lessThan(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.compare(a, b, o, Core.CMP_LT); return o }
    fun lessThan(a: Mat, v: Double): Mat { val o = Mat().keep(); Core.compare(a, Scalar(v), o, Core.CMP_LT); return o }
    fun greaterThan(a: Mat, v: Double): Mat { val o = Mat().keep(); Core.compare(a, Scalar(v), o, Core.CMP_GT); return o }

    // ---- float maths ----
    fun scaled(a: Mat, k: Double): Mat { val o = Mat().keep(); a.convertTo(o, CvType.CV_32F, k); return o }
    fun plusVal(a: Mat, v: Double): Mat { val o = Mat().keep(); a.convertTo(o, CvType.CV_32F, 1.0, v); return o }
    fun minus(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.subtract(a, b, o); return o }
    fun times(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.multiply(a, b, o); return o }
    fun over(a: Mat, b: Mat): Mat { val o = Mat().keep(); Core.divide(a, b, o); return o }
    fun magnitude(x: Mat, y: Mat): Mat { val o = Mat().keep(); Core.magnitude(x, y, o); return o }

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
     * Smooth model of the paper (colour AND shading) built only from pixels where mask8 is set
     * (normalised convolution), so print and fingers never leak into it. Works for 1 or 3 channels.
     */
    fun paperModel(img: Mat, mask8: Mat, sigma: Double): Mat {
        val m = soft(mask8, 0.0).keep()
        val mm = if (img.channels() == 3) merge3(m).keep() else m
        val num = Mat().keep()
        Core.multiply(img, mm, num)
        Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sigma)
        val den = Mat().keep()
        Imgproc.GaussianBlur(m, den, Size(0.0, 0.0), sigma)
        Core.max(den, Scalar(1e-3), den)
        val dd = if (img.channels() == 3) merge3(den).keep() else den
        val out = Mat().keep()
        Core.divide(num, dd, out)
        return out
    }
}

internal fun ellipse(n: Int): Mat =
    Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(n.toDouble(), n.toDouble()))

/** 8-bit 0/255 mask -> float 0..1, optionally blurred. */
internal fun soft(mask8: Mat, sigma: Double): Mat {
    val f = Mat()
    mask8.convertTo(f, CvType.CV_32F, 1.0 / 255.0)
    if (sigma > 0.0) Imgproc.GaussianBlur(f, f, Size(0.0, 0.0), sigma)
    return f
}

internal fun merge3(m: Mat): Mat {
    val out = Mat()
    Core.merge(listOf(m, m, m), out)
    return out
}

internal fun oneMinus(m: Mat): Mat {
    val ones = Mat(m.size(), m.type(), Scalar(1.0))
    val out = Mat()
    Core.subtract(ones, m, out)
    ones.release()
    return out
}
