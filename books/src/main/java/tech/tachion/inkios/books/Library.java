package tech.tachion.inkios.books;

import tech.tachion.inkios.net.Net;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

public final class Library {
    public interface Progress { void onProgress(int percent); }

    private Library() {}

    public static File dir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Books");
        d.mkdirs();
        return d;
    }

    public static File fileFor(Item it) {
        String base = it.title == null ? "Book" : it.title;
        if (it.author != null && !it.author.isEmpty()) base = it.author + " - " + base;
        base = base.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").replaceAll("\\s+", " ").trim();
        if (base.length() > 120) base = base.substring(0, 120).trim();
        return new File(dir(), base + "." + it.format);
    }

    public static File download(Item it, Progress p) throws Exception {
        long[] waits = {0, 3000, 8000};
        Exception last = null;
        for (long w : waits) {
            if (w > 0) Thread.sleep(w);
            try {
                return downloadOnce(it, p);
            } catch (java.net.SocketTimeoutException e) {
                last = e;
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (!m.startsWith("HTTP 5") && !m.contains("reset") && !m.contains("closed")) throw e;
                last = e;
            }
        }
        throw last;
    }

    private static File downloadOnce(Item it, Progress p) throws Exception {
        Sources.resolve(it);
        File target = fileFor(it);
        File part = new File(target.getPath() + ".part");
        HttpURLConnection c = Net.open(it.downloadUrl);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            long total = c.getContentLength(), done = 0;
            byte[] buf = new byte[32768];
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 10 != last / 10) { last = pct; p.onProgress(pct); }
                }
            }
        } finally {
            c.disconnect();
        }
        if (part.length() < 512) {
            part.delete();
            throw new IOException("Download was empty");
        }
        if (!part.renameTo(target)) throw new IOException("Could not save " + target.getName());
        return target;
    }

    public static List<Item> local() {
        List<Item> out = new ArrayList<>();
        File[] fs = dir().listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : fs) {
            String n = f.getName();
            if (!f.isFile() || n.startsWith(".") || n.endsWith(".part")) continue;
            int dot = n.lastIndexOf('.');
            Item it = new Item();
            it.title = dot > 0 ? n.substring(0, dot) : n;
            it.format = dot > 0 ? n.substring(dot + 1).toLowerCase() : "";
            int dash = it.title.indexOf(" - ");
            if (dash > 0) {
                it.author = it.title.substring(0, dash);
                it.title = it.title.substring(dash + 3);
            }
            it.file = f;
            it.size = f.length();
            it.detailsLoaded = true;
            it.source = BooksActivity.sizeText(f.length()) + "  ·  " + it.format.toUpperCase() + "  ·  "
                    + android.text.format.DateFormat.format("d MMM yyyy", f.lastModified());
            out.add(it);
        }
        return out;
    }

    public static final class Catalog {
        public final String name, url;
        Catalog(String name, String url) { this.name = name; this.url = url; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("catalogs", Context.MODE_PRIVATE);
    }

    public static List<Catalog> catalogs(Context c) {
        List<Catalog> out = new ArrayList<>();
        String raw = prefs(c).getString("list", null);
        if (raw == null) {
            out.add(new Catalog("Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/"));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Catalog(o.getString("name"), o.getString("url")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveCatalogs(Context c, List<Catalog> list) {
        JSONArray a = new JSONArray();
        try {
            for (Catalog k : list) a.put(new JSONObject().put("name", k.name).put("url", k.url));
        } catch (Exception ignored) {}
        prefs(c).edit().putString("list", a.toString()).apply();
    }

    public static void addCatalog(Context c, String name, String url) {
        List<Catalog> l = catalogs(c);
        l.add(new Catalog(name, url));
        saveCatalogs(c, l);
    }

    public static void removeCatalog(Context c, Catalog k) {
        List<Catalog> l = catalogs(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).url.equals(k.url)) { l.remove(i); break; }
        saveCatalogs(c, l);
    }
}
