package com.scalpian.marty.offline.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/* =========================================================================
 * Decoy app pool — a direct port of the reference web app's ~60-app icon
 * list. Kept as (name, color) pairs rather than full brand SVGs: getting
 * the mechanic (paging, the invisible keypad, the reveal) exactly right is
 * the part that actually matters for the trick to work, and hand-porting
 * ~60 multi-path brand SVGs into Compose ImageVectors is a large separate
 * job that doesn't change how the screen behaves. Real icons can replace
 * these tiles per-app via Magic Settings → appIconOverrides (keyed by the
 * same names below) without touching this file again.
 * ========================================================================= */

data class HsDecoyApp(val name: String, val color: Color)

/** Public so a future "customize app icons" settings screen can list the
 *  exact same set this rendering uses — the appIconOverrides map is keyed
 *  by these names. */
val homeScreenDockApps = listOf(
    HsDecoyApp("Phone", Color(0xFF34D399)),
    HsDecoyApp("Messages", Color(0xFF1B6EF3)),
    HsDecoyApp("Camera", Color(0xFF4B5563))
)

val homeScreenDecoyApps = listOf(
    HsDecoyApp("Chrome", Color(0xFF4285F4)),
    HsDecoyApp("WhatsApp", Color(0xFF25D366)),
    HsDecoyApp("Instagram", Color(0xFFC13584)),
    HsDecoyApp("YouTube", Color(0xFFFF0000)),
    HsDecoyApp("Maps", Color(0xFFEA4335)),
    HsDecoyApp("Gmail", Color(0xFFC5221F)),
    HsDecoyApp("Photos", Color(0xFF4285F4)),
    HsDecoyApp("Play Store", Color(0xFF34A853)),
    HsDecoyApp("Spotify", Color(0xFF1DB954)),
    HsDecoyApp("TikTok", Color(0xFF010101)),
    HsDecoyApp("Settings", Color(0xFF4B5563)),
    HsDecoyApp("Word", Color(0xFF2B579A)),
    HsDecoyApp("Excel", Color(0xFF217346)),
    HsDecoyApp("ChatGPT", Color(0xFF10A37F)),
    HsDecoyApp("Xbox", Color(0xFF107C10)),
    HsDecoyApp("PlayStation", Color(0xFF0072CE)),
    HsDecoyApp("Notion", Color(0xFF2F3437)),
    HsDecoyApp("Discord", Color(0xFF5865F2)),
    HsDecoyApp("LinkedIn", Color(0xFF0A66C2)),
    HsDecoyApp("GitHub", Color(0xFF181717)),
    HsDecoyApp("Facebook", Color(0xFF1877F2)),
    HsDecoyApp("X", Color(0xFF0A0A0A)),
    HsDecoyApp("Snapchat", Color(0xFFFFFC00)),
    HsDecoyApp("Zoom", Color(0xFF2D8CFF)),
    HsDecoyApp("Uber", Color(0xFF0A0A0A)),
    HsDecoyApp("Airbnb", Color(0xFFFF5A5F)),
    HsDecoyApp("Pinterest", Color(0xFFE60023)),
    HsDecoyApp("Telegram", Color(0xFF229ED9)),
    HsDecoyApp("Reddit", Color(0xFFFF4500)),
    HsDecoyApp("Twitch", Color(0xFF9146FF)),
    HsDecoyApp("Amazon", Color(0xFFFF9900)),
    HsDecoyApp("PayPal", Color(0xFF00457C)),
    HsDecoyApp("InstaPay", Color(0xFF3B82F6)),
    HsDecoyApp("Weather", Color(0xFF38BDF8)),
    HsDecoyApp("Calendar", Color(0xFFEF4444)),
    HsDecoyApp("Steam", Color(0xFF171A21)),
    HsDecoyApp("Cash App", Color(0xFF00D632)),
    HsDecoyApp("Contacts", Color(0xFF3B82F6)),
    HsDecoyApp("Files", Color(0xFFEAB308)),
    HsDecoyApp("Compass", Color(0xFFEF4444)),
    HsDecoyApp("Keep", Color(0xFFEAB308)),
    HsDecoyApp("Music", Color(0xFFEC4899)),
    HsDecoyApp("News", Color(0xFF6366F1)),
    HsDecoyApp("Health", Color(0xFFF43F5E)),
    HsDecoyApp("Wallet", Color(0xFF0284C7)),
    HsDecoyApp("Translate", Color(0xFF2563EB)),
    HsDecoyApp("Tasks", Color(0xFF16A34A)),
    HsDecoyApp("Podcasts", Color(0xFFA855F7)),
    HsDecoyApp("Books", Color(0xFFF97316)),
    HsDecoyApp("Recorder", Color(0xFFDC2626)),
    HsDecoyApp("Radio", Color(0xFF059669)),
    HsDecoyApp("Security", Color(0xFF0D9488)),
    HsDecoyApp("Games", Color(0xFF4F46E5)),
    HsDecoyApp("Alarm", Color(0xFFEA580C)),
    HsDecoyApp("Store", Color(0xFFDB2777)),
    HsDecoyApp("Travel", Color(0xFF0891B2)),
    HsDecoyApp("Food", Color(0xFFE11D48)),
    HsDecoyApp("Taxi", Color(0xFFEAB308)),
    HsDecoyApp("Fitness", Color(0xFF84CC16)),
    HsDecoyApp("Movies", Color(0xFF9333EA))
)

