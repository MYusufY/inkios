package tech.tachion.inkios.share;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Server {
    public interface Listener {
        void onTransfer(String name, long done, long total);
        void onReceived(String name);
        void onDeleted(String name);
    }

    private final AssetManager assets;
    private final File dir;
    private final String key;
    private final Listener listener;
    private ServerSocket socket;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running;

    public Server(AssetManager assets, File dir, String key, Listener listener) {
        this.assets = assets;
        this.dir = dir;
        this.key = key;
        this.listener = listener;
    }

    public int start(int preferred) throws IOException {
        IOException last = null;
        for (int p = preferred; p < preferred + 10; p++) {
            try {
                socket = new ServerSocket(p);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (socket == null) throw last;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    final Socket s = socket.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }, "share-accept");
        t.setDaemon(true);
        t.start();
        return socket.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 65536);
            OutputStream out = sock.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0];
            String target = req[1];
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US), lines[i].substring(c + 1).trim());
            }
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) q.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }
            route(method, path, q, headers, in, out);
        } catch (SocketException ignored) {
        } catch (Exception e) {
        }
    }

    private void route(String method, String path, Map<String, String> q, Map<String, String> h,
                       InputStream in, OutputStream out) throws IOException {
        boolean authed = key.equals(q.get("k"));
        if (path.equals("/") || path.equals("/index.html")) {
            if (!authed) {
                send(out, 403, "text/html; charset=utf-8", page("Scan the QR code on your e-reader again.").getBytes("UTF-8"));
                return;
            }
            send(out, 200, "text/html; charset=utf-8", asset("web/index.html"));
            return;
        }
        if (path.startsWith("/fonts/") && path.endsWith(".ttf") && !path.contains("..")) {
            send(out, 200, "font/ttf", asset("fonts/" + path.substring(7)));
            return;
        }
        if (path.equals("/favicon.ico")) {
            send(out, 404, "text/plain", new byte[0]);
            return;
        }
        if (!authed) {
            send(out, 403, "application/json", "{\"error\":\"bad key\"}".getBytes("UTF-8"));
            return;
        }
        switch (path) {
            case "/api/list":
                send(out, 200, "application/json; charset=utf-8", list().getBytes("UTF-8"));
                return;
            case "/api/upload":
                if (!method.equals("PUT") && !method.equals("POST")) break;
                upload(q.get("name"), h, in, out);
                return;
            case "/api/file":
                file(q.get("name"), out);
                return;
            case "/api/delete":
                if (!method.equals("POST")) break;
                File f = safe(q.get("name"));
                boolean ok = f != null && f.isFile() && f.delete();
                if (ok) listener.onDeleted(f.getName());
                send(out, ok ? 200 : 404, "application/json", ("{\"ok\":" + ok + "}").getBytes("UTF-8"));
                return;
            default:
        }
        send(out, 404, "application/json", "{\"error\":\"not found\"}".getBytes("UTF-8"));
    }

    private String list() {
        JSONArray a = new JSONArray();
        File[] fs = dir.listFiles();
        if (fs != null) {
            Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            for (File f : fs) {
                if (!f.isFile() || f.getName().endsWith(".part") || f.getName().startsWith(".")) continue;
                try {
                    a.put(new JSONObject().put("name", f.getName()).put("size", f.length()).put("modified", f.lastModified()));
                } catch (Exception ignored) {}
            }
        }
        return a.toString();
    }

    private void upload(String name, Map<String, String> h, InputStream in, OutputStream out) throws IOException {
        File target = safe(name);
        long total = parseLong(h.get("content-length"));
        if (target == null || total <= 0) {
            send(out, 400, "application/json", "{\"error\":\"missing name or size\"}".getBytes("UTF-8"));
            return;
        }
        if (target.exists()) target = unique(target);
        File part = new File(target.getPath() + ".part");
        long done = 0, lastNotify = 0;
        try (OutputStream fo = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            while (done < total) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, total - done));
                if (n < 0) break;
                fo.write(buf, 0, n);
                done += n;
                if (done - lastNotify > total / 20 || done == total) {
                    lastNotify = done;
                    listener.onTransfer(target.getName(), done, total);
                }
            }
        }
        if (done != total || !part.renameTo(target)) {
            part.delete();
            send(out, 500, "application/json", "{\"error\":\"upload incomplete\"}".getBytes("UTF-8"));
            return;
        }
        listener.onReceived(target.getName());
        send(out, 200, "application/json",
                ("{\"ok\":true,\"name\":" + JSONObject.quote(target.getName()) + "}").getBytes("UTF-8"));
    }

    private void file(String name, OutputStream out) throws IOException {
        File f = safe(name);
        if (f == null || !f.isFile()) {
            send(out, 404, "text/plain", "not found".getBytes("UTF-8"));
            return;
        }
        String header = "HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length()
                + "\r\nContent-Disposition: attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20")
                + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        try (InputStream fi = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = fi.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }

    private File safe(String name) {
        if (name == null) return null;
        name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", " ").trim();
        if (name.isEmpty() || name.startsWith(".") || name.equals("..")) return null;
        if (name.length() > 180) name = name.substring(name.length() - 180);
        return new File(dir, name);
    }

    private static File unique(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File g = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!g.exists()) return g;
        }
        return f;
    }

    private byte[] asset(String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int state = 0, b;
        while ((b = in.read()) >= 0) {
            bo.write(b);
            if (bo.size() > 16384) return null;
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return bo.toString("ISO-8859-1");
        }
        return null;
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
                : code == 400 ? "Bad Request" : "Error";
        String head = "HTTP/1.0 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return -1; }
    }

    private static String page(String msg) {
        return "<!doctype html><meta name=viewport content='width=device-width'><title>Inkios</title>"
                + "<body style='font:17px -apple-system,system-ui,sans-serif;padding:40px 24px;color:#111'>"
                + "<h2>Inkios Share</h2><p>" + msg + "</p></body>";
    }
}
