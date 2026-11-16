package tech.tachion.inkios.notes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tech.tachion.inkios.eink.Eink;

public final class InkView extends View {
    public interface Listener {
        void onPageChanged();
        void onTwoFingerTap();
        void onTextTap(float x, float y, Page.Text existing);
    }

    public static final int TOOL_PEN = 0, TOOL_TEXT = 1, TOOL_ERASER = 2;
    public static final float[] PEN_WIDTHS = {2.6f, 4.4f, 7.5f};
    public static final String[] PEN_NAMES = {"Fine", "Medium", "Bold"};
    private static final float ERASER_RADIUS = 16f;

    private Listener listener;
    private Page page = new Page();
    private int template = Page.DOTS;
    private int tool = TOOL_PEN;
    private int penSize = 1;

    private Bitmap layer;
    private Canvas lc;
    private float scale = 1f;

    private Stroke live;
    private final Path seg = new Path();
    private final RectF dirtyF = new RectF();
    private final Rect dirty = new Rect();
    private final RectF tmpF = new RectF();

    private boolean multi, cancelled;
    private long multiStart;
    private float downX, downY;
    private final List<Stroke> erasedThisGesture = new ArrayList<>();

    private static final class Action {
        final List<Stroke> addS, remS;
        final List<Page.Text> addT, remT;

        Action(List<Stroke> addS, List<Stroke> remS, List<Page.Text> addT, List<Page.Text> remT) {
            this.addS = addS; this.remS = remS; this.addT = addT; this.remT = remT;
        }

        Action inverse() { return new Action(remS, addS, remT, addT); }
    }

    private static <T> List<T> none() { return Collections.emptyList(); }

    private static <T> List<T> one(T t) {
        List<T> l = new ArrayList<>(1);
        l.add(t);
        return l;
    }

    private final ArrayDeque<Action> undo = new ArrayDeque<>();
    private final ArrayDeque<Action> redo = new ArrayDeque<>();

    public InkView(Context c) {
        super(c);
        setBackgroundColor(0xFFFFFFFF);
    }

    public void setListener(Listener l) { listener = l; }

    public void setPage(Page p, int template) {
        page = p;
        this.template = template;
        undo.clear();
        redo.clear();
        renderAll();
        Eink.refresh(this, Eink.Mode.CLEAN);
    }

    public Page page() { return page; }

    public void setTemplate(int template) {
        this.template = template;
        renderAll();
        Eink.refresh(this, Eink.Mode.CLEAN);
    }

    public void setTool(int tool) { this.tool = tool; }

    public int tool() { return tool; }

    public void setPenSize(int size) { penSize = size; }

    public int penSize() { return penSize; }

