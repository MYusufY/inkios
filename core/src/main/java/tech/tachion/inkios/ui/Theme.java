package tech.tachion.inkios.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;

public final class Theme {
    public static final float DESIGN_WIDTH = 560f;

    public static final int INK = 0xFF000000;
    public static final int SECONDARY = 0xFF444444;
    public static final int TERTIARY = 0xFF888888;
    public static final int SEPARATOR = 0xFFCCCCCC;
    public static final int FILL = 0xFFEEEEEE;
    public static final int GROUPED = 0xFFDDDDDD;
    public static final int PAPER = 0xFFFFFFFF;

    private static Theme sInstance;

    public final float pt;
    public final int widthPx;
    public final int heightPx;

    public final Typeface text;
    public final Typeface textMedium;
    public final Typeface textSemibold;
    public final Typeface textBold;
    public final Typeface displayLight;
    public final Typeface displaySemibold;
    public final Typeface displayBold;

    private Theme(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        widthPx = Math.min(dm.widthPixels, dm.heightPixels);
        heightPx = Math.max(dm.widthPixels, dm.heightPixels);
        pt = widthPx / DESIGN_WIDTH;
        text = font(c, "Inter-Regular");
        textMedium = font(c, "Inter-Medium");
        textSemibold = font(c, "Inter-SemiBold");
        textBold = font(c, "Inter-Bold");
        displayLight = font(c, "InterDisplay-Light");
        displaySemibold = font(c, "InterDisplay-SemiBold");
        displayBold = font(c, "InterDisplay-Bold");
    }

    public static Theme get(Context c) {
        if (sInstance == null) sInstance = new Theme(c.getApplicationContext());
        return sInstance;
    }

    private static Typeface font(Context c, String name) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf");
        } catch (Throwable t) {
            return Typeface.DEFAULT;
        }
    }

    public float px(float points) {
        return points * pt;
    }

    public int pxi(float points) {
        return Math.round(points * pt);
    }

    public Paint textPaint(Typeface face, float sizePt, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(px(sizePt));
        p.setColor(color);
        return p;
    }

    public Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    public Paint stroke(int color, float widthPt) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(widthPt));
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }
}
