package com.scalpian.marty.offline

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.scalpian.marty.offline.data.CardCode
import com.scalpian.marty.offline.data.InputMode
import com.scalpian.marty.offline.data.MartySettings
import com.scalpian.marty.offline.ui.CaptureScreen
import com.scalpian.marty.offline.ui.GalleryRevealScreen
import com.scalpian.marty.offline.ui.HomeScreenInput
import com.scalpian.marty.offline.ui.LockInputFlow
import com.scalpian.marty.offline.ui.MenuScreen
import com.scalpian.marty.offline.ui.OptionsScreen
import com.scalpian.marty.offline.ui.QuadScreen
import com.scalpian.marty.offline.ui.saveToPhoneLibrary
import com.scalpian.marty.offline.ui.vibrateOnce
import com.scalpian.marty.offline.vision.CardCompositor
import com.scalpian.marty.offline.vision.CardDetector
import com.scalpian.marty.offline.vision.CardFaceRenderer
import com.scalpian.marty.offline.vision.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

private enum class Screen { MENU, OPTIONS, CAPTURE, TRAIN_CAPTURE, ADJUST, TRAIN_RESULT, INPUT, REVEAL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status-bar icons everywhere: the whole app is dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        OpenCVLoader.initLocal()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { MartyApp() }
            }
        }
    }
}

@Composable
private fun MartyApp() {
    val context = LocalContext.current
    val settings = remember { MartySettings(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.MENU) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }

    fun reset() {
        photo = null
        quad = null
        result = null
        screen = Screen.MENU
    }

    // Detection runs the moment the photo is taken, so it's ready by the time the code is entered.
    fun onPhoto(bmp: Bitmap, training: Boolean) {
        photo = bmp
        scope.launch {
            val found = withContext(Dispatchers.Default) { CardDetector.detect(bmp) }
            quad = found
            screen = when {
                training -> Screen.TRAIN_RESULT
                found == null -> Screen.ADJUST
                else -> Screen.INPUT
            }
        }
    }

    // The code has been entered: build the card, swap it into the photo, open the fake roll.
    fun onCode(digits: String) {
        val src = photo
        val q = quad
        if (src == null || q == null) {
            reset()
            return
        }
        working = true
        scope.launch {
            val card = CardCode.decode(digits, settings.suitOrder, settings.plusOne)
            val out = withContext(Dispatchers.Default) {
                CardCompositor.compositeInk(src, q, card)
            }
            if (settings.saveToGallery) withContext(Dispatchers.IO) { saveToPhoneLibrary(context, out) }
            if (settings.vibrate) vibrateOnce(context)
            result = out
            working = false
            screen = Screen.REVEAL
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.MENU -> MenuScreen(
                onPerform = { screen = Screen.CAPTURE },
                onTraining = { screen = Screen.TRAIN_CAPTURE },
                onOptions = { screen = Screen.OPTIONS }
            )

            Screen.OPTIONS -> OptionsScreen(settings, onBack = { screen = Screen.MENU })

            Screen.CAPTURE -> CaptureScreen(
                leftHanded = settings.leftHanded,
                onCaptured = { onPhoto(it, training = false) },
                onBack = { reset() }
            )

            Screen.TRAIN_CAPTURE -> CaptureScreen(
                leftHanded = settings.leftHanded,
                onCaptured = { onPhoto(it, training = true) },
                onBack = { reset() }
            )

            Screen.ADJUST -> photo?.let { p ->
                QuadScreen(
                    bitmap = p,
                    detected = null,
                    training = false,
                    onConfirm = { confirmed -> quad = confirmed; screen = Screen.INPUT },
                    onRetake = { screen = Screen.CAPTURE }
                )
            }

            Screen.TRAIN_RESULT -> photo?.let { p ->
                QuadScreen(
                    bitmap = p,
                    detected = quad,
                    training = true,
                    onConfirm = {},
                    onRetake = { screen = Screen.TRAIN_CAPTURE }
                )
            }

            Screen.INPUT -> when (settings.inputMode) {
                InputMode.LOCK_SCREEN -> LockInputFlow(
                    settings = settings,
                    onSubmit = { onCode(it) },
                    onCancel = { screen = Screen.CAPTURE }
                )
                InputMode.HOME_SCREEN -> HomeScreenInput(
                    requiredDigits = CardCode.MEANINGFUL_DIGITS,
                    wallpaperPath = settings.homeBgPath,
                    onSubmit = { onCode(it) },
                    onAbort = { screen = Screen.CAPTURE }
                )
            }

            Screen.REVEAL -> result?.let { r ->
                GalleryRevealScreen(result = r, rollPaths = settings.rollPaths, onClose = { reset() })
            }
        }

        // Invisible-ish busy indicator: only shown if compositing takes a noticeable moment.
        if (working && screen != Screen.INPUT) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
