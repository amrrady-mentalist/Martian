package com.scalpian.marty.offline.vision

import android.graphics.PointF
import kotlin.math.hypot

/** Four corners of a card in photo pixel coordinates: top-left, top-right, bottom-right, bottom-left. */
data class Quad(val tl: PointF, val tr: PointF, val br: PointF, val bl: PointF) {
    val points: List<PointF> get() = listOf(tl, tr, br, bl)

    companion object {
        /** Sorts any four points into tl/tr/br/bl using the classic sum/difference trick. */
        fun ordered(p: List<PointF>): Quad {
            val tl = p.minBy { it.x + it.y }
            val br = p.maxBy { it.x + it.y }
            val tr = p.minBy { it.y - it.x }
            val bl = p.maxBy { it.y - it.x }
            return Quad(tl, tr, br, bl)
        }

        /** A centred, card-shaped starting rectangle for manual adjustment. */
        fun centered(w: Int, h: Int): Quad {
            val ch = h * 0.55f
            val cw = ch * 0.714f
            val cx = w / 2f
            val cy = h / 2f
            return Quad(
                PointF(cx - cw / 2, cy - ch / 2), PointF(cx + cw / 2, cy - ch / 2),
                PointF(cx + cw / 2, cy + ch / 2), PointF(cx - cw / 2, cy + ch / 2)
            )
        }

        fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
    }
}
