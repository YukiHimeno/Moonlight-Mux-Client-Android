package com.xmcfw.moonlightmuxclient;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

final class OverlayKeepAlive {
    private WindowManager windowManager;
    private View view;

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    synchronized String start(Context context) {
        if (view != null) {
            return "Overlay active";
        }
        if (!hasPermission(context)) {
            return "Overlay permission missing";
        }

        Context appContext = context.getApplicationContext();
        windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return "Overlay failed: WindowManager unavailable";
        }

        View overlay = new View(appContext);
        overlay.setAlpha(0.01f);
        overlay.setBackgroundColor(Color.TRANSPARENT);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.alpha = 0.01f;
        params.setTitle("Moonlight Mux keepalive");

        try {
            windowManager.addView(overlay, params);
            view = overlay;
            return "Overlay active";
        } catch (RuntimeException e) {
            view = null;
            return "Overlay failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    synchronized boolean stop() {
        if (view == null || windowManager == null) {
            view = null;
            return false;
        }
        try {
            windowManager.removeView(view);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            view = null;
        }
    }

    synchronized boolean isActive() {
        return view != null;
    }
}
