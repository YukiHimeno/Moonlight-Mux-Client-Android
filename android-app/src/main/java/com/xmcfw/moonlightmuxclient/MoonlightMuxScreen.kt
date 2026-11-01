package com.xmcfw.moonlightmuxclient

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

fun installMoonlightContent(
    activity: ComponentActivity,
    profileName: MutableState<String>, server: MutableState<String>, key: MutableState<String>,
    local: MutableState<String>, port: MutableState<String>, debug: MutableState<Boolean>,
    status: MutableState<String>, logs: MutableState<String>, overlayAllowed: MutableState<Boolean>,
    running: MutableState<Boolean>, profilesOpen: MutableState<Boolean>, newProfileName: MutableState<String>,
    profileNames: MutableState<String>, onProfiles: () -> Unit, onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit, onRenameProfile: () -> Unit, onStart: () -> Unit,
    onStop: () -> Unit, onOverlay: () -> Unit, onCopy: () -> Unit, onClear: () -> Unit,
) = activity.setContent {
    MoonlightMuxScreen(profileName, server, key, local, port, debug, status, logs, overlayAllowed,
        running, profilesOpen, newProfileName, profileNames, onProfiles, onSelectProfile,
        onAddProfile, onRenameProfile, onStart, onStop, onOverlay, onCopy, onClear)
}

@Composable
fun MoonlightMuxScreen(
    profileName: MutableState<String>, server: MutableState<String>, key: MutableState<String>,
    local: MutableState<String>, port: MutableState<String>, debug: MutableState<Boolean>,
    status: MutableState<String>, logs: MutableState<String>, overlayAllowed: MutableState<Boolean>,
    running: MutableState<Boolean>, profilesOpen: MutableState<Boolean>, newProfileName: MutableState<String>,
    profileNames: MutableState<String>, onProfiles: () -> Unit, onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit, onRenameProfile: () -> Unit, onStart: () -> Unit,
    onStop: () -> Unit, onOverlay: () -> Unit, onCopy: () -> Unit, onClear: () -> Unit,
) {
    val controller = androidx.compose.runtime.remember { ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFF3482FF)) }
    MiuixTheme(controller = controller) {
        if (profilesOpen.value) {
            OverlayDialog(show = profilesOpen.value, title = "Profiles", onDismissRequest = { profilesOpen.value = false }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    profileNames.value.lines().filter { it.contains('|') }.forEach { entry ->
                        val id = entry.substringBefore('|')
                        val name = entry.substringAfter('|')
                        TextButton(text = if (name == profileName.value) "✓  $name" else name,
                            onClick = { onSelectProfile(id) }, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Field("Profile name", newProfileName.value, { newProfileName.value = it }, "Name")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = "Add", onClick = onAddProfile, modifier = Modifier.weight(1f))
                        TextButton(text = "Rename current", onClick = onRenameProfile, modifier = Modifier.weight(1f))
                    }
                    TextButton(text = "Close", onClick = { profilesOpen.value = false }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Moonlight Mux", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Android relay client", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth(), onClick = onProfiles) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ACTIVE PROFILE", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(profileName.value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Switch  ›", color = MiuixTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Field("Server", server.value, { server.value = it }, "host:port")
                    Field("Key", key.value, { key.value = it }, "At least 8 characters")
                    Field("Local bind", local.value, { local.value = it }, "127.0.0.1")
                    Field("Base port", port.value, { port.value = it }, "47989")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Debug log")
                        Checkbox(state = if (debug.value) ToggleableState.On else ToggleableState.Off,
                            onClick = { debug.value = !debug.value })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text(status.value, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = if (running.value) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
                    Spacer(Modifier.height(8.dp))
                    if (!overlayAllowed.value) TextButton(text = "Grant overlay permission", onClick = onOverlay, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = !running.value, modifier = Modifier.weight(1f)) { Text("Start") }
                        Button(onClick = onStop, enabled = running.value, modifier = Modifier.weight(1f)) { Text("Stop") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Logs", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Row {
                            TextButton(text = "Copy", onClick = onCopy)
                            TextButton(text = "Clear", onClick = onClear)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(logs.value.ifEmpty { "No log output yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
    Column {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Spacer(Modifier.height(4.dp))
        top.yukonga.miuix.kmp.basic.TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = hint,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (label == "Base port") androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                autoCorrect = false,
            ),
            visualTransformation = if (label == "Key") PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}
