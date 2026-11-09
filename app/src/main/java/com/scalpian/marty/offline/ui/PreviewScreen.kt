package com.scalpian.marty.offline.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scalpian.marty.offline.data.PlayingCard
import com.scalpian.marty.offline.vision.CardCompositor
import com.scalpian.marty.offline.vision.CompositeOutput
import com.scalpian.marty.offline.vision.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Test run" before the trick: shows what the edit will look like with a sample card, using exactly the
 * same code as the real reveal. Tap "Show erased / protected" to see which pixels will be erased (red) and
 * which are treated as fingers and left untouched (green). If it looks wrong, adjust the corners or retake.
 */
@Composable
fun PreviewScreen(
    photo: Bitmap,
    quad: Quad,
    testCard: PlayingCard,
    onContinue: () -> Unit,
    onAdjust: () -> Unit,
    onRetake: () -> Unit
) {
    BackHandler(onBack = onRetake)
    var output by remember(photo, quad) { mutableStateOf<CompositeOutput?>(null) }
    var error by remember(photo, quad) { mutableStateOf<String?>(null) }
    var showMasks by remember { mutableStateOf(false) }

    LaunchedEffect(photo, quad) {
        output = null
        error = null
        try {
            output = withContext(Dispatchers.Default) { CardCompositor.compositePreview(photo, quad, testCard) }
        } catch (e: Throwable) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Text(
            "Test run",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        Text(
            "Sample card shown. Old print should be gone and your fingers untouched.",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val o = output
            when {
                o != null -> {
                    val bmp = if (showMasks) (o.overlay ?: o.result) else o.result
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                error != null -> Text(
                    "The test run failed:\n$error\n\nTry Adjust corners or Retake.",
                    color = Color(0xFFFF8A80),
                    modifier = Modifier.padding(24.dp)
                )
                else -> CircularProgressIndicator(color = Color.White)
            }
        }

        if (showMasks) {
            Text(
                "Red = will be erased.  Green = fingers, protected.",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChip(
                selected = showMasks,
                onClick = { showMasks = !showMasks },
                enabled = output?.overlay != null,
                label = { Text("Show erased / protected") }
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake", color = Color.White) }
            OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f)) { Text("Adjust", color = Color.White) }
            Button(
                onClick = onContinue,
                enabled = output != null,
                modifier = Modifier.weight(1.2f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5C451), contentColor = Color.Black)
            ) { Text("Looks good") }
        }
    }
}