    public boolean canUndo() { return !undo.isEmpty(); }

    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        Action a = undo.pop();
        perform(a.inverse());
        redo.push(a);
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Action a = redo.pop();
        perform(a);
        undo.push(a);
    }

    private void perform(Action a) {
        RectF area = new RectF();
        for (Stroke s : a.remS) area.union(s.paddedBounds(tmpF));
        for (Stroke s : a.addS) area.union(s.paddedBounds(tmpF));
        for (Page.Text t : a.remT) area.union(t.bounds(tmpF));
        for (Page.Text t : a.addT) area.union(t.bounds(tmpF));
        page.strokes.removeAll(a.remS);
        page.strokes.addAll(a.addS);
        page.texts.removeAll(a.remT);
        page.texts.addAll(a.addT);
        boolean text = !a.addT.isEmpty() || !a.remT.isEmpty();
        rerenderAndPush(toPx(area, dirtyF), text ? Eink.Mode.TEXT : Eink.Mode.FAST);
        if (listener != null) listener.onPageChanged();
    }

    private void record(Action a) {
        undo.push(a);
        redo.clear();
    }

    public void addText(float x, float y, String text, boolean bold) {
        if (text.trim().isEmpty()) return;
        Action a = new Action(none(), none(), one(new Page.Text(x, y, text, bold)), none());
        perform(a);
        record(a);
    }

    public void replaceText(Page.Text old, String text, boolean bold) {
        List<Page.Text> add = text.trim().isEmpty() ? none() : one(new Page.Text(old.x, old.y, text, bold));
        Action a = new Action(none(), none(), add, one(old));
        perform(a);
        record(a);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == 0 || h == 0) return;
        if (layer != null) layer.recycle();
        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        lc = new Canvas(layer);
        scale = w / Page.WIDTH;
        renderAll();
    }

    private void renderAll() {
        if (lc == null) return;
        lc.drawColor(0xFFFFFFFF);
        Page.drawTemplate(lc, template, scale);
        page.drawStrokes(lc, scale);
        invalidate();
    }

    private void rerenderAndPush(RectF px, Eink.Mode mode) {
        if (lc == null || px.isEmpty()) return;
        px.roundOut(dirty);
        dirty.inset(-3, -3);
        lc.save();
        lc.clipRect(dirty);
        lc.drawColor(0xFFFFFFFF);
        Page.drawTemplate(lc, template, scale);
        page.drawStrokes(lc, scale);
        lc.restore();
        Eink.refresh(this, dirty, mode);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (layer != null) c.drawBitmap(layer, 0, 0, null);
    }

    private RectF toPx(RectF pageUnits, RectF out) {
        out.set(pageUnits.left * scale, pageUnits.top * scale, pageUnits.right * scale, pageUnits.bottom * scale);
        return out;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multi = false;
                cancelled = false;
                downX = e.getX();
                downY = e.getY();
                if (tool == TOOL_ERASER) {
                    erasedThisGesture.clear();
                    eraseAt(e.getX(), e.getY());
                } else if (tool == TOOL_PEN) {
                    startStroke(e.getX(), e.getY());
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (!multi) {
                    multi = true;
                    multiStart = SystemClock.uptimeMillis();
                    cancelLive();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (multi || cancelled) return true;
                if (tool == TOOL_ERASER) {
                    for (int h = 0; h < e.getHistorySize(); h++) eraseAt(e.getHistoricalX(h), e.getHistoricalY(h));
                    eraseAt(e.getX(), e.getY());
                } else if (tool == TOOL_PEN && live != null) {
                    dirtyF.setEmpty();
                    for (int h = 0; h < e.getHistorySize(); h++) extend(e.getHistoricalX(h), e.getHistoricalY(h));
                    extend(e.getX(), e.getY());
                    pushLive();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (multi) {
                    if (SystemClock.uptimeMillis() - multiStart < 400 && listener != null) listener.onTwoFingerTap();
                    multi = false;
                    return true;
                }
                if (tool == TOOL_ERASER) finishErase();
                else if (tool == TOOL_PEN) finishStroke();
                else if (tool == TOOL_TEXT && Math.abs(e.getX() - downX) < 24 && Math.abs(e.getY() - downY) < 24
                        && listener != null) {
                    float px = e.getX() / scale, py = e.getY() / scale;
                    listener.onTextTap(px, py, page.textAt(px, py));
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelLive();
                return true;
        }
        return true;
    }

    private void startStroke(float x, float y) {
        live = new Stroke(Stroke.PEN, PEN_WIDTHS[penSize]);
        live.add(x / scale, y / scale);
        lc.drawPoint(x, y, Page.paintFor(live, scale));
        dirtyF.set(x, y, x, y);
        pushLive();
    }

    private void extend(float x, float y) {
        int n = live.points();
        float lx = live.x(n - 1) * scale, ly = live.y(n - 1) * scale;
        if (Math.abs(x - lx) < 1.2f && Math.abs(y - ly) < 1.2f) return;
        live.add(x / scale, y / scale);
        float sx, sy;
        if (n >= 2) {
            sx = (live.x(n - 2) * scale + lx) / 2;
            sy = (live.y(n - 2) * scale + ly) / 2;
        } else {
            sx = lx;
            sy = ly;
        }
        float ex = (lx + x) / 2, ey = (ly + y) / 2;
        seg.rewind();
        seg.moveTo(sx, sy);
        seg.quadTo(lx, ly, ex, ey);
        Paint p = Page.paintFor(live, scale);
        lc.drawPath(seg, p);
        float pad = p.getStrokeWidth() / 2 + 2;
        dirtyF.union(Math.min(Math.min(sx, lx), ex) - pad, Math.min(Math.min(sy, ly), ey) - pad,
                Math.max(Math.max(sx, lx), ex) + pad, Math.max(Math.max(sy, ly), ey) + pad);
    }

    private void pushLive() {
        float pad = Page.paintFor(live, scale).getStrokeWidth() / 2 + 2;
        dirtyF.inset(-pad, -pad);
        dirtyF.roundOut(dirty);
        Eink.refresh(this, dirty, Eink.Mode.INSTANT);
    }

    private void finishStroke() {
        if (live == null) return;
        Stroke s = live;
        live = null;
        int n = s.points();
        if (n >= 2) {
            float lx = s.x(n - 1) * scale, ly = s.y(n - 1) * scale;
            float mx = (s.x(n - 2) * scale + lx) / 2, my = (s.y(n - 2) * scale + ly) / 2;
            seg.rewind();
            seg.moveTo(mx, my);
            seg.lineTo(lx, ly);
            Paint p = Page.paintFor(s, scale);
            lc.drawPath(seg, p);
            float pad = p.getStrokeWidth() / 2 + 2;
            dirtyF.set(Math.min(mx, lx) - pad, Math.min(my, ly) - pad, Math.max(mx, lx) + pad, Math.max(my, ly) + pad);
            dirtyF.roundOut(dirty);
            Eink.refresh(this, dirty, Eink.Mode.INSTANT);
        }
        page.strokes.add(s);
        record(new Action(one(s), none(), none(), none()));
        if (listener != null) listener.onPageChanged();
    }

    private void cancelLive() {
        if (live != null) {
            toPx(live.paddedBounds(tmpF), dirtyF);
            live = null;
            rerenderAndPush(dirtyF, Eink.Mode.FAST);
        }
        cancelled = true;
    }

    private void eraseAt(float x, float y) {
        float px = x / scale, py = y / scale;
        List<Stroke> hit = null;
        for (Stroke s : page.strokes) {
            if (s.hits(px, py, ERASER_RADIUS)) {
                if (hit == null) hit = new ArrayList<>();
                hit.add(s);
            }
        }
        if (hit == null) return;
        RectF area = new RectF();
        for (Stroke s : hit) {
            page.strokes.remove(s);
            erasedThisGesture.add(s);
            area.union(s.paddedBounds(tmpF));
        }
        rerenderAndPush(toPx(area, dirtyF), Eink.Mode.FAST);
    }

    private void finishErase() {
        if (erasedThisGesture.isEmpty()) return;
        record(new Action(none(), new ArrayList<>(erasedThisGesture), none(), none()));
        erasedThisGesture.clear();
        if (listener != null) listener.onPageChanged();
    }

    public void clear() {
        if (page.strokes.isEmpty() && page.texts.isEmpty()) return;
        record(new Action(none(), new ArrayList<>(page.strokes), none(), new ArrayList<>(page.texts)));
        page.strokes.clear();
        page.texts.clear();
        renderAll();
        Eink.refresh(this, Eink.Mode.CLEAN);
        if (listener != null) listener.onPageChanged();
    }
}
