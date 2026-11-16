package tech.tachion.inkios.system;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import tech.tachion.inkios.RecentsActivity;

public final class KeyService extends AccessibilityService {
    private static final int LIGHT_KEY = KeyEvent.KEYCODE_F1;
    private static final long LONG_PRESS_MS = 550;
    private static final long DOUBLE_GAP_MS = 380;
    private static final long SETTLE_MS = 350;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Frontlight light;

    private long downAt;
    private long gestureStart;
    private long lastUpAt;
    private int presses;
    private boolean longFired;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            longFired = true;
            goHome();
            restoreLightLater();
        }
    };

    @Override
    protected void onServiceConnected() {
        light = new Frontlight(this);
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(wake, f);
        renderSleep();
    }

    private final android.content.BroadcastReceiver wake = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { renderSleep(); }
    };

    private void renderSleep() {
        new Thread(() -> tech.tachion.inkios.sleep.SleepScreen.render(this), "sleep-render").start();
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(wake); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != LIGHT_KEY) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            long now = e.getEventTime();
            if (now - lastUpAt > DOUBLE_GAP_MS) {
                presses = 0;
                gestureStart = e.getDownTime();
            }
            presses++;
            downAt = now;
            longFired = false;
            main.removeCallbacks(longPress);
            if (presses == 2) {
                showRecents();
                restoreLightLater();
                presses = 0;
                lastUpAt = 0;
                return false;
            }
            main.postDelayed(longPress, LONG_PRESS_MS);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            main.removeCallbacks(longPress);
            lastUpAt = longFired ? 0 : e.getEventTime();
            if (longFired) presses = 0;
        }
        return false;
    }

    private void restoreLightLater() {
        if (light == null || !light.isSupported()) return;
        final long before = gestureStart - 50;
        main.postDelayed(() -> light.set(light.wasOnAt(before)), SETTLE_MS);
    }

    private void goHome() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void showRecents() {
        Intent i = new Intent(this, RecentsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
