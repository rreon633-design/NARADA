package com.narada.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.narada.android.R
import com.narada.domain.Destination

@Composable fun Glyph(@DrawableRes icon: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(painterResource(icon), description, modifier.size(24.dp), tint)
}
@Composable fun TileIcon(@DrawableRes icon: Int, modifier: Modifier = Modifier, dark: Boolean = false) {
    Box(modifier.size(48.dp).background(if (dark) NaradaColors.Ink else NaradaColors.Soft, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
        Glyph(icon, tint = if (dark) Color.White else NaradaColors.Ink)
    }
}
@Composable fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = NaradaColors.Muted) {
    Text(text, modifier, color, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold))
}
@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}
@Composable fun Caption(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = NaradaColors.Muted, style = MaterialTheme.typography.bodyMedium) }
@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, NaradaColors.Border)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun InfoPanel(title: String, detail: String, @DrawableRes icon: Int = R.drawable.ic_shield) {
    Panel { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Glyph(icon); Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Caption(detail) } } }
}
@Composable fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) { Text(text) }
}
@Composable fun MenuRow(title: String, subtitle: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick, modifier.fillMaxWidth().heightIn(min = 80.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, NaradaColors.Border)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TileIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Caption(subtitle)
            }
            Glyph(R.drawable.ic_right, tint = NaradaColors.Muted)
        }
    }
}
@Composable fun DeviceCard(name: String, onClick: (() -> Unit)? = null) {
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TileIcon(R.drawable.ic_phone, dark = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Caption("This device · Android")
            }
            if (onClick != null) Glyph(R.drawable.ic_right)
        }
    }
    if (onClick != null) Surface(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, NaradaColors.Border), content = body)
    else Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, NaradaColors.Border), content = body)
}
@DrawableRes fun Destination.icon(): Int = when (this) {
    Destination.HOME -> R.drawable.ic_home; Destination.FILES, Destination.STORAGE -> R.drawable.ic_folder
    Destination.CHAT -> R.drawable.ic_chat; Destination.TRANSFERS -> R.drawable.ic_transfers
    Destination.MORE -> R.drawable.ic_more; Destination.SYNC -> R.drawable.ic_sync
    Destination.OFFLINE -> R.drawable.ic_offline; Destination.DEVICES -> R.drawable.ic_devices
    Destination.NOTIFICATIONS -> R.drawable.ic_bell; Destination.SETTINGS -> R.drawable.ic_settings
    Destination.HELP -> R.drawable.ic_help; Destination.DIAGNOSTICS -> R.drawable.ic_diagnostics
    Destination.APPEARANCE -> R.drawable.ic_appearance
}
