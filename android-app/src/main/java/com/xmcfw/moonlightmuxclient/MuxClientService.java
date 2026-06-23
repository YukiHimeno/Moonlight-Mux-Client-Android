package com.xmcfw.moonlightmuxclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MuxClientService extends Service {
    public static final String ACTION_START = "com.xmcfw.moonlightmuxclient.START";
    public static final String ACTION_STOP = "com.xmcfw.moonlightmuxclient.STOP";
    public static final String ACTION_LOG = "com.xmcfw.moonlightmuxclient.LOG";
    public static final String ACTION_STATUS = "com.xmcfw.moonlightmuxclient.STATUS";

    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_LOCAL = "local";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_DEBUG = "debug";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_RUNNING = "running";

    private static final String CHANNEL_ID = "moonlightmux_status";
    private static final int NOTIFICATION_ID = 721;
    private static final String TAG = "MuxClientService";

    private static volatile boolean running;

    private Process process;
    private Thread readerThread;
    private final OverlayKeepAlive overlayKeepAlive = new OverlayKeepAlive();

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopClient("Stopped");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting"));
        startClient(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopClient("Service destroyed");
        super.onDestroy();
    }

    private synchronized void startClient(Intent intent) {
        if (process != null && process.isAlive()) {
            appendLog("Client is already running");
            updateStatus(true, overlayKeepAlive.isActive() ? "Running - Overlay active" : "Running");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String server = getStringExtra(intent, EXTRA_SERVER, prefs.getString(EXTRA_SERVER, ""));
        String key = getStringExtra(intent, EXTRA_KEY, prefs.getString(EXTRA_KEY, ""));
        String local = getStringExtra(intent, EXTRA_LOCAL, prefs.getString(EXTRA_LOCAL, "127.0.0.1"));
        int port = getIntExtra(intent, EXTRA_PORT, prefs.getInt(EXTRA_PORT, 47989));
        boolean debug = getBooleanExtra(intent, EXTRA_DEBUG, prefs.getBoolean(EXTRA_DEBUG, true));

        if (server.trim().isEmpty() || key.length() < 8) {
            appendLog("Server and an at least 8-character key are required");
            stopClient("Invalid config");
            stopSelf();
            return;
        }

        File binary = new File(getApplicationInfo().nativeLibraryDir, "libmoonlightmuxclient.so");
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.add("-server");
        command.add(server.trim());
        command.add("-local");
        command.add(local.trim().isEmpty() ? "127.0.0.1" : local.trim());
        command.add("-port");
        command.add(String.valueOf(port));
        command.add("-key");
        command.add(key);
        if (debug) {
            command.add("-debug");
        }

        try {
            appendLog("Starting " + binary.getAbsolutePath());
            appendLog("Command: " + redactCommand(command));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(getFilesDir());
            builder.redirectErrorStream(true);
            process = builder.start();
            running = true;
            String overlayStatus = overlayKeepAlive.start(this);
            appendLog(overlayStatus);
            updateStatus(true, "Running - " + overlayStatus);
            startReaderThread();
        } catch (Exception e) {
            appendLog("Start failed: " + e);
            stopClient("Start failed");
            stopSelf();
        }
    }

    private void startReaderThread() {
        readerThread = new Thread(() -> {
            int exitCode = -1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
                exitCode = process.waitFor();
            } catch (Exception e) {
                appendLog("Reader ended: " + e);
            } finally {
                synchronized (MuxClientService.this) {
                    running = false;
                    process = null;
                }
                if (overlayKeepAlive.stop()) {
                    appendLog("Overlay removed");
                }
                appendLog("Client exited with code " + exitCode);
                updateStatus(false, "Stopped");
                stopForegroundCompat();
                stopSelf();
            }
        }, "moonlightmux-reader");
        readerThread.start();
    }

    private synchronized void stopClient(String reason) {
        Process current = process;
        process = null;
        running = false;
        if (current != null) {
            appendLog(reason);
            current.destroy();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (current.isAlive()) {
                current.destroyForcibly();
            }
        }
        if (overlayKeepAlive.stop()) {
            appendLog("Overlay removed");
        }
        updateStatus(false, reason);
        stopForegroundCompat();
    }

    private String getStringExtra(Intent intent, String name, String fallback) {
        if (intent == null || !intent.hasExtra(name)) {
            return fallback;
        }
        return intent.getStringExtra(name);
    }

    private int getIntExtra(Intent intent, String name, int fallback) {
        if (intent == null || !intent.hasExtra(name)) {
            return fallback;
        }
        return intent.getIntExtra(name, fallback);
    }

    private boolean getBooleanExtra(Intent intent, String name, boolean fallback) {
        if (intent == null || !intent.hasExtra(name)) {
            return fallback;
        }
        return intent.getBooleanExtra(name, fallback);
    }

    private void appendLog(String line) {
        LogBuffer.append(line);
        Log.i(TAG, line);
        Intent intent = new Intent(ACTION_LOG);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_LINE, line);
        sendBroadcast(intent);
    }

    private void updateStatus(boolean isRunning, String message) {
        running = isRunning;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && isRunning) {
            manager.notify(NOTIFICATION_ID, buildNotification(message));
        }
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, isRunning);
        intent.putExtra(EXTRA_LINE, message);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String message) {
        createChannel();
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Moonlight Mux Client")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Moonlight Mux status",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Moonlight Mux client runtime status");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private String redactCommand(List<String> command) {
        List<String> copy = new ArrayList<>(command);
        for (int i = 0; i < copy.size() - 1; i++) {
            if ("-key".equals(copy.get(i))) {
                copy.set(i + 1, "********");
            }
        }
        return copy.toString();
    }
}
