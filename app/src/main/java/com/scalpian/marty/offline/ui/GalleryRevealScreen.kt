package com.scalpian.marty.offline.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The fake camera roll. Opens on the edited photo, which sits last in the filmstrip (newest),
 * after whatever "fake roll" pictures were added in Options. Pinch to zoom.
 */
@Composable
fun GalleryRevealScreen(result: Bitmap, rollPaths: List<String>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val rollBitmaps = remember(rollPaths) { rollPaths.mapNotNull { decodeScaledFile(it, 1200) } }
    val items = remember(rollBitmaps, result) { rollBitmaps + result }
    var selected by remember { mutableIntStateOf(items.lastIndex) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = items.lastIndex)
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }

    var scale by remember(selected) { mutableFloatStateOf(1f) }
    var pan by remember(selected) { mutableStateOf(Offset.Zero) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(26.dp).clickable(onClick = onClose)
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Today", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(clock, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            Box(Modifier.size(26.dp))
        }

        // Photo
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(selected) {
                    detectTransformGestures { _, panChange, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        pan = if (scale > 1f) pan + panChange else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = items[selected].asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    }
            )
        }

        // Filmstrip
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
            itemsIndexed(items) { index, bmp ->
                val isSelected = index == selected
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(56.dp)
                        .width(if (isSelected) 56.dp else 44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .then(if (isSelected) Modifier.border(BorderStroke(2.dp, Color.White), RoundedCornerShape(4.dp)) else Modifier)
                        .clickable { selected = index }
                )
            }
        }

        // Action row (decorative, like the system gallery)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(Icons.Filled.Share, Icons.Filled.FavoriteBorder, Icons.Filled.Tune, Icons.Filled.DeleteOutline).forEach {
                Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}
