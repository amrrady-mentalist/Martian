package com.scalpian.marty.offline.ui

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scalpian.marty.offline.R
import com.scalpian.marty.offline.data.MartySettings
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class LockStep { BLACKOUT, AMBIENT, PIN }

/**
 * The fake lock screen: black "screen off" -> double-tap -> always-on clock -> swipe up ->
 * PIN pad. The performer's secret card code is typed on that pad (see CardCode).
 * Back = abandon the flow. Double-tap the PIN header = go back to the black screen.
 */
@Composable
fun LockInputFlow(settings: MartySettings, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    ImmersiveWhileVisible()
    BackHandler(onBack = onCancel)

    var step by remember { mutableStateOf(LockStep.BLACKOUT) }
    var pin by remember { mutableStateOf("") }
    var unlocking by remember { mutableStateOf(false) }
    val required = if (settings.sixDigits) 6 else 4
    val background = settings.lockBgPath
    val scope = rememberCoroutineScope()

    fun handleKey(key: String) {
        when (key) {
            "delete" -> if (!unlocking) pin = pin.dropLast(1)
            "empty" -> Unit
            else -> if (!unlocking && pin.length < required) {
                pin += key
                if (pin.length == required) {
                    unlocking = true
                    val code = pin
                    scope.launch {
                        delay(180)
                        onSubmit(code)
                    }
                }
            }
        }
    }

    when (step) {
        LockStep.BLACKOUT -> BlackoutScreen(onDoubleTap = { step = LockStep.AMBIENT })
        LockStep.AMBIENT -> Box(Modifier.fillMaxSize()) {
            // The PIN pad sits underneath so dragging the clock away reveals it, like a real phone.
            PinScreen(pin, unlocking, required, background, { handleKey(it) }, { pin = ""; step = LockStep.BLACKOUT })
            AmbientScreen(backgroundPath = background, onSwipeUp = { step = LockStep.PIN })
        }
        LockStep.PIN -> PinScreen(pin, unlocking, required, background, { handleKey(it) }, { pin = ""; step = LockStep.BLACKOUT })
    }
}

/** Renders the picked lock-background photo (if any) full-bleed behind the
 *  content, with a dark scrim on top so the clock/PIN dots/keypad stay
 *  readable — colors and stop positions match the web app's scrimAmbient /
 *  scrimPin CSS gradients exactly. */
@Composable
private fun LockBackground(path: String?, scrimStops: List<Pair<Float, Color>>) {
    val bmp = remember(path) {
        decodeScaledFile(path, 1600)
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(*scrimStops.toTypedArray()))
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }
}

private fun doubleTapModifier(windowMillis: Long = 320L, onDoubleTap: () -> Unit): Modifier {
    var lastTap = 0L
    return Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = {
            val now = System.currentTimeMillis()
            if (now - lastTap < windowMillis) {
                lastTap = 0L
                onDoubleTap()
            } else {
                lastTap = now
            }
        })
    }
}

@Composable
private fun BlackoutScreen(onDoubleTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(doubleTapModifier(onDoubleTap = onDoubleTap))
    )
}

/** Custom clock typeface — currently expecting "Valcore", a bold poster-style
 *  numeral font. Font resources are matched by filename (without extension),
 *  so both .ttf and .otf work the same way here — no code change needed
 *  between them, just drop the file in as:
 *    app/src/main/res/font/clock_numerals.otf
 *  (or .ttf, whichever you have — keep the base name "clock_numerals").
 */
private val ClockFontFamily = FontFamily(Font(R.font.clock_numerals))

// Same two knobs Samsung/OEM Always-On-Display clock customization exposes:
// "Size" (overall font size) and "Stretch" (a vertical-only scale that makes
// the digits taller/more elongated without changing their width — it's just
// a non-uniform scale applied to the rendered text, not a different font).
// ClockSize below is a starting guess sized for a bold/wide poster font like
// Valcore — there's no reliable way to carry over a size value from another
// app's font-preview tool (unknown unit, and many of those auto-shrink text
// to fit regardless of the number shown), so build, look at it on-device,
// and tell me to push it up or down; same for stretch.
private val ClockSize = 284.sp // 316sp - 10%
private const val ClockStretch = 1f

