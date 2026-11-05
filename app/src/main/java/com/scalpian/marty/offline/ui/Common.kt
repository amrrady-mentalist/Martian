package com.scalpian.marty.offline.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Hides the real status/navigation bars while a fake system screen is showing. */
@Composable
fun ImmersiveWhileVisible() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Decodes a file, sampling it down so the longest side is at most about [maxDim]. */
fun decodeScaledFile(path: String?, maxDim: Int = 1600): Bitmap? {
    if (path == null) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}

fun Bitmap.downscaled(maxDim: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxDim) return this
    val k = maxDim.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * k).toInt(), (height * k).toInt(), true)
}

fun vibrateOnce(context: Context) {
    runCatching {
        val v = context.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** Saves into DCIM/Camera with a stock-looking file name, so it sits in the real library too. */
fun saveToPhoneLibrary(context: Context, bitmap: Bitmap) {
    runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
