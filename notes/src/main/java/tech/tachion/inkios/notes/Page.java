package tech.tachion.inkios.notes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Page {
    public static final class Text {
        public float x, y;
        public String text;
        public boolean bold;
        public static final float SIZE = 34f;
        public static final float MARGIN = 40f;

        public Text(float x, float y, String text, boolean bold) {
            this.x = x;
            this.y = y;
            this.text = text;
            this.bold = bold;
        }

        float width() { return Math.max(120f, WIDTH - MARGIN - x); }

        StaticLayout layout(float scale) {
            TextPaint p = bold ? BOLD : REGULAR;
            p.setTextSize(SIZE * scale);
            return new StaticLayout(text, p, Math.round(width() * scale), Layout.Alignment.ALIGN_NORMAL,
                    1.15f, 0, false);
        }

        public RectF bounds(RectF out) {
            StaticLayout l = layout(1f);
            out.set(x, y, x + width(), y + l.getHeight());
            return out;
        }
    }

    private static final TextPaint REGULAR = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private static final TextPaint BOLD = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    static {
        REGULAR.setColor(0xFF000000);
        BOLD.setColor(0xFF000000);
        REGULAR.setTypeface(Typeface.DEFAULT);
        BOLD.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public static void setTypefaces(Typeface regular, Typeface bold) {
        REGULAR.setTypeface(regular);
        BOLD.setTypeface(bold);
    }

    public final java.util.List<Text> texts = new java.util.ArrayList<>();

    public static final float WIDTH = 1000f;
    public static final float HEIGHT = 1232f;

    public static final int BLANK = 0, DOTS = 1, LINES = 2, GRID = 3;
    public static final String[] TEMPLATE_NAMES = {"Blank", "Dots", "Lines", "Grid"};

    private static final int MAGIC_V1 = 0x494E4B31;
    private static final int MAGIC = 0x494E4B32;

    public final List<Stroke> strokes = new ArrayList<>();

    private static final Paint PEN = paint(0xFF000000);
    private static final Paint MARKER = paint(0xFF999999);
    private static final Paint RULE = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        RULE.setColor(0xFFCCCCCC);
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    public static void drawTemplate(Canvas c, int template, float scale) {
        float w = WIDTH * scale, h = HEIGHT * scale;
        float step = 40 * scale;
        switch (template) {
            case DOTS: {
                float r = Math.max(1.2f, 2.0f * scale);
                for (float y = step * 2; y < h - step; y += step) {
                    for (float x = step; x < w - step / 2; x += step) c.drawCircle(x, y, r, RULE);
                }
                break;
            }
            case LINES: {
                RULE.setStrokeWidth(Math.max(1f, 1.6f * scale));
                for (float y = step * 3; y < h - step; y += step * 1.25f) c.drawLine(step, y, w - step, y, RULE);
                break;
            }
            case GRID: {
                RULE.setStrokeWidth(Math.max(1f, 1.2f * scale));
                for (float y = step; y < h; y += step) c.drawLine(0, y, w, y, RULE);
                for (float x = step; x < w; x += step) c.drawLine(x, 0, x, h, RULE);
                break;
            }
            default:
        }
    }

    public static Path path(Stroke s, float scale, Path out) {
        out.rewind();
        int n = s.points();
        if (n == 0) return out;
        float px = s.x(0) * scale, py = s.y(0) * scale;
        out.moveTo(px, py);
        if (n == 1) {
            out.lineTo(px + 0.1f, py);
            return out;
        }
        for (int i = 1; i < n; i++) {
            float x = s.x(i) * scale, y = s.y(i) * scale;
            out.quadTo(px, py, (px + x) / 2, (py + y) / 2);
            px = x;
            py = y;
        }
        out.lineTo(px, py);
        return out;
    }

    public static Paint paintFor(Stroke s, float scale) {
        Paint p = s.tool == Stroke.MARKER ? MARKER : PEN;
        p.setStrokeWidth(Math.max(1f, s.width * scale));
        return p;
    }

    private final Path tmp = new Path();

    public void drawStrokes(Canvas c, float scale) {
        for (Text t : texts) {
            c.save();
            c.translate(t.x * scale, t.y * scale);
            t.layout(scale).draw(c);
            c.restore();
        }
        for (Stroke s : strokes) if (s.tool == Stroke.MARKER) c.drawPath(path(s, scale, tmp), paintFor(s, scale));
        for (Stroke s : strokes) if (s.tool != Stroke.MARKER) c.drawPath(path(s, scale, tmp), paintFor(s, scale));
    }

    public Text textAt(float x, float y) {
        RectF r = new RectF();
        for (int i = texts.size() - 1; i >= 0; i--) {
            Text t = texts.get(i);
            t.bounds(r);
            r.inset(-8, -8);
            if (r.contains(x, y)) return t;
        }
        return null;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(strokes.size());
        for (Stroke s : strokes) {
            out.writeByte(s.tool);
            out.writeFloat(s.width);
            out.writeInt(s.points());
            for (int i = 0; i < s.n; i++) out.writeFloat(s.pts[i]);
        }
        out.writeInt(texts.size());
        for (Text t : texts) {
            out.writeFloat(t.x);
            out.writeFloat(t.y);
            out.writeBoolean(t.bold);
            out.writeUTF(t.text);
        }
    }

    public static Page read(DataInputStream in) throws IOException {
        Page p = new Page();
        int magic = in.readInt();
        if (magic != MAGIC && magic != MAGIC_V1) throw new IOException("not an Inkios page");
        int count = in.readInt();
        for (int k = 0; k < count; k++) {
            Stroke s = new Stroke(in.readByte(), in.readFloat());
            int pts = in.readInt();
            for (int i = 0; i < pts; i++) s.add(in.readFloat(), in.readFloat());
            p.strokes.add(s);
        }
        if (magic == MAGIC) {
            int texts = in.readInt();
            for (int k = 0; k < texts; k++) {
                float x = in.readFloat(), y = in.readFloat();
                boolean bold = in.readBoolean();
                p.texts.add(new Text(x, y, in.readUTF(), bold));
            }
        }
        return p;
    }
}
