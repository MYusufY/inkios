package tech.tachion.inkios.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import tech.tachion.inkios.eink.Eink;

import java.util.ArrayList;
import java.util.List;

public abstract class TapView extends View {
    public interface Action { void run(); }

    protected static final class Target {
        public final RectF bounds = new RectF();
        public float radius;
        public Action onTap;
        public Action onLongPress;
        public boolean highlight = true;
    }

    protected final Theme t;
    protected final List<Target> targets = new ArrayList<>();
    protected Target pressed;

    private final Paint pressPaint;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float downX, downY;
    private boolean longFired;
    private final Rect dirty = new Rect();

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressed != null && pressed.onLongPress != null) {
                longFired = true;
                Target p = pressed;
                setPressedTarget(null);
                p.onLongPress.run();
            }
        }
    };

    protected TapView(Context c) {
        super(c);
        t = Theme.get(c);
        pressPaint = t.fill(Theme.GROUPED);
        setWillNotDraw(false);
        setBackgroundColor(Theme.PAPER);
    }

    protected Target target(float l, float top, float r, float b, float radius, Action onTap) {
        Target tg = new Target();
        tg.bounds.set(l, top, r, b);
        tg.radius = radius;
        tg.onTap = onTap;
        targets.add(tg);
        return tg;
    }

    protected void onSwipe(int direction) {}

    @Override
    protected final void onDraw(Canvas c) {
        if (pressed != null && pressed.highlight) {
            Draw.roundRect(c, pressed.bounds.left, pressed.bounds.top, pressed.bounds.right,
                    pressed.bounds.bottom, pressed.radius, pressPaint);
        }
        drawContent(c);
    }

    protected abstract void drawContent(Canvas c);

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                Target hit = hit(downX, downY);
                setPressedTarget(hit);
                if (hit != null && hit.onLongPress != null) handler.postDelayed(longPress, 550);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed != null && (Math.abs(e.getX() - downX) > t.px(14)
                        || Math.abs(e.getY() - downY) > t.px(14))) {
                    handler.removeCallbacks(longPress);
                    setPressedTarget(null);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > t.px(70) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    setPressedTarget(null);
                    onSwipe(dx < 0 ? -1 : 1);
                    return true;
                }
                if (longFired) return true;
                final Target p = pressed;
                if (p != null && p.onTap != null) {
                    p.onTap.run();
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (pressed == p) { pressed = null; invalidate(); }
                        }
                    }, 700);
                } else {
                    setPressedTarget(null);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                setPressedTarget(null);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private Target hit(float x, float y) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            Target tg = targets.get(i);
            if (tg.bounds.contains(x, y)) return tg;
        }
        return null;
    }

    private void setPressedTarget(Target tg) {
        if (pressed == tg) return;
        Target old = pressed;
        pressed = tg;
        if (old != null) refreshRect(old.bounds, Eink.Mode.FAST);
        if (tg != null) refreshRect(tg.bounds, Eink.Mode.FAST);
    }

    protected void refreshRect(RectF r, Eink.Mode mode) {
        r.roundOut(dirty);
        dirty.inset(-2, -2);
        Eink.refresh(this, dirty, mode);
    }

    protected boolean isPressed(Target tg) {
        return pressed == tg;
    }
}
