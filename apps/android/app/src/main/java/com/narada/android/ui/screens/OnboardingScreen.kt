package com.narada.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.narada.android.R
import com.narada.android.ui.*
import com.narada.domain.DeviceNamePolicy

@Composable
fun OnboardingScreen(initialName: String, busy: Boolean, complete: (String) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    val valid = runCatching { DeviceNamePolicy.normalize(name) }.isSuccess
    BackHandler(enabled = page > 0) { page-- }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("N A R A D A")
            Eyebrow("${page + 1} / 3")
        }
        Spacer(Modifier.height(24.dp))
        TileIcon(if (page == 0) R.drawable.ic_devices else if (page == 1) R.drawable.ic_phone else R.drawable.ic_shield, dark = true)
        when (page) {
            0 -> {
                Text("Your devices.\nCloser together.", style = MaterialTheme.typography.headlineLarge)
                Caption("Connect your phone and computer—no cloud account.")
                InfoPanel("Start with this phone", "Connect with a QR invitation. Chat needs no shared folder.", R.drawable.ic_phone)
                PrimaryAction("Get started", { page = 1 }, Modifier.fillMaxWidth())
                Caption("Setup does not pair devices or send content.")
            }
            1 -> {
                Text("Make it yours.", style = MaterialTheme.typography.headlineLarge)
                Caption("Choose a name for this phone.")
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 192) name = it }, label = { Text("Device name") },
                    supportingText = { Text(if (valid) "1–48 characters. You can change this later." else "Enter a name without control characters, up to 48 characters.") },
                    isError = !valid, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
                PrimaryAction("Continue", { page = 2 }, Modifier.fillMaxWidth(), enabled = valid && !busy)
                TextButton({ name = "My phone"; page = 2 }, Modifier.heightIn(min = 48.dp)) { Text("Use “My phone” instead") }
            }
            else -> {
                Text("Your phone.\nYour choices.", style = MaterialTheme.typography.headlineLarge)
                Caption("Permissions are requested only when you need them.")
                DeviceCard(DeviceNamePolicy.normalize(name))
                InfoPanel("Connect after setup", "In Devices, scan your computer’s QR, compare fingerprints and finish approval. Folder sharing is optional.", R.drawable.ic_devices)
                PrimaryAction(if (busy) "Saving setup…" else "Open NARADA", { complete(name) }, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
