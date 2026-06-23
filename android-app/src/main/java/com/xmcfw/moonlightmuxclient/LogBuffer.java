package com.xmcfw.moonlightmuxclient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LogBuffer {
    private static final int MAX_CHARS = 60000;
    private static final StringBuilder BUFFER = new StringBuilder();
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private LogBuffer() {
    }

    static synchronized void append(String line) {
        if (line == null) {
            return;
        }
        BUFFER.append(FORMAT.format(new Date())).append("  ").append(line).append('\n');
        if (BUFFER.length() > MAX_CHARS) {
            BUFFER.delete(0, BUFFER.length() - MAX_CHARS);
        }
    }

    static synchronized String snapshot() {
        return BUFFER.toString();
    }

    static synchronized void clear() {
        BUFFER.setLength(0);
    }
}
