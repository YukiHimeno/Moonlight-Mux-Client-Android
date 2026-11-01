package com.xmcfw.moonlightmuxclient

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val profileNameState = mutableStateOf("")
    private val serverState = mutableStateOf("")
    private val keyState = mutableStateOf("")
    private val localState = mutableStateOf("127.0.0.1")
    private val portState = mutableStateOf("47989")
    private val debugState = mutableStateOf(true)
    private val statusState = mutableStateOf("Stopped")
    private val logsState = mutableStateOf("")
    private val overlayAllowedState = mutableStateOf(false)
    private val runningState = mutableStateOf(false)
    private val profilesOpenState = mutableStateOf(false)
    private val newProfileNameState = mutableStateOf("")
    private val profileNamesState = mutableStateOf("")
    private var selectedProfileId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MuxClientService.ACTION_LOG -> refreshLogs()
                MuxClientService.ACTION_STATUS -> {
                    val isRunning = intent.getBooleanExtra(
                        MuxClientService.EXTRA_RUNNING,
                        MuxClientService.isRunning(),
                    )
                    updateStatus(isRunning, intent.getStringExtra(MuxClientService.EXTRA_LINE))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        loadConfig()
        buildUi()
        handleLaunchIntent(intent)
        refreshLogs()
        updateStatus(MuxClientService.isRunning(), if (MuxClientService.isRunning()) "Running" else "Stopped")
        updateOverlayPermissionStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MuxClientService.ACTION_LOG)
            addAction(MuxClientService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        refreshLogs()
        updateStatus(MuxClientService.isRunning(), if (MuxClientService.isRunning()) "Running" else "Stopped")
        updateOverlayPermissionStatus()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    private fun buildUi() {
        installMoonlightContent(
            this,
            profileNameState, serverState, keyState, localState, portState, debugState,
            statusState, logsState, overlayAllowedState, runningState,
            profilesOpenState, newProfileNameState, profileNamesState,
            ::showProfiles, ::selectProfile, ::addProfile, ::renameCurrentProfile,
            ::startClient, ::stopClient, ::openOverlaySettings, ::copyLogs, ::clearLogs,
        )
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        var profiles = readProfiles()
        if (profiles.length() > 0 && profiles.optJSONObject(0) == null) {
            profiles = JSONArray()
            prefs.edit().remove(PROFILES_KEY).apply()
        }
        if (profiles.length() == 0) {
            val legacy = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("name", "Default")
                put(MuxClientService.EXTRA_SERVER, prefs.getString(MuxClientService.EXTRA_SERVER, ""))
                put(MuxClientService.EXTRA_KEY, prefs.getString(MuxClientService.EXTRA_KEY, ""))
                put(MuxClientService.EXTRA_LOCAL, prefs.getString(MuxClientService.EXTRA_LOCAL, "127.0.0.1"))
                put(MuxClientService.EXTRA_PORT, prefs.getInt(MuxClientService.EXTRA_PORT, 47989))
                put(MuxClientService.EXTRA_DEBUG, prefs.getBoolean(MuxClientService.EXTRA_DEBUG, true))
            }
            profiles.put(legacy)
            saveProfiles(profiles)
        }
        val defaultId = profiles.optJSONObject(0)?.optString("id")
        selectedProfileId = prefs.getString(ACTIVE_PROFILE_KEY, defaultId)
        selectedProfileId?.let(::showProfile)
    }

    private fun readProfiles(): JSONArray = try {
        JSONArray(getSharedPreferences("config", MODE_PRIVATE).getString(PROFILES_KEY, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun saveProfiles(profiles: JSONArray) {
        getSharedPreferences("config", MODE_PRIVATE).edit().putString(PROFILES_KEY, profiles.toString()).apply()
    }

    private fun profile(id: String?): JSONObject? {
        if (id == null) return null
        val all = readProfiles()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i)
            if (item?.optString("id") == id) return item
        }
        return null
    }

    private fun showProfile(id: String) {
        val item = profile(id) ?: return
        selectedProfileId = id
        profileNameState.value = item.optString("name", "Default")
        serverState.value = item.optString(MuxClientService.EXTRA_SERVER, "")
        keyState.value = item.optString(MuxClientService.EXTRA_KEY, "")
        localState.value = item.optString(MuxClientService.EXTRA_LOCAL, "127.0.0.1")
        portState.value = item.optInt(MuxClientService.EXTRA_PORT, 47989).toString()
        debugState.value = item.optBoolean(MuxClientService.EXTRA_DEBUG, true)
        getSharedPreferences("config", MODE_PRIVATE).edit().putString(ACTIVE_PROFILE_KEY, id).apply()
    }

    private fun persistCurrentProfile() {
        val all = readProfiles()
        var item = profile(selectedProfileId) ?: JSONObject()
        var profileIndex = -1
        for (i in 0 until all.length()) {
            if (all.optJSONObject(i)?.optString("id") == selectedProfileId) {
                profileIndex = i
                break
            }
        }
        try {
            item.put("id", selectedProfileId)
            item.put("name", profileNameState.value)
            item.put(MuxClientService.EXTRA_SERVER, serverState.value.trim())
            item.put(MuxClientService.EXTRA_KEY, keyState.value)
            item.put(MuxClientService.EXTRA_LOCAL, localState.value.trim())
            item.put(MuxClientService.EXTRA_PORT, parsePort())
            item.put(MuxClientService.EXTRA_DEBUG, debugState.value)
            if (profileIndex < 0) all.put(item) else all.put(profileIndex, item)
            saveProfiles(all)
            getSharedPreferences("config", MODE_PRIVATE).edit()
                .putString(MuxClientService.EXTRA_SERVER, serverState.value.trim())
                .putString(MuxClientService.EXTRA_KEY, keyState.value)
                .putString(MuxClientService.EXTRA_LOCAL, localState.value.trim())
                .putInt(MuxClientService.EXTRA_PORT, parsePort())
                .putBoolean(MuxClientService.EXTRA_DEBUG, debugState.value)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun showProfiles() {
        if (MuxClientService.isRunning()) {
            toast("Stop the client before switching profiles")
            return
        }
        persistCurrentProfile()
        profilesOpenState.value = true
        updateProfileNames()
    }

    private fun updateProfileNames() {
        val all = readProfiles()
        profileNamesState.value = (0 until all.length()).joinToString("\n") { i ->
            val item = all.optJSONObject(i)
            "${item?.optString("id")}|${item?.optString("name", "Profile")}"
        }
    }

    private fun addProfile() {
        val name = newProfileNameState.value.trim()
        if (name.isEmpty()) {
            toast("Name is required")
            return
        }
        try {
            val item = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("name", name)
                put(MuxClientService.EXTRA_SERVER, serverState.value)
                put(MuxClientService.EXTRA_KEY, keyState.value)
                put(MuxClientService.EXTRA_LOCAL, localState.value)
                put(MuxClientService.EXTRA_PORT, parsePort())
                put(MuxClientService.EXTRA_DEBUG, debugState.value)
            }
            val all = readProfiles().put(item)
            saveProfiles(all)
            showProfile(item.optString("id"))
            updateProfileNames()
            newProfileNameState.value = ""
        } catch (_: Exception) {
        }
    }

    private fun renameCurrentProfile() {
        val name = newProfileNameState.value.trim()
        if (name.isEmpty()) {
            toast("Name is required")
            return
        }
        val all = readProfiles()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i)
            if (item?.optString("id") == selectedProfileId) {
                try {
                    item.put("name", name)
                } catch (_: Exception) {
                }
            }
        }
        saveProfiles(all)
        profileNameState.value = name
        updateProfileNames()
    }

    private fun selectProfile(id: String) {
        persistCurrentProfile()
        showProfile(id)
        profilesOpenState.value = false
    }

    private fun handleLaunchIntent(launchIntent: Intent?) {
        if (launchIntent?.action != MuxClientService.ACTION_START) return
        serverState.value = launchIntent.getStringExtra(MuxClientService.EXTRA_SERVER) ?: serverState.value
        keyState.value = launchIntent.getStringExtra(MuxClientService.EXTRA_KEY) ?: keyState.value
        localState.value = launchIntent.getStringExtra(MuxClientService.EXTRA_LOCAL) ?: localState.value
        if (launchIntent.hasExtra(MuxClientService.EXTRA_PORT)) {
            portState.value = launchIntent.getIntExtra(MuxClientService.EXTRA_PORT, 47989).toString()
        }
        if (launchIntent.hasExtra(MuxClientService.EXTRA_DEBUG)) {
            debugState.value = launchIntent.getBooleanExtra(MuxClientService.EXTRA_DEBUG, true)
        }
        persistCurrentProfile()
        startClient()
    }

    private fun startClient() {
        if (!OverlayKeepAlive.hasPermission(this)) {
            updateOverlayPermissionStatus()
            toast("Overlay permission is required")
            openOverlaySettings()
            return
        }
        if (serverState.value.trim().isEmpty()) {
            toast("Server is required")
            return
        }
        if (keyState.value.length < 8) {
            toast("Key must be at least 8 characters")
            return
        }
        persistCurrentProfile()
        val serviceIntent = Intent(this, MuxClientService::class.java).apply {
            action = MuxClientService.ACTION_START
            putExtra(MuxClientService.EXTRA_SERVER, serverState.value.trim())
            putExtra(MuxClientService.EXTRA_KEY, keyState.value)
            putExtra(MuxClientService.EXTRA_LOCAL, localState.value.trim())
            putExtra(MuxClientService.EXTRA_PORT, parsePort())
            putExtra(MuxClientService.EXTRA_DEBUG, debugState.value)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        updateStatus(true, "Starting")
    }

    private fun stopClient() {
        val serviceIntent = Intent(this, MuxClientService::class.java).setAction(MuxClientService.ACTION_STOP)
        startService(serviceIntent)
        updateStatus(false, "Stopping")
    }

    private fun refreshLogs() {
        logsState.value = LogBuffer.snapshot()
    }

    private fun updateStatus(isRunning: Boolean, message: String?) {
        runningState.value = isRunning
        statusState.value = "${if (isRunning) "Running" else "Stopped"} · ${message.orEmpty()}"
        updateOverlayPermissionStatus()
    }

    private fun updateOverlayPermissionStatus() {
        overlayAllowedState.value = OverlayKeepAlive.hasPermission(this)
    }

    private fun openOverlaySettings() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(settingsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun parsePort(): Int = portState.value.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 47989

    private fun copyLogs() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Moonlight Mux logs", LogBuffer.snapshot()))
        toast("Logs copied")
    }

    private fun clearLogs() {
        LogBuffer.clear()
        refreshLogs()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PROFILES_KEY = "profiles_json"
        const val ACTIVE_PROFILE_KEY = "active_profile"
    }
}
