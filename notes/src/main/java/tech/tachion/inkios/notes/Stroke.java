package tech.tachion.inkios.notes;

import android.graphics.RectF;

public final class Stroke {
    public static final int PEN = 0;
    public static final int MARKER = 1;

    public final int tool;
    public final float width;
    float[] pts = new float[64];
    int n;
    final RectF bounds = new RectF();

    public Stroke(int tool, float width) {
        this.tool = tool;
        this.width = width;
    }

    public void add(float x, float y) {
        if (n + 2 > pts.length) {
            float[] np = new float[pts.length * 2];
            System.arraycopy(pts, 0, np, 0, n);
            pts = np;
        }
        pts[n++] = x;
        pts[n++] = y;
        if (n == 2) bounds.set(x, y, x, y);
        else bounds.union(x, y);
    }

    public int points() { return n / 2; }

    public float x(int i) { return pts[i * 2]; }

    public float y(int i) { return pts[i * 2 + 1]; }

    public RectF paddedBounds(RectF out) {
        out.set(bounds);
        out.inset(-width, -width);
        return out;
    }

    public boolean hits(float x, float y, float r) {
        float rr = r + width / 2;
        if (x < bounds.left - rr || x > bounds.right + rr || y < bounds.top - rr || y > bounds.bottom + rr) {
            return false;
        }
        int count = points();
        if (count == 1) return dist2(x, y, x(0), y(0)) <= rr * rr;
        for (int i = 1; i < count; i++) {
            if (segDist2(x, y, x(i - 1), y(i - 1), x(i), y(i)) <= rr * rr) return true;
        }
        return false;
    }

    private static float dist2(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static float segDist2(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax, vy = by - ay;
        float len = vx * vx + vy * vy;
        float t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / len));
        return dist2(px, py, ax + t * vx, ay + t * vy);
    }
}
