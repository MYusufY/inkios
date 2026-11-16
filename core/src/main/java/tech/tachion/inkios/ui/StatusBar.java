package tech.tachion.inkios.ui;

import android.graphics.Canvas;
import android.graphics.Paint;

public final class StatusBar {
    public static final class State {
        public int batteryPercent = -1;
        public boolean charging;
        public boolean wifiEnabled;
        public boolean wifiConnected;
        public int wifiLevel;
        public String leftText = "";
    }

    public static final float HEIGHT_PT = 34f;

    private final Theme t;
    private final Paint label, percent, ink, dim, paper;

    public StatusBar(Theme t) {
        this.t = t;
        label = t.textPaint(t.textSemibold, 15, Theme.INK);
        percent = t.textPaint(t.textSemibold, 14, Theme.INK);
        ink = t.fill(Theme.INK);
        dim = t.fill(Theme.SEPARATOR);
        paper = t.fill(Theme.PAPER);
    }

    public void draw(Canvas c, State s, float width) {
        float m = t.px(26);
        float base = t.px(23);
        c.drawText(s.leftText, m, base, label);

        float right = width - m;
        float bw = t.px(27), bh = t.px(13);
        float bt = base - bh + t.px(1);
        if (s.batteryPercent >= 0) {
            Draw.battery(c, right - bw, bt, bw, bh, s.batteryPercent / 100f, s.charging, ink, paper);
            right -= bw + t.px(6);
            right -= Draw.textRight(c, s.batteryPercent + "%", right, base, percent);
            right -= t.px(14);
        }
        if (s.wifiEnabled) {
            float size = t.px(15);
            Draw.wifi(c, right - size, base + t.px(1), size, s.wifiConnected ? s.wifiLevel : 0,
                    !s.wifiConnected, ink, dim);
        }
    }
}
