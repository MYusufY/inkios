package tech.tachion.inkios.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

public final class Draw {
    private Draw() {}

    private static final RectF TMP = new RectF();

    public static void roundRect(Canvas c, float l, float t, float r, float b, float radius, Paint p) {
        TMP.set(l, t, r, b);
        c.drawRoundRect(TMP, radius, radius, p);
    }

    public static float text(Canvas c, String s, float x, float y, float maxWidth, Paint p) {
        if (s == null) return 0;
        TextPaint tp = asTextPaint(p);
        CharSequence e = TextUtils.ellipsize(s, tp, maxWidth, TextUtils.TruncateAt.END);
        c.drawText(e, 0, e.length(), x, y, p);
        return p.measureText(e, 0, e.length());
    }

    public static float textRight(Canvas c, String s, float right, float y, Paint p) {
        float w = p.measureText(s);
        c.drawText(s, right - w, y, p);
        return w;
    }

    public static float tracked(Canvas c, String s, float x, float y, float tracking, Paint p) {
        String u = s.toUpperCase();
        float cx = x;
        for (int i = 0; i < u.length(); i++) {
            String ch = u.substring(i, i + 1);
            c.drawText(ch, cx, y, p);
            cx += p.measureText(ch) + tracking;
        }
        return cx - x - tracking;
    }

    public static float wrapped(Canvas c, String s, float x, float top, int width, int maxLines,
                                float lineSpacingMult, Paint p) {
        if (s == null || width <= 0) return 0;
        TextPaint tp = asTextPaint(p);
        StaticLayout l = new StaticLayout(s, tp, width, Layout.Alignment.ALIGN_NORMAL,
                lineSpacingMult, 0, false);
        int lines = Math.min(maxLines, l.getLineCount());
        if (l.getLineCount() > maxLines) {
            int cut = l.getLineStart(maxLines - 1);
            String head = s.substring(0, cut);
            String tail = s.substring(cut).trim();
            CharSequence e = TextUtils.ellipsize(tail, tp, width, TextUtils.TruncateAt.END);
            l = new StaticLayout(head + e, tp, width, Layout.Alignment.ALIGN_NORMAL,
                    lineSpacingMult, 0, false);
            lines = Math.min(maxLines, l.getLineCount());
        }
        c.save();
        c.translate(x, top);
        c.clipRect(0, 0, width, l.getLineBottom(lines - 1));
        l.draw(c);
        c.restore();
        return l.getLineBottom(lines - 1);
    }

    private static TextPaint asTextPaint(Paint p) {
        if (p instanceof TextPaint) return (TextPaint) p;
        TextPaint tp = new TextPaint(p);
        return tp;
    }

    public static void wifi(Canvas c, float cx, float bottom, float size, int level, boolean off,
                            Paint on, Paint dim) {
        float[] radii = {size * 0.30f, size * 0.62f, size * 0.95f};
        float stroke = size * 0.15f;
        for (int i = 0; i < 3; i++) {
            Paint p = (!off && level >= i + 1) ? on : dim;
            Paint s = new Paint(p);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeWidth(stroke);
            s.setStrokeCap(Paint.Cap.ROUND);
            float r = radii[i];
            TMP.set(cx - r, bottom - r, cx + r, bottom + r);
            c.drawArc(TMP, 225, 90, false, s);
        }
        c.drawCircle(cx, bottom - stroke * 0.2f, stroke * 0.75f, off ? dim : on);
    }

    public static void battery(Canvas c, float l, float t, float w, float h, float level, boolean charging,
                               Paint ink, Paint paper) {
        float r = h * 0.28f;
        float capW = w * 0.07f;
        float bodyR = l + w - capW;
        Paint outline = new Paint(ink);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(h * 0.09f);
        float inset = outline.getStrokeWidth() / 2f;
        roundRect(c, l + inset, t + inset, bodyR - inset, t + h - inset, r, outline);
        roundRect(c, bodyR + h * 0.04f, t + h * 0.32f, l + w, t + h * 0.68f, capW * 0.5f, ink);
        float pad = h * 0.18f;
        float innerW = (bodyR - l) - pad * 2;
        float fillW = Math.max(innerW * 0.06f, innerW * Math.max(0f, Math.min(1f, level)));
        roundRect(c, l + pad, t + pad, l + pad + fillW, t + h - pad, r * 0.6f, ink);
        if (charging) {
            float cx = l + (bodyR - l) / 2f, cy = t + h / 2f, s = h * 0.75f;
            Path bolt = new Path();
            bolt.moveTo(cx + s * 0.10f, cy - s * 0.55f);
            bolt.lineTo(cx - s * 0.32f, cy + s * 0.08f);
            bolt.lineTo(cx - s * 0.02f, cy + s * 0.08f);
            bolt.lineTo(cx - s * 0.10f, cy + s * 0.55f);
            bolt.lineTo(cx + s * 0.32f, cy - s * 0.08f);
            bolt.lineTo(cx + s * 0.02f, cy - s * 0.08f);
            bolt.close();
            Paint halo = new Paint(paper);
            halo.setStyle(Paint.Style.STROKE);
            halo.setStrokeWidth(h * 0.12f);
            halo.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(bolt, halo);
            c.drawPath(bolt, ink);
        }
    }

    public static void grid(Canvas c, float cx, float cy, float size, Paint p) {
        float cell = size / 3.6f, gap = (size - cell * 3) / 2f;
        float l0 = cx - size / 2f, t0 = cy - size / 2f;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float l = l0 + j * (cell + gap), t = t0 + i * (cell + gap);
                roundRect(c, l, t, l + cell, t + cell, cell * 0.3f, p);
            }
        }
    }

    public static void chevron(Canvas c, float cx, float cy, float size, int dir, Paint stroke) {
        Path p = new Path();
        float hx = size * 0.25f * dir, hy = size * 0.5f;
        p.moveTo(cx - hx, cy - hy);
        p.lineTo(cx + hx, cy);
        p.lineTo(cx - hx, cy + hy);
        c.drawPath(p, stroke);
    }
}
