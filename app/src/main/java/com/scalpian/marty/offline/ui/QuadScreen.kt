package com.scalpian.marty.offline.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scalpian.marty.offline.vision.Quad
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the photo with the card outline.
 *  - training = true: read-only, tells you whether the card was found (use it to learn how to frame shots).
 *  - training = false: the outline's four corners can be dragged; used when auto-detection failed.
 */
@Composable
fun QuadScreen(
    bitmap: Bitmap,
    detected: Quad?,
    training: Boolean,
    onConfirm: (Quad) -> Unit,
    onRetake: () -> Unit
) {
    BackHandler(onBack = onRetake)
    val start = detected ?: Quad.centered(bitmap.width, bitmap.height)
    val pts = remember { mutableStateListOf(*start.points.map { Offset(it.x, it.y) }.toTypedArray()) }
    val outline = if (detected != null) Color(0xFF4ADE80) else Color(0xFFF5C451)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / bitmap.width, boxH / bitmap.height)
            val ox = (boxW - bitmap.width * scale) / 2f
            val oy = (boxH - bitmap.height * scale) / 2f

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            if (detected != null || !training) {
                Canvas(Modifier.fillMaxSize()) {
                    val mapped = pts.map { Offset(it.x * scale + ox, it.y * scale + oy) }
                    for (i in mapped.indices) {
                        drawLine(outline, mapped[i], mapped[(i + 1) % 4], strokeWidth = 5f)
                    }
                }
            }

            if (!training) {
                pts.forEachIndexed { i, p ->
                    Box(
                        Modifier
                            .offset {
                                val r = 22.dp.roundToPx()
                                IntOffset((p.x * scale + ox).roundToInt() - r, (p.y * scale + oy).roundToInt() - r)
                            }
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(2.dp, outline, CircleShape)
                            .pointerInput(i) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val cur = pts[i]
                                    pts[i] = Offset(
                                        (cur.x + drag.x / scale).coerceIn(0f, bitmap.width.toFloat()),
                                        (cur.y + drag.y / scale).coerceIn(0f, bitmap.height.toFloat())
                                    )
                                }
                            }
                    )
                }
            }
        }

        val message = when {
            training && detected != null -> "Card found. This is how Marty will see your shot."
            training -> "No card found. Use a plain background, good light, and keep the whole card in view."
            else -> "Couldn't find the card automatically. Drag the four corners onto it."
        }
        Text(message, color = Color.White, modifier = Modifier.padding(16.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                Text(if (training) "Take another" else "Retake", color = Color.White)
            }
            if (!training) {
                Button(
                    onClick = { onConfirm(Quad.ordered(pts.map { PointF(it.x, it.y) })) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5C451), contentColor = Color.Black)
                ) { Text("Confirm") }
            }
        }
    }
}