/* =========================================================================
 * Page/cell model — a direct port of renderHomeScreen()'s per-page layout
 * math. Built once per flow (keyed on totalPages) and used as the single
 * source of truth for BOTH rendering the grid AND resolving what a tap
 * landed on, so the two can never drift apart.
 * ========================================================================= */

private enum class HsWidget { NONE, SEARCH, CLOCK }
private sealed class HsCellContent {
    data class App(val app: HsDecoyApp) : HsCellContent()
    data object Notes : HsCellContent()
}
private data class HsPage(val widget: HsWidget, val cells: Map<Pair<Int, Int>, HsCellContent>)

/** cols=4, rows=6 for every page, matching the web app's fixed grid used
 *  for BOTH layout and the invisible-keypad hit-testing math. */
private fun buildHsPages(totalPages: Int): List<HsPage> {
    var appIdx = 0
    fun nextApp() = homeScreenDecoyApps[appIdx % homeScreenDecoyApps.size].also { appIdx++ }

    return (0 until totalPages).map { p ->
        val cells = mutableMapOf<Pair<Int, Int>, HsCellContent>()
        val widget: HsWidget
        when {
            // totalPages-1 must win over the p==1/p==0 shortcuts whenever
            // they'd collide (i.e. totalPages is 2 or 3) — otherwise the
            // final page's Notes icon silently never gets placed at all.
            p == totalPages - 1 -> {
                widget = HsWidget.NONE
                for (i in 0 until 12) {
                    val cell = (i / 4) to (i % 4)
                    cells[cell] = if (i == 9) HsCellContent.Notes else HsCellContent.App(nextApp())
                }
            }
            p == 0 -> {
                widget = HsWidget.SEARCH
                for (i in 0 until 16) cells[(i / 4 + 2) to (i % 4)] = HsCellContent.App(nextApp())
            }
            p == 1 -> {
                widget = HsWidget.CLOCK
                for (i in 0 until 20) cells[(i / 4 + 1) to (i % 4)] = HsCellContent.App(nextApp())
            }
            else -> {
                widget = HsWidget.NONE
                for (i in 0 until 24) cells[(i / 4) to (i % 4)] = HsCellContent.App(nextApp())
            }
        }
        HsPage(widget, cells)
    }
}

