package tech.tachion.inkios.games;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.StatusBar;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class GamesActivity extends Activity {
    private Hub hub;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        hub = new Hub(this);
        setContentView(hub);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.invalidate();
        Eink.fullRefreshSoon(hub, 80);
    }

    static Typeface symbols(Context c) {
        return Typeface.createFromAsset(c.getAssets(), "fonts/NotoSansSymbols2-Regular.ttf");
    }

    final class Hub extends TapView {
        private final String[] names = {"Sudoku", "Chess", "Go", "Reversi"};
        private final String[] blurbs = {
                "Fill the grid with 1 to 9. A new puzzle every time.",
                "Play the computer or a friend. Easy, Medium or Hard.",
                "Surround territory on a 9×9 board.",
                "Flip discs and take the corners."};
        private final StatusBar statusBar;
        private final StatusBar.State st = new StatusBar.State();
        private final Paint large, name, blurb, sym, card;
        private final Typeface symbols;

        Hub(Context c) {
            super(c);
            statusBar = new StatusBar(t);
            large = t.textPaint(t.displayBold, 36, Theme.INK);
            name = t.textPaint(t.displaySemibold, 22, Theme.INK);
            blurb = t.textPaint(t.textMedium, 15, Theme.SECONDARY);
            symbols = GamesActivity.symbols(c);
            sym = t.textPaint(symbols, 54, Theme.INK);
            card = t.stroke(Theme.SEPARATOR, 1.5f);
        }

        private RectF cardR(int i) {
            float m = t.px(22), h = t.px(124), gap = t.px(14);
            float top = t.px(126) + i * (h + gap);
            return new RectF(m, top, getWidth() - m, top + h);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            targets.clear();
            for (int i = 0; i < names.length; i++) {
                final int game = i;
                RectF r = cardR(i);
                target(r.left, r.top, r.right, r.bottom, t.px(22), () -> {
                    Intent in = new Intent(GamesActivity.this, GameActivity.class);
                    in.putExtra(GameActivity.EXTRA_GAME, game);
                    startActivity(in);
                });
            }
        }

        @Override
        protected void drawContent(Canvas c) {
            statusBar.draw(c, st, getWidth());
            c.drawText("Games", t.px(22), t.px(96), large);
            for (int i = 0; i < names.length; i++) {
                RectF r = cardR(i);
                Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(22), card);
                RectF ic = new RectF(r.left + t.px(18), r.top + t.px(18), r.left + t.px(106), r.bottom - t.px(18));
                drawIcon(c, i, ic);
                float tx = ic.right + t.px(20);
                c.drawText(names[i], tx, r.top + t.px(52), name);
                Draw.wrapped(c, blurbs[i], tx, r.top + t.px(62), Math.round(r.right - tx - t.px(18)), 2, 1.1f, blurb);
            }
        }

        private void drawIcon(Canvas c, int game, RectF r) {
            Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(16), t.fill(Theme.FILL));
            float cx = r.centerX(), cy = r.centerY();
            switch (game) {
                case 0: {
                    float s = r.width() * 0.6f, l = cx - s / 2, tp = cy - s / 2;
                    Paint g = t.stroke(Theme.INK, 1.5f);
                    for (int k = 0; k <= 3; k++) {
                        c.drawLine(l + k * s / 3, tp, l + k * s / 3, tp + s, g);
                        c.drawLine(l, tp + k * s / 3, l + s, tp + k * s / 3, g);
                    }
                    Paint d = t.textPaint(t.displaySemibold, 15, Theme.INK);
                    c.drawText("5", l + s / 6 - d.measureText("5") / 2, tp + s / 6 + t.px(5.5f), d);
                    c.drawText("9", l + s * 5 / 6 - d.measureText("9") / 2, tp + s * 5 / 6 + t.px(5.5f), d);
                    break;
                }
                case 1: {
                    String k = "♞";
                    c.drawText(k, cx - sym.measureText(k) / 2, cy + t.px(19), sym);
                    break;
                }
                case 2: {
                    float s = r.width() * 0.6f, l = cx - s / 2, tp = cy - s / 2;
                    Paint g = t.stroke(Theme.INK, 1.2f);
                    for (int k = 0; k <= 3; k++) {
                        c.drawLine(l + k * s / 3, tp, l + k * s / 3, tp + s, g);
                        c.drawLine(l, tp + k * s / 3, l + s, tp + k * s / 3, g);
                    }
                    float sr = s / 7;
                    c.drawCircle(l + s / 3, tp + s / 3, sr, t.fill(Theme.INK));
                    c.drawCircle(l + s * 2 / 3, tp + s * 2 / 3, sr, t.fill(Theme.PAPER));
                    c.drawCircle(l + s * 2 / 3, tp + s * 2 / 3, sr, t.stroke(Theme.INK, 1.5f));
                    c.drawCircle(l + s * 2 / 3, tp + s / 3, sr, t.fill(Theme.INK));
                    break;
                }
                default: {
                    float sr = r.width() * 0.17f;
                    c.drawCircle(cx - sr * 1.1f, cy, sr, t.fill(Theme.INK));
                    c.drawCircle(cx + sr * 1.1f, cy, sr, t.fill(Theme.PAPER));
                    c.drawCircle(cx + sr * 1.1f, cy, sr, t.stroke(Theme.INK, 1.8f));
                }
            }
        }
    }
}
