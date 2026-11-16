package tech.tachion.inkios.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import tech.tachion.inkios.data.Apps;
import tech.tachion.inkios.data.Book;
import tech.tachion.inkios.data.KOReader;
import tech.tachion.inkios.eink.Eink;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class HomeView extends TapView {
    public interface Host {
        void openBook(Book b);
        void openReader();
        void launch(Apps.App app);
        void appInfo(Apps.App app);
        void showLibrary();
        void requestFullRefresh();
    }

    private final Host host;
    private final StatusBar statusBar;
    public final StatusBar.State status = new StatusBar.State();

    private Book current;
    private Bitmap currentCover;
    private final List<Book> recent = new ArrayList<>();
    private final List<Bitmap> recentCovers = new ArrayList<>();
    private KOReader.Stats stats;
    private final List<Apps.App> dock = new ArrayList<>();

    private final RectF statusR = new RectF(), heroR = new RectF(), clockR = new RectF(),
            statsR = new RectF(), shelfR = new RectF(), dockR = new RectF();
    private final RectF coverR = new RectF(), pillR = new RectF();
    private final List<RectF> shelfCoverR = new ArrayList<>();
    private final List<RectF> dockIconR = new ArrayList<>();
    private RectF libraryIconR = new RectF();
    private Target pillTarget;
    public boolean quiet;

    private final Paint caption, title, author, meta, pillText, clockBig, clockDay, clockDate,
            statNum, statUnit, statSmall, shelfTitle, shelfMeta, placeholderTitle;
    private final Paint card, cardFill, ink, track, bar, barDim, coverEdge, paper, dockFill, sep;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("d MMMM", Locale.getDefault());
    private final SimpleDateFormat shortDateFmt = new SimpleDateFormat("EEE d MMM", Locale.getDefault());

    public HomeView(Context c, Host host) {
        super(c);
        this.host = host;
        statusBar = new StatusBar(t);
        caption = t.textPaint(t.displaySemibold, 17, Theme.SECONDARY);
        title = t.textPaint(t.displayBold, 27, Theme.INK);
        author = t.textPaint(t.textMedium, 16, Theme.SECONDARY);
        meta = t.textPaint(t.textMedium, 14, Theme.SECONDARY);
        pillText = t.textPaint(t.textSemibold, 16, Theme.PAPER);
        clockBig = t.textPaint(t.displaySemibold, 64, Theme.INK);
        clockDay = t.textPaint(t.displaySemibold, 17, Theme.SECONDARY);
        clockDate = t.textPaint(t.textMedium, 17, Theme.SECONDARY);
        statNum = t.textPaint(t.displaySemibold, 46, Theme.INK);
        statUnit = t.textPaint(t.textMedium, 16, Theme.SECONDARY);
        statSmall = t.textPaint(t.textMedium, 11, Theme.TERTIARY);
        shelfTitle = t.textPaint(t.textMedium, 13, Theme.INK);
        shelfMeta = t.textPaint(t.textMedium, 12, Theme.TERTIARY);
        placeholderTitle = t.textPaint(t.displaySemibold, 13, Theme.SECONDARY);

        card = t.stroke(Theme.SEPARATOR, 1.5f);
        cardFill = t.fill(Theme.PAPER);
        ink = t.fill(Theme.INK);
        track = t.fill(Theme.GROUPED);
        bar = t.fill(Theme.INK);
        barDim = t.fill(0xFFAAAAAA);
        coverEdge = t.stroke(Theme.SEPARATOR, 1f);
        paper = t.fill(Theme.PAPER);
        dockFill = t.fill(Theme.FILL);
        sep = t.fill(Theme.SEPARATOR);
        updateClock();
    }

    public void setBooks(List<Book> books, Bitmap cover, List<Bitmap> covers) {
        current = books.isEmpty() ? null : books.get(0);
        currentCover = cover;
        recent.clear();
        recentCovers.clear();
        for (int i = 1; i < books.size(); i++) {
            recent.add(books.get(i));
            recentCovers.add(i - 1 < covers.size() ? covers.get(i - 1) : null);
        }
        rebuildTargets();
        if (quiet) { invalidate(); return; }
        refreshRect(heroR, Eink.Mode.CLEAN);
        refreshRect(shelfR, Eink.Mode.CLEAN);
    }

    public void setStats(KOReader.Stats s) {
        stats = s;
        if (quiet) { invalidate(); return; }
        refreshRect(statsR, Eink.Mode.TEXT);
    }

    public void setDock(List<Apps.App> apps) {
        dock.clear();
        dock.addAll(apps);
        rebuildTargets();
        if (quiet) { invalidate(); return; }
        refreshRect(dockR, Eink.Mode.CLEAN);
    }

    public void updateClock() {
        status.leftText = shortDateFmt.format(new Date());
        if (getWidth() > 0) {
            refreshRect(clockR, Eink.Mode.TEXT);
            refreshRect(statusR, Eink.Mode.TEXT);
        }
    }

    public void updateStatus() {
        if (getWidth() > 0) refreshRect(statusR, Eink.Mode.TEXT);
    }

    public int[] heroCoverSize() { return new int[]{t.pxi(112), t.pxi(168)}; }

    public int[] shelfCoverSize() {
        if (shelfCoverR.isEmpty()) return new int[]{t.pxi(84), t.pxi(126)};
        RectF r = shelfCoverR.get(0);
        return new int[]{Math.round(r.width()), Math.round(r.height())};
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float m = t.px(22);
        float gap = t.px(14);
        statusR.set(0, 0, w, t.px(StatusBar.HEIGHT_PT));

        float y = statusR.bottom + t.px(8);
        heroR.set(m, y, w - m, y + t.px(204));
        y = heroR.bottom + gap;

        float half = (w - 2 * m - gap) / 2f;
        clockR.set(m, y, m + half, y + t.px(164));
        statsR.set(clockR.right + gap, y, w - m, clockR.bottom);
        y = clockR.bottom + gap;

        float dockH = t.px(88);
        float dockBottom = h - t.px(18);
        dockR.set(0, dockBottom - dockH, 0, dockBottom);
        shelfR.set(m, y, w - m, dockR.top - t.px(18));

        float pad = t.px(20);
        int[] cs = heroCoverSize();
        coverR.set(heroR.left + pad, heroR.top + (heroR.height() - cs[1]) / 2f,
                heroR.left + pad + cs[0], heroR.top + (heroR.height() + cs[1]) / 2f);
        pillR.set(heroR.right - pad - t.px(124), heroR.bottom - pad - t.px(42),
                heroR.right - pad, heroR.bottom - pad);

        shelfCoverR.clear();
        float sTop = shelfR.top + t.px(46);
        float labelsH = t.px(58);
        float coverH = shelfR.bottom - t.px(16) - labelsH - sTop;
        float coverW = coverH / 1.5f;
        float avail = shelfR.width() - 2 * pad;
        float sGap = t.px(18);
        int n = 4;
        if (coverW * n + sGap * (n - 1) > avail) {
            coverW = (avail - sGap * (n - 1)) / n;
            coverH = coverW * 1.5f;
        } else {
            sGap = (avail - coverW * n) / (n - 1);
        }
        for (int i = 0; i < n; i++) {
            float l = shelfR.left + pad + i * (coverW + sGap);
            shelfCoverR.add(new RectF(l, sTop, l + coverW, sTop + coverH));
        }
        rebuildTargets();
    }

    private void rebuildTargets() {
        if (getWidth() == 0) return;
        targets.clear();
        int w = getWidth();
        float r = t.px(22);

        target(0, 0, w, statusR.bottom, 0, host::requestFullRefresh).highlight = false;

        Target heroT = target(heroR.left, heroR.top, heroR.right, heroR.bottom, r, () -> {
            if (current != null) host.openBook(current); else host.openReader();
        });
        heroT.highlight = true;
        pillTarget = target(pillR.left, pillR.top, pillR.right, pillR.bottom, pillR.height() / 2, () -> {
            if (current != null) host.openBook(current); else host.openReader();
        });
        pillTarget.highlight = false;

        target(statsR.left, statsR.top, statsR.right, statsR.bottom, r, host::openReader);

        for (int i = 0; i < shelfCoverR.size() && i < recent.size(); i++) {
            RectF c = shelfCoverR.get(i);
            final Book b = recent.get(i);
            target(c.left - t.px(6), c.top - t.px(6), c.right + t.px(6), c.bottom + t.px(58), t.px(10),
                    () -> host.openBook(b));
        }

        dockIconR.clear();
        float icon = t.px(60), gap = t.px(22), pad = t.px(16);
        int count = dock.size();
        float width = pad * 2 + (count + 1) * icon + count * gap + t.px(14);
        float l = (w - width) / 2f;
        dockR.left = l;
        dockR.right = l + width;
        float top = dockR.top + (dockR.height() - icon) / 2f;
        float x = l + pad;
        for (int i = 0; i < count; i++) {
            RectF ir = new RectF(x, top, x + icon, top + icon);
            dockIconR.add(ir);
            final Apps.App app = dock.get(i);
            Target tg = target(ir.left - t.px(8), ir.top - t.px(8), ir.right + t.px(8), ir.bottom + t.px(8),
                    t.px(18), () -> host.launch(app));
            tg.onLongPress = () -> host.appInfo(app);
            x += icon + gap;
        }
        x += t.px(14);
        libraryIconR = new RectF(x, top, x + icon, top + icon);
        target(libraryIconR.left - t.px(8), libraryIconR.top - t.px(8), libraryIconR.right + t.px(8),
                libraryIconR.bottom + t.px(8), t.px(18), host::showLibrary);
    }

    @Override
    protected void onSwipe(int direction) {
        if (direction < 0) host.showLibrary();
    }

    @Override
    protected void drawContent(Canvas c) {
        statusBar.draw(c, status, getWidth());
        drawHero(c);
        drawClock(c);
        drawStats(c);
        drawShelf(c);
        drawDock(c);
    }

    private void cardOutline(Canvas c, RectF r) {
        float rad = t.px(22);
        Draw.roundRect(c, r.left, r.top, r.right, r.bottom, rad, card);
    }

    private void drawHero(Canvas c) {
        cardOutline(c, heroR);
        float pad = t.px(20);
        drawCover(c, coverR, current, currentCover, t.px(7));

        float x = coverR.right + t.px(22);
        float right = heroR.right - pad;
        float width = right - x;
        float y = heroR.top + pad + t.px(14);
        c.drawText(current != null && current.finished ? "Finished" : "Continue reading", x, y, caption);

        String ttl = current != null ? current.title : "Your library awaits";
        y += t.px(10);
        float used = Draw.wrapped(c, ttl, x, y, Math.round(width), 2, 0.96f, title);
        y += used + t.px(22);
        String au = current != null ? current.author
                : "Open KOReader to start a book. It will show up here.";
        if (au != null) Draw.text(c, au, x, y, width, author);

        boolean pr = isPressed(pillTarget);
        Paint pillFill = pr ? t.fill(Theme.SECONDARY) : ink;
        Draw.roundRect(c, pillR.left, pillR.top, pillR.right, pillR.bottom, pillR.height() / 2, pillFill);
        String label = current != null ? (current.finished ? "Open" : "Resume") : "Open Reader";
        float lw = pillText.measureText(label);
        float tri = t.px(9);
        float total = lw + (current != null ? tri + t.px(8) : 0);
        float tx = pillR.centerX() - total / 2f;
        float cy = pillR.centerY();
        if (current != null) {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(tx, cy - tri * 0.6f);
            p.lineTo(tx + tri, cy);
            p.lineTo(tx, cy + tri * 0.6f);
            p.close();
            c.drawPath(p, paper);
            tx += tri + t.px(8);
        }
        c.drawText(label, tx, cy + pillText.getTextSize() * 0.36f, pillText);

        if (current != null && current.progress >= 0) {
            float barR = pillR.left - t.px(24);
            float barY = pillR.centerY() + t.px(9);
            float bh = t.px(6);
            Draw.roundRect(c, x, barY, barR, barY + bh, bh / 2, track);
            float pw = Math.max(bh, (barR - x) * Math.min(1f, current.progress));
            Draw.roundRect(c, x, barY, x + pw, barY + bh, bh / 2, bar);
            int pct = Math.round(current.progress * 100);
            String m = pct + "%";
            if (current.pages > 0) m += "  ·  Page " + current.currentPage() + " of " + current.pages;
            Draw.text(c, m, x, barY - t.px(10), barR - x, meta);
        }
    }

    private void drawClock(Canvas c) {
        cardOutline(c, clockR);
        float pad = t.px(20);
        Date now = new Date();
        float x = clockR.left + pad;
        c.drawText(capitalize(dayFmt.format(now)), x, clockR.top + pad + t.px(14), clockDay);
        String time = timeFmt.format(now);
        c.drawText(time, x - t.px(3), clockR.top + pad + t.px(80), clockBig);
        Draw.text(c, dateFmt.format(now), x, clockR.bottom - pad - t.px(4), clockR.width() - 2 * pad, clockDate);
    }

    private void drawStats(Canvas c) {
        cardOutline(c, statsR);
        float pad = t.px(20);
        float x = statsR.left + pad, right = statsR.right - pad;
        c.drawText("Reading", x, statsR.top + pad + t.px(14), caption);
        if (stats != null && stats.streakDays > 1) {
            Draw.textRight(c, stats.streakDays + "-day streak", right, statsR.top + pad + t.px(14), meta);
        }
        long today = stats != null ? stats.todaySeconds : 0;
        String num = formatMinutesNumber(today);
        String unit = formatMinutesUnit(today) + " today";
        float base = statsR.top + pad + t.px(62);
        float nw = Draw.text(c, num, x - t.px(2), base, right - x, statNum);
        Draw.text(c, unit, x + nw + t.px(6), base, right - x - nw, statUnit);

        float chartTop = base + t.px(14);
        float labelBase = statsR.bottom - pad + t.px(2);
        float chartBottom = labelBase - t.px(16);
        float chartH = chartBottom - chartTop;
        long max = 15 * 60;
        if (stats != null) for (long d : stats.days) max = Math.max(max, d);
        float slot = (right - x) / 7f;
        float bw = Math.min(t.px(16), slot * 0.55f);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -6);
        SimpleDateFormat dn = new SimpleDateFormat("EEEEE", Locale.getDefault());
        for (int i = 0; i < 7; i++) {
            float cx = x + slot * i + slot / 2f;
            long v = stats != null ? stats.days[i] : 0;
            float bh = Math.max(t.px(3), chartH * (v / (float) max));
            Draw.roundRect(c, cx - bw / 2, chartBottom - bh, cx + bw / 2, chartBottom, t.px(3),
                    i == 6 ? bar : (v > 0 ? barDim : track));
            String d = dn.format(cal.getTime());
            float lw = statSmall.measureText(d);
            c.drawText(d, cx - lw / 2, labelBase, i == 6 ? meta : statSmall);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private void drawShelf(Canvas c) {
        cardOutline(c, shelfR);
        float pad = t.px(20);
        c.drawText("Recently read", shelfR.left + pad, shelfR.top + pad + t.px(14), caption);
        if (recent.isEmpty()) {
            Draw.text(c, "Books you open in KOReader will line up here.", shelfR.left + pad,
                    shelfR.top + t.px(80), shelfR.width() - 2 * pad, meta);
            return;
        }
        for (int i = 0; i < shelfCoverR.size() && i < recent.size(); i++) {
            RectF r = shelfCoverR.get(i);
            Book b = recent.get(i);
            drawCover(c, r, b, i < recentCovers.size() ? recentCovers.get(i) : null, t.px(5));
            float th = Draw.wrapped(c, b.title, r.left, r.bottom + t.px(7), Math.round(r.width() + t.px(10)),
                    2, 0.95f, shelfTitle);
            String sub = b.finished ? "Finished" : (b.progress >= 0 ? Math.round(b.progress * 100) + "%" : "New");
            Draw.text(c, sub, r.left, r.bottom + t.px(7) + th + t.px(14), r.width(), shelfMeta);
        }
    }

    private void drawDock(Canvas c) {
        float rad = t.px(28);
        Draw.roundRect(c, dockR.left, dockR.top, dockR.right, dockR.bottom, rad, dockFill);
        int size = t.pxi(60);
        for (int i = 0; i < dockIconR.size() && i < dock.size(); i++) {
            RectF r = dockIconR.get(i);
            Bitmap b = dock.get(i).icon(getContext(), size);
            c.drawBitmap(b, r.left, r.top, null);
        }
        if (!dockIconR.isEmpty()) {
            float dx = libraryIconR.left - t.px(18);
            c.drawRect(dx, dockR.top + t.px(18), dx + t.px(1.5f), dockR.bottom - t.px(18), sep);
        }
        RectF l = libraryIconR;
        Draw.roundRect(c, l.left, l.top, l.right, l.bottom, t.px(14), paper);
        Draw.roundRect(c, l.left, l.top, l.right, l.bottom, t.px(14), coverEdge);
        Draw.grid(c, l.centerX(), l.centerY(), t.px(28), ink);
    }

    private void drawCover(Canvas c, RectF r, Book b, Bitmap cover, float radius) {
        if (b == null) {
            Draw.roundRect(c, r.left, r.top, r.right, r.bottom, radius, t.fill(Theme.FILL));
            Draw.grid(c, r.centerX(), r.centerY(), r.width() * 0.3f, t.fill(Theme.SEPARATOR));
            return;
        }
        if (cover != null) {
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            android.graphics.Rect src = new android.graphics.Rect(0, 0, cover.getWidth(), cover.getHeight());
            c.drawBitmap(cover, src, r, new Paint(Paint.FILTER_BITMAP_FLAG));
            c.restore();
        } else {
            Draw.roundRect(c, r.left, r.top, r.right, r.bottom, radius, t.fill(Theme.FILL));
            float p = r.width() * 0.12f;
            Paint pt = new Paint(placeholderTitle);
            pt.setTextSize(Math.max(t.px(9), r.width() * 0.12f));
            Draw.wrapped(c, b.title, r.left + p, r.top + p * 1.4f, Math.round(r.width() - 2 * p), 5, 1f, pt);
            c.drawRect(r.left + p, r.bottom - p * 1.6f, r.left + p + r.width() * 0.25f,
                    r.bottom - p * 1.6f + t.px(1.5f), t.fill(Theme.TERTIARY));
        }
        Draw.roundRect(c, r.left, r.top, r.right, r.bottom, radius, coverEdge);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static String formatMinutesNumber(long s) {
        long min = s / 60;
        if (min < 60) return String.valueOf(min);
        long h = min / 60, m = min % 60;
        return h + ":" + (m < 10 ? "0" + m : String.valueOf(m));
    }

    private static String formatMinutesUnit(long s) {
        return s / 60 < 60 ? "min" : "h";
    }
}
