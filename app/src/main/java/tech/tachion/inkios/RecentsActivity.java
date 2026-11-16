package tech.tachion.inkios;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

import tech.tachion.inkios.data.Covers;
import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class RecentsActivity extends Activity {
    static final class Item {
        int taskId;
        Intent baseIntent;
        String label;
        Bitmap icon;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new RecentsView(this, load()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Eink.fullRefreshSoon(getWindow().getDecorView(), 80);
    }

    @SuppressWarnings("deprecation")
    private List<Item> load() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        PackageManager pm = getPackageManager();
        List<Item> out = new ArrayList<>();
        int icon = Theme.get(this).pxi(56);
        for (ActivityManager.RecentTaskInfo t : am.getRecentTasks(24, ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
            Intent base = new Intent(t.baseIntent);
            if (t.origActivity != null) base.setComponent(t.origActivity);
            if (base.getComponent() == null) continue;
            String pkg = base.getComponent().getPackageName();
            if (pkg.equals(getPackageName())) continue;
            ResolveInfo ri = pm.resolveActivity(base, 0);
            if (ri == null) continue;
            if (isHome(pm, pkg)) continue;
            Item it = new Item();
            it.taskId = t.persistentId;
            it.baseIntent = base;
            it.label = String.valueOf(ri.loadLabel(pm));
            Bitmap b = Bitmap.createBitmap(icon, icon, Bitmap.Config.ARGB_8888);
            android.graphics.drawable.Drawable d = ri.loadIcon(pm);
            d.setBounds(0, 0, icon, icon);
            d.draw(new Canvas(b));
            it.icon = Covers.toEinkGrey(b);
            b.recycle();
            boolean running = false;
            for (ActivityManager.RunningTaskInfo r : am.getRunningTasks(32)) {
                if (r.id == t.id) { running = true; break; }
            }
            if (!running) it.taskId = -1;
            out.add(it);
        }
        return out;
    }

    private static boolean isHome(PackageManager pm, String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : pm.queryIntentActivities(home, 0)) {
            if (r.activityInfo.packageName.equals(pkg)) return true;
        }
        return false;
    }

    void open(Item it) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (it.taskId >= 0) {
            try {
                am.moveTaskToFront(it.taskId, 0);
                finish();
                return;
            } catch (Throwable ignored) {}
        }
        Intent i = new Intent(it.baseIntent);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Throwable ignored) {}
        finish();
    }

    void close(Item it) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        am.killBackgroundProcesses(it.baseIntent.getComponent().getPackageName());
    }

    final class RecentsView extends TapView {
        private final List<Item> items;
        private int page;
        private static final int PER_PAGE = 8;
        private final Paint title, done, label, hint, cardStroke, dotOn, dotOff;

        RecentsView(Context c, List<Item> items) {
            super(c);
            this.items = items;
            title = t.textPaint(t.displayBold, 36, Theme.INK);
            done = t.textPaint(t.textSemibold, 18, Theme.INK);
            label = t.textPaint(t.displaySemibold, 19, Theme.INK);
            hint = t.textPaint(t.textMedium, 15, Theme.TERTIARY);
            cardStroke = t.stroke(Theme.SEPARATOR, 1.5f);
            dotOn = t.fill(Theme.INK);
            dotOff = t.fill(Theme.SEPARATOR);
        }

        private int pages() { return Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE); }

        private RectF card(int i, int w) {
            float m = t.px(22), gap = t.px(14);
            float cw = (w - 2 * m - gap) / 2f, ch = t.px(116);
            int col = i % 2, row = i / 2;
            float l = m + col * (cw + gap), top = t.px(128) + row * (ch + gap);
            return new RectF(l, top, l + cw, top + ch);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rebuild();
        }

        private void rebuild() {
            targets.clear();
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            target(w - t.px(110), t.px(52), w - t.px(8), t.px(108), t.px(14), RecentsActivity.this::finish);
            int start = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && start + i < items.size(); i++) {
                final Item it = items.get(start + i);
                RectF r = card(i, w);
                Target tg = target(r.left, r.top, r.right, r.bottom, t.px(20), () -> open(it));
                tg.onLongPress = () -> {
                    close(it);
                    items.remove(it);
                    page = Math.min(page, pages() - 1);
                    rebuild();
                    Eink.refresh(this, Eink.Mode.CLEAN);
                };
            }
            if (pages() > 1) {
                target(0, h - t.px(70), w / 2f, h, 0, () -> flip(-1)).highlight = false;
                target(w / 2f, h - t.px(70), w, h, 0, () -> flip(1)).highlight = false;
            }
        }

        private void flip(int d) {
            int np = Math.max(0, Math.min(pages() - 1, page + d));
            if (np == page) return;
            page = np;
            rebuild();
            Eink.refresh(this, Eink.Mode.CLEAN);
        }

        @Override
        protected void onSwipe(int direction) { flip(direction < 0 ? 1 : -1); }

        @Override
        protected void drawContent(Canvas c) {
            int w = getWidth(), h = getHeight();
            float m = t.px(22);
            c.drawText("Recent apps", m, t.px(96), title);
            Draw.textRight(c, "Done", w - m, t.px(90), done);
            if (items.isEmpty()) {
                c.drawText("No recent apps", m, t.px(170), hint);
                return;
            }
            int start = page * PER_PAGE;
            for (int i = 0; i < PER_PAGE && start + i < items.size(); i++) {
                Item it = items.get(start + i);
                RectF r = card(i, w);
                Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(20), cardStroke);
                float icon = t.px(56);
                float iy = r.centerY() - icon / 2;
                c.drawBitmap(it.icon, r.left + t.px(18), iy, null);
                float tx = r.left + t.px(18) + icon + t.px(14);
                Draw.text(c, it.label, tx, r.centerY() + t.px(7), r.right - tx - t.px(14), label);
            }
            String foot = "Tap to switch  ·  Hold to close";
            c.drawText(foot, (w - hint.measureText(foot)) / 2f, h - t.px(84), hint);
            int n = pages();
            if (n > 1) {
                float d = t.px(8), gap = t.px(12), x = (w - (n * d + (n - 1) * gap)) / 2f;
                for (int i = 0; i < n; i++) {
                    c.drawCircle(x + d / 2, h - t.px(40), d / 2, i == page ? dotOn : dotOff);
                    x += d + gap;
                }
            }
        }
    }
}
