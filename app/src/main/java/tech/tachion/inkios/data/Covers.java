package tech.tachion.inkios.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Covers {
    private Covers() {}

    public static Bitmap load(Context ctx, Book book, int maxW, int maxH) {
        File cache = new File(ctx.getCacheDir(), "covers");
        cache.mkdirs();
        String key = Integer.toHexString(book.file.getAbsolutePath().hashCode())
                + "_" + book.file.lastModified() + "_" + maxW + "x" + maxH + ".png";
        File cached = new File(cache, key);
        if (cached.exists()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getPath());
            if (b != null) return b;
        }
        if (!"epub".equals(book.extension())) return null;
        byte[] raw = Epub.coverBytes(book.file);
        if (raw == null) return null;
        Bitmap b = decodeScaled(raw, maxW, maxH);
        if (b == null) return null;
        Bitmap g = toEinkGrey(b);
        if (g != b) b.recycle();
        try (FileOutputStream out = new FileOutputStream(cached)) {
            g.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) {}
        return g;
    }

    private static Bitmap decodeScaled(byte[] raw, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (b == null) return null;
        float scale = Math.max(maxW / (float) b.getWidth(), maxH / (float) b.getHeight());
        int w = Math.round(b.getWidth() * scale), h = Math.round(b.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(b, w, h, true);
        if (scaled != b) b.recycle();
        int x = Math.max(0, (w - maxW) / 2), y = Math.max(0, (h - maxH) / 2);
        Bitmap out = Bitmap.createBitmap(scaled, x, y, Math.min(maxW, w), Math.min(maxH, h));
        if (out != scaled) scaled.recycle();
        return out;
    }

    public static Bitmap toEinkGrey(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0f);
        float k = 1.18f, off = -255f * (k - 1f) / 2f;
        ColorMatrix contrast = new ColorMatrix(new float[]{
                k, 0, 0, 0, off,
                0, k, 0, 0, off,
                0, 0, k, 0, off,
                0, 0, 0, 1, 0});
        grey.postConcat(contrast);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(grey));
        c.drawBitmap(src, 0, 0, p);
        return out;
    }
}
