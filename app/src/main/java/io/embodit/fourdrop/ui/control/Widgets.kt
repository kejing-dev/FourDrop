package io.embodit.fourdrop.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import io.embodit.fourdrop.platform.TAG
import io.embodit.fourdrop.platform.VOLUME_PERCENT
import io.embodit.fourdrop.ui.theme.FDColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

/** B31: every button is 64 dp tall. */
private val BUTTON = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 120.dp)

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    val fg = PicoTheme.colorScheme.labelPrimaryLight
    Button(
        onClick = onClick, modifier = modifier.alpha(if (enabled) 1f else 0.55f), enabled = enabled,
        size = BUTTON, shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = fg)
            Box(Modifier.width(10.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = fg)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null) {
    val fg = if (danger) FDColors.OnError else PicoTheme.colorScheme.labelPrimary
    // a disabled button keeps its colours at 55 % (the SpatialUI disabled grey is ~1.2:1 on the glass, pruning-class lesson)
    Button(
        onClick = onClick, modifier = modifier.alpha(if (enabled) 1f else 0.55f), enabled = enabled,
        size = BUTTON, shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) FDColors.Error else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = fg)
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier,
        size = BUTTON, shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = fg)
            Box(Modifier.width(8.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A whole 64 dp row that toggles (label + Switch); the bare SpatialUI Switch is only ~32 x 20 dp (QA q1-4, D44). */
@Composable
internal fun SwitchRow(label: String, tag: String, checked: Boolean, icon: Int, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(FDColors.CardFace)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = FDColors.Ink)
        Box(Modifier.width(14.dp))
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = FDColors.Ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A tappable 64 dp row that opens a picker: icon, label, current value, ▸. */
@Composable
internal fun PickerRow(label: String, value: String, tag: String, icon: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(FDColors.CardFace).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = FDColors.Ink)
        Box(Modifier.width(14.dp))
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = FDColors.Ink, modifier = Modifier.weight(1f))
        Txt("$value  ▸", "$tag/value", style = PicoTheme.typography.labelLarge, color = FDColors.Ink)
    }
}

/** One option of a picker dialog (60 dp): title + a one-line note, a ring that fills when selected. */
@Composable
internal fun ChoiceRow(title: String, note: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp))
            .background(if (selected) FDColors.PrimaryContainer else FDColors.CardFace)
            .border(if (selected) 2.dp else 0.dp, if (selected) FDColors.Primary else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, FDColors.Ink, CircleShape).padding(4.dp)) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(FDColors.Primary))
        }
        Box(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = FDColors.Ink)
            if (note != null) Txt(note, "$tag/note", style = PicoTheme.typography.bodySmall, color = FDColors.InkSecondary)
        }
    }
}

/** A slider that snaps to three stops (35 / 65 / 100 %) and shows the percentage (B20: steps = 1, tap and drag both land). */
@Composable
internal fun VolumeRow(label: String, level: Int, names: List<String>, icon: Int, onChange: (Int) -> Unit) {
    val change by rememberUpdatedState(onChange)
    var local by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(20.dp)).background(FDColors.CardFace).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = FDColors.Ink)
        Box(Modifier.width(14.dp))
        Txt(label, "settings/volume-label", style = PicoTheme.typography.labelLarge, color = FDColors.Ink, modifier = Modifier.width(110.dp))
        // the SpatialUI Slider works on 0..1 (it did not honour valueRange 0..2 on the emulator: the thumb sat at the left)
        Slider(
            value = local / 2f,
            onValueChange = { v -> local = (v * 2f).coerceIn(0f, 2f) },
            // snap on release (a tap lands too): the stop and the number move together
            onValueChangeFinished = { val stop = kotlin.math.round(local).toInt().coerceIn(0, 2); local = stop.toFloat(); change(stop) },
            modifier = Modifier.weight(1f).height(48.dp),
        )
        Box(Modifier.width(12.dp))
        val k = local.let { kotlin.math.round(it).toInt().coerceIn(0, 2) }
        Txt("${names[k]} ${VOLUME_PERCENT[k]}%", "settings/volume-pct", style = PicoTheme.typography.labelLarge, color = FDColors.Ink, modifier = Modifier.width(96.dp))
    }
}
