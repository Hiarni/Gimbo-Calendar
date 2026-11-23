package de.luma.calendar.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import de.luma.calendar.i18n.*
import java.util.Locale

val gimboPalette = listOf(0xFFB9A7FFL, 0xFF8D78F0L, 0xFFFF7AB6L, 0xFFFF7070L, 0xFFF0BD91L, 0xFFFFA447L,
    0xFFFFD66AL, 0xFFC8DF70L, 0xFFA5D6BEL, 0xFF51CAA4L, 0xFF54D2D5L, 0xFF9DCBEAL, 0xFF69A1FFL, 0xFFAEABFEL, 0xFFD5C6B8L, 0xFFBAC1CAL)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ColorPicker(color: Long, enabled: Boolean = true, onChange: (Long) -> Unit) {
    val t = texts()
    var custom by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            gimboPalette.forEach { value ->
                Surface(onClick = { onChange(value) }, enabled = enabled, color = Color(value), shape = CircleShape,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = t("Akzentfarbe %1\$s", "#%06X".format(Locale.ROOT, value and 0xFFFFFF)); selected = value == color },
                    border = if (value == color) BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null) {
                    Box(contentAlignment = Alignment.Center) { if (value == color) Icon(Icons.Check, null, tint = Color(textOnAccent(value.toInt()))) }
                }
            }
        }
        OutlinedButton({ custom = true }, Modifier.fillMaxWidth().testTag("custom-colour"), enabled = enabled) { Text(t("Eigene Farbe")) }
    }
    if (custom) CustomColorDialog(color, { custom = false }) { onChange(it); custom = false }
}

@Composable private fun CustomColorDialog(initial: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val t = texts()
    val hsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) } }
    var hue by rememberSaveable { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(hsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(hsv[2]) }
    var hex by rememberSaveable { mutableStateOf("%06X".format(Locale.ROOT, initial and 0xFFFFFF)) }
    val chosen = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFFFL
    fun slider(h: Float = hue, s: Float = saturation, b: Float = brightness) {
        hue = h; saturation = s; brightness = b
        hex = "%06X".format(Locale.ROOT, android.graphics.Color.HSVToColor(floatArrayOf(h, s, b)).toLong() and 0xFFFFFF)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Eigene Farbe")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.fillMaxWidth().height(56.dp), color = Color(chosen), shape = RoundedCornerShape(16.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(t("Farbvorschau"), color = Color(textOnAccent(chosen.toInt()))) }
            }
            Text(t("Farbton")); Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), CircleShape))
            Slider(hue, { slider(h = it) }, valueRange = 0f..360f, modifier = Modifier.semantics { contentDescription = t("Farbton") })
            Text(t("Sättigung")); Slider(saturation, { slider(s = it) }, modifier = Modifier.semantics { contentDescription = t("Sättigung") })
            Text(t("Helligkeit")); Slider(brightness, { slider(b = it) }, modifier = Modifier.semantics { contentDescription = t("Helligkeit") })
            OutlinedTextField(hex, { input ->
                hex = input.removePrefix("#").filter { it in "0123456789abcdefABCDEF" }.take(6)
                hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { n -> val next = FloatArray(3); android.graphics.Color.colorToHSV((0xFF000000L or n).toInt(), next); hue = next[0]; saturation = next[1]; brightness = next[2] }
            }, label = { Text(t("Hex-Farbe")) }, prefix = { Text("#") }, singleLine = true, isError = hex.length != 6, modifier = Modifier.testTag("colour-hex"))
        }
    }, confirmButton = { TextButton({ onSave(chosen) }, enabled = hex.length == 6) { Text(t("Übernehmen")) } }, dismissButton = { TextButton(onDismiss) { Text(t("Abbrechen")) } })
}