/** digits so far -> what the status-bar clock should peek-show while
 *  mid-swipe, formatted to look like an ordinary "HH:mm" — direct port of
 *  formatPeekTime(). */
private fun formatPeekTime(digits: String): String {
    if (digits.isEmpty()) return "00:00"
    return when (digits.length) {
        1 -> "00:0$digits"
        2 -> "00:$digits"
        3 -> "0${digits[0]}:${digits.substring(1, 3)}"
        else -> "${digits.substring(0, 2)}:${digits.substring(2, 4)}"
    }
}

/** The bottom-right 3x3 of the 4x6 grid is the invisible keypad, read like
 *  a phone dialpad (top-left of that block = 1 ... bottom-right = 9).
 *  Starting a touch anywhere else on the page means "0". Direct port of
 *  the touchstart handler's zone math. */
private fun zoneForTouch(pos: Offset, w: Float, h: Float): Int {
    if (w <= 0f || h <= 0f) return 0
    val cellW = w / 4f
    val cellH = h / 6f
    val col = (pos.x / cellW).toInt().coerceIn(0, 3)
    val row = (pos.y / cellH).toInt().coerceIn(0, 5)
    return if (col in 1..3 && row in 3..5) (row - 3) * 3 + col else 0
}

private fun cellForTouch(pos: Offset, w: Float, h: Float): Pair<Int, Int> {
    if (w <= 0f || h <= 0f) return 0 to 0
    val col = (pos.x / (w / 4f)).toInt().coerceIn(0, 3)
    val row = (pos.y / (h / 6f)).toInt().coerceIn(0, 5)
    return row to col
}

/* ========================================================================= */

/**
 * The fake home screen input. Each swipe to the next page records one digit of the secret
 * code (decided by where the thumb lands: the bottom-right 3x3 block is an invisible 1-9
 * keypad, anywhere else is 0). On the last page, tapping the "Photos" icon submits the code.
 * Back or a double-tap on the dock's lock icon abandons the flow.
 */
