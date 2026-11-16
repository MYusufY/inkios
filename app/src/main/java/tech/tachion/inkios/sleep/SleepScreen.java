package tech.tachion.inkios.sleep;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import tech.tachion.inkios.data.Book;
import tech.tachion.inkios.data.Covers;
import tech.tachion.inkios.data.KOReader;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.InkiosMark;
import tech.tachion.inkios.ui.Theme;

public final class SleepScreen {
    private SleepScreen() {}

    public static File file(Context c) {
        return new File(new File(c.getFilesDir(), "sleep"), "sleep.png");
    }

    private static final Object LOCK = new Object();

    public static void render(Context ctx) {
        synchronized (LOCK) {
            try {
                renderLocked(ctx.getApplicationContext());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void renderLocked(Context c) throws Exception {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels, dm.heightPixels), h = Math.max(dm.widthPixels, dm.heightPixels);
        Theme t = Theme.get(c);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Theme.PAPER);

        float m = t.px(44);
        String[] item = pick(c);
        List<Book> books = KOReader.history(1);
        Book book = books.isEmpty() ? null : books.get(0);

        float cardH = book != null ? t.px(196) : 0;
        float bottomLimit = h - t.px(86) - cardH - t.px(30);
        boolean quote = item[0].equals("quote");
        String label = quote ? "Quote" : item[2];
        String text = item[1];
        float size = text.length() < 70 ? 36 : text.length() < 120 ? 31 : 27;
        Paint body = t.textPaint(t.displayLight, size, Theme.INK);
        Paint cap = t.textPaint(t.displaySemibold, 18, Theme.SECONDARY);
        Paint attr = t.textPaint(t.textMedium, 18, Theme.SECONDARY);
        Paint mark = t.textPaint(t.displayBold, 96, Theme.INK);
        int textW = Math.round(w - 2 * m);

        android.text.StaticLayout lay = new android.text.StaticLayout(text, new android.text.TextPaint(body), textW,
                android.text.Layout.Alignment.ALIGN_NORMAL, 1.12f, 0, false);
        float blockH = t.px(quote ? 70 : 34) + lay.getHeight() + (quote ? t.px(44) : 0);
        float top = Math.max(t.px(90), (bottomLimit - blockH) / 2f);
        if (quote) {
            cv.drawText("“", m - t.px(4), top + t.px(72), mark);
            top += t.px(70);
        } else {
            cv.drawText(label, m, top + t.px(18), cap);
            top += t.px(34);
        }
        Draw.wrapped(cv, text, m, top, textW, 9, 1.12f, body);
        top += lay.getHeight();
        if (quote) Draw.text(cv, "— " + item[2], m, top + t.px(40), textW, attr);

        if (book != null) {
            RectF card = new RectF(m - t.px(4), h - t.px(86) - cardH, w - m + t.px(4), h - t.px(86));
            Draw.roundRect(cv, card.left, card.top, card.right, card.bottom, t.px(22), t.stroke(Theme.SEPARATOR, 1.5f));
            float pad = t.px(20);
            RectF cr = new RectF(card.left + pad, card.top + pad, card.left + pad + t.px(104), card.bottom - pad);
            Bitmap cover = Covers.load(c, book, Math.round(cr.width()), Math.round(cr.height()));
            if (cover != null) {
                cv.drawBitmap(cover, new Rect(0, 0, cover.getWidth(), cover.getHeight()), cr, new Paint(Paint.FILTER_BITMAP_FLAG));
            } else {
                Draw.roundRect(cv, cr.left, cr.top, cr.right, cr.bottom, t.px(6), t.fill(Theme.FILL));
            }
            Draw.roundRect(cv, cr.left, cr.top, cr.right, cr.bottom, t.px(6), t.stroke(Theme.SEPARATOR, 1f));
            float tx = cr.right + t.px(22), tw = card.right - pad - tx;
            float y = card.top + pad + t.px(16);
            cv.drawText("Now reading", tx, y, cap);
            float used = Draw.wrapped(cv, book.title, tx, y + t.px(10), Math.round(tw), 2, 1f,
                    t.textPaint(t.displaySemibold, 25, Theme.INK));
            y += t.px(10) + used + t.px(24);
            if (book.author != null) Draw.text(cv, book.author, tx, y, tw, attr);
            if (book.progress >= 0) {
                float by = card.bottom - pad - t.px(8), bh = t.px(6);
                Draw.roundRect(cv, tx, by, tx + tw, by + bh, bh / 2, t.fill(Theme.GROUPED));
                Draw.roundRect(cv, tx, by, tx + Math.max(bh, tw * Math.min(1f, book.progress)), by + bh, bh / 2, t.fill(Theme.INK));
                String p = Math.round(book.progress * 100) + "%";
                if (book.pages > 0) p += "  ·  page " + book.currentPage() + " of " + book.pages;
                Draw.text(cv, p, tx, by - t.px(12), tw, t.textPaint(t.textMedium, 16, Theme.SECONDARY));
            }
        }

        float fs = t.px(28), fy = h - t.px(52);
        new InkiosMark().draw(cv, m, fy - fs / 2, fs, InkiosMark.Mode.LIGHT, InkiosMark.BANDS, false);
        Paint foot = t.textPaint(t.textMedium, 15, Theme.TERTIARY);
        cv.drawText("Sleeping  ·  press the power button to wake", m + fs + t.px(8), fy + t.px(5), foot);

        File out = file(c);
        File dir = out.getParentFile();
        dir.mkdirs();
        dir.setExecutable(true, false);
        dir.setReadable(true, false);
        File tmp = new File(dir, "sleep.tmp");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
        bmp.recycle();
        tmp.setReadable(true, false);
        if (!tmp.renameTo(out)) throw new java.io.IOException("rename failed");
    }

    private static String[] pick(Context c) throws Exception {
        List<String[]> items = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("sleep/items.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", 3);
                if (p.length == 3) items.add(p);
            }
        }
        SharedPreferences prefs = c.getSharedPreferences("sleep", Context.MODE_PRIVATE);
        String recent = prefs.getString("recent", "");
        Random rnd = new Random();
        int idx = rnd.nextInt(items.size());
        for (int tries = 0; tries < 50 && recent.contains("," + idx + ","); tries++) idx = rnd.nextInt(items.size());
        String[] parts = (recent + idx + ",").split(",");
        StringBuilder keep = new StringBuilder(",");
        for (int i = Math.max(0, parts.length - 20); i < parts.length; i++) {
            if (!parts[i].isEmpty()) keep.append(parts[i]).append(',');
        }
        prefs.edit().putString("recent", keep.toString()).apply();
        return items.get(idx);
    }
}
