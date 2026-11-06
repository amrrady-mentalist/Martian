package com.scalpian.marty.offline.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scalpian.marty.offline.data.InputMode
import com.scalpian.marty.offline.data.MartySettings

@Composable
fun OptionsScreen(settings: MartySettings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    val lockPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) settings.setLockBackground(uri)
    }
    val homePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) settings.setHomeBackground(uri)
    }
    val rollPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris ->
        if (uris.isNotEmpty()) settings.addRollImages(uris)
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Options", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        SectionTitle("How you enter the card")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.inputMode == InputMode.LOCK_SCREEN,
                onClick = { settings.updateInputMode(InputMode.LOCK_SCREEN) },
                label = { Text("Fake lock screen") }
            )
            FilterChip(
                selected = settings.inputMode == InputMode.HOME_SCREEN,
                onClick = { settings.updateInputMode(InputMode.HOME_SCREEN) },
                label = { Text("Fake home screen") }
            )
        }
        Note(
            if (settings.inputMode == InputMode.LOCK_SCREEN)
                "Double-tap the black screen, swipe the clock up, then type the code on the PIN pad."
            else
                "Swipe through the home screen pages. Where your thumb starts each swipe is one digit " +
                    "(bottom-right 3x3 block = keypad 1-9, anywhere else = 0). Then tap the Photos icon."
        )
        SwitchRow("6-digit PIN", "Lock screen only. The last digits don't matter.", settings.sixDigits, settings::updateSixDigits)

        SectionTitle("The code")
        SwitchRow("+1 on the value", "Enter value + 1 (Ace = 02, Queen = 13, King = 14).", settings.plusOne, settings::updatePlusOne)
        Text("Suit order (first digit)", color = Color.White, modifier = Modifier.padding(top = 12.dp))
        settings.suitOrder.forEachIndexed { index, suit ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.width(24.dp))
                Text(
                    "${suit.symbol}  ${suit.label}",
                    color = if (suit.isRed) Color(0xFFFF6B6B) else Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { settings.moveSuit(index, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up", tint = Color.White)
                }
                IconButton(onClick = { settings.moveSuit(index, 1) }, enabled = index < 3) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down", tint = Color.White)
                }
            }
        }
        Note("Example: Queen of ${settings.suitOrder[1].label} = ${exampleCode(settings)}")

        SectionTitle("Camera and reveal")
        SwitchRow("Left-handed", "Moves the gallery shortcut to the right side.", settings.leftHanded, settings::updateLeftHanded)
        SwitchRow("Vibrate on reveal", null, settings.vibrate, settings::updateVibrate)
        SwitchRow(
            "Also save to phone library",
            "Writes the result to the real camera roll too (DCIM/Camera).",
            settings.saveToGallery,
            settings::updateSaveToGallery
        )

        SectionTitle("Fake screens")
        OutlinedButton(onClick = { lockPicker.launch(imageOnly) }) {
            Text(if (settings.lockBgPath == null) "Choose lock screen wallpaper" else "Change lock screen wallpaper", color = Color.White)
        }
        if (settings.lockBgPath != null) {
            OutlinedButton(onClick = { settings.setLockBackground(null) }) { Text("Remove lock wallpaper", color = Color.White) }
        }
        OutlinedButton(onClick = { homePicker.launch(imageOnly) }) {
            Text(if (settings.homeBgPath == null) "Choose home screen wallpaper" else "Change home screen wallpaper", color = Color.White)
        }
        if (settings.homeBgPath != null) {
            OutlinedButton(onClick = { settings.setHomeBackground(null) }) { Text("Remove home wallpaper", color = Color.White) }
        }

        SectionTitle("Fake camera roll")
        Note("Pictures added here appear before the result in the filmstrip, so it looks like a real roll. (${settings.rollPaths.size} added)")
        OutlinedButton(onClick = { rollPicker.launch(imageOnly) }) { Text("Add pictures", color = Color.White) }
        if (settings.rollPaths.isNotEmpty()) {
            OutlinedButton(onClick = { settings.clearRoll() }) { Text("Clear roll", color = Color.White) }
        }
    }
}

private fun exampleCode(settings: MartySettings): String {
    val card = com.scalpian.marty.offline.data.PlayingCard(12, settings.suitOrder[1])
    return com.scalpian.marty.offline.data.CardCode.encode(card, settings.suitOrder, settings.plusOne)
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 18.dp), color = Color.White.copy(alpha = 0.15f))
    Text(
        text,
        color = Color(0xFFF5C451),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
