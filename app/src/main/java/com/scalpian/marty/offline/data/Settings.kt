package com.scalpian.marty.offline.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class InputMode { LOCK_SCREEN, HOME_SCREEN }

/** Default order mirrors the original app's "TreCoeuPiCar" (Clubs, Hearts, Spades, Diamonds). */
enum class Suit(val symbol: String, val isRed: Boolean, val label: String) {
    CLUBS("♣", false, "Clubs"),
    HEARTS("♥", true, "Hearts"),
    SPADES("♠", false, "Spades"),
    DIAMONDS("♦", true, "Diamonds")
}

/** All user options, persisted in SharedPreferences and observable from Compose. */
class MartySettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("marty_settings", Context.MODE_PRIVATE)

    var inputMode by mutableStateOf(
        runCatching { InputMode.valueOf(prefs.getString("inputMode", null)!!) }
            .getOrDefault(InputMode.LOCK_SCREEN)
    )
        private set

    var suitOrder by mutableStateOf(loadSuits())
        private set

    /** Entered value = real value + 1 (Ace = 02 ... King = 14). Mirrors the old "+1 pin code" option. */
    var plusOne by mutableStateOf(prefs.getBoolean("plusOne", true))
        private set

    /** Lock-screen mode only: 6-digit PIN, the last digits are ignored. */
    var sixDigits by mutableStateOf(prefs.getBoolean("sixDigits", false))
        private set

    var leftHanded by mutableStateOf(prefs.getBoolean("leftHanded", false))
        private set

    var vibrate by mutableStateOf(prefs.getBoolean("vibrate", true))
        private set

    /** Also write the result into the phone's real photo library (DCIM/Camera). */
    var saveToGallery by mutableStateOf(prefs.getBoolean("saveToGallery", false))
        private set

    var lockBgPath by mutableStateOf(prefs.getString("lockBgPath", null))
        private set

    var homeBgPath by mutableStateOf(prefs.getString("homeBgPath", null))
        private set

    var rollPaths by mutableStateOf(
        (prefs.getString("rollPaths", "") ?: "").split("|").filter { it.isNotBlank() && File(it).exists() }
    )
        private set

    // ---- setters ----

    fun updateInputMode(v: InputMode) { inputMode = v; prefs.edit().putString("inputMode", v.name).apply() }
    fun updatePlusOne(v: Boolean) { plusOne = v; prefs.edit().putBoolean("plusOne", v).apply() }
    fun updateSixDigits(v: Boolean) { sixDigits = v; prefs.edit().putBoolean("sixDigits", v).apply() }
    fun updateLeftHanded(v: Boolean) { leftHanded = v; prefs.edit().putBoolean("leftHanded", v).apply() }
    fun updateVibrate(v: Boolean) { vibrate = v; prefs.edit().putBoolean("vibrate", v).apply() }
    fun updateSaveToGallery(v: Boolean) { saveToGallery = v; prefs.edit().putBoolean("saveToGallery", v).apply() }

    fun moveSuit(index: Int, delta: Int) {
        val target = index + delta
        if (index !in suitOrder.indices || target !in suitOrder.indices) return
        val list = suitOrder.toMutableList()
        val moved = list.removeAt(index)
        list.add(target, moved)
        suitOrder = list
        prefs.edit().putString("suitOrder", list.joinToString(",") { it.name }).apply()
    }

    fun setLockBackground(uri: Uri?) {
        lockBgPath?.let { File(it).delete() }
        lockBgPath = uri?.let { copyIn(it, "lockbg") }
        prefs.edit().putString("lockBgPath", lockBgPath).apply()
    }

    fun setHomeBackground(uri: Uri?) {
        homeBgPath?.let { File(it).delete() }
        homeBgPath = uri?.let { copyIn(it, "homebg") }
        prefs.edit().putString("homeBgPath", homeBgPath).apply()
    }

    fun addRollImages(uris: List<Uri>) {
        val added = uris.mapNotNull { copyIn(it, "roll") }
        rollPaths = rollPaths + added
        prefs.edit().putString("rollPaths", rollPaths.joinToString("|")).apply()
    }

    fun clearRoll() {
        rollPaths.forEach { File(it).delete() }
        rollPaths = emptyList()
        prefs.edit().putString("rollPaths", "").apply()
    }

    // ---- helpers ----

    private fun loadSuits(): List<Suit> {
        val saved = prefs.getString("suitOrder", null)
            ?.split(",")
            ?.mapNotNull { runCatching { Suit.valueOf(it) }.getOrNull() }
        return if (saved != null && saved.size == 4 && saved.toSet().size == 4) saved
        else listOf(Suit.CLUBS, Suit.HEARTS, Suit.SPADES, Suit.DIAMONDS)
    }

    private fun copyIn(uri: Uri, prefix: String): String? {
        return try {
            val f = File(appContext.filesDir, "${prefix}_${System.nanoTime()}.jpg")
            val input = appContext.contentResolver.openInputStream(uri) ?: return null
            input.use { src -> f.outputStream().use { dst -> src.copyTo(dst) } }
            f.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
