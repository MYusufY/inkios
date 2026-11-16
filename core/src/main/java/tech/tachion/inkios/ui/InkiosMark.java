package tech.tachion.inkios.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

public final class InkiosMark {
    public static final float TILE = 1024f;
    private static final float CORNER = 230f;
    private static final float R = 60f;
    private static final float CX = 512f;
    private static final float DROP_CY = 272f;
    private static final float APEX_Y = DROP_CY - 2 * R;
    private static final float STEM_TOP = DROP_CY + R + R;
    private static final float STEM_H = 8 * R;
    public static final int BANDS = 15;
    private static final float BAND_H = STEM_H / BANDS;

    public enum Mode { LIGHT, DARK }

    private static int level(int k) {
        int g = k * 0x11;
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path drop = new Path();
    private final Path stemClip = new Path();
    private final Path[] bandPaths = new Path[BANDS];
    private final RectF tmp = new RectF();

    public InkiosMark() {
        double phi = Math.PI / 2 - Math.asin(0.5);
        float dx = (float) (R * Math.sin(phi)), dy = (float) (R * Math.cos(phi));
        drop.addCircle(CX, DROP_CY, R, Path.Direction.CW);
        Path tip = new Path();
        tip.moveTo(CX - dx, DROP_CY - dy);
        tip.lineTo(CX, APEX_Y);
        tip.lineTo(CX + dx, DROP_CY - dy);
        tip.lineTo(CX, DROP_CY);
        tip.close();
        drop.addPath(tip);
        drop.setFillType(Path.FillType.WINDING);
        tmp.set(CX - R, STEM_TOP, CX + R, STEM_TOP + STEM_H);
        stemClip.addRoundRect(tmp, R, R, Path.Direction.CW);
        for (int i = 0; i < BANDS; i++) {
            Path band = new Path();
            band.addRect(CX - R, STEM_TOP + i * BAND_H, CX + R, STEM_TOP + (i + 1) * BAND_H, Path.Direction.CW);
            band.op(stemClip, Path.Op.INTERSECT);
            bandPaths[i] = band;
        }
    }

    public static RectF stemBounds(RectF out) {
        out.set(CX - R, STEM_TOP, CX + R, STEM_TOP + STEM_H);
        return out;
    }

    public void draw(Canvas c, float left, float top, float size, Mode mode, int bands, boolean tile) {
        boolean dark = mode == Mode.DARK;
        float s = size / TILE;
        c.save();
        c.translate(left, top);
        c.scale(s, s);
        if (tile) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dark ? level(0) : level(15));
            tmp.set(0, 0, TILE, TILE);
            c.drawRoundRect(tmp, CORNER, CORNER, paint);
        }
        paint.setColor(dark ? level(15) : level(0));
        c.drawPath(drop, paint);

        for (int i = 0; i < Math.min(bands, BANDS); i++) {
            paint.setColor(dark ? level(15 - i) : level(i));
            c.drawPath(bandPaths[i], paint);
        }
        c.restore();
    }
}
