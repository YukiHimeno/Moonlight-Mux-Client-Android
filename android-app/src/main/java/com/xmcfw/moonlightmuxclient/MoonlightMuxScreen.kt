package com.xmcfw.moonlightmuxclient

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

data class ProfileItem(val id: String, val name: String)

fun installMoonlightContent(
    activity: ComponentActivity,
    profileName: MutableState<String>, server: MutableState<String>, key: MutableState<String>,
    local: MutableState<String>, port: MutableState<String>, debug: MutableState<Boolean>,
    status: MutableState<String>, logs: MutableState<String>, overlayAllowed: MutableState<Boolean>,
    running: MutableState<Boolean>, profilesOpen: MutableState<Boolean>, newProfileName: MutableState<String>,
    profiles: MutableState<List<ProfileItem>>, selectedProfile: MutableState<String>,
    onProfiles: () -> Unit, onSelectProfile: (String) -> Unit, onAddProfile: () -> Unit,
    onRenameProfile: () -> Unit, onDeleteProfile: (String) -> Unit, onStart: () -> Unit,
    onStop: () -> Unit, onOverlay: () -> Unit, onCopy: () -> Unit, onClear: () -> Unit,
) = activity.setContent {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFF3482FF)) }
    MiuixTheme(controller = controller) {
        Scaffold(topBar = { SmallTopAppBar(title = "Moonlight Mux") }) { padding ->
            ProfileDialog(
                show = profilesOpen.value,
                profiles = profiles.value,
                selectedId = selectedProfile.value,
                name = newProfileName.value,
                onNameChange = { newProfileName.value = it },
                onDismiss = { profilesOpen.value = false },
                onSelect = onSelectProfile,
                onAdd = onAddProfile,
                onRename = onRenameProfile,
                onDelete = onDeleteProfile,
            )
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 600.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SmallTitle(text = "Connection")
                    Card(Modifier.fillMaxWidth()) {
                        Text(if (running.value) "CONNECTED" else "DISCONNECTED", fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (running.value) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text(status.value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Profile · ${profileName.value}", color = MiuixTheme.colorScheme.onSurfaceSecondary, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        TextButton(
                            text = if (running.value) "Stop connection" else "Start connection",
                            onClick = if (running.value) onStop else onStart,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!overlayAllowed.value) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(text = "Allow overlay permission", onClick = onOverlay, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    SmallTitle(text = "Profiles")
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(profileName.value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(if (running.value) "Stop the connection to switch profiles" else "Manage saved connections",
                                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            }
                            TextButton(text = "Manage", onClick = onProfiles, enabled = !running.value)
                        }
                    }
                    SmallTitle(text = "Server settings")
                    Card(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Connection details", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("Changes are saved to the active profile.", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary)
                            Field("Server address", server.value, { server.value = it }, enabled = !running.value)
                            Field("Access key", key.value, { key.value = it }, secret = true, enabled = !running.value)
                            Field("Local address", local.value, { local.value = it }, enabled = !running.value)
                            Field("Local port", port.value, { port.value = it }, numeric = true, enabled = !running.value)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Debug logging", fontWeight = FontWeight.Medium)
                                    Text("Include detailed connection events", fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary)
                                }
                                Switch(checked = debug.value, onCheckedChange = { debug.value = it }, enabled = !running.value)
                            }
                        }
                    }
                    SmallTitle(text = "Activity")
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Connection log", modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(text = "Copy", onClick = onCopy)
                            TextButton(text = "Clear", onClick = onClear)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(logs.value.ifEmpty { "No connection events yet." }, fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileDialog(
    show: Boolean, profiles: List<ProfileItem>, selectedId: String, name: String,
    onNameChange: (String) -> Unit, onDismiss: () -> Unit, onSelect: (String) -> Unit,
    onAdd: () -> Unit, onRename: () -> Unit, onDelete: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ProfileItem?>(null) }
    OverlayDialog(show = show, title = "Profiles", summary = "Choose a saved connection or create a new one.",
        onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightInForProfiles().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { profile ->
                TextButton(
                    text = if (profile.id == selectedId) "✓  ${profile.name} · Current" else profile.name,
                    onClick = { onSelect(profile.id) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Field("Profile name", name, onNameChange)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "Add", onClick = onAdd, modifier = Modifier.weight(1f), enabled = name.isNotBlank())
                TextButton(text = "Rename", onClick = onRename, modifier = Modifier.weight(1f), enabled = name.isNotBlank())
            }
            if (profiles.size > 1) {
                TextButton(text = "Delete current profile", onClick = { deleteTarget = profiles.firstOrNull { it.id == selectedId } },
                    modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
    val target = deleteTarget
    OverlayDialog(show = target != null, title = "Delete profile?",
        summary = "${target?.name.orEmpty()} and its saved connection settings will be removed.",
        onDismissRequest = { deleteTarget = null }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = "Cancel", onClick = { deleteTarget = null }, modifier = Modifier.weight(1f))
            TextButton(text = "Delete", onClick = { target?.let { onDelete(it.id) }; deleteTarget = null },
                modifier = Modifier.weight(1f))
        }
    }
}

private fun Modifier.heightInForProfiles(): Modifier = this.then(Modifier.heightIn(max = 420.dp))

@Composable
private fun Field(
    label: String, value: String, onValueChange: (String) -> Unit,
    numeric: Boolean = false, secret: Boolean = false, enabled: Boolean = true,
) {
    TextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
        label = label, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (numeric) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
}