@Composable
fun HomeScreenInput(
    requiredDigits: Int,
    wallpaperPath: String?,
    onSubmit: (String) -> Unit,
    onAbort: () -> Unit
) {
    ImmersiveWhileVisible()
    BackHandler(onBack = onAbort)
    val notesIconPath: String? = null
    val iconOverrides = emptyMap<String, String>()
    val nameOverrides = emptyMap<String, String>()
    val totalPages = requiredDigits + 1

    val pages = remember(totalPages) { buildHsPages(totalPages) }
    var currentPage by remember(totalPages) { mutableIntStateOf(0) }
    var pinDigits by remember(totalPages) { mutableStateOf("") }
    val offsetAnim = remember(totalPages) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    var realTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            realTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(15_000)
        }
    }
    var topBarText by remember { mutableStateOf("") }
    LaunchedEffect(realTime, currentPage) {
        // Once the reveal page is reached, the clock permanently shows the
        // digits collected so far (so a mis-swipe can be double-checked
        // before tapping the Notes icon) instead of the real time.
        topBarText = if (currentPage == totalPages - 1) formatPeekTime(pinDigits) else realTime
    }

    // Guards against a rapid double-tap on the Notes icon firing
    // resolveHomeScreenPin() twice (and creating a duplicate note) in the
    // brief window between the tap and the screen actually switching away.
    var notesTapped by remember { mutableStateOf(false) }

    fun handleTap(page: Int, cell: Pair<Int, Int>) {
        if (notesTapped) return
        when (pages.getOrNull(page)?.cells?.get(cell)) {
            is HsCellContent.Notes -> {
                notesTapped = true
                onSubmit(pinDigits)
            }
            // Decoy icons are silent no-ops now — tapping one used to pop
            // an "App not installed" toast, which was an unwanted tell.
            is HsCellContent.App -> Unit
            null -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HsWallpaper(wallpaperPath)

        Column(Modifier.fillMaxSize()) {
            HsStatusBar(topBarText)

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val pageWidthPx = constraints.maxWidth.toFloat()
                val pageHeightPx = constraints.maxHeight.toFloat()

                // Each page is its own full-size Box, positioned purely via
                // graphicsLayer.translationX at draw time. This replaced an
                // earlier Row(width = pageWidth * totalPages) approach: that
                // Row asked to be 3 screens wide, but its parent
                // (BoxWithConstraints, exact-width-constrained by
                // .fillMaxWidth()) clamps any child's requested width back
                // down to what it was given — so every page past the first
                // got silently squeezed to ~zero width and simply never
                // drew, even though its data (cells/widget) was always
                // correct. translationX is a post-layout draw transform, not
                // a measured size, so it isn't subject to that clamp: each
                // page now measures at the full, unclamped viewport size and
                // is just shifted into place visually.
                pages.forEachIndexed { index, page ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = offsetAnim.value + index * pageWidthPx }
                    ) {
                        HsPageContent(page, notesIconPath, iconOverrides, nameOverrides)
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(totalPages) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val touchZone = zoneForTouch(down.position, pageWidthPx, pageHeightPx)
                                val downCell = cellForTouch(down.position, pageWidthPx, pageHeightPx)
                                var lock: Char? = null
                                var totalDx = 0f
                                var totalDy = 0f
                                val baseOffset = -(currentPage * pageWidthPx)

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                    if (!change.pressed) {
                                        when (lock) {
                                            'x' -> {
                                                val canForward = currentPage < totalPages - 1
                                                when {
                                                    totalDx < -40f && canForward -> {
                                                        pinDigits += touchZone.toString()
                                                        currentPage += 1
                                                    }
                                                    totalDx > 40f && currentPage > 0 -> {
                                                        currentPage -= 1
                                                        pinDigits = pinDigits.dropLast(1)
                                                    }
                                                    else -> topBarText = realTime
                                                }
                                                val target = -(currentPage * pageWidthPx)
                                                scope.launch { offsetAnim.animateTo(target, tween(380)) }
                                            }
                                            null -> handleTap(currentPage, downCell)
                                            // 'y' — a genuine vertical drag, not a tap and not a
                                            // page swipe, matches a real touch UI: absorbed, no-op.
                                            else -> Unit
                                        }
                                        break
                                    }

                                    val delta = change.positionChange()
                                    totalDx += delta.x
                                    totalDy += delta.y

                                    if (lock == null && (abs(totalDx) > 6f || abs(totalDy) > 6f)) {
                                        lock = if (abs(totalDx) > abs(totalDy)) 'x' else 'y'
                                    }

                                    if (lock == 'x') {
                                        change.consume()
                                        val canForward = currentPage < totalPages - 1
                                        val resisted = when {
                                            totalDx < 0 && !canForward -> totalDx * 0.28f
                                            totalDx > 0 && currentPage == 0 -> totalDx * 0.28f
                                            totalDx > 0 -> totalDx * 0.55f
                                            else -> totalDx
                                        }
                                        scope.launch { offsetAnim.snapTo(baseOffset + resisted) }

                                        topBarText = if (resisted < 0 && canForward) {
                                            val progress = (abs(resisted) / pageWidthPx).coerceAtMost(1f)
                                            if (progress > 0.15f) formatPeekTime(pinDigits + touchZone) else realTime
                                        } else realTime
                                    }
                                }
                            }
                        }
                )
            }

            HsPageDots(totalPages, currentPage)
            HsDock(
                iconOverrides = iconOverrides,
                nameOverrides = nameOverrides,
                onDummyTap = { /* no-op — decoy dock icons don't respond */ },
                onLockDoubleTap = { onAbort() }
            )
        }
    }
}

@Composable
private fun HsWallpaper(path: String?) {
    val bmp = remember(path) {
        decodeScaledFile(path, 1600)
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Matches the web app's filter:brightness(0.85) on the wallpaper.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0F172A), Color.Black)))
        )
    }
}

