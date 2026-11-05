package com.scalpian.marty.offline.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.scalpian.marty.offline.data.PlayingCard
import com.scalpian.marty.offline.data.Suit

/**
 * Draws a flat, front-on playing card entirely in code (no image assets, so no artwork
 * licensing questions). The compositor warps this onto the card found in the photo.
 */
object CardFaceRenderer {
    private val RED = Color.rgb(200, 16, 46)
    private val BLACK = Color.rgb(20, 20, 24)

    fun rankLabel(v: Int): String = when (v) {
        1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> v.toString()
    }

    fun render(card: PlayingCard, w: Int = 500, h: Int = 700): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        c.drawRoundRect(RectF(0f, 0f, wf, hf), 16f, 16f, paint)
        paint.color = Color.rgb(205, 205, 210)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        c.drawRoundRect(RectF(1.5f, 1.5f, wf - 1.5f, hf - 1.5f), 16f, 16f, paint)

        drawInk(c, card, wf, hf)
        return bmp
    }

    /** The colour the card's print is drawn in (red suits / black suits). */
    fun inkColor(card: PlayingCard): Int = if (card.suit.isRed) RED else BLACK

    /**
     * Only the print (corner indices + pips / court letter) on a fully transparent canvas:
     * no white card, no border. The compositor prints this onto the real photographed card.
     */
    fun renderInk(card: PlayingCard, w: Int = 500, h: Int = 700): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawInk(Canvas(bmp), card, w.toFloat(), h.toFloat())
        return bmp
    }

    private fun drawInk(c: Canvas, card: PlayingCard, wf: Float, hf: Float) {
        val ink = inkColor(card)
        drawCorners(c, card, ink, wf, hf)
        when {
            card.value == 1 -> drawSuit(c, card.suit, wf / 2f, hf / 2f, 110f, ink)
            card.value in 2..10 -> drawPips(c, card, ink, wf, hf)
            else -> drawCourt(c, card, ink, wf, hf)
        }
    }

    private fun drawCorners(c: Canvas, card: PlayingCard, ink: Int, w: Float, h: Float) {
        val label = rankLabel(card.value)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = if (label.length == 2) 64f else 78f
        }
        fun corner() {
            c.drawText(label, 56f, 104f, text)
            drawSuit(c, card.suit, 56f, 152f, 26f, ink)
        }
        corner()
        c.save()
        c.rotate(180f, w / 2f, h / 2f)
        corner()
        c.restore()
    }

    private fun drawPips(c: Canvas, card: PlayingCard, ink: Int, w: Float, h: Float) {
        val l = 0f
        val m = 0.5f
        val r = 1f
        val six = listOf(l to 0f, r to 0f, l to 0.5f, r to 0.5f, l to 1f, r to 1f)
        val eightOuter = listOf(
            l to 0f, r to 0f, l to 1f / 3f, r to 1f / 3f,
            l to 2f / 3f, r to 2f / 3f, l to 1f, r to 1f
        )
        val layout: List<Pair<Float, Float>> = when (card.value) {
            2 -> listOf(m to 0f, m to 1f)
            3 -> listOf(m to 0f, m to 0.5f, m to 1f)
            4 -> listOf(l to 0f, r to 0f, l to 1f, r to 1f)
            5 -> listOf(l to 0f, r to 0f, l to 1f, r to 1f, m to 0.5f)
            6 -> six
            7 -> six + (m to 0.25f)
            8 -> six + (m to 0.25f) + (m to 0.75f)
            9 -> eightOuter + (m to 0.5f)
            else -> eightOuter + (m to 1f / 6f) + (m to 5f / 6f)
        }
        for ((col, row) in layout) {
            val x = w * (0.30f + 0.40f * col)
            val y = h * (0.20f + 0.60f * row)
            c.save()
            if (row > 0.5f) c.rotate(180f, x, y)
            drawSuit(c, card.suit, x, y, 36f, ink)
            c.restore()
        }
    }

    private fun drawCourt(c: Canvas, card: PlayingCard, ink: Int, w: Float, h: Float) {
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        c.drawRoundRect(RectF(110f, 130f, w - 110f, h - 130f), 10f, 10f, frame)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 300f
        }
        c.drawText(rankLabel(card.value), w / 2f, h / 2f + 105f, big)
        drawSuit(c, card.suit, 170f, 215f, 30f, ink)
        c.save()
        c.rotate(180f, w / 2f, h / 2f)
        drawSuit(c, card.suit, 170f, 215f, 30f, ink)
        c.restore()
    }

    /** [s] is a scale factor: the symbol is roughly 1.6 * s wide and tall, centred on (cx, cy). */
    private fun drawSuit(c: Canvas, suit: Suit, cx: Float, cy: Float, s: Float, ink: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.FILL
        }
        when (suit) {
            Suit.DIAMONDS -> {
                val path = Path().apply {
                    moveTo(cx, cy - 0.95f * s)
                    lineTo(cx + 0.65f * s, cy)
                    lineTo(cx, cy + 0.95f * s)
                    lineTo(cx - 0.65f * s, cy)
                    close()
                }
                c.drawPath(path, p)
            }
            Suit.HEARTS -> c.drawPath(heartPath(cx, cy, s), p)
            Suit.SPADES -> {
                c.save()
                c.scale(1f, -1f, cx, cy)
                c.drawPath(heartPath(cx, cy + 0.1f * s, s), p)
                c.restore()
                val stem = Path().apply {
                    moveTo(cx, cy + 0.1f * s)
                    lineTo(cx - 0.32f * s, cy + 0.95f * s)
                    lineTo(cx + 0.32f * s, cy + 0.95f * s)
                    close()
                }
                c.drawPath(stem, p)
            }
            Suit.CLUBS -> {
                val r = 0.42f * s
                c.drawCircle(cx, cy - 0.45f * s, r, p)
                c.drawCircle(cx - 0.5f * s, cy + 0.25f * s, r, p)
                c.drawCircle(cx + 0.5f * s, cy + 0.25f * s, r, p)
                val stem = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx - 0.3f * s, cy + 0.95f * s)
                    lineTo(cx + 0.3f * s, cy + 0.95f * s)
                    close()
                }
                c.drawPath(stem, p)
            }
        }
    }

    private fun heartPath(cx: Float, cy: Float, s: Float): Path {
        val k = 1.25f * s
        return Path().apply {
            moveTo(cx, cy + 0.75f * k)
            cubicTo(cx - 1.1f * k, cy - 0.05f * k, cx - 0.62f * k, cy - 0.85f * k, cx, cy - 0.32f * k)
            cubicTo(cx + 0.62f * k, cy - 0.85f * k, cx + 1.1f * k, cy - 0.05f * k, cx, cy + 0.75f * k)
            close()
        }
    }
}