/** How far (as a fraction of screen height) the ambient card must be
 *  dragged before releasing commits to the swipe-up transition instead of
 *  springing back down. */
private const val SwipeCommitFraction = 0.16f

@Composable
private fun AmbientScreen(backgroundPath: String?, onSwipeUp: () -> Unit) {
    var time by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            time = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
            date = SimpleDateFormat("EEE MMM d", Locale.getDefault()).format(now)
            kotlinx.coroutines.delay(15_000)
        }
    }

    val scope = rememberCoroutineScope()
    // The whole screen behaves as one draggable "card": dragging up carries
    // the clock/background with the finger (with a little resistance, and
    // a matching fade), and releasing past the commit threshold finishes
    // the slide off the top of the screen before handing off to the PIN
    // screen — releasing short of it springs back down to rest, exactly
    // like swiping up a real always-on-display clock.
    val offsetY = remember { Animatable(0f) }
    var containerHeightPx by remember { mutableStateOf(1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                containerHeightPx = size.height.toFloat()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalDy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            val committed = totalDy < -(containerHeightPx * SwipeCommitFraction)
                            scope.launch {
                                if (committed) {
                                    offsetY.animateTo(
                                        targetValue = -containerHeightPx,
                                        animationSpec = tween(220)
                                    )
                                    onSwipeUp()
                                } else {
                                    offsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                            break
                        }
                        val dy = change.positionChange().y
                        // Only upward drags move the card; a downward drag
                        // just rubber-bands a little instead of dragging it
                        // further down past its resting position.
                        totalDy += dy
                        change.consume()
                        val next = (offsetY.value + dy).coerceAtMost(0f)
                        scope.launch { offsetY.snapTo(next) }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        val progress = (abs(offsetY.value) / containerHeightPx).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = offsetY.value
                    alpha = 1f - progress * 0.9f
                }
                .clip(RoundedCornerShape(44.dp))
        ) {
            LockBackground(
                path = backgroundPath,
                scrimStops = listOf(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.4f to Color.Black.copy(alpha = 0.2f),
                    1f to Color.Black.copy(alpha = 0.6f)
                )
            )
            // iOS-style Lock Screen clock: bare text directly on the wallpaper
            // (no glass card behind it), anchored near the top like Apple's —
            // not centered on the whole screen — with a contained width (not
            // stretched edge-to-edge) and enough stroke weight to still read as
            // solid glyphs rather than hollow outlines.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 76.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = date,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = time,
                    style = TextStyle(
                        // Top-to-bottom fade instead of a flat fill — the depth
                        // cue that reads as "glass"/light even without doing the
                        // full photo-subject cutout effect.
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White, Color.White.copy(alpha = 0.62f))
                        )
                    ),
                    fontSize = ClockSize,
                    fontFamily = ClockFontFamily,
                    letterSpacing = (-0.5).sp,
                    maxLines = 1,
                    softWrap = false,
                    // "Stretch" — a vertical-only scale, drawn taller without
                    // getting wider. Scaling is a draw-time transform, so it
                    // doesn't change the space this Text reserves in the
                    // Column; a bit of extra top padding below keeps the
                    // stretched glyphs from crowding the date above.
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .scale(scaleX = 1f, scaleY = ClockStretch)
                )
            }

            // Same two quick-action buttons a real lock screen shows in its
            // bottom corners (torch, camera) — built from the exact same
            // frosted-glass circle style as the PIN keypad's number keys,
            // so it reads as one consistent design language.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TorchQuickAction()
                CameraQuickAction()
            }
        }
    }
}

