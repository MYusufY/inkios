package tech.tachion.inkios.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

public final class Grey {
    private Grey() {}

    public static Bitmap toEink(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0f);
        float k = 1.18f, off = -255f * (k - 1f) / 2f;
        grey.postConcat(new ColorMatrix(new float[]{
                k, 0, 0, 0, off,
                0, k, 0, 0, off,
                0, 0, k, 0, off,
                0, 0, 0, 1, 0}));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(grey));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    public static Bitmap decodeCover(byte[] raw, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b == null) return null;
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int sw = Math.max(1, Math.round(b.getWidth() * scale)), sh = Math.max(1, Math.round(b.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(b, sw, sh, true);
        if (scaled != b) b.recycle();
        Bitmap crop = Bitmap.createBitmap(scaled, Math.max(0, (sw - w) / 2), Math.max(0, (sh - h) / 2),
                Math.min(w, sw), Math.min(h, sh));
        if (crop != scaled) scaled.recycle();
        Bitmap g = toEink(crop);
        crop.recycle();
        return g;
    }
}
