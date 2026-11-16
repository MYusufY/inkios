package tech.tachion.inkios.system;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayDeque;
import java.util.Iterator;

public final class Frontlight {
    private static final String SWITCH = "screen_brightness_switch";
    private static final String LEVEL = "screen_brightness";
    private static final String OLD = "screen_brightness_old";

    private final ContentResolver cr;
    private final ArrayDeque<long[]> history = new ArrayDeque<>();

    public Frontlight(Context c) {
        cr = c.getContentResolver();
        record();
        cr.registerContentObserver(Settings.System.getUriFor(SWITCH), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) { record(); }
                });
    }

    public boolean isSupported() {
        try {
            Settings.System.getInt(cr, SWITCH);
            return true;
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    public boolean isOn() {
        return Settings.System.getInt(cr, SWITCH, 0) == 1;
    }

    public boolean wasOnAt(long uptime) {
        boolean state = history.isEmpty() ? isOn() : history.peekFirst()[1] == 1;
        for (long[] h : history) {
            if (h[0] <= uptime) state = h[1] == 1;
        }
        return state;
    }

    public void set(boolean on) {
        if (on == isOn()) return;
        try {
            if (on) {
                int old = Settings.System.getInt(cr, OLD, 128);
                Settings.System.putInt(cr, LEVEL, Math.max(1, old));
                Settings.System.putInt(cr, SWITCH, 1);
            } else {
                int level = Settings.System.getInt(cr, LEVEL, 0);
                if (level > 0) Settings.System.putInt(cr, OLD, level);
                Settings.System.putInt(cr, LEVEL, 0);
                Settings.System.putInt(cr, SWITCH, 0);
            }
        } catch (SecurityException ignored) {
        }
    }

    private void record() {
        history.addLast(new long[]{SystemClock.uptimeMillis(), isOn() ? 1 : 0});
        while (history.size() > 16) history.removeFirst();
        long cutoff = SystemClock.uptimeMillis() - 60_000;
        Iterator<long[]> it = history.iterator();
        while (history.size() > 1 && it.hasNext() && it.next()[0] < cutoff) it.remove();
    }
}