@Composable
private fun TorchQuickAction() {
    val context = LocalContext.current
    var torchOn by remember { mutableStateOf(false) }
    GlassKey(size = 56.dp, onTap = {
        torchOn = !torchOn
        setTorchEnabled(context, torchOn)
    }) {
        // Real Material flashlight glyphs (swaps on/off variant with the
        // toggle) instead of a hand-drawn Canvas shape — the hand-drawn
        // version was rendering as an unrecognizable blob rather than an
        // actual torch.
        Icon(
            imageVector = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
            contentDescription = if (torchOn) "Turn off flashlight" else "Turn on flashlight",
            tint = if (torchOn) Color(0xFFFFD54A) else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
    // If the ambient screen goes away (swiped past, or the flow resets)
    // while the torch is on, turn it back off rather than leaving the
    // phone's flash lit in someone's pocket.
    DisposableEffect(Unit) {
        onDispose { if (torchOn) setTorchEnabled(context, false) }
    }
}

@Composable
private fun CameraQuickAction() {
    val context = LocalContext.current
    GlassKey(size = 56.dp, onTap = {
        runCatching {
            context.startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }) {
        // Same swap: real Material camera glyph instead of the hand-drawn
        // one, which was coming out misshapen.
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = "Open camera",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun setTorchEnabled(context: Context, enabled: Boolean) {
    runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        manager.setTorchMode(camId, enabled)
    }
}

@Composable
private fun PinScreen(
    pin: String,
    unlocking: Boolean,
    required: Int,
    backgroundPath: String?,
    onKey: (String) -> Unit,
    onAbort: () -> Unit
) {

    // Drives the whole "unlocking" moment: the padlock swings open and
    // turns green, the keypad/dots fade and settle back a touch, and a
    // soft light bloom washes over the screen — instead of the note list
    // just hard-cutting in the instant the 4th digit lands.
    val unlockProgress by animateFloatAsState(
        targetValue = if (unlocking) 1f else 0f,
        animationSpec = tween(180),
        label = "unlockProgress"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LockBackground(
            path = backgroundPath,
            scrimStops = listOf(
                0f to Color.Black.copy(alpha = 0.55f),
                0.32f to Color.Black.copy(alpha = 0.25f),
                1f to Color.Black.copy(alpha = 0.75f)
            )
        )
        // Soft light bloom that washes over the screen right as the trick
        // resolves, reinforcing the "unlocked" moment.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = unlockProgress }
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 90.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.then(doubleTapModifier(onDoubleTap = onAbort)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LockGlyphIcon(open = unlocking)
                    Text(
                        if (unlocking) "Unlocked" else "Enter PIN",
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 20.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    PinDotsRow(pin.length, required)
                }
            }

            // Two weighted spacers (rather than one large one that only
            // sits above the keypad) pull the keypad up into the middle of
            // the screen instead of pinning it to the very bottom — matches
            // where it's meant to sit rather than reading as an afterthought
            // stuck to the bottom edge.
            Box(modifier = Modifier.weight(0.38f))

            PinKeypad(
                onKey = onKey,
                modifier = Modifier
                    .padding(top = 54.dp) // +2mm, +2.5mm, +3mm, then another +1mm (≈6.3dp) fixed shift down — total ≈8.5mm from original
                    .graphicsLayer {
                        // Fades and settles back very slightly so the keypad
                        // recedes as the unlock confirmation takes over.
                        alpha = 1f - unlockProgress
                        scaleX = 1f - unlockProgress * 0.06f
                        scaleY = 1f - unlockProgress * 0.06f
                    }
            )

            Box(modifier = Modifier.weight(0.62f))
        }
    }
}

/** A padlock glyph sitting above "Enter PIN", matching the reference
 *  lock-screen design. When [open] is true (the PIN just resolved) it
 *  crossfades to the unlocked variant and tints green, giving the
 *  "unlocking" moment a visual instead of a hard cut. */
@Composable
private fun LockGlyphIcon(open: Boolean = false) {
    // Swapped the hand-drawn rotating-shackle Canvas for real Material
    // Lock/LockOpen glyphs. The custom version was rotating the shackle
    // arc around the wrong pivot and ended up twisting into the body
    // instead of swinging open — a real vetted vector icon sidesteps that
    // entirely and is guaranteed to always point the right way.
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (open) Color(0xFF4FE8C4) else Color.White.copy(alpha = 0.95f),
        animationSpec = tween(320),
        label = "lockTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (open) 1.1f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "lockScale"
    )
    androidx.compose.animation.Crossfade(
        targetState = open,
        animationSpec = tween(260),
        label = "lockGlyph"
    ) { isOpen ->
        Icon(
            imageVector = if (isOpen) Icons.Filled.LockOpen else Icons.Filled.Lock,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(30.dp)
                .scale(scale)
        )
    }
}

@Composable
private fun PinDotsRow(filledCount: Int, total: Int) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.padding(top = 20.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        repeat(total) { i ->
            val filled = i < filledCount
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (filled) Color.White else Color.White.copy(alpha = 0.25f)
                    )
                    .then(
                        if (filled) Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                        else Modifier
                    )
            )
        }
    }
}

