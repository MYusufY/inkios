package tech.tachion.inkios.eink;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;

public final class Eink {
    private static final String TAG = "InkiosEink";

    public enum Mode { FULL, CLEAN, TEXT, FAST, INSTANT }

    private static boolean sInit;
    private static Method sInvalidateMode;
    private static Method sInvalidateRect;
    private static int sFull, sClean, sText, sFast, sInstant;

    private static final int GHOST_BUDGET = 24;
    private static int sPartialCount;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Eink() {}

    private static synchronized void init() {
        if (sInit) return;
        sInit = true;
        try {
            sInvalidateMode = View.class.getMethod("invalidate", int.class);
            try {
                sInvalidateRect = View.class.getMethod("invalidate",
                        int.class, int.class, int.class, int.class, int.class);
            } catch (Throwable ignored) {
                sInvalidateRect = null;
            }
            sFull = constant("EINK_WAVEFORM_MODE_GC16", 2) | constant("EINK_UPDATE_MODE_FULL", 32)
                    | constant("EINK_WAIT_MODE_NOWAIT", 0);
            sClean = constant("EINK_MODE_GC16_PART", 2);
            sText = constant("EINK_MODE_GL16_PART", 3);
            sFast = constant("EINK_MODE_DU_PART", 1);
            sInstant = constant("EINK_MODE_A2_PART", 6);
            Log.i(TAG, "i.MX EPDC refresh API found");
        } catch (Throwable t) {
            sInvalidateMode = null;
            Log.i(TAG, "No vendor e-ink API, using standard invalidation");
        }
    }

    private static int constant(String name, int fallback) {
        try {
            return View.class.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static boolean isSupported() {
        init();
        return sInvalidateMode != null;
    }

    public static void refresh(View v, Mode mode) {
        if (v == null) return;
        refresh(v, new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), mode);
    }

    private static final long GAP_MS = 160;
    private static long sLastSent;
    private static final java.util.Map<View, Pending> PENDING = new java.util.LinkedHashMap<>();
    private static boolean sScheduled;

    private static final class Pending {
        final android.graphics.Rect rect = new android.graphics.Rect();
        Mode mode;
    }

    private static int strength(Mode m) {
        switch (m) {
            case FULL: return 4;
            case CLEAN: return 3;
            case TEXT: return 2;
            case FAST: return 1;
            default: return 0;
        }
    }

    public static void refresh(View v, android.graphics.Rect dirty, Mode mode) {
        init();
        if (v == null) return;
        android.graphics.Rect r = new android.graphics.Rect(dirty);
        if (!r.intersect(0, 0, v.getWidth(), v.getHeight()) || r.isEmpty()) return;
        if (sInvalidateRect == null || sInvalidateMode == null) {
            v.invalidate(r);
            return;
        }
        if (mode == Mode.INSTANT) {
            send(v, r, mode);
            return;
        }
        Pending p = PENDING.get(v);
        if (p == null) {
            p = new Pending();
            p.rect.set(r);
            p.mode = mode;
            PENDING.put(v, p);
        } else {
            p.rect.union(r);
            if (strength(mode) > strength(p.mode)) p.mode = mode;
        }
        schedule();
    }

    private static void schedule() {
        if (sScheduled) return;
        sScheduled = true;
        long wait = Math.max(0, sLastSent + GAP_MS - android.os.SystemClock.uptimeMillis());
        MAIN.postDelayed(FLUSH, wait);
    }

    private static final Runnable FLUSH = new Runnable() {
        @Override public void run() {
            sScheduled = false;
            if (PENDING.isEmpty()) return;
            java.util.Iterator<java.util.Map.Entry<View, Pending>> it = PENDING.entrySet().iterator();
            java.util.Map.Entry<View, Pending> e = it.next();
            it.remove();
            View v = e.getKey();
            if (v.getWindowToken() != null && v.getWidth() > 0) send(v, e.getValue().rect, e.getValue().mode);
            if (!PENDING.isEmpty()) schedule();
        }
    };

    private static void send(View v, android.graphics.Rect r, Mode mode) {
        int code;
        switch (mode) {
            case FULL: code = sFull; sPartialCount = 0; break;
            case CLEAN: code = sClean; sPartialCount = 0; break;
            case TEXT: code = sText; sPartialCount++; break;
            case FAST: code = sFast; sPartialCount++; break;
            default: code = sInstant; sPartialCount++; break;
        }
        if (mode != Mode.INSTANT) sLastSent = android.os.SystemClock.uptimeMillis();
        try {
            if (r.left == 0 && r.top == 0 && r.right == v.getWidth() && r.bottom == v.getHeight()) {
                sInvalidateMode.invoke(v, code);
            } else {
                sInvalidateRect.invoke(v, r.left, r.top, r.right, r.bottom, code);
            }
        } catch (Throwable t) {
            v.invalidate(r);
        }
    }

    public static void fullRefresh(View anyView) {
        if (anyView == null) return;
        refresh(anyView.getRootView(), Mode.FULL);
    }

    public static void fullRefreshSoon(final View anyView, long delayMs) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { fullRefresh(anyView); }
        }, delayMs);
    }

    public static void settle(final View v) {
        if (sPartialCount < GHOST_BUDGET) return;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { refresh(v.getRootView(), Mode.CLEAN); }
        }, 400);
    }
}
