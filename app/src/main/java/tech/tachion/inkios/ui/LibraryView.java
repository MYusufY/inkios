package tech.tachion.inkios.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import tech.tachion.inkios.data.Apps;
import tech.tachion.inkios.eink.Eink;

import java.util.ArrayList;
import java.util.List;

public final class LibraryView extends TapView {
    public interface Host {
        void launch(Apps.App app);
        void appInfo(Apps.App app);
        void showHome();
    }

    private final Host host;
    private final StatusBar statusBar;
    private final StatusBar.State status;
    private final List<Apps.App> apps = new ArrayList<>();
    private int page;
    private int cols = 4, rows = 5;

    private final RectF gridR = new RectF(), dotsR = new RectF();
    private final Paint largeTitle, done, label, dotOn, dotOff, hint;

    public LibraryView(Context c, Host host, StatusBar.State sharedStatus) {
        super(c);
        this.host = host;
        this.status = sharedStatus;
        statusBar = new StatusBar(t);
        largeTitle = t.textPaint(t.displayBold, 36, Theme.INK);
        done = t.textPaint(t.textSemibold, 18, Theme.INK);
        label = t.textPaint(t.textMedium, 14, Theme.INK);
        hint = t.textPaint(t.textMedium, 13, Theme.TERTIARY);
        dotOn = t.fill(Theme.INK);
        dotOff = t.fill(Theme.SEPARATOR);
    }

    public void setApps(List<Apps.App> list) {
        apps.clear();
        apps.addAll(list);
        page = Math.min(page, pageCount() - 1);
        rebuildTargets();
        invalidate();
    }

    public void resetPage() {
        page = 0;
        rebuildTargets();
    }

    private int perPage() { return cols * rows; }

    private int pageCount() { return Math.max(1, (apps.size() + perPage() - 1) / perPage()); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float m = t.px(22);
        gridR.set(m, t.px(128), w - m, h - t.px(70));
        dotsR.set(0, h - t.px(58), w, h - t.px(18));
        float cellH = t.px(124);
        rows = Math.max(1, (int) (gridR.height() / cellH));
        rebuildTargets();
    }

    private void rebuildTargets() {
        if (getWidth() == 0) return;
        targets.clear();
        int w = getWidth();
        target(0, 0, w, t.px(StatusBar.HEIGHT_PT), 0, () -> Eink.fullRefresh(this)).highlight = false;
        float doneW = done.measureText("Done");
        target(w - t.px(22) - doneW - t.px(14), t.px(52), w - t.px(8), t.px(108), t.px(14), host::showHome);

        float cellW = gridR.width() / cols;
        float cellH = gridR.height() / rows;
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < apps.size(); i++) {
            int col = i % cols, row = i / cols;
            float l = gridR.left + col * cellW, top = gridR.top + row * cellH;
            final Apps.App a = apps.get(start + i);
            Target tg = target(l + t.px(4), top, l + cellW - t.px(4), top + cellH - t.px(6), t.px(18),
                    () -> host.launch(a));
            tg.onLongPress = () -> host.appInfo(a);
        }
        if (pageCount() > 1) {
            target(0, dotsR.top, w / 2f, dotsR.bottom, 0, () -> flip(-1)).highlight = false;
            target(w / 2f, dotsR.top, w, dotsR.bottom, 0, () -> flip(1)).highlight = false;
        }
    }

    private void flip(int d) {
        int np = Math.max(0, Math.min(pageCount() - 1, page + d));
        if (np == page) return;
        page = np;
        rebuildTargets();
        Eink.refresh(this, Eink.Mode.CLEAN);
    }

    @Override
    protected void onSwipe(int direction) {
        if (direction > 0 && page == 0) host.showHome();
        else flip(direction < 0 ? 1 : -1);
    }

    @Override
    protected void drawContent(Canvas c) {
        int w = getWidth();
        statusBar.draw(c, status, w);
        float m = t.px(22);
        c.drawText("App Library", m, t.px(96), largeTitle);
        Draw.textRight(c, "Done", w - m, t.px(90), done);

        float cellW = gridR.width() / cols;
        float cellH = gridR.height() / rows;
        int icon = t.pxi(68);
        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < apps.size(); i++) {
            int col = i % cols, row = i / cols;
            float l = gridR.left + col * cellW, top = gridR.top + row * cellH;
            Apps.App a = apps.get(start + i);
            Bitmap b = a.icon(getContext(), icon);
            float ix = l + (cellW - icon) / 2f;
            float iy = top + t.px(10);
            c.drawBitmap(b, ix, iy, null);
            String s = a.label;
            float maxW = cellW - t.px(10);
            float lw = Math.min(maxW, label.measureText(s));
            Draw.text(c, s, l + (cellW - lw) / 2f, iy + icon + t.px(24), maxW, label);
        }

        int n = pageCount();
        if (n > 1) {
            float d = t.px(8), gap = t.px(12);
            float total = n * d + (n - 1) * gap;
            float x = (w - total) / 2f;
            float cy = dotsR.centerY();
            for (int i = 0; i < n; i++) {
                c.drawCircle(x + d / 2, cy, d / 2, i == page ? dotOn : dotOff);
                x += d + gap;
            }
        } else {
            String h = "Long-press an app for details";
            Draw.textRight(c, h, (w + hint.measureText(h)) / 2f, dotsR.centerY() + t.px(5), hint);
        }
    }
}
