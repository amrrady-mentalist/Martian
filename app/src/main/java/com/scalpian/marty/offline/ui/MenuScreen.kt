package com.scalpian.marty.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Gold = Color(0xFFF5C451)

@Composable
fun MenuScreen(onPerform: () -> Unit, onTraining: () -> Unit, onOptions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Gold),
            contentAlignment = Alignment.Center
        ) {
            Text("M", color = Color.Black, fontSize = 52.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
        Text("Marty", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("offline", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onPerform,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)
        ) { Text("Perform", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onTraining, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Training", fontSize = 16.sp, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOptions, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Options", fontSize = 16.sp, color = Color.White)
        }
    }
}
