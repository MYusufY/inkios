package tech.tachion.inkios.browser;

import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import tech.tachion.inkios.net.Net;

final class Fetcher {
    static final String ASSET_ORIGIN = "https://inkios.local/";

    private final AssetManager assets;
    private final String userAgent;
    private final String pageCss, contrastCss, pageJs;

    volatile int viewportWidth = 480;
    volatile boolean highContrast = true;
    volatile boolean scripts = true;
    volatile String mainUrl;
    interface RedirectListener { void onMainRedirect(String from, String to); }
    private final RedirectListener redirects;

    Fetcher(AssetManager assets, String userAgent, RedirectListener redirects) {
        this.assets = assets;
        this.userAgent = userAgent;
        this.redirects = redirects;
        pageCss = text("page.css");
        contrastCss = text("contrast.css");
        pageJs = text("page.js");
        if (!(java.net.CookieHandler.getDefault() instanceof java.net.CookieManager)) {
            java.net.CookieHandler.setDefault(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL));
        }
    }

    private static final String[] BLOCKED_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.", "pagead2.", "connect.facebook.net",
            "facebook.com/tr", "scorecardresearch.com", "amazon-adsystem.com", "criteo.", "taboola.com",
            "outbrain.com", "hotjar.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "moatads.com", "quantserve.com", "chartbeat.", "newrelic.com", "nr-data.net",
            "segment.io", "segment.com", "mixpanel.com", "branch.io", "adform.net", "smartadserver.com",
            "yandex.ru/metrika", "mc.yandex.", "teads.tv", "sharethrough.com", "bidswitch.net", "3lift.com",
            "cookielaw.org", "onetrust.com", "trustarc.com", "addthis.com", "sharethis.com", "disqus.com/count",
    };

    private static boolean blocked(String url) {
        String u = url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.endsWith(".woff2") || path.endsWith(".woff") || path.endsWith(".ttf") || path.endsWith(".otf")
                || path.endsWith(".eot")) return !u.startsWith(ASSET_ORIGIN);
        if (u.contains("fonts.googleapis.com") || u.contains("fonts.gstatic.com") || u.contains("use.typekit")) return true;
        for (String h : BLOCKED_HOSTS) if (u.contains(h)) return true;
        return false;
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    WebResourceResponse fetch(String url) {
        if (url.startsWith(ASSET_ORIGIN)) return asset(url.substring(ASSET_ORIGIN.length()));
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        if (!url.equals(mainUrl) && blocked(url)) return emptyResponse();
        try {
            boolean isMain = url.equals(mainUrl);
            String current = url;
            HttpURLConnection c = null;
            for (int hop = 0; hop < 8; hop++) {
                c = (HttpURLConnection) new URL(current).openConnection();
                Net.secure(c);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(45000);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept-Language", Locale.getDefault().getLanguage() + ",en;q=0.8");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    String next = new URL(new URL(current), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    if (isMain) {
                        redirects.onMainRedirect(url, next);
                        String html = "<!doctype html><meta http-equiv=refresh content=\"0;url=" + escape(next) + "\">";
                        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(html.getBytes("UTF-8")));
                    }
                    current = next;
                    continue;
                }
                break;
            }
            if (c == null) return null;
            String type = c.getContentType();
            String mime = "application/octet-stream", charset = null;
            if (type != null) {
                String[] parts = type.split(";");
                mime = parts[0].trim().toLowerCase(Locale.US);
                for (String p : parts) {
                    p = p.trim();
                    if (p.toLowerCase(Locale.US).startsWith("charset=")) charset = p.substring(8).replace("\"", "").trim();
                }
            } else if (url.endsWith(".css")) {
                mime = "text/css";
            } else if (url.endsWith(".js")) {
                mime = "application/javascript";
            }
            InputStream body = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (body == null) body = new ByteArrayInputStream(new byte[0]);
            if (isMain && (mime.equals("text/html") || mime.equals("application/xhtml+xml"))) {
                byte[] raw = readAll(body);
                body.close();
                String html = rewrite(new String(raw, "ISO-8859-1"));
                body = new ByteArrayInputStream(html.getBytes("ISO-8859-1"));
            }
            return new WebResourceResponse(mime, charset, body);
        } catch (Exception e) {
            if (url.equals(mainUrl)) return errorPage(url, e);
            return null;
        }
    }

    private static final java.util.regex.Pattern VIEWPORT = java.util.regex.Pattern.compile(
            "(<meta[^>]{0,200}?name\\s*=\\s*[\"']?)viewport", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HEAD = java.util.regex.Pattern.compile(
            "<head(\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HTML = java.util.regex.Pattern.compile(
            "<html(\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE);

    String rewrite(String html) {
        StringBuilder inject = new StringBuilder();
        if (viewportWidth > 0) {
            html = VIEWPORT.matcher(html).replaceAll("$1x-viewport");
            inject.append("<meta name=\"viewport\" content=\"width=").append(viewportWidth).append("\">");
        }
        inject.append("<style>").append(pageCss);
        if (highContrast) inject.append(contrastCss);
        inject.append("</style>");
        if (scripts) inject.append("<script>").append(pageJs).append("</script>");
        java.util.regex.Matcher m = HEAD.matcher(html);
        if (!m.find()) {
            m = HTML.matcher(html);
            if (!m.find()) return inject + html;
        }
        return html.substring(0, m.end()) + inject + html.substring(m.end());
    }

    private String text(String asset) {
        try (InputStream in = assets.open(asset)) {
            return new String(readAll(in), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private WebResourceResponse asset(String path) {
        try {
            String mime = path.endsWith(".ttf") ? "font/ttf" : path.endsWith(".css") ? "text/css"
                    : path.endsWith(".js") ? "application/javascript" : "application/octet-stream";
            return new WebResourceResponse(mime, null, assets.open(path));
        } catch (IOException e) {
            return null;
        }
    }

    private static WebResourceResponse errorPage(String url, Exception e) {
        String reason = e instanceof java.net.UnknownHostException ? "No internet connection, or the address doesn’t exist."
                : e instanceof java.net.SocketTimeoutException ? "The site took too long to answer."
                : e instanceof javax.net.ssl.SSLException ? "A secure connection couldn’t be made."
                : String.valueOf(e.getMessage());
        String html = "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<style>@font-face{font-family:Inter;src:url(" + ASSET_ORIGIN + "fonts/Inter-Regular.ttf)}"
                + "@font-face{font-family:InterD;src:url(" + ASSET_ORIGIN + "fonts/InterDisplay-Bold.ttf)}"
                + "body{font-family:Inter,sans-serif;font-size:30px;margin:60px 48px;color:#000}"
                + "h1{font-family:InterD;font-size:56px;margin:0 0 20px}p{color:#444;line-height:1.4}"
                + "code{font-size:22px;color:#888;word-break:break-all}</style>"
                + "<h1>Can’t open this page</h1><p>" + escape(reason) + "</p><p><code>" + escape(url) + "</code></p>";
        try {
            return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(html.getBytes("UTF-8")));
        } catch (Exception x) {
            return null;
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