@Composable
private fun HsStatusBar(timeText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A little extra breathing room off the left edge — it sat flush
        // against the 16.dp row padding, which read as oddly tight next to
        // a real status bar's clock.
        Text(
            timeText,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            HsSignalBars()
            HsWifiIcon()
            HsBatteryIcon()
        }
    }
}

@Composable
private fun HsSignalBars() {
    Canvas(Modifier.size(width = 16.dp, height = 11.dp)) {
        val barW = size.width / 4.5f
        for (i in 0 until 4) {
            val h = size.height * (0.4f + 0.2f * i)
            drawRect(
                color = Color.White,
                topLeft = Offset(i * barW * 1.25f, size.height - h),
                size = Size(barW, h)
            )
        }
    }
}

@Composable
private fun HsWifiIcon() {
    // A proper 3-arc + dot WiFi glyph: arcs drawn top-down (outer to
    // inner) around a shared bottom point, with Dp-scaled stroke width
    // and round caps — the previous version used a raw 1.6px stroke
    // (invisible/inconsistent across densities) and slightly wrong arc
    // geometry, which is why it read as "off".
    Canvas(Modifier.size(16.dp)) {
        val strokeW = 1.7.dp.toPx()
        val c = Offset(size.width / 2f, size.height * 0.80f)
        drawCircle(color = Color.White, radius = 1.3.dp.toPx(), center = c)
        for (i in 1..3) {
            val r = size.width * 0.26f * i
            drawArc(
                color = Color.White,
                startAngle = 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun HsBatteryIcon() {
    Canvas(Modifier.size(width = 20.dp, height = 11.dp)) {
        val bodyW = size.width - 3.dp.toPx()
        drawRoundRect(
            color = Color.White,
            size = Size(bodyW, size.height),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = 1.2f)
        )
        drawRect(
            color = Color.White,
            topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
            size = Size(bodyW - 4.dp.toPx(), size.height - 4.dp.toPx())
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.28f),
            size = Size(2.dp.toPx(), size.height * 0.44f),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
    }
}

@Composable
private fun HsPageContent(
    page: HsPage,
    notesIconPath: String?,
    iconOverrides: Map<String, String>,
    nameOverrides: Map<String, String>
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        for (row in 0 until 6) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (row == 0 && page.widget != HsWidget.NONE) {
                    Box(Modifier.weight(4f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        when (page.widget) {
                            HsWidget.SEARCH -> HsSearchPill()
                            HsWidget.CLOCK -> HsClockWidget()
                            HsWidget.NONE -> Unit
                        }
                    }
                } else {
                    for (col in 0 until 4) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            when (val content = page.cells[row to col]) {
                                is HsCellContent.App -> HsAppIcon(
                                    content.app,
                                    iconOverrides[content.app.name],
                                    nameOverrides[content.app.name]
                                )
                                is HsCellContent.Notes -> HsNotesIcon(notesIconPath)
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HsSearchPill() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("G", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Text("Search", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
}

@Composable
private fun HsClockWidget() {
    val cities = listOf("Cairo" to 0f, "New York" to -210f, "Moscow" to 0f, "Kuching" to 150f)
    val now = remember { Date() }
    val cal = remember { java.util.Calendar.getInstance().apply { time = now } }
    val hr = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val min = cal.get(java.util.Calendar.MINUTE)
    val hrDeg = (hr % 12) * 30 + (min * 0.5f)
    val minDeg = min * 6f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.3f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        cities.forEach { (city, offset) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(40.dp)) {
                    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = size.minDimension / 2, style = Stroke(width = 2.dp.toPx()))
                    val hAngle = Math.toRadians((hrDeg + offset - 90).toDouble())
                    val mAngle = Math.toRadians((minDeg - 90).toDouble())
                    val c = Offset(size.width / 2, size.height / 2)
                    drawLine(Color.White, c, c + Offset(cos(hAngle).toFloat(), sin(hAngle).toFloat()) * (size.minDimension * 0.22f), strokeWidth = 2.dp.toPx())
                    drawLine(Color.White, c, c + Offset(cos(mAngle).toFloat(), sin(mAngle).toFloat()) * (size.minDimension * 0.34f), strokeWidth = 2.dp.toPx())
                }
                Spacer(Modifier.height(6.dp))
                Text(city, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Grid icons now match the dock's icon size (56.dp) exactly — they used to
// be rendered smaller (52.dp) than the dock's own icons even though both
// represent the same kind of app tile, which read as an inconsistency once
// you looked at the two side by side.
private val HsIconSize = 56.dp

@Composable
private fun HsAppIcon(app: HsDecoyApp, overridePath: String?, overrideName: String?) {
    val bmp = remember(overridePath) {
        overridePath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    val displayName = overrideName?.takeIf { it.isNotBlank() } ?: app.name
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(HsIconSize)
                .clip(RoundedCornerShape(14.dp))
                .background(if (bmp != null) Color.Transparent else app.color),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    displayName.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            displayName,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(58.dp)
        )
    }
}

@Composable
private fun HsNotesIcon(iconPath: String?) {
    val context = LocalContext.current
    val bmp = remember(iconPath) {
        iconPath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
            // Falls back to the app's own real launcher icon — a sensible
            // default since this button opens the real app. ic_launcher is
            // an adaptive-icon XML (background layer + foreground layer),
            // which painterResource()/Image() can't load directly — it
            // only supports VectorDrawables and raster assets (PNG/JPG/
            // WEBP). Going through PackageManager instead always returns a
            // single flattened Bitmap, regardless of the icon's format.
            ?: runCatching {
                context.packageManager.getApplicationIcon(context.packageName)
                    .toBitmap()
            }.getOrNull()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(HsIconSize)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Photos", color = Color.White, fontSize = 10.sp)
    }
}

@Composable
private fun HsPageDots(total: Int, current: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            // Equal breathing room above (from the icon grid) and below
            // (to the dock) so the dots sit centered in that gap instead
            // of hugging one side.
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until total) {
            val active = i == current
            Box(
                Modifier
                    .padding(horizontal = 3.5.dp)
                    .size(if (active) 7.5.dp else 6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (active) 0.95f else 0.4f))
            )
        }
    }
}

@Composable
private fun HsDock(
    iconOverrides: Map<String, String>,
    nameOverrides: Map<String, String>,
    onDummyTap: () -> Unit,
    onLockDoubleTap: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        homeScreenDockApps.take(2).forEach { app ->
            DockIcon(app, iconOverrides[app.name], nameOverrides[app.name], onDummyTap)
        }
        DockLockIcon(onLockDoubleTap)
        val third = homeScreenDockApps[2]
        DockIcon(third, iconOverrides[third.name], nameOverrides[third.name], onDummyTap)
    }
}

@Composable
private fun DockIcon(app: HsDecoyApp, overridePath: String?, overrideName: String?, onTap: () -> Unit) {
    val bmp = remember(overridePath) {
        overridePath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    val displayName = overrideName?.takeIf { it.isNotBlank() } ?: app.name
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (bmp != null) Color.Transparent else app.color)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(displayName.take(2).uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DockLockIcon(onDoubleTap: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E293B))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val bodyTop = size.height * 0.42f
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.12f, bodyTop),
                size = Size(size.width * 0.76f, size.height * 0.5f),
                cornerRadius = CornerRadius(3f)
            )
            drawArc(
                color = Color.White,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(size.width * 0.28f, size.height * 0.06f),
                size = Size(size.width * 0.44f, size.height * 0.55f),
                style = Stroke(width = size.width * 0.11f)
            )
        }
    }
}

