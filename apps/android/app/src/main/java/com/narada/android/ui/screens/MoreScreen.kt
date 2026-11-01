package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.narada.android.ui.*
import com.narada.domain.Destination

@Composable
fun MoreScreen(name: String, navigate: (Destination) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Caption("A few more things. All in one place.") }
        item { Spacer(Modifier.height(8.dp)); DeviceCard(name) { navigate(Destination.SETTINGS) }; Spacer(Modifier.height(12.dp)) }
        item { Eyebrow("WORKSPACE") }
        item { MenuRow("Sync", "Scoped one-way refresh", Destination.SYNC.icon(), { navigate(Destination.SYNC) }) }
        item { MenuRow("Offline files", "Verified private pins", Destination.OFFLINE.icon(), { navigate(Destination.OFFLINE) }) }
        item { MenuRow("Devices", "Protected identity, pairing and saved devices", Destination.DEVICES.icon(), { navigate(Destination.DEVICES) }) }
        item { Spacer(Modifier.height(12.dp)); Eyebrow("PREFERENCES & SUPPORT") }
        item { MenuRow("Notifications", "Private updates and real local activity", Destination.NOTIFICATIONS.icon(), { navigate(Destination.NOTIFICATIONS) }) }
        item { MenuRow("Settings", "Name, appearance and local access", Destination.SETTINGS.icon(), { navigate(Destination.SETTINGS) }) }
        item { MenuRow("Help", "Roadmap, limitations and diagnostics", Destination.HELP.icon(), { navigate(Destination.HELP) }) }
        item { Spacer(Modifier.height(12.dp)); Caption("NARADA 0.2.0 · Trusted connections\nDevelopment build, not a production release.") }
    }
}
