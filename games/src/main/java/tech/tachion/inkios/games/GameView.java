package tech.tachion.inkios.games;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

abstract class GameView extends TapView {
    protected final Activity activity;
    protected final Handler main = new Handler(Looper.getMainLooper());
    protected final SharedPreferences prefs;
    protected final RectF board = new RectF();
    protected final RectF statusR = new RectF();
    private final RectF headerR = new RectF();
    protected String status = "";
    private final Paint back, titleP, button, statusP;
    private String[] buttons = new String[0];
    private final java.util.List<RectF> buttonR = new java.util.ArrayList<>();

    GameView(Activity a) {
        super(a);
        activity = a;
        prefs = a.getSharedPreferences("games", Context.MODE_PRIVATE);
        back = t.textPaint(t.textSemibold, 17, Theme.INK);
        titleP = t.textPaint(t.displayBold, 30, Theme.INK);
        button = t.textPaint(t.textSemibold, 16, Theme.INK);
        statusP = t.textPaint(t.textMedium, 17, Theme.SECONDARY);
    }

    abstract String title();

    abstract String[] buttons();

    abstract void onButton(int index);

    abstract void layoutBoard();

    abstract void drawBoard(Canvas c);

    float footerPt() { return 0; }

    void drawFooter(Canvas c, RectF area) {}

    protected final RectF footer = new RectF();

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    protected void relayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        targets.clear();
        buttonR.clear();
        float m = t.px(18);
        headerR.set(0, 0, w, t.px(150));
        target(m - t.px(6), t.px(8), m + t.px(110), t.px(56), t.px(12), activity::finish);
        buttons = buttons();
        float x = w - m;
        for (int i = buttons.length - 1; i >= 0; i--) {
            float bw = button.measureText(buttons[i]) + t.px(28);
            RectF r = new RectF(x - bw, t.px(66), x, t.px(104));
            buttonR.add(0, r);
            final int idx = i;
            target(r.left, r.top, r.right, r.bottom, t.px(19), () -> onButton(idx));
            x -= bw + t.px(8);
        }
        statusR.set(m, t.px(110), w - m, t.px(146));
        float footerH = t.px(footerPt());
        float avail = Math.min(w - 2 * m, h - t.px(156) - footerH - t.px(12));
        float top = t.px(152);
        board.set((w - avail) / 2f, top, (w + avail) / 2f, top + avail);
        footer.set(m, board.bottom + t.px(10), w - m, h - t.px(10));
        layoutBoard();
    }

    @Override
    protected final void drawContent(Canvas c) {
        float m = t.px(18);
        Draw.chevron(c, m + t.px(6), t.px(32), t.px(18), -1, t.stroke(Theme.INK, 2.4f));
        c.drawText("Games", m + t.px(18), t.px(38), back);
        c.drawText(title(), m, t.px(94), titleP);
        Paint pill = t.stroke(Theme.INK, 1.5f);
        for (int i = 0; i < buttonR.size(); i++) {
            RectF r = buttonR.get(i);
            Draw.roundRect(c, r.left, r.top, r.right, r.bottom, r.height() / 2, pill);
            float tw = button.measureText(buttons[i]);
            c.drawText(buttons[i], r.centerX() - tw / 2, r.centerY() + t.px(5.5f), button);
        }
        Draw.text(c, status, statusR.left, statusR.bottom - t.px(10), statusR.width(), statusP);
        drawBoard(c);
        if (footerPt() > 0) drawFooter(c, footer);
    }

    protected void setStatus(String s) {
        status = s;
        refreshRect(statusR, Eink.Mode.TEXT);
    }

    protected void fast(RectF r) { refreshRect(r, Eink.Mode.FAST); }

    protected void clean() {
        refreshRect(new RectF(0, statusR.top, getWidth(), getHeight()), Eink.Mode.CLEAN);
    }

    interface Pick { void accept(int which); }

    protected void choose(String title, String[] items, int checked, Pick pick) {
        new AlertDialog.Builder(activity, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, (d, w) -> { d.dismiss(); pick.accept(w); })
                .show();
    }

    protected void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(activity, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("OK", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    void save() {}
}
