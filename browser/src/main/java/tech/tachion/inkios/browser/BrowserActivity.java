package tech.tachion.inkios.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.net.Net;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.InkField;
import tech.tachion.inkios.ui.InkMenu;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class BrowserActivity extends Activity implements Fetcher.RedirectListener {
    private static final String TAG = "InkiosBrowser";

    static final String[] ENGINE_NAMES = {"DuckDuckGo", "Vikipedi"};
    static final String[] ENGINE_URLS = {"https://lite.duckduckgo.com/lite/?q=",
            "https://tr.wikipedia.org/w/index.php?search="};
    static final String[] LAYOUT_NAMES = {"Phone", "Tablet", "Desktop"};
    static final int[] LAYOUT_WIDTHS = {480, 720, 0};
    static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra large"};
    static final int[] SIZE_ZOOM = {85, 100, 120, 140};
    static final int[] SIZE_READER_PX = {26, 30, 35, 41};

    private final Handler main = new Handler(Looper.getMainLooper());
    private Theme t;
    private FrameLayout host;
    private Toolbar toolbar;
    private InkWebView web;
    private StartView start;
    private PageButtons pager;
    private View readerMask;
    private Fetcher fetcher;
    private SharedPreferences prefs;
    private InkField field;
    private InkMenu menu;

    int engine, layout, size = 1;
    boolean scripts = true, images = true, contrast = true, swipe = true;
    private boolean needsReload;

    boolean onStart = true, reader, loading, readerPending;
    String readerSource, readerJson;
    String title = "", url = "";
    int progress, pageNow = 1, pageCount = 1;
    long loadStart;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        t = Theme.get(this);
        Net.init(this);
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        engine = Math.min(ENGINE_NAMES.length - 1, prefs.getInt("engine", 0));
        layout = prefs.getInt("layout", 0);
        size = prefs.getInt("size", 1);
        scripts = prefs.getBoolean("scripts", true);
        images = prefs.getBoolean("images", true);
        contrast = prefs.getBoolean("contrast", true);
        swipe = prefs.getBoolean("swipe", true);
        try {
            if (android.net.http.HttpResponseCache.getInstalled() == null) {
                android.net.http.HttpResponseCache.install(new File(getCacheDir(), "http"), 40L * 1024 * 1024);
            }
        } catch (Exception ignored) {}

        host = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        toolbar = new Toolbar(this);
        column.addView(toolbar, new LinearLayout.LayoutParams(-1, t.pxi(Toolbar.HEIGHT_PT)));
        View rule = new View(this);
        rule.setBackgroundColor(Theme.SEPARATOR);
        column.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, t.pxi(1))));

        FrameLayout content = new FrameLayout(this);
        web = new InkWebView(this);
        content.addView(web);
        readerMask = new View(this);
        readerMask.setBackgroundColor(Theme.PAPER);
        readerMask.setVisibility(View.GONE);
        content.addView(readerMask, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        start = new StartView(this);
        content.addView(start);
        pager = new PageButtons(this);
        content.addView(pager);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        host.addView(column);
        setContentView(host);
        field = new InkField(this, host);

        WebSettings s = web.getSettings();
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieSyncManager.createInstance(this);
        CookieManager.getInstance().setAcceptCookie(true);

        fetcher = new Fetcher(getAssets(), s.getUserAgentString(), this);
        applySettings();
        web.addJavascriptInterface(new Bridge(), "InkiosBridge");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                if (p / 25 != progress / 25) {
                    progress = p;
                    toolbar.changed();
                }
            }

            @Override public void onReceivedTitle(WebView view, String tt) {
                if (!reader && tt != null) title = tt;
            }
        });
        web.setDownloadListener((dlUrl, ua, disposition, mime, length) -> download(dlUrl, disposition, mime));

        Uri data = getIntent().getData();
        if (data != null) open(data.toString());
        else showStart();
    }

    private void applySettings() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(scripts || reader);
        s.setLoadsImagesAutomatically(images);
        s.setBlockNetworkImage(!images);
        s.setTextZoom(reader ? 100 : SIZE_ZOOM[size]);
        fetcher.viewportWidth = LAYOUT_WIDTHS[layout];
        fetcher.highContrast = contrast;
        fetcher.scripts = scripts;
    }

    private void save() {
        prefs.edit().putInt("engine", engine).putInt("layout", layout).putInt("size", size)
                .putBoolean("scripts", scripts).putBoolean("images", images).putBoolean("contrast", contrast)
                .putBoolean("swipe", swipe).apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getData() != null) open(intent.getData().toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Eink.fullRefreshSoon(toolbar, 80);
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieSyncManager.getInstance().sync();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (field.isShown()) field.hide();
            else if (menu != null && menu.isShowing()) menu.dismiss();
            else goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    void open(String input) {
        String u = toUrl(input);
        if (u == null) return;
        leaveReader();
        onStart = false;
        start.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        fetcher.mainUrl = u;
        url = u;
        title = "";
        loading = true;
        progress = 0;
        web.loadUrl(u);
        toolbar.changed();
    }

    static String toUrl(String input, int engine) {
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        if (!s.contains(" ") && s.contains(".") && !s.endsWith(".")) return "https://" + s;
        return ENGINE_URLS[engine] + Uri.encode(s);
    }

    private String toUrl(String input) { return toUrl(input, engine); }

    private void leaveReader() {
        if (!reader) return;
        reader = false;
        readerMask.setVisibility(View.GONE);
        web.lineStep = 0;
        applySettings();
    }

    private void expectHistory(int delta) {
        WebBackForwardList l = web.copyBackForwardList();
        int i = l.getCurrentIndex() + delta;
        if (i >= 0 && i < l.getSize()) fetcher.mainUrl = l.getItemAtIndex(i).getUrl();
    }

    void goBack() {
        if (onStart) {
            finish();
        } else if (web.canGoBack()) {
            leaveReader();
            expectHistory(-1);
            web.goBack();
        } else {
            showStart();
        }
    }

    void goForward() {
        if (onStart && web.getUrl() != null) {
            hideStart();
        } else if (web.canGoForward()) {
            leaveReader();
            expectHistory(1);
            web.goForward();
        }
    }

    void reloadOrStop() {
        if (onStart) return;
        if (loading) {
            web.stopLoading();
            loading = false;
            toolbar.changed();
        } else if (reader) {
            open(readerSource);
        } else {
            fetcher.mainUrl = url;
            web.reload();
        }
    }

    void showStart() {
        onStart = true;
        start.setVisibility(View.VISIBLE);
        pager.setVisibility(View.GONE);
        start.reload();
        toolbar.changed();
        Eink.refresh(start, Eink.Mode.CLEAN);
    }

    void hideStart() {
        onStart = false;
        start.setVisibility(View.GONE);
        pager.setVisibility(View.VISIBLE);
        toolbar.changed();
        Eink.refresh(web, Eink.Mode.CLEAN);
    }

    @Override
    public void onMainRedirect(String from, final String to) {
        fetcher.mainUrl = to;
        main.post(() -> {
            url = to;
            toolbar.changed();
        });
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String u) {
            return fetcher.fetch(u);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String u) {
            if (u.startsWith("http://") || u.startsWith("https://")) {
                fetcher.mainUrl = u;
                leaveReader();
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            } catch (Exception ignored) {}
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String u, Bitmap favicon) {
            loadStart = SystemClock.uptimeMillis();
            loading = true;
            progress = 0;
            if (!reader && u != null && u.startsWith("http")) url = u;
            toolbar.changed();
        }

        @Override
        public void onPageFinished(WebView view, String u) {
            if (BuildConfig.DEBUG) Log.d(TAG, "loaded in " + (SystemClock.uptimeMillis() - loadStart) + " ms: " + u);
            loading = false;
            progress = 100;
            if (!reader && u != null && u.startsWith("http")) {
                url = u;
                title = view.getTitle() == null ? "" : view.getTitle();
                History.add(BrowserActivity.this, title, u);
            }
            web.updatePages();
            toolbar.changed();
            if (readerPending) {
                readerPending = false;
                runReader();
            }
            main.postDelayed(() -> Eink.refresh(web, Eink.Mode.CLEAN), 250);
        }
    }

    private void submitForm(final String action, final String body) {
        loading = true;
        progress = 0;
        toolbar.changed();
        final String referer = url, ua = web.getSettings().getUserAgentString();
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(action).openConnection();
                Net.secure(c);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(45000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setRequestProperty("Referer", referer);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    final String next = new URL(new URL(action), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    main.post(() -> open(next));
                    return;
                }
                String charset = "UTF-8", type = c.getContentType();
                if (type != null) {
                    for (String p : type.split(";")) {
                        p = p.trim();
                        if (p.toLowerCase(Locale.US).startsWith("charset=")) charset = p.substring(8).replace("\"", "").trim();
                    }
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] raw = in == null ? new byte[0] : Fetcher.readAll(in);
                if (in != null) in.close();
                String decoded;
                try {
                    decoded = new String(raw, charset);
                } catch (java.io.UnsupportedEncodingException e) {
                    decoded = new String(raw, "UTF-8");
                }
                final String html = fetcher.rewrite(decoded);
                main.post(() -> {
                    leaveReader();
                    fetcher.mainUrl = action;
                    url = action;
                    web.loadDataWithBaseURL(action, html, "text/html", "utf-8", action);
                });
            } catch (final Exception e) {
                main.post(() -> {
                    loading = false;
                    toolbar.changed();
                    Toast.makeText(this, "Couldn’t send the form: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "form-post").start();
    }

    void toggleReader() {
        if (onStart || loading) return;
        if (reader) {
            open(readerSource);
        } else if (!scripts) {
            readerPending = true;
            web.getSettings().setJavaScriptEnabled(true);
            fetcher.mainUrl = url;
            web.reload();
        } else {
            runReader();
        }
    }

    private void runReader() {
        try (InputStream in = getAssets().open("reader.js")) {
            web.evaluateJavascript(new String(Fetcher.readAll(in), "UTF-8"), null);
        } catch (Exception e) {
            Toast.makeText(this, "Reader isn’t available for this page", Toast.LENGTH_SHORT).show();
        }
    }

    final class Bridge {
        @JavascriptInterface
        public void deliver(final String json) {
            main.post(() -> showReader(json));
        }

        @JavascriptInterface
        public void post(final String action, final String body) {
            if (action == null || !(action.startsWith("http://") || action.startsWith("https://"))) return;
            main.post(() -> submitForm(action, body == null ? "" : body));
        }
    }

    private void showReader(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String body = o.optString("html");
            if (o.optInt("words") < 60 || body.length() < 200) {
                Toast.makeText(this, "No article found on this page", Toast.LENGTH_SHORT).show();
                applySettings();
                return;
            }
            if (!reader) readerSource = url;
            readerJson = json;
            reader = true;
            int f = SIZE_READER_PX[size], L = Math.round(f * 1.5f);
            int minutes = Math.max(1, o.optInt("words") / 220);
            String by = o.optString("byline");
            String meta = Fetcher.escape(o.optString("site")) + (by.isEmpty() ? "" : " · " + Fetcher.escape(by))
                    + " · " + minutes + " min read";
            String A = Fetcher.ASSET_ORIGIN + "fonts/";
            String css = "@font-face{font-family:I;src:url(" + A + "Inter-Regular.ttf)}"
                    + "@font-face{font-family:I;font-weight:bold;src:url(" + A + "Inter-SemiBold.ttf)}"
                    + "@font-face{font-family:D;src:url(" + A + "InterDisplay-Bold.ttf)}"
                    + "html{background:#fff}"
                    + "body{font-family:I,sans-serif;color:#000;margin:0 auto;max-width:34em;padding:" + L + "px 56px 100vh;"
                    + "font-size:" + f + "px;line-height:" + L + "px;-webkit-text-size-adjust:none}"
                    + "h1.t{font-family:D,sans-serif;font-size:" + Math.round(L * 1.2f) + "px;line-height:" + 2 * L + "px;margin:0}"
                    + ".m{color:#555;font-size:" + Math.round(f * .8f) + "px;margin:0 0 " + L + "px}"
                    + "h2,h3,h4{font-family:D,sans-serif;font-size:" + Math.round(f * 1.2f) + "px;margin:" + L + "px 0 0}"
                    + "p,ul,ol,blockquote,pre,table,figure{margin:0 0 " + L + "px}li{margin:0}a{color:#000}"
                    + "img{max-width:100%;max-height:70vh;height:auto;display:block;margin:0 auto " + L + "px}"
                    + "blockquote{padding-left:1em;border-left:4px solid #000}"
                    + "pre,code{font-size:.8em;white-space:pre-wrap}figure{margin-left:0;margin-right:0}"
                    + "figcaption{color:#555;font-size:.8em}"
                    + "table{border-collapse:collapse;font-size:.85em}td,th{padding:0 .5em;text-align:left;vertical-align:top}"
                    + "sup,sub{line-height:0;font-size:.7em}hr{display:none}";
            String js = "(function(){var L=" + L + ";function fit(i){i.style.marginBottom='0';var r=i.offsetHeight%L;"
                    + "i.style.marginBottom=((r?L-r:0)+L)+'px';}var m=document.images;for(var k=0;k<m.length;k++){"
                    + "m[k].onload=function(){fit(this)};if(m[k].complete)fit(m[k]);}})();";
            String html = "<!doctype html><html><head><meta charset=utf-8>"
                    + "<meta name=viewport content='width=device-width,initial-scale=1'><style>" + css + "</style></head>"
                    + "<body><h1 class=t>" + Fetcher.escape(o.optString("title")) + "</h1><div class=m>" + meta + "</div>"
                    + body + "<script>" + js + "</script></body></html>";
            applySettings();
            web.lineStep = L;
            int cut = web.getHeight() % L;
            readerMask.getLayoutParams().height = cut;
            readerMask.requestLayout();
            readerMask.setVisibility(cut > 0 ? View.VISIBLE : View.GONE);
            web.loadDataWithBaseURL(readerSource, html, "text/html", "utf-8", readerSource);
            toolbar.changed();
        } catch (Exception e) {
            Toast.makeText(this, "Reader isn’t available for this page", Toast.LENGTH_SHORT).show();
        }
    }

    void page(int dir) {
        int step = web.step(), max = web.maxScroll();
        int target;
        if (web.lineStep > 0) {
            target = (Math.round(web.getScrollY() / (float) step) + dir) * step;
            if (target > max) return;
        } else {
            target = Math.min(max, web.getScrollY() + dir * step);
        }
        target = Math.max(0, target);
        if (target != web.getScrollY()) web.scrollTo(web.getScrollX(), target);
    }

    final class InkWebView extends WebView {
        int lineStep;
        private float downX, downY;
        private boolean dragging;
        private final int slop;
        private final Runnable settle = () -> Eink.refresh(this, Eink.Mode.CLEAN);

        InkWebView(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2;
        }

        int step() {
            int h = getHeight();
            if (lineStep > 0) return Math.max(lineStep, h / lineStep * lineStep);
            return Math.max(1, Math.round(h * 0.9f));
        }

        int maxScroll() { return Math.max(0, computeVerticalScrollRange() - getHeight()); }

        void updatePages() {
            int step = step(), max = maxScroll();
            int count = lineStep > 0 ? Math.max(1, (int) Math.ceil(max / (float) step))
                    : 1 + (int) Math.ceil(max / (float) step);
            int now = Math.min(count, 1 + Math.round(getScrollY() / (float) step));
            if (count != pageCount || now != pageNow) {
                pageCount = count;
                pageNow = now;
                toolbar.changed();
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!swipe || e.getPointerCount() > 1) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    return super.onTouchEvent(e);
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && (Math.abs(e.getY() - downY) > slop || Math.abs(e.getX() - downX) > slop)) {
                        dragging = true;
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                    return dragging || super.onTouchEvent(e);
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (Math.abs(dy) > Math.abs(dx)) page(dy < 0 ? 1 : -1);
                        return true;
                    }
                    return super.onTouchEvent(e);
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return super.onTouchEvent(e);
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        protected void onScrollChanged(int l, int tp, int ol, int ot) {
            super.onScrollChanged(l, tp, ol, ot);
            updatePages();
            main.removeCallbacks(settle);
            main.postDelayed(settle, 180);
        }
    }

    void askAddress(View anchor, RectF r) {
        field.show(anchor, r, onStart ? "" : (reader ? readerSource : url),
                "Search " + ENGINE_NAMES[engine] + " or enter address", true, this::open);
    }

    void showMenu() {
        needsReload = false;
        float top = t.px(Toolbar.HEIGHT_PT) + t.px(4);
        menu = InkMenu.show(host, null, host.getWidth() - t.px(8), top, this::menuItems, () -> {
            if (!needsReload || onStart) return;
            if (reader) {
                if (readerJson != null) showReader(readerJson);
            } else {
                fetcher.mainUrl = url;
                web.reload();
            }
        });
    }

    private List<InkMenu.Item> menuItems() {
        List<InkMenu.Item> l = new ArrayList<>();
        if (!onStart) {
            l.add(new InkMenu.Item("Add to Favorites", () -> {
                Favorites.add(this, title.isEmpty() ? host(url) : title, reader ? readerSource : url);
                Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show();
            }));
        }
        l.add(new InkMenu.Item("Text size", SIZE_NAMES[size], true, () -> {
            size = (size + 1) % SIZE_NAMES.length;
            changed(reader);
        }));
        l.add(new InkMenu.Item("Page layout", LAYOUT_NAMES[layout], true, () -> {
            layout = (layout + 1) % LAYOUT_NAMES.length;
            changed(!reader);
        }));
        l.add(new InkMenu.Item("High contrast", contrast ? "On" : "Off", true, () -> {
            contrast = !contrast;
            changed(!reader);
        }));
        l.add(new InkMenu.Item("Images", images ? "On" : "Off", true, () -> {
            images = !images;
            changed(true);
        }));
        l.add(new InkMenu.Item("Scripts", scripts ? "On" : "Off", true, () -> {
            scripts = !scripts;
            changed(!reader);
        }));
        l.add(new InkMenu.Item("Swipe turns pages", swipe ? "On" : "Off", true, () -> {
            swipe = !swipe;
            changed(false);
        }));
        l.add(new InkMenu.Item("Search with", ENGINE_NAMES[engine], true, () -> {
            engine = (engine + 1) % ENGINE_NAMES.length;
            changed(false);
            start.invalidate();
        }));
        l.add(new InkMenu.Item("Clean the screen", () -> Eink.fullRefresh(host)));
        return l;
    }

    private void changed(boolean reload) {
        save();
        applySettings();
        if (reload) needsReload = true;
    }

    private void download(final String dlUrl, String disposition, String mime) {
        final String name = URLUtil.guessFileName(dlUrl, disposition, mime);
        String low = name.toLowerCase(Locale.US);
        boolean book = low.endsWith(".epub") || low.endsWith(".pdf") || low.endsWith(".mobi") || low.endsWith(".fb2")
                || low.endsWith(".azw3") || low.endsWith(".djvu") || low.endsWith(".cbz") || low.endsWith(".txt");
        final File dir = new File(Environment.getExternalStorageDirectory(), book ? "Books" : "Download");
        dir.mkdirs();
        Toast.makeText(this, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                HttpURLConnection c = Net.open(dlUrl);
                File f = new File(dir, name);
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
                msg = "Saved to " + dir.getName() + ": " + name;
            } catch (Exception e) {
                msg = "Download failed: " + e.getMessage();
            }
            final String m = msg;
            main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }, "download").start();
    }

    static String host(String u) {
        if (u == null) return "";
        String h = Uri.parse(u).getHost();
        if (h == null) return u;
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    final class Toolbar extends TapView {
        static final float HEIGHT_PT = 58f;
        private final Paint addr, addrHint, pages, line, lineDim, ink;
        private final RectF backR = new RectF(), fwdR = new RectF(), homeR = new RectF(), addrR = new RectF(),
                reloadR = new RectF(), readerR = new RectF(), moreR = new RectF();

        Toolbar(Context c) {
            super(c);
            addr = t.textPaint(t.textMedium, 16, Theme.INK);
            addrHint = t.textPaint(t.textMedium, 16, Theme.TERTIARY);
            pages = t.textPaint(t.textMedium, 13, Theme.SECONDARY);
            line = t.stroke(Theme.INK, 2.4f);
            lineDim = t.stroke(Theme.SEPARATOR, 2.4f);
            ink = t.fill(Theme.INK);
        }

        void changed() {
            if (getWidth() > 0) refreshRect(new RectF(0, 0, getWidth(), getHeight()), Eink.Mode.TEXT);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            targets.clear();
            float m = t.px(6), b = t.px(42), cy = h / 2f;
            backR.set(m, cy - b / 2, m + b, cy + b / 2);
            fwdR.set(backR.right, cy - b / 2, backR.right + b, cy + b / 2);
            homeR.set(fwdR.right, cy - b / 2, fwdR.right + b, cy + b / 2);
            moreR.set(w - m - b, cy - b / 2, w - m, cy + b / 2);
            readerR.set(moreR.left - b, cy - b / 2, moreR.left, cy + b / 2);
            reloadR.set(readerR.left - b, cy - b / 2, readerR.left, cy + b / 2);
            addrR.set(homeR.right + t.px(6), cy - t.px(19), reloadR.left - t.px(6), cy + t.px(19));
            target(backR.left, backR.top, backR.right, backR.bottom, t.px(12), BrowserActivity.this::goBack);
            target(fwdR.left, fwdR.top, fwdR.right, fwdR.bottom, t.px(12), BrowserActivity.this::goForward);
            target(homeR.left, homeR.top, homeR.right, homeR.bottom, t.px(12), BrowserActivity.this::showStart);
            target(addrR.left, addrR.top, addrR.right, addrR.bottom, t.px(10), () -> askAddress(this, addrR));
            target(reloadR.left, reloadR.top, reloadR.right, reloadR.bottom, t.px(12), BrowserActivity.this::reloadOrStop);
            target(readerR.left, readerR.top, readerR.right, readerR.bottom, t.px(12), BrowserActivity.this::toggleReader)
                    .highlight = false;
            target(moreR.left, moreR.top, moreR.right, moreR.bottom, t.px(12), BrowserActivity.this::showMenu);
        }

        @Override
        protected void drawContent(Canvas c) {
            float cy = getHeight() / 2f;
            Draw.chevron(c, backR.centerX(), cy, t.px(18), -1, line);
            boolean canFwd = (onStart && web.getUrl() != null) || (!onStart && web.canGoForward());
            Draw.chevron(c, fwdR.centerX(), cy, t.px(18), 1, canFwd ? line : lineDim);
            drawHome(c, homeR, onStart ? lineDim : line);
            if (loading && !onStart) drawStop(c, reloadR, line);
            else drawReload(c, reloadR, onStart ? lineDim : line);

            Draw.roundRect(c, addrR.left, addrR.top, addrR.right, addrR.bottom, t.px(10), t.fill(Theme.FILL));
            float tx = addrR.left + t.px(12), max = addrR.width() - t.px(24);
            if (onStart) {
                c.drawText("Search or address", tx, cy + t.px(5.5f), addrHint);
            } else {
                float counterW = 0;
                if (pageCount > 1 && !loading) {
                    String p = pageNow + " / " + pageCount;
                    counterW = Draw.textRight(c, p, addrR.right - t.px(12), cy + t.px(4.5f), pages) + t.px(10);
                }
                String shown = reader ? "Reader · " + host(readerSource) : host(url);
                float w = Math.min(max - counterW, addr.measureText(shown));
                float left = Math.max(tx, Math.min(addrR.centerX() - w / 2, addrR.right - t.px(12) - counterW - w));
                Draw.text(c, shown, left, cy + t.px(5.5f), max - counterW, addr);
            }

            boolean on = reader;
            if (on) Draw.roundRect(c, readerR.left, readerR.top, readerR.right, readerR.bottom, t.px(12), ink);
            Paint aa = t.textPaint(t.displaySemibold, 20, on ? Theme.PAPER : (onStart ? Theme.SEPARATOR : Theme.INK));
            c.drawText("Aa", readerR.centerX() - aa.measureText("Aa") / 2, cy + t.px(7), aa);
            float d = t.px(2.6f);
            for (int i = -1; i <= 1; i++) c.drawCircle(moreR.centerX() + i * t.px(8), cy, d, ink);

            if (loading && !onStart) {
                float w = getWidth() * Math.max(0.08f, (progress / 25) * 0.25f);
                c.drawRect(0, getHeight() - t.px(3), w, getHeight(), ink);
            }
        }

        private void drawHome(Canvas c, RectF r, Paint p) {
            float cx = r.centerX(), cy = r.centerY(), s = t.px(9);
            Path h = new Path();
            h.moveTo(cx - s * 1.15f, cy - s * 0.05f);
            h.lineTo(cx, cy - s * 1.1f);
            h.lineTo(cx + s * 1.15f, cy - s * 0.05f);
            h.moveTo(cx - s * 0.8f, cy - s * 0.35f);
            h.lineTo(cx - s * 0.8f, cy + s);
            h.lineTo(cx + s * 0.8f, cy + s);
            h.lineTo(cx + s * 0.8f, cy - s * 0.35f);
            c.drawPath(h, p);
        }

        private void drawReload(Canvas c, RectF r, Paint p) {
            float cx = r.centerX(), cy = r.centerY(), s = t.px(9);
            c.drawArc(new RectF(cx - s, cy - s, cx + s, cy + s), -60, 300, false, p);
            Path head = new Path();
            float ax = cx + s * 0.5f, ay = cy - s * 0.87f;
            head.moveTo(ax - s * 0.55f, ay - s * 0.45f);
            head.lineTo(ax, ay);
            head.lineTo(ax - s * 0.6f, ay + s * 0.45f);
            c.drawPath(head, p);
        }

        private void drawStop(Canvas c, RectF r, Paint p) {
            float cx = r.centerX(), cy = r.centerY(), s = t.px(7.5f);
            c.drawLine(cx - s, cy - s, cx + s, cy + s, p);
            c.drawLine(cx - s, cy + s, cx + s, cy - s, p);
        }
    }

    final class PageButtons extends TapView {
        private final RectF upR = new RectF(), downR = new RectF();

        PageButtons(Context c) {
            super(c);
            setBackgroundColor(0x00000000);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            targets.clear();
            float s = t.px(50), m = t.px(14);
            downR.set(w - m - s, h - m - s, w - m, h - m);
            upR.set(downR.left, downR.top - s - t.px(8), downR.right, downR.top - t.px(8));
            target(upR.left, upR.top, upR.right, upR.bottom, s / 2, () -> page(-1));
            target(downR.left, downR.top, downR.right, downR.bottom, s / 2, () -> page(1));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    && !upR.contains(e.getX(), e.getY()) && !downR.contains(e.getX(), e.getY())) {
                return false;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void drawContent(Canvas c) {
            Paint fill = t.fill(Theme.PAPER), edge = t.stroke(Theme.INK, 2f), arrow = t.stroke(Theme.INK, 3f);
            for (int i = 0; i < 2; i++) {
                RectF r = i == 0 ? upR : downR;
                c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill);
                c.drawCircle(r.centerX(), r.centerY(), r.width() / 2 - t.px(1), edge);
                Path p = new Path();
                float s = t.px(9), dir = i == 0 ? -1 : 1;
                p.moveTo(r.centerX() - s, r.centerY() - dir * s * 0.45f);
                p.lineTo(r.centerX(), r.centerY() + dir * s * 0.55f);
                p.lineTo(r.centerX() + s, r.centerY() - dir * s * 0.45f);
                c.drawPath(p, arrow);
            }
        }
    }

    final class StartView extends TapView {
        private static final int COLS = 5;
        private final Paint large, search, section, tileLetter, tileName, rowTitle, rowHost;
        private final RectF searchR = new RectF();
        private List<Favorites.Fav> favs = new ArrayList<>();
        private List<History.Entry> recent = new ArrayList<>();
        private float recentTop;

        StartView(Context c) {
            super(c);
            large = t.textPaint(t.displayBold, 36, Theme.INK);
            search = t.textPaint(t.textMedium, 17, Theme.TERTIARY);
            section = t.textPaint(t.displaySemibold, 17, Theme.SECONDARY);
            tileLetter = t.textPaint(t.displayBold, 26, Theme.INK);
            tileName = t.textPaint(t.textMedium, 13, Theme.INK);
            rowTitle = t.textPaint(t.textMedium, 16, Theme.INK);
            rowHost = t.textPaint(t.textMedium, 13, Theme.TERTIARY);
        }

        void reload() {
            favs = Favorites.list(BrowserActivity.this);
            recent = History.list(BrowserActivity.this);
            rebuild();
            invalidate();
        }

        private RectF tile(int i) {
            float m = t.px(22), gap = t.px(12);
            float w = (getWidth() - 2 * m - gap * (COLS - 1)) / COLS;
            float h = w + t.px(32);
            float l = m + (i % COLS) * (w + gap), top = t.px(190) + (i / COLS) * h;
            return new RectF(l, top, l + w, top + w);
        }

        private RectF row(int i) {
            float top = recentTop + t.px(16) + i * t.px(44);
            return new RectF(t.px(22), top, getWidth() - t.px(22), top + t.px(44));
        }

        private void rebuild() {
            targets.clear();
            if (getWidth() == 0) return;
            float m = t.px(22);
            searchR.set(m, t.px(92), getWidth() - m, t.px(134));
            target(searchR.left, searchR.top, searchR.right, searchR.bottom, t.px(12), () -> askAddress(this, searchR));
            int shown = Math.min(favs.size(), COLS * 2);
            for (int i = 0; i < shown; i++) {
                final Favorites.Fav f = favs.get(i);
                RectF r = tile(i);
                Target tg = target(r.left - 4, r.top - 4, r.right + 4, r.bottom + t.px(28), t.px(14), () -> open(f.url));
                tg.onLongPress = () -> menu = InkMenu.show(host, f.name, r.right + t.px(120), r.bottom + t.px(60), () -> {
                    List<InkMenu.Item> l = new ArrayList<>();
                    l.add(new InkMenu.Item("Remove from Favorites", () -> {
                        Favorites.remove(BrowserActivity.this, f);
                        reload();
                        Eink.refresh(this, Eink.Mode.CLEAN);
                    }));
                    return l;
                }, null);
            }
            int rows = shown == 0 ? 0 : (shown + COLS - 1) / COLS;
            recentTop = (rows == 0 ? t.px(170) : tile((rows - 1) * COLS).bottom + t.px(32)) + t.px(26);
            for (int i = 0; i < recent.size(); i++) {
                RectF r = row(i);
                if (r.bottom > getHeight() - t.px(8)) break;
                final History.Entry e = recent.get(i);
                target(r.left - t.px(8), r.top, r.right + t.px(8), r.bottom, t.px(10), () -> open(e.url));
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            reload();
        }

        @Override
        protected void drawContent(Canvas c) {
            float m = t.px(22);
            c.drawText("Browser", m, t.px(62), large);
            Draw.roundRect(c, searchR.left, searchR.top, searchR.right, searchR.bottom, t.px(12), t.fill(Theme.FILL));
            float cy = searchR.centerY();
            Paint lens = t.stroke(Theme.SECONDARY, 2f);
            float lx = searchR.left + t.px(20);
            c.drawCircle(lx, cy - t.px(1.5f), t.px(6.5f), lens);
            c.drawLine(lx + t.px(4.6f), cy + t.px(3), lx + t.px(9), cy + t.px(7.5f), lens);
            c.drawText("Search " + ENGINE_NAMES[engine] + " or enter address", searchR.left + t.px(38), cy + t.px(6), search);

            int shown = Math.min(favs.size(), COLS * 2);
            if (shown > 0) c.drawText("Favorites", m, t.px(172), section);
            for (int i = 0; i < shown; i++) {
                RectF r = tile(i);
                Favorites.Fav f = favs.get(i);
                Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(14), t.fill(Theme.FILL));
                String letter = f.name.isEmpty() ? "?" : f.name.substring(0, 1).toUpperCase();
                c.drawText(letter, r.centerX() - tileLetter.measureText(letter) / 2, r.centerY() + t.px(9.5f), tileLetter);
                float nw = Math.min(r.width() + t.px(8), tileName.measureText(f.name));
                Draw.text(c, f.name, r.centerX() - nw / 2, r.bottom + t.px(19), r.width() + t.px(8), tileName);
            }

            if (!recent.isEmpty()) c.drawText("Recent", m, recentTop, section);
            Paint rule = t.fill(Theme.SEPARATOR);
            for (int i = 0; i < recent.size(); i++) {
                RectF r = row(i);
                if (r.bottom > getHeight() - t.px(8)) break;
                History.Entry e = recent.get(i);
                String hostName = host(e.url);
                float hw = rowHost.measureText(hostName);
                Draw.textRight(c, hostName, r.right, r.centerY() + t.px(5), rowHost);
                Draw.text(c, e.title.isEmpty() ? hostName : e.title, r.left, r.centerY() + t.px(6),
                        r.width() - hw - t.px(16), rowTitle);
                c.drawRect(r.left, r.bottom - Math.max(1, t.px(1)), r.right, r.bottom, rule);
            }
        }
    }

    static final class History {
        static final class Entry {
            final String title, url;
            Entry(String title, String url) { this.title = title; this.url = url; }
        }

        private static final int MAX = 12;

        static List<Entry> list(Context c) {
            List<Entry> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(c.getSharedPreferences("browser", MODE_PRIVATE).getString("recent", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Entry(o.optString("t"), o.getString("u")));
                }
            } catch (Exception ignored) {}
            return out;
        }

        static void add(Context c, String title, String url) {
            List<Entry> l = list(c);
            for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).url.equals(url)) l.remove(i);
            l.add(0, new Entry(title == null ? "" : title, url));
            JSONArray a = new JSONArray();
            try {
                for (int i = 0; i < l.size() && i < MAX; i++) {
                    a.put(new JSONObject().put("t", l.get(i).title).put("u", l.get(i).url));
                }
            } catch (Exception ignored) {}
            c.getSharedPreferences("browser", MODE_PRIVATE).edit().putString("recent", a.toString()).apply();
        }
    }
}