@Composable
private fun PinKeypad(onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    // The web app uses a fixed pixel size for each key (68px) and grid
    // gap (18px row / 26px column) — not a percentage of screen width.
    // Keys are fixed-size circles in a plain Row/Column grid rather than a
    // stretchy LazyVerticalGrid, so they stay the same size regardless of
    // screen width. From the original 15dp row / 22dp column baseline:
    // row gap +2mm (≈12.6dp) -> 28dp, column gap +1mm (≈6.3dp) -> 28dp.
    // The row (vertical) gap is laid out top-down by this Column, so the
    // added space is inserted between rows below row 1, not above it —
    // row 1 stays anchored in place and rows 2-4 shift further down,
    // never up.
    val keySize = 68.dp // 62dp + 1mm (≈6.3dp) — lands almost exactly on the web app's own 68px key size
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("empty", "0", "delete")
    )

    // Briefly highlights whichever key was tapped last — matches the
    // reference design's glowing "3" key. Purely a local UI touch, doesn't
    // need to live in the ViewModel.
    var glowingKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(glowingKey) {
        if (glowingKey != null) {
            kotlinx.coroutines.delay(220)
            glowingKey = null
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(28.dp), // red lines: +2mm (≈12.6dp) over the 15dp baseline — this is the ROW gap
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { // yellow lines: was +1mm over 22dp baseline (28dp), now -1mm back down to 22dp — this is the COLUMN gap
                row.forEach { key ->
                    when (key) {
                        "empty" -> Box(modifier = Modifier.size(keySize))
                        "delete" -> Box(
                            modifier = Modifier
                                .size(keySize)
                                .clip(CircleShape)
                                .pointerInput(Unit) { detectTapGestures(onTap = { onKey("delete") }) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Delete", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                        else -> {
                            GlassKey(
                                size = keySize,
                                onTap = {
                                    glowingKey = key
                                    onKey(key)
                                }
                            ) {
                                Text(key, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The frosted-glass circle button used everywhere in the lock flow that
 * needs a "physical key" feel: the PIN keypad's digits above, and the
 * ambient screen's torch/camera quick actions. Kept as one shared
 * composable (rather than copy-pasted styling) so every glass button in
 * the flow is guaranteed to look and animate identically — a soft
 * top-to-bottom frosted sheen at rest, with a brightening radial glow on
 * tap that's fully clipped to the circle.
 */
@Composable
private fun GlassKey(
    size: androidx.compose.ui.unit.Dp = 68.dp,
    onTap: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(220)
            pressed = false
        }
    }
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(180),
        label = "glassKeyGlow"
    )
    Box(
        modifier = Modifier
            .size(size)
            // A very faint, tiny hint of light that just barely reaches
            // past the button's own edge on tap — kept small and subtle
            // on purpose, not a halo. Drawn before .clip so it isn't cut
            // off at the circle.
            .drawBehind {
                if (glowAlpha > 0f) {
                    val hintRadius = this.size.minDimension * 0.62f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.05f * glowAlpha),
                                Color.Transparent
                            ),
                            center = center,
                            radius = hintRadius
                        ),
                        radius = hintRadius,
                        center = center
                    )
                }
            }
            .clip(CircleShape)
            // Frosted-glass base: a soft top-to-bottom sheen instead of a
            // flat fill, so the key reads as a piece of glass at rest.
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.06f)
                    )
                )
            )
            // The tap feedback itself lives entirely inside the glass — a
            // brightening centered in the circle, clipped to it, so no
            // glow escapes the button.
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.42f * glowAlpha),
                        Color.White.copy(alpha = 0.14f * glowAlpha),
                        Color.Transparent
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.20f + 0.25f * glowAlpha), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    pressed = true
                    onTap()
                })
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}
