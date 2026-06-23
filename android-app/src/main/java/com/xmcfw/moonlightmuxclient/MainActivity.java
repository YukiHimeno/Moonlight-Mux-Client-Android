package com.xmcfw.moonlightmuxclient;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private EditText serverInput;
    private EditText keyInput;
    private EditText localInput;
    private EditText portInput;
    private CheckBox debugInput;
    private Button startButton;
    private Button stopButton;
    private Button overlayButton;
    private TextView statusView;
    private TextView overlayStatusView;
    private TextView logView;
    private ScrollView logScroll;

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
        buildUi();
        loadConfig();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("Moonlight Mux Client");
        title.setTextColor(0xFF111827);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF4B5563);
        statusView.setPadding(0, dp(4), 0, dp(12));
        root.addView(statusView, matchWrap());

        overlayStatusView = new TextView(this);
        overlayStatusView.setTextSize(13);
        overlayStatusView.setTextColor(0xFF4B5563);
        overlayStatusView.setPadding(0, 0, 0, dp(4));
        root.addView(overlayStatusView, matchWrap());

        overlayButton = new Button(this);
        overlayButton.setText("Overlay permission");
        overlayButton.setOnClickListener(v -> openOverlaySettings());
        root.addView(overlayButton, matchWrap());

        serverInput = addInput(root, "Server", "host:port", InputType.TYPE_CLASS_TEXT);
        keyInput = addInput(root, "Key", "at least 8 characters", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        localInput = addInput(root, "Local bind", "127.0.0.1", InputType.TYPE_CLASS_TEXT);
        portInput = addInput(root, "Base port", "47989", InputType.TYPE_CLASS_NUMBER);

        debugInput = new CheckBox(this);
        debugInput.setText("Debug log");
        debugInput.setTextColor(0xFF111827);
        debugInput.setChecked(true);
        root.addView(debugInput, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, dp(8));

        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setOnClickListener(v -> startClient());
        actions.addView(startButton, weightedButton());

        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(v -> stopClient());
        actions.addView(stopButton, weightedButton());

        Button copyButton = new Button(this);
        copyButton.setText("Copy");
        copyButton.setOnClickListener(v -> copyLogs());
        actions.addView(copyButton, weightedButton());

        Button clearButton = new Button(this);
        clearButton.setText("Clear");
        clearButton.setOnClickListener(v -> {
            LogBuffer.clear();
            refreshLogs();
        });
        actions.addView(clearButton, weightedButton());
        root.addView(actions, matchWrap());

        logView = new TextView(this);
        logView.setTextColor(0xFF101827);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundColor(0xFFFFFFFF);

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        setContentView(root);
    }

    private EditText addInput(LinearLayout root, String label, String hint, int inputType) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(0xFF374151);
        text.setTextSize(12);
        text.setPadding(0, dp(6), 0, dp(2));
        root.addView(text, matchWrap());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setTextSize(15);
        input.setSelectAllOnFocus(false);
        root.addView(input, matchWrap());
        return input;
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        serverInput.setText(prefs.getString(MuxClientService.EXTRA_SERVER, ""));
        keyInput.setText(prefs.getString(MuxClientService.EXTRA_KEY, ""));
        localInput.setText(prefs.getString(MuxClientService.EXTRA_LOCAL, "127.0.0.1"));
        portInput.setText(String.valueOf(prefs.getInt(MuxClientService.EXTRA_PORT, 47989)));
        debugInput.setChecked(prefs.getBoolean(MuxClientService.EXTRA_DEBUG, true));
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null || !MuxClientService.ACTION_START.equals(intent.getAction())) {
            return;
        }
        if (intent.hasExtra(MuxClientService.EXTRA_SERVER)) {
            serverInput.setText(intent.getStringExtra(MuxClientService.EXTRA_SERVER));
        }
        if (intent.hasExtra(MuxClientService.EXTRA_KEY)) {
            keyInput.setText(intent.getStringExtra(MuxClientService.EXTRA_KEY));
        }
        if (intent.hasExtra(MuxClientService.EXTRA_LOCAL)) {
            localInput.setText(intent.getStringExtra(MuxClientService.EXTRA_LOCAL));
        }
        if (intent.hasExtra(MuxClientService.EXTRA_PORT)) {
            portInput.setText(String.valueOf(intent.getIntExtra(MuxClientService.EXTRA_PORT, 47989)));
        }
        if (intent.hasExtra(MuxClientService.EXTRA_DEBUG)) {
            debugInput.setChecked(intent.getBooleanExtra(MuxClientService.EXTRA_DEBUG, true));
        }
        startClient();
    }

    private void saveConfig() {
        getSharedPreferences("config", MODE_PRIVATE)
                .edit()
                .putString(MuxClientService.EXTRA_SERVER, serverInput.getText().toString().trim())
                .putString(MuxClientService.EXTRA_KEY, keyInput.getText().toString())
                .putString(MuxClientService.EXTRA_LOCAL, localInput.getText().toString().trim())
                .putInt(MuxClientService.EXTRA_PORT, parsePort())
                .putBoolean(MuxClientService.EXTRA_DEBUG, debugInput.isChecked())
                .apply();
    }

    private void startClient() {
        if (!OverlayKeepAlive.hasPermission(this)) {
            updateOverlayPermissionStatus();
            toast("Overlay permission is required");
            openOverlaySettings();
            return;
        }
        if (serverInput.getText().toString().trim().isEmpty()) {
            toast("Server is required");
            return;
        }
        if (keyInput.getText().toString().length() < 8) {
            toast("Key must be at least 8 characters");
            return;
        }
        saveConfig();

        Intent intent = new Intent(this, MuxClientService.class);
        intent.setAction(MuxClientService.ACTION_START);
        intent.putExtra(MuxClientService.EXTRA_SERVER, serverInput.getText().toString().trim());
        intent.putExtra(MuxClientService.EXTRA_KEY, keyInput.getText().toString());
        intent.putExtra(MuxClientService.EXTRA_LOCAL, localInput.getText().toString().trim());
        intent.putExtra(MuxClientService.EXTRA_PORT, parsePort());
        intent.putExtra(MuxClientService.EXTRA_DEBUG, debugInput.isChecked());

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
        logView.setText(LogBuffer.snapshot());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateStatus(boolean running, String message) {
        statusView.setText(running ? "Running - " + message : "Stopped - " + message);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        updateOverlayPermissionStatus();
    }

    private void updateOverlayPermissionStatus() {
        boolean allowed = OverlayKeepAlive.hasPermission(this);
        if (overlayStatusView != null) {
            overlayStatusView.setText(allowed ? "Overlay: authorized" : "Overlay: permission missing");
            overlayStatusView.setTextColor(allowed ? 0xFF047857 : 0xFFB45309);
        }
        if (overlayButton != null) {
            overlayButton.setEnabled(!allowed);
            overlayButton.setVisibility(allowed ? View.GONE : View.VISIBLE);
        }
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
            int port = Integer.parseInt(portInput.getText().toString().trim());
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
