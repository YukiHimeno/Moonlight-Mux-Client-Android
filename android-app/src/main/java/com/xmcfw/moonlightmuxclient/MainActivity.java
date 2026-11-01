package com.xmcfw.moonlightmuxclient;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.mutableStateOf;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends ComponentActivity {
    private static final String PROFILES_KEY = "profiles_json";
    private static final String ACTIVE_PROFILE_KEY = "active_profile";
    private String selectedProfileId;
    private final MutableState<String> profileNameState = mutableStateOf("");
    private final MutableState<String> serverState = mutableStateOf("");
    private final MutableState<String> keyState = mutableStateOf("");
    private final MutableState<String> localState = mutableStateOf("127.0.0.1");
    private final MutableState<String> portState = mutableStateOf("47989");
    private final MutableState<Boolean> debugState = mutableStateOf(true);
    private final MutableState<String> statusState = mutableStateOf("Stopped");
    private final MutableState<String> logsState = mutableStateOf("");
    private final MutableState<Boolean> overlayAllowedState = mutableStateOf(false);
    private final MutableState<Boolean> runningState = mutableStateOf(false);
    private final MutableState<Boolean> profilesOpenState = mutableStateOf(false);
    private final MutableState<String> newProfileNameState = mutableStateOf("");
    private final MutableState<String> profileNamesState = mutableStateOf("");

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MuxClientService.ACTION_LOG.equals(intent.getAction())) {
                refreshLogs();
            } else if (MuxClientService.ACTION_STATUS.equals(intent.getAction())) {
                boolean running = intent.getBooleanExtra(MuxClientService.EXTRA_RUNNING, MuxClientService.isRunning());
                String message = intent.getStringExtra(MuxClientService.EXTRA_LINE);
                updateStatus(running, message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
        loadConfig();
        buildUi();
        handleLaunchIntent(getIntent());
        refreshLogs();
        updateStatus(MuxClientService.isRunning(), MuxClientService.isRunning() ? "Running" : "Stopped");
        updateOverlayPermissionStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MuxClientService.ACTION_LOG);
        filter.addAction(MuxClientService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        refreshLogs();
        updateStatus(MuxClientService.isRunning(), MuxClientService.isRunning() ? "Running" : "Stopped");
        updateOverlayPermissionStatus();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(receiver);
        super.onPause();
    }

    private void buildUi() {
        MoonlightMuxScreenKt.installMoonlightContent(this,
                profileNameState, serverState, keyState, localState, portState, debugState,
                statusState, logsState, overlayAllowedState, runningState,
                profilesOpenState, newProfileNameState, profileNamesState,
                this::showProfiles, this::selectProfile, this::addProfile, this::renameCurrentProfile,
                this::startClient, this::stopClient,
                this::openOverlaySettings, this::copyLogs, this::clearLogs);
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        JSONArray profiles = readProfiles();
        if (profiles.length() > 0 && profiles.optJSONObject(0) == null) {
            profiles = new JSONArray();
            prefs.edit().remove(PROFILES_KEY).apply();
        }
        if (profiles.length() == 0) {
            JSONObject legacy = new JSONObject();
            try {
                legacy.put("id", UUID.randomUUID().toString());
                legacy.put("name", "Default");
                legacy.put(MuxClientService.EXTRA_SERVER, prefs.getString(MuxClientService.EXTRA_SERVER, ""));
                legacy.put(MuxClientService.EXTRA_KEY, prefs.getString(MuxClientService.EXTRA_KEY, ""));
                legacy.put(MuxClientService.EXTRA_LOCAL, prefs.getString(MuxClientService.EXTRA_LOCAL, "127.0.0.1"));
                legacy.put(MuxClientService.EXTRA_PORT, prefs.getInt(MuxClientService.EXTRA_PORT, 47989));
                legacy.put(MuxClientService.EXTRA_DEBUG, prefs.getBoolean(MuxClientService.EXTRA_DEBUG, true));
                profiles.put(legacy);
            } catch (Exception ignored) { }
            saveProfiles(profiles);
        }
        selectedProfileId = prefs.getString(ACTIVE_PROFILE_KEY, profiles.optJSONObject(0).optString("id"));
        showProfile(selectedProfileId);
    }

    private JSONArray readProfiles() {
        try { return new JSONArray(getSharedPreferences("config", MODE_PRIVATE).getString(PROFILES_KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void saveProfiles(JSONArray profiles) {
        getSharedPreferences("config", MODE_PRIVATE).edit().putString(PROFILES_KEY, profiles.toString()).apply();
    }

    private JSONObject profile(String id) {
        JSONArray all = readProfiles();
        for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i).optString("id").equals(id)) return all.optJSONObject(i);
        return null;
    }

    private void showProfile(String id) {
        JSONObject p = profile(id);
        if (p == null) return;
        selectedProfileId = id;
        profileNameState.setValue(p.optString("name", "Default"));
        serverState.setValue(p.optString(MuxClientService.EXTRA_SERVER, ""));
        keyState.setValue(p.optString(MuxClientService.EXTRA_KEY, ""));
        localState.setValue(p.optString(MuxClientService.EXTRA_LOCAL, "127.0.0.1"));
        portState.setValue(String.valueOf(p.optInt(MuxClientService.EXTRA_PORT, 47989)));
        debugState.setValue(p.optBoolean(MuxClientService.EXTRA_DEBUG, true));
        getSharedPreferences("config", MODE_PRIVATE).edit().putString(ACTIVE_PROFILE_KEY, id).apply();
    }

    private void persistCurrentProfile() {
        JSONArray all = readProfiles();
        JSONObject p = profile(selectedProfileId);
        int profileIndex = -1;
        for (int i = 0; i < all.length(); i++) {
            if (all.optJSONObject(i).optString("id").equals(selectedProfileId)) { profileIndex = i; break; }
        }
        if (p == null) p = new JSONObject();
        try {
            p.put("id", selectedProfileId);
            p.put("name", profileNameState.getValue());
            p.put(MuxClientService.EXTRA_SERVER, serverState.getValue().trim());
            p.put(MuxClientService.EXTRA_KEY, keyState.getValue());
            p.put(MuxClientService.EXTRA_LOCAL, localState.getValue().trim());
            p.put(MuxClientService.EXTRA_PORT, parsePort());
            p.put(MuxClientService.EXTRA_DEBUG, debugState.getValue());
            if (profileIndex < 0) all.put(p); else all.put(profileIndex, p);
            saveProfiles(all);
            getSharedPreferences("config", MODE_PRIVATE).edit()
                    .putString(MuxClientService.EXTRA_SERVER, serverState.getValue().trim())
                    .putString(MuxClientService.EXTRA_KEY, keyState.getValue())
                    .putString(MuxClientService.EXTRA_LOCAL, localState.getValue().trim())
                    .putInt(MuxClientService.EXTRA_PORT, parsePort())
                    .putBoolean(MuxClientService.EXTRA_DEBUG, debugState.getValue()).apply();
        } catch (Exception ignored) { }
    }

    private void showProfiles() {
        if (MuxClientService.isRunning()) { toast("Stop the client before switching profiles"); return; }
        persistCurrentProfile();
        profilesOpenState.setValue(true);
        updateProfileNames();
    }

    private void updateProfileNames() {
        JSONArray all = readProfiles();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            if (i > 0) names.append("\n");
            names.append(all.optJSONObject(i).optString("id")).append("|")
                    .append(all.optJSONObject(i).optString("name", "Profile"));
        }
        profileNamesState.setValue(names.toString());
    }

    private void addProfile() {
        String name = newProfileNameState.getValue().trim();
        if (name.isEmpty()) { toast("Name is required"); return; }
        JSONObject p = new JSONObject();
        try {
            p.put("id", UUID.randomUUID().toString()); p.put("name", name);
            p.put(MuxClientService.EXTRA_SERVER, serverState.getValue()); p.put(MuxClientService.EXTRA_KEY, keyState.getValue());
            p.put(MuxClientService.EXTRA_LOCAL, localState.getValue()); p.put(MuxClientService.EXTRA_PORT, parsePort());
            p.put(MuxClientService.EXTRA_DEBUG, debugState.getValue());
            JSONArray all = readProfiles(); all.put(p); saveProfiles(all); showProfile(p.optString("id"));
            updateProfileNames(); newProfileNameState.setValue("");
        } catch (Exception ignored) { }
    }

    private void renameCurrentProfile() {
        String name = newProfileNameState.getValue().trim();
        if (name.isEmpty()) { toast("Name is required"); return; }
        JSONArray all = readProfiles();
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p.optString("id").equals(selectedProfileId)) try { p.put("name", name); } catch (Exception ignored) { }
        }
        saveProfiles(all);
        profileNameState.setValue(name);
        updateProfileNames();
    }

    private void selectProfile(String id) {
        persistCurrentProfile();
        showProfile(id);
        profilesOpenState.setValue(false);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null || !MuxClientService.ACTION_START.equals(intent.getAction())) {
            return;
        }
        serverState.setValue(intent.getStringExtra(MuxClientService.EXTRA_SERVER, serverState.getValue()));
        keyState.setValue(intent.getStringExtra(MuxClientService.EXTRA_KEY, keyState.getValue()));
        localState.setValue(intent.getStringExtra(MuxClientService.EXTRA_LOCAL, localState.getValue()));
        if (intent.hasExtra(MuxClientService.EXTRA_PORT)) portState.setValue(String.valueOf(intent.getIntExtra(MuxClientService.EXTRA_PORT, 47989)));
        if (intent.hasExtra(MuxClientService.EXTRA_DEBUG)) debugState.setValue(intent.getBooleanExtra(MuxClientService.EXTRA_DEBUG, true));
        persistCurrentProfile();
        startClient();
    }

    private void saveConfig() {
        persistCurrentProfile();
    }

    private void startClient() {
        if (!OverlayKeepAlive.hasPermission(this)) {
            updateOverlayPermissionStatus();
            toast("Overlay permission is required");
            openOverlaySettings();
            return;
        }
        if (serverState.getValue().trim().isEmpty()) {
            toast("Server is required");
            return;
        }
        if (keyState.getValue().length() < 8) {
            toast("Key must be at least 8 characters");
            return;
        }
        saveConfig();

        Intent intent = new Intent(this, MuxClientService.class);
        intent.setAction(MuxClientService.ACTION_START);
        intent.putExtra(MuxClientService.EXTRA_SERVER, serverState.getValue().trim());
        intent.putExtra(MuxClientService.EXTRA_KEY, keyState.getValue());
        intent.putExtra(MuxClientService.EXTRA_LOCAL, localState.getValue().trim());
        intent.putExtra(MuxClientService.EXTRA_PORT, parsePort());
        intent.putExtra(MuxClientService.EXTRA_DEBUG, debugState.getValue());

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateStatus(true, "Starting");
    }

    private void stopClient() {
        Intent intent = new Intent(this, MuxClientService.class);
        intent.setAction(MuxClientService.ACTION_STOP);
        startService(intent);
        updateStatus(false, "Stopping");
    }

    private void refreshLogs() {
        logsState.setValue(LogBuffer.snapshot());
    }

    private void updateStatus(boolean running, String message) {
        runningState.setValue(running);
        statusState.setValue((running ? "Running" : "Stopped") + " · " + message);
        updateOverlayPermissionStatus();
    }

    private void updateOverlayPermissionStatus() {
        boolean allowed = OverlayKeepAlive.hasPermission(this);
        overlayAllowedState.setValue(allowed);
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int parsePort() {
        try {
            int port = Integer.parseInt(portState.getValue().trim());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        return 47989;
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Moonlight Mux logs", LogBuffer.snapshot()));
            toast("Logs copied");
        }
    }

    private void clearLogs() {
        LogBuffer.clear();
        refreshLogs();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
