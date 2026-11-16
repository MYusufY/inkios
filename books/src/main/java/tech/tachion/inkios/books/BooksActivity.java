package tech.tachion.inkios.books;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import tech.tachion.inkios.data.Epub;
import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.net.Net;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.Grey;
import tech.tachion.inkios.ui.InkField;
import tech.tachion.inkios.ui.InkMenu;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class BooksActivity extends Activity {
    private static final String TAG = "InkiosBooks";
    static final int TAB_TR = 0, TAB_CLASSICS = 1, TAB_ARCHIVE = 2, TAB_CATALOGS = 3, TAB_MINE = 4;
    static final String[] TABS = {"Türkçe", "Classics", "Archive", "Catalogs", "My books"};
    static final String[] PLACEHOLDER = {"Search Turkish books", "Search classics", "Search the Internet Archive",
            "Search this catalog", ""};
    static final String[] TR_CHIPS = {"Ömer Seyfettin", "Sabahattin Ali", "Halit Ziya", "Namık Kemal", "Mehmet Akif",
            "Ahmet Mithat", "Hüseyin Rahmi", "Sait Faik", "Yunus Emre", "Nasreddin Hoca", "Ziya Gökalp",
            "Tevfik Fikret", "Reşat Nuri", "Refik Halit", "Dede Korkut", "Evliya Çelebi"};
    static final String[] GENRE_CHIPS = {"Fiction", "Mystery", "Science fiction", "Adventure", "Philosophy", "Poetry",
            "History", "Children", "Science", "Drama", "Humor", "Travel"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final ExecutorService covers = Executors.newFixedThreadPool(2);
    private FrameLayout host;
    private BooksView view;
    private InkField field;
    private InkMenu menu;

    int tab = TAB_TR;
    String query = "";
    final List<Item> results = new ArrayList<>();
    int sourcePage;
    boolean hasMore, loading, fromCache;
    String error;
    Item detail;
    final ArrayDeque<String> feeds = new ArrayDeque<>();
    final ArrayDeque<String> feedTitles = new ArrayDeque<>();
    String feedNext, feedSearch;
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        Net.init(this);
        host = new FrameLayout(this);
        view = new BooksView(this);
        host.addView(view);
        setContentView(host);
        field = new InkField(this, host);
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tab == TAB_MINE && detail == null) reload();
        Eink.fullRefreshSoon(view, 80);
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        covers.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (field.isShown()) { field.hide(); return true; }
            if (menu != null && menu.isShowing()) { menu.dismiss(); return true; }
            if (detail != null) { closeDetail(); return true; }
            if (tab == TAB_CATALOGS && !feeds.isEmpty()) { popFeed(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    void selectTab(int t) {
        if (t == tab) return;
        tab = t;
        query = "";
        feeds.clear();
        feedTitles.clear();
        view.chipStart = 0;
        reload();
    }

    private boolean cacheable() { return query.isEmpty() && tab <= TAB_ARCHIVE; }

    private File cacheFile() { return new File(getCacheDir(), "list-" + tab + ".json"); }

    void reload() {
        generation++;
        results.clear();
        sourcePage = 0;
        hasMore = true;
        loading = false;
        fromCache = false;
        error = null;
        feedNext = null;
        view.page = 0;
        if (tab == TAB_MINE) {
            hasMore = false;
            results.addAll(Library.local());
            view.changed(true);
            return;
        }
        if (tab == TAB_CATALOGS && feeds.isEmpty()) {
            hasMore = false;
            for (Library.Catalog k : Library.catalogs(this)) {
                Item it = new Item();
                it.title = k.name;
                it.source = k.url;
                it.browseUrl = k.url;
                it.kind = Sources.KIND_OPDS;
                results.add(it);
            }
            view.changed(true);
            return;
        }
        if (cacheable()) {
            List<Item> cached = readCache();
            if (!cached.isEmpty()) {
                results.addAll(cached);
                markDownloaded(results);
                fromCache = true;
                view.changed(true);
            }
        }
        fetchMore();
    }

    void fetchMore() {
        if (loading || !hasMore) return;
        loading = true;
        error = null;
        if (results.isEmpty()) view.changed(true);
        final int gen = generation, page = fromCache ? 0 : sourcePage, t = tab;
        final String q = query;
        final String feedUrl = t == TAB_CATALOGS ? (feedNext != null ? feedNext : searchOrFeed()) : null;
        net.execute(() -> {
            List<Item> got = new ArrayList<>();
            String err = null, next = null, search = null;
            boolean more = true;
            try {
                switch (t) {
                    case TAB_TR: {
                        Future<List<Item>> wf = covers.submit(() -> Sources.wikisource(q, page, 4));
                        List<Item> a = Sources.archive(q, true, page, 8);
                        List<Item> w = new ArrayList<>();
                        try {
                            w = wf.get();
                        } catch (Exception e) {
                            Log.w(TAG, "Vikikaynak", e);
                        }
                        int x = 0, y = 0;
                        while (x < a.size() || y < w.size()) {
                            if (x < a.size()) got.add(a.get(x++));
                            if (x < a.size()) got.add(a.get(x++));
                            if (y < w.size()) got.add(w.get(y++));
                        }
                        more = a.size() == 8 || w.size() == 4;
                        break;
                    }
                    case TAB_CLASSICS:
                        got = Sources.gutenberg(q, page);
                        more = !got.isEmpty();
                        break;
                    case TAB_ARCHIVE:
                        got = Sources.archive(q, false, page, 10);
                        more = got.size() == 10;
                        break;
                    default: {
                        Sources.Feed f = Sources.opds(feedUrl);
                        got = f.items;
                        next = f.next;
                        search = f.searchTemplate;
                        more = next != null;
                    }
                }
                markDownloaded(got);
            } catch (Exception e) {
                err = friendly(e);
            }
            final List<Item> fGot = got;
            final String fErr = err, fNext = next, fSearch = search;
            final boolean fMore = more;
            main.post(() -> {
                if (gen != generation) return;
                loading = false;
                if (fErr != null) {
                    if (!fromCache) error = fErr;
                    hasMore = false;
                    fromCache = false;
                    view.changed(results.isEmpty());
                    return;
                }
                boolean wasCache = fromCache;
                fromCache = false;
                if (wasCache) {
                    boolean same = sameList(results, fGot);
                    writeCache(fGot);
                    sourcePage = 1;
                    hasMore = fMore && !fGot.isEmpty();
                    if (!same) {
                        results.clear();
                        results.addAll(fGot);
                        view.changed(true);
                    } else {
                        view.changed(false);
                    }
                    return;
                }
                if (page == 0 && q.isEmpty() && t <= TAB_ARCHIVE) writeCache(fGot);
                results.addAll(fGot);
                sourcePage++;
                hasMore = fMore && !fGot.isEmpty();
                if (t == TAB_CATALOGS) {
                    feedNext = fNext;
                    if (fSearch != null) feedSearch = fSearch;
                }
                view.changed(true);
            });
        });
    }

    private static void markDownloaded(List<Item> items) {
        for (Item it : items) {
            if (it.isFolder()) continue;
            File f = Library.fileFor(it);
            if (f.exists()) it.file = f;
        }
    }

    private static boolean sameList(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!String.valueOf(a.get(i).title).equals(String.valueOf(b.get(i).title))
                    || !String.valueOf(a.get(i).id).equals(String.valueOf(b.get(i).id))) return false;
        }
        return true;
    }

    private List<Item> readCache() {
        List<Item> out = new ArrayList<>();
        try (InputStream in = new java.io.FileInputStream(cacheFile())) {
            JSONArray a = new JSONArray(new String(readAll(in), "UTF-8"));
            for (int i = 0; i < a.length(); i++) out.add(Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    private void writeCache(List<Item> items) {
        try (FileOutputStream out = new FileOutputStream(cacheFile())) {
            JSONArray a = new JSONArray();
            for (Item it : items) a.put(it.toJson());
            out.write(a.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private String searchOrFeed() {
        if (!query.isEmpty() && feedSearch != null) return feedSearch.replace("{searchTerms}", Net.enc(query));
        return feeds.peek();
    }

    static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "No internet connection. Check Wi-Fi and try again.";
        if (e instanceof javax.net.ssl.SSLException) return "Secure connection failed (" + m + ").";
        if (e instanceof java.net.SocketTimeoutException) return "The server took too long to answer. Try again.";
        return m;
    }

    void openFeed(Item folder) {
        feeds.push(folder.browseUrl);
        feedTitles.push(folder.title == null ? "Catalog" : folder.title);
        feedSearch = null;
        query = "";
        reload();
    }

    void popFeed() {
        feeds.pop();
        feedTitles.pop();
        query = "";
        reload();
    }

    void runSearch(String q) {
        query = q.trim();
        reload();
    }

    void primaryAction(Item it) {
        if (it.isFolder()) openFeed(it);
        else if (it.file != null) openBook(it);
        else if (it.progress < 0) download(it);
    }

    void tapRow(Item it) {
        if (it.isFolder()) openFeed(it);
        else if (tab == TAB_MINE) openBook(it);
        else openDetail(it);
    }

    void openDetail(final Item it) {
        detail = it;
        view.changed(true);
        if (it.detailsLoaded) return;
        net.execute(() -> {
            try {
                Sources.details(it);
            } catch (Exception e) {
                Log.w(TAG, "details", e);
            }
            main.post(() -> { if (detail == it) view.changed(true); });
        });
    }

    void closeDetail() {
        detail = null;
        view.changed(true);
    }

    void download(final Item it) {
        it.progress = 0;
        it.error = null;
        view.itemChanged(it);
        net.execute(() -> {
            try {
                final File f = Library.download(it, pct -> main.post(() -> {
                    it.progress = pct;
                    view.itemChanged(it);
                }));
                main.post(() -> {
                    it.progress = -1;
                    it.file = f;
                    view.itemChanged(it);
                });
            } catch (Exception e) {
                final String msg = friendly(e);
                main.post(() -> {
                    it.progress = -1;
                    it.error = msg;
                    view.itemChanged(it);
                });
            }
        });
    }

    void openBook(Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        String mime = "epub".equals(it.format) ? "application/epub+zip"
                : "pdf".equals(it.format) ? "application/pdf" : "*/*";
        i.setDataAndType(Uri.fromFile(it.file), mime);
        i.setPackage("org.koreader.launcher");
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            i.setPackage(null);
            try { startActivity(i); } catch (ActivityNotFoundException ignored) {}
        }
    }

    void rowMenu(final Item it, float x, float y) {
        final List<InkMenu.Item> l = new ArrayList<>();
        if (tab == TAB_CATALOGS && feeds.isEmpty()) {
            l.add(new InkMenu.Item("Remove catalog", () -> {
                for (Library.Catalog k : Library.catalogs(this)) {
                    if (k.url.equals(it.browseUrl)) Library.removeCatalog(this, k);
                }
                reload();
            }));
        } else if (it.file != null) {
            l.add(new InkMenu.Item("Open", () -> openBook(it)));
            l.add(new InkMenu.Item("Delete from device", () -> {
                it.file.delete();
                it.file = null;
                if (tab == TAB_MINE) reload(); else view.itemChanged(it);
            }));
        } else if (!it.isFolder()) {
            l.add(new InkMenu.Item("Details", () -> openDetail(it)));
            l.add(new InkMenu.Item("Download", () -> download(it)));
        }
        if (l.isEmpty()) return;
        menu = InkMenu.show(host, it.title, x, y, () -> l, null);
    }

    void addCatalog(RectF where) {
        field.show(view, where, "", "Catalog address (OPDS), e.g. https://…", true, text -> {
            String u = text.trim();
            if (u.isEmpty()) return;
            if (!u.startsWith("http")) u = "http://" + u;
            String name = Uri.parse(u).getHost();
            Library.addCatalog(this, name == null ? u : name, u);
            reload();
        });
    }

    private final Map<String, Bitmap> coverCache = new HashMap<>();
    private final Set<String> coverPending = new HashSet<>();

    Bitmap cover(final Item it, final int w, final int h) {
        final String src = it.coverUrl != null ? it.coverUrl
                : (it.file != null && "epub".equals(it.format) ? "file:" + it.file.getPath() : null);
        if (src == null) return null;
        final String key = src + "@" + w;
        Bitmap b = coverCache.get(key);
        if (b != null || coverPending.contains(key)) return b;
        coverPending.add(key);
        covers.execute(() -> {
            Bitmap got = null;
            File dir = new File(getCacheDir(), "covers");
            dir.mkdirs();
            File disk = new File(dir, Integer.toHexString(key.hashCode()) + ".png");
            try {
                if (disk.exists()) got = BitmapFactory.decodeFile(disk.getPath());
                if (got == null) {
                    byte[] raw;
                    if (src.startsWith("file:")) {
                        raw = Epub.coverBytes(it.file);
                    } else {
                        HttpURLConnection c = Net.open(src);
                        try (InputStream in = c.getInputStream()) {
                            raw = readAll(in);
                        } finally {
                            c.disconnect();
                        }
                    }
                    if (raw != null) got = Grey.decodeCover(raw, w, h);
                    if (got != null) {
                        try (FileOutputStream out = new FileOutputStream(disk)) {
                            got.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    }
                }
            } catch (Exception ignored) {}
            final Bitmap fb = got;
            main.post(() -> {
                if (fb == null) return;
                if (coverCache.size() > 80) coverCache.clear();
                coverCache.put(key, fb);
                view.itemChanged(it);
            });
        });
        return null;
    }

    static String sizeText(long bytes) {
        if (bytes < 0) return null;
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    final class BooksView extends TapView {
        int page, chipStart;
        private int rows = 4;
        private final Paint largeTitle, back, searchHint, searchText, segText, segTextSel, chip, chipSel, section,
                title, author, src, pill, pillInv, msg, footer, footerDim, dTitle, dAuthor, dBody;
        private final Paint fill, ink, edge, rowRule, coverEdge;
        private final RectF searchR = new RectF(), clearR = new RectF(), segR = new RectF(), listR = new RectF(),
                prevR = new RectF(), nextR = new RectF(), backR = new RectF(), addR = new RectF(), chipsR = new RectF(),
                dCoverR = new RectF(), dPillR = new RectF();
        private final Map<Item, RectF> rowRects = new HashMap<>();
        private final List<RectF> chipRects = new ArrayList<>();
        private final List<String> chipLabels = new ArrayList<>();

        BooksView(Context c) {
            super(c);
            largeTitle = t.textPaint(t.displayBold, 36, Theme.INK);
            back = t.textPaint(t.textSemibold, 17, Theme.INK);
            searchHint = t.textPaint(t.textMedium, 17, Theme.TERTIARY);
            searchText = t.textPaint(t.textMedium, 17, Theme.INK);
            segText = t.textPaint(t.textSemibold, 14, Theme.INK);
            segTextSel = t.textPaint(t.textSemibold, 14, Theme.PAPER);
            chip = t.textPaint(t.textSemibold, 14, Theme.INK);
            chipSel = t.textPaint(t.textSemibold, 14, Theme.PAPER);
            section = t.textPaint(t.displaySemibold, 17, Theme.SECONDARY);
            title = t.textPaint(t.displaySemibold, 18, Theme.INK);
            author = t.textPaint(t.textMedium, 14, Theme.SECONDARY);
            src = t.textPaint(t.textMedium, 12, Theme.TERTIARY);
            pill = t.textPaint(t.textSemibold, 15, Theme.INK);
            pillInv = t.textPaint(t.textSemibold, 15, Theme.PAPER);
            msg = t.textPaint(t.textMedium, 16, Theme.SECONDARY);
            footer = t.textPaint(t.textSemibold, 16, Theme.INK);
            footerDim = t.textPaint(t.textSemibold, 16, Theme.SEPARATOR);
            dTitle = t.textPaint(t.displayBold, 26, Theme.INK);
            dAuthor = t.textPaint(t.textMedium, 17, Theme.SECONDARY);
            dBody = t.textPaint(t.text, 16, Theme.INK);
            fill = t.fill(Theme.FILL);
            ink = t.fill(Theme.INK);
            edge = t.stroke(Theme.INK, 1.5f);
            rowRule = t.fill(Theme.SEPARATOR);
            coverEdge = t.stroke(Theme.SEPARATOR, 1f);
        }

        private float rowH() { return t.px(100); }

        private boolean atCatalogList() { return tab == TAB_CATALOGS && feeds.isEmpty(); }

        private boolean inFeed() { return tab == TAB_CATALOGS && !feeds.isEmpty(); }

        private boolean hasSearch() { return tab != TAB_MINE && !atCatalogList(); }

        private String[] chips() {
            return tab == TAB_TR ? TR_CHIPS : (tab == TAB_CLASSICS || tab == TAB_ARCHIVE) ? GENRE_CHIPS : null;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            rebuild();
        }

        void changed(boolean whole) {
            if (detail == null) {
                int first = page * rows;
                if (first + rows > results.size() && hasMore && !loading && error == null) fetchMore();
            }
            rebuild();
            if (getWidth() == 0) return;
            if (whole) Eink.refresh(this, Eink.Mode.CLEAN);
            else invalidate();
        }

        void itemChanged(Item it) {
            if (detail != null) {
                if (detail == it) {
                    refreshRect(dCoverR, Eink.Mode.CLEAN);
                    refreshRect(new RectF(dPillR.left - t.px(4), dPillR.top - t.px(4), getWidth(), dPillR.bottom + t.px(34)),
                            Eink.Mode.TEXT);
                }
                return;
            }
            RectF r = rowRects.get(it);
            if (r != null) refreshRect(r, Eink.Mode.CLEAN);
        }

        private void rebuild() {
            targets.clear();
            rowRects.clear();
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float m = t.px(22);
            backR.set(m - t.px(8), t.px(26), m + t.px(120), t.px(74));
            if (detail != null) {
                target(backR.left, backR.top, backR.right, backR.bottom, t.px(12), BooksActivity.this::closeDetail);
                dCoverR.set(m, t.px(96), m + t.px(150), t.px(96) + t.px(220));
                dPillR.set(dCoverR.right + t.px(22), dCoverR.bottom - t.px(48), dCoverR.right + t.px(22) + t.px(190),
                        dCoverR.bottom);
                final Item it = detail;
                target(dPillR.left, dPillR.top, dPillR.right, dPillR.bottom, dPillR.height() / 2, () -> primaryAction(it))
                        .highlight = false;
                return;
            }

            addR.set(w - m - t.px(90), t.px(26), w - m + t.px(8), t.px(74));
            searchR.set(m, t.px(84), w - m, t.px(124));
            clearR.set(searchR.right - t.px(44), searchR.top, searchR.right, searchR.bottom);
            segR.set(m, t.px(136), w - m, t.px(172));
            if (inFeed()) target(backR.left, backR.top, backR.right, backR.bottom, t.px(12), BooksActivity.this::popFeed);
            if (atCatalogList()) {
                target(addR.left, addR.top, addR.right, addR.bottom, t.px(12), () -> addCatalog(searchR));
            } else if (hasSearch()) {
                target(searchR.left, searchR.top, searchR.right, searchR.bottom, t.px(12),
                        () -> field.show(this, searchR, query, PLACEHOLDER[tab], false, BooksActivity.this::runSearch));
                if (!query.isEmpty()) {
                    target(clearR.left, clearR.top, clearR.right, clearR.bottom, t.px(12), () -> runSearch(""))
                            .highlight = false;
                }
            }
            float sw = segR.width() / TABS.length;
            for (int i = 0; i < TABS.length; i++) {
                final int tb = i;
                target(segR.left + i * sw, segR.top, segR.left + (i + 1) * sw, segR.bottom, t.px(10), () -> selectTab(tb))
                        .highlight = false;
            }

            chipRects.clear();
            chipLabels.clear();
            String[] all = inFeed() ? null : chips();
            float top = segR.bottom + t.px(12);
            if (all != null) {
                chipsR.set(m, top, w - m, top + t.px(34));
                float moreW = chip.measureText("More") + t.px(28);
                float x = chipsR.left, gap = t.px(8);
                int i = chipStart % all.length, shown = 0;
                while (shown < all.length) {
                    float cw = chip.measureText(all[i]) + t.px(26);
                    if (x + cw > chipsR.right - moreW - gap) break;
                    final String label = all[i];
                    chipRects.add(new RectF(x, chipsR.top, x + cw, chipsR.bottom));
                    chipLabels.add(label);
                    target(x, chipsR.top - t.px(4), x + cw, chipsR.bottom + t.px(4), t.px(17),
                            () -> runSearch(label.equalsIgnoreCase(query) ? "" : label)).highlight = false;
                    x += cw + gap;
                    i = (i + 1) % all.length;
                    shown++;
                }
                final int next = i;
                chipRects.add(new RectF(chipsR.right - moreW, chipsR.top, chipsR.right, chipsR.bottom));
                chipLabels.add("More");
                target(chipsR.right - moreW, chipsR.top - t.px(4), chipsR.right, chipsR.bottom + t.px(4), t.px(17), () -> {
                    chipStart = next;
                    rebuild();
                    refreshRect(chipsR, Eink.Mode.TEXT);
                });
                top = chipsR.bottom + t.px(12);
            }
            listR.set(m, top + t.px(30), w - m, h - t.px(56));
            rows = Math.max(1, (int) (listR.height() / rowH()));

            int first = page * rows;
            for (int i = 0; i < rows && first + i < results.size(); i++) {
                final Item it = results.get(first + i);
                final RectF r = new RectF(listR.left, listR.top + i * rowH(), listR.right, listR.top + (i + 1) * rowH());
                rowRects.put(it, r);
                Target tg = target(r.left - t.px(8), r.top, r.right + t.px(8), r.bottom - t.px(2), t.px(14), () -> tapRow(it));
                tg.onLongPress = () -> rowMenu(it, r.right, r.top + t.px(20));
                if (!it.isFolder()) {
                    RectF p = pillRect(r);
                    target(p.left - t.px(10), p.top - t.px(14), p.right + t.px(8), p.bottom + t.px(14), p.height() / 2,
                            () -> primaryAction(it)).highlight = false;
                }
            }
            if (error != null && results.size() <= first) {
                target(listR.left, listR.top - t.px(10), listR.right, listR.top + t.px(120), t.px(12), BooksActivity.this::reload);
            }
            prevR.set(0, h - t.px(54), w / 3f, h);
            nextR.set(w * 2 / 3f, h - t.px(54), w, h);
            if (page > 0) target(prevR.left, prevR.top, prevR.right, prevR.bottom, 0, () -> flip(-1)).highlight = false;
            if (canNext()) target(nextR.left, nextR.top, nextR.right, nextR.bottom, 0, () -> flip(1)).highlight = false;
        }

        private RectF pillRect(RectF row) {
            float pw = t.px(80), ph = t.px(34);
            return new RectF(row.right - pw, row.centerY() - ph / 2, row.right, row.centerY() + ph / 2);
        }

        private boolean canNext() {
            return (page + 1) * rows < results.size() || hasMore;
        }

        private void flip(int d) {
            if (d > 0 && !canNext()) return;
            int np = Math.max(0, page + d);
            if (np == page) return;
            page = np;
            changed(false);
            refreshRect(new RectF(0, listR.top - t.px(34), getWidth(), getHeight()), Eink.Mode.CLEAN);
        }

        @Override
        protected void onSwipe(int direction) {
            if (detail != null) {
                if (direction > 0) closeDetail();
                return;
            }
            flip(direction < 0 ? 1 : -1);
        }

        @Override
        protected void drawContent(Canvas c) {
            if (detail != null) {
                drawDetail(c, detail);
                return;
            }
            int w = getWidth(), h = getHeight();
            float m = t.px(22);

            if (inFeed()) {
                Draw.chevron(c, backR.left + t.px(14), backR.centerY(), t.px(18), -1, t.stroke(Theme.INK, 2.4f));
                c.drawText("Back", backR.left + t.px(26), backR.centerY() + t.px(6), back);
                Paint tp = t.textPaint(t.displayBold, 24, Theme.INK);
                Draw.text(c, feedTitles.peek(), backR.right + t.px(4), backR.centerY() + t.px(8), w - backR.right - m, tp);
            } else {
                c.drawText("Books", m, t.px(66), largeTitle);
            }
            if (atCatalogList()) Draw.textRight(c, "+ Add", w - m, t.px(60), back);

            if (hasSearch()) {
                Draw.roundRect(c, searchR.left, searchR.top, searchR.right, searchR.bottom, t.px(12), fill);
                float cy = searchR.centerY();
                Paint lens = t.stroke(Theme.SECONDARY, 2f);
                float lx = searchR.left + t.px(20);
                c.drawCircle(lx, cy - t.px(1.5f), t.px(6.5f), lens);
                c.drawLine(lx + t.px(4.6f), cy + t.px(3), lx + t.px(9), cy + t.px(7.5f), lens);
                float tx = searchR.left + t.px(38);
                if (query.isEmpty()) {
                    c.drawText(PLACEHOLDER[tab], tx, cy + t.px(6), searchHint);
                } else {
                    Draw.text(c, query, tx, cy + t.px(6), clearR.left - tx, searchText);
                    Paint x = t.stroke(Theme.SECONDARY, 2f);
                    float xc = clearR.centerX(), s = t.px(6);
                    c.drawLine(xc - s, cy - s, xc + s, cy + s, x);
                    c.drawLine(xc - s, cy + s, xc + s, cy - s, x);
                }
            } else if (tab == TAB_MINE) {
                String n = results.size() == 1 ? "1 book on this device" : results.size() + " books on this device";
                c.drawText(n, m, searchR.centerY() + t.px(6), msg);
            } else {
                c.drawText("Your own libraries and OPDS catalogs", m, searchR.centerY() + t.px(6), msg);
            }

            float sw = segR.width() / TABS.length;
            Draw.roundRect(c, segR.left, segR.top, segR.right, segR.bottom, t.px(10), fill);
            for (int i = 0; i < TABS.length; i++) {
                float l = segR.left + i * sw;
                boolean sel = i == tab;
                if (sel) Draw.roundRect(c, l + t.px(2), segR.top + t.px(2), l + sw - t.px(2), segR.bottom - t.px(2), t.px(8), ink);
                Paint p = sel ? segTextSel : segText;
                c.drawText(TABS[i], l + (sw - p.measureText(TABS[i])) / 2, segR.centerY() + t.px(5), p);
            }

            for (int i = 0; i < chipRects.size(); i++) {
                RectF r = chipRects.get(i);
                String label = chipLabels.get(i);
                boolean more = i == chipRects.size() - 1;
                boolean sel = !more && label.equalsIgnoreCase(query);
                if (sel) Draw.roundRect(c, r.left, r.top, r.right, r.bottom, r.height() / 2, ink);
                else Draw.roundRect(c, r.left, r.top, r.right, r.bottom, r.height() / 2, more ? fill : edge);
                Paint p = sel ? chipSel : chip;
                c.drawText(label, r.centerX() - p.measureText(label) / 2, r.centerY() + t.px(5), p);
            }

            String label;
            if (atCatalogList()) label = "Your catalogs";
            else if (tab == TAB_MINE) label = "Newest first";
            else if (!query.isEmpty()) label = "Results for “" + query + "”";
            else if (tab == TAB_CATALOGS) label = "Browse";
            else label = tab == TAB_TR ? "Turkish classics" : "Most downloaded";
            Draw.text(c, label, m, listR.top - t.px(12), w - 2 * m, section);

            int first = page * rows;
            for (int i = 0; i < rows && first + i < results.size(); i++) {
                drawRow(c, results.get(first + i), listR.left, listR.top + i * rowH(), listR.right);
            }

            float my = listR.top + t.px(36);
            if (results.size() <= first) {
                if (loading) c.drawText("Loading…", m, my, msg);
                else if (error != null) {
                    float eh = Draw.wrapped(c, error, m, my - t.px(16), Math.round(w - 2 * m), 4, 1.1f, msg);
                    c.drawText("Tap to try again", m, my - t.px(16) + eh + t.px(28), footer);
                } else if (atCatalogList()) c.drawText("Add an OPDS catalog with “+ Add”.", m, my, msg);
                else if (tab == TAB_MINE) c.drawText("Books you download or send with Share appear here.", m, my, msg);
                else c.drawText("No books found.", m, my, msg);
            } else if (loading && !fromCache && first + rows > results.size()) {
                c.drawText("Loading more…", m, listR.top + (results.size() - first) * rowH() + t.px(36), msg);
            }

            if (page > 0 || canNext()) {
                float fy = h - t.px(22);
                c.drawText("‹ Previous", m, fy, page > 0 ? footer : footerDim);
                String pg = "Page " + (page + 1);
                c.drawText(pg, (w - src.measureText(pg)) / 2, fy, src);
                Draw.textRight(c, "Next ›", w - m, fy, canNext() ? footer : footerDim);
            }
        }

        private void drawCover(Canvas c, Item it, RectF cr, float radius) {
            Bitmap b = cover(it, Math.round(cr.width()), Math.round(cr.height()));
            if (b != null) {
                c.drawBitmap(b, null, cr, new Paint(Paint.FILTER_BITMAP_FLAG));
            } else {
                Draw.roundRect(c, cr.left, cr.top, cr.right, cr.bottom, radius, fill);
                Paint ph = t.textPaint(t.displaySemibold, Math.max(8, cr.width() / t.pt * 0.15f), Theme.SECONDARY);
                float pad = cr.width() * 0.1f;
                Draw.wrapped(c, it.title == null ? "" : it.title, cr.left + pad, cr.top + pad * 1.3f,
                        Math.round(cr.width() - 2 * pad), 5, 1f, ph);
            }
            Draw.roundRect(c, cr.left, cr.top, cr.right, cr.bottom, radius, coverEdge);
        }

        private String metaLine(Item it) {
            if (it.error != null) return "Couldn’t download. Tap for details";
            if (tab == TAB_MINE) return it.source;
            String s = it.source == null ? "" : it.source;
            if (!it.isFolder() && it.format != null && !s.equalsIgnoreCase(it.format)) {
                s += (s.isEmpty() ? "" : "  ·  ") + it.format.toUpperCase();
            }
            return s;
        }

        private void drawPill(Canvas c, Item it, RectF pr) {
            float ph = pr.height();
            if (it.progress >= 0) {
                Draw.roundRect(c, pr.left, pr.top, pr.right, pr.bottom, ph / 2, fill);
                Draw.roundRect(c, pr.left, pr.top, pr.left + pr.width() * Math.max(0.14f, it.progress / 100f), pr.bottom,
                        ph / 2, t.fill(Theme.GROUPED));
                String p = it.progress + "%";
                c.drawText(p, pr.centerX() - pill.measureText(p) / 2, pr.centerY() + t.px(5.5f), pill);
            } else if (it.file != null) {
                Draw.roundRect(c, pr.left, pr.top, pr.right, pr.bottom, ph / 2, ink);
                c.drawText("Open", pr.centerX() - pillInv.measureText("Open") / 2, pr.centerY() + t.px(5.5f), pillInv);
            } else {
                Draw.roundRect(c, pr.left, pr.top, pr.right, pr.bottom, ph / 2, edge);
                String g = it.error != null ? "Retry" : "Get";
                c.drawText(g, pr.centerX() - pill.measureText(g) / 2, pr.centerY() + t.px(5.5f), pill);
            }
        }

        private void drawRow(Canvas c, Item it, float l, float top, float r) {
            float coverW = t.px(56), coverH = t.px(82);
            float ct = top + (rowH() - coverH) / 2;
            RectF cr = new RectF(l, ct, l + coverW, ct + coverH);
            boolean folder = it.isFolder();
            if (folder) {
                Draw.roundRect(c, cr.left, cr.top + t.px(13), cr.right, cr.bottom - t.px(13), t.px(8), fill);
                Draw.grid(c, cr.centerX(), cr.centerY(), t.px(22), t.fill(Theme.TERTIARY));
            } else {
                drawCover(c, it, cr, t.px(4));
            }
            RectF pr = pillRect(new RectF(l, top, r, top + rowH()));
            float tx = cr.right + t.px(16), tw = (folder ? r - t.px(26) : pr.left - t.px(14)) - tx;
            float y = top + t.px(12);
            float used = Draw.wrapped(c, it.title == null ? "Untitled" : it.title, tx, y, Math.round(tw), 2, 1f, title);
            y += used + t.px(18);
            if (it.author != null && !it.author.isEmpty()) {
                Draw.text(c, it.author, tx, y, tw, author);
                y += t.px(20);
            }
            String s = metaLine(it);
            if (s != null && !s.isEmpty()) Draw.text(c, s, tx, Math.min(y, top + rowH() - t.px(12)), tw, src);
            if (folder) Draw.chevron(c, r - t.px(10), top + rowH() / 2, t.px(18), 1, t.stroke(Theme.TERTIARY, 2.4f));
            else drawPill(c, it, pr);
            c.drawRect(tx, top + rowH() - Math.max(1, t.px(1)), r, top + rowH(), rowRule);
        }

        private void drawDetail(Canvas c, Item it) {
            int w = getWidth(), h = getHeight();
            float m = t.px(22);
            Draw.chevron(c, backR.left + t.px(14), backR.centerY(), t.px(18), -1, t.stroke(Theme.INK, 2.4f));
            c.drawText("Books", backR.left + t.px(26), backR.centerY() + t.px(6), back);

            drawCover(c, it, dCoverR, t.px(8));
            float tx = dCoverR.right + t.px(22), tw = w - m - tx;
            float y = dCoverR.top;
            y += Draw.wrapped(c, it.title == null ? "Untitled" : it.title, tx, y, Math.round(tw), 3, 1.02f, dTitle);
            y += t.px(24);
            if (it.author != null && !it.author.isEmpty()) {
                Draw.text(c, it.author, tx, y, tw, dAuthor);
                y += t.px(24);
            }
            StringBuilder meta = new StringBuilder(it.source == null ? "" : it.source);
            if (it.format != null) meta.append("  ·  ").append(it.format.toUpperCase());
            String size = sizeText(it.size);
            if (size != null) meta.append("  ·  ").append(size);
            Draw.text(c, meta.toString(), tx, y, tw, author);

            RectF pr = dPillR;
            float ph = pr.height();
            Paint big = t.textPaint(t.textSemibold, 18, Theme.PAPER);
            if (it.progress >= 0) {
                Draw.roundRect(c, pr.left, pr.top, pr.right, pr.bottom, ph / 2, fill);
                Draw.roundRect(c, pr.left, pr.top, pr.left + pr.width() * Math.max(0.14f, it.progress / 100f), pr.bottom,
                        ph / 2, t.fill(Theme.GROUPED));
                big.setColor(Theme.INK);
                String p = "Downloading " + it.progress + "%";
                c.drawText(p, pr.centerX() - big.measureText(p) / 2, pr.centerY() + t.px(6.5f), big);
            } else {
                Draw.roundRect(c, pr.left, pr.top, pr.right, pr.bottom, ph / 2, ink);
                String g = it.file != null ? "Open" : it.error != null ? "Try again" : "Get";
                c.drawText(g, pr.centerX() - big.measureText(g) / 2, pr.centerY() + t.px(6.5f), big);
            }

            float ty = dCoverR.bottom + t.px(44);
            if (it.error != null) {
                ty += Draw.wrapped(c, "Couldn’t download: " + it.error, m, ty - t.px(16), Math.round(w - 2 * m), 3, 1.1f, msg)
                        + t.px(18);
            } else if (it.file != null) {
                Draw.text(c, "Saved in Books on this device.", m, ty, w - 2 * m, msg);
                ty += t.px(34);
            }
            c.drawText("About", m, ty, section);
            ty += t.px(14);
            String d = it.description;
            if (d == null) d = it.detailsLoaded || it.kind == Sources.KIND_OPDS ? "No description available." : "Loading…";
            int lines = Math.max(2, (int) ((h - ty - t.px(20)) / (dBody.getTextSize() * 1.42f)));
            Draw.wrapped(c, d, m, ty, Math.round(w - 2 * m), lines, 1.3f, d == it.description ? dBody : msg);
        }
    }
}
