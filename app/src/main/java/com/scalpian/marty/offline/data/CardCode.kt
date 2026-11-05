package com.scalpian.marty.offline.data

data class PlayingCard(val value: Int, val suit: Suit) {
    /** 1 = Ace ... 11 = Jack, 12 = Queen, 13 = King. */
    init { require(value in 1..13) }
}

/**
 * The secret code the performer enters to pick the card.
 *
 * Three meaningful digits, whichever input screen is used:
 *   digit 1   = position of the suit in the configured suit order (1-4)
 *   digit 2-3 = card value as two digits (01-13), or value + 1 (02-14) when "+1" is on
 *
 * Lock-screen mode uses a 4- or 6-digit PIN: only the first three digits matter, the
 * rest are free filler. Home-screen mode collects exactly the three digits by swiping.
 */
object CardCode {
    const val MEANINGFUL_DIGITS = 3

    fun decode(digits: String, suitOrder: List<Suit>, plusOne: Boolean): PlayingCard {
        val d = digits.filter { it.isDigit() }.padEnd(MEANINGFUL_DIGITS, '0')
        val suitIndex = Math.floorMod(d[0].digitToInt() - 1, 4)
        var raw = d[1].digitToInt() * 10 + d[2].digitToInt()
        if (plusOne) raw -= 1
        val value = Math.floorMod(raw - 1, 13) + 1
        return PlayingCard(value, suitOrder[suitIndex])
    }

    fun encode(card: PlayingCard, suitOrder: List<Suit>, plusOne: Boolean): String {
        val s = suitOrder.indexOf(card.suit) + 1
        val v = card.value + if (plusOne) 1 else 0
        return "$s${v.toString().padStart(2, '0')}"
    }
}
