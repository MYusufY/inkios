package tech.tachion.inkios.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import tech.tachion.inkios.eink.Eink;

public final class InkMenu extends TapView {
    public static final class Item {
        final String label, value;
        final boolean stay;
        final Action action;

        public Item(String label, String value, boolean stay, Action action) {
            this.label = label;
            this.value = value;
            this.stay = stay;
            this.action = action;
        }

        public Item(String label, Action action) { this(label, null, false, action); }
    }

    public interface Provider { List<Item> items(); }

    private static final float WIDTH_PT = 310, ROW_PT = 50, TITLE_PT = 38;

    private final FrameLayout host;
    private final String title;
    private final Provider provider;
    private final Runnable onDismiss;
    private final float anchorRight, anchorTop;
    private List<Item> items = new ArrayList<>();
    private final List<Target> rows = new ArrayList<>();
    private final RectF card = new RectF();
    private boolean dismissed;
    private final Paint label, value, titleP, fill, edge, sep, press;

    public static InkMenu show(FrameLayout host, String title, float rightPx, float topPx,
                               Provider provider, Runnable onDismiss) {
        InkMenu m = new InkMenu(host.getContext(), host, title, rightPx, topPx, provider, onDismiss);
        host.addView(m, new FrameLayout.LayoutParams(-1, -1));
        return m;
    }

    private InkMenu(Context c, FrameLayout host, String title, float right, float top, Provider p, Runnable onDismiss) {
        super(c);
        setBackgroundColor(0x00000000);
        this.host = host;
        this.title = title;
        this.anchorRight = right;
        this.anchorTop = top;
        this.provider = p;
        this.onDismiss = onDismiss;
        label = t.textPaint(t.textMedium, 17, Theme.INK);
        value = t.textPaint(t.textMedium, 16, Theme.SECONDARY);
        titleP = t.textPaint(t.displaySemibold, 15, Theme.SECONDARY);
        fill = t.fill(Theme.PAPER);
        edge = t.stroke(Theme.INK, 1.5f);
        sep = t.fill(Theme.SEPARATOR);
        press = t.fill(Theme.GROUPED);
    }

    public boolean isShowing() { return !dismissed; }

    public void dismiss() {
        if (dismissed) return;
        dismissed = true;
        Rect r = new Rect();
        card.roundOut(r);
        r.inset(-4, -4);
        host.removeView(this);
        Eink.refresh(host, r, Eink.Mode.CLEAN);
        if (onDismiss != null) onDismiss.run();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuild();
        refreshRect(card, Eink.Mode.CLEAN);
    }

    private float titleH() { return title == null ? t.px(6) : t.px(TITLE_PT); }

    private void rebuild() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        items = provider.items();
        float cw = Math.min(t.px(WIDTH_PT), w - t.px(16));
        float ch = titleH() + items.size() * t.px(ROW_PT) + t.px(6);
        float right = Math.min(w - t.px(8), Math.max(cw + t.px(8), anchorRight));
        float top = Math.max(t.px(8), Math.min(anchorTop, h - ch - t.px(8)));
        card.set(right - cw, top, right, top + ch);

        targets.clear();
        rows.clear();
        target(0, 0, w, h, 0, this::dismiss).highlight = false;
        target(card.left, card.top, card.right, card.bottom, 0, () -> {}).highlight = false;
        for (int i = 0; i < items.size(); i++) {
            final Item it = items.get(i);
            float rt = card.top + titleH() + i * t.px(ROW_PT);
            Target tg = target(card.left, rt, card.right, rt + t.px(ROW_PT), 0, () -> {
                it.action.run();
                if (it.stay && !dismissed) {
                    rebuild();
                    refreshRect(card, Eink.Mode.TEXT);
                } else {
                    dismiss();
                }
            });
            tg.highlight = false;
            rows.add(tg);
        }
    }

    @Override
    protected void drawContent(Canvas c) {
        float rad = t.px(16), pad = t.px(18);
        Draw.roundRect(c, card.left, card.top, card.right, card.bottom, rad, fill);
        if (title != null) Draw.text(c, title, card.left + pad, card.top + t.px(26), card.width() - 2 * pad, titleP);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            RectF r = rows.get(i).bounds;
            if (isPressed(rows.get(i))) c.drawRect(r.left + t.px(2), r.top, r.right - t.px(2), r.bottom, press);
            if (i > 0 || title != null) c.drawRect(r.left + pad, r.top, r.right, r.top + Math.max(1, t.px(1)), sep);
            float base = r.centerY() + t.px(6);
            float vw = 0;
            if (it.value != null) vw = Draw.textRight(c, it.value, r.right - pad, base, value) + t.px(12);
            Draw.text(c, it.label, r.left + pad, base, r.width() - 2 * pad - vw, label);
        }
        Draw.roundRect(c, card.left, card.top, card.right, card.bottom, rad, edge);
    }
}
