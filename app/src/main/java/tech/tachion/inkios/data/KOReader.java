package tech.tachion.inkios.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KOReader {
    public static final String PACKAGE = "org.koreader.launcher";

    private static final Pattern HISTORY_ENTRY = Pattern.compile(
            "\\[\"file\"\\]\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\\[\"time\"\\]\\s*=\\s*(\\d+)");
    private static final Pattern HISTORY_ENTRY_REV = Pattern.compile(
            "\\[\"time\"\\]\\s*=\\s*(\\d+)\\s*,\\s*\\[\"file\"\\]\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private KOReader() {}

    public static File home() {
        File f = new File(Environment.getExternalStorageDirectory(), "koreader");
        return f.isDirectory() ? f : null;
    }

    public static List<Book> history(int limit) {
        List<Book> out = new ArrayList<>();
        File home = home();
        if (home == null) return out;
        String lua = read(new File(home, "history.lua"));
        if (lua == null) lua = read(new File(home, "settings/history.lua"));
        if (lua == null) return out;

        List<long[]> order = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        Matcher m = HISTORY_ENTRY.matcher(lua);
        while (m.find()) {
            paths.add(unescape(m.group(1)));
            order.add(new long[]{Long.parseLong(m.group(2)), paths.size() - 1});
        }
        if (paths.isEmpty()) {
            m = HISTORY_ENTRY_REV.matcher(lua);
            while (m.find()) {
                paths.add(unescape(m.group(2)));
                order.add(new long[]{Long.parseLong(m.group(1)), paths.size() - 1});
            }
        }
        java.util.Collections.sort(order, (a, b) -> Long.compare(b[0], a[0]));

        for (long[] o : order) {
            if (out.size() >= limit) break;
            File f = resolve(paths.get((int) o[1]));
            if (f == null) continue;
            Book b = new Book(f);
            b.lastOpened = o[0];
            readSidecar(b);
            out.add(b);
        }
        return out;
    }

    private static File resolve(String path) {
        File f = new File(path);
        if (f.exists()) return f;
        String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] prefixes = {"/storage/emulated/0", "/sdcard", "/mnt/sdcard", "/storage/sdcard0"};
        for (String p : prefixes) {
            if (path.startsWith(p)) {
                File g = new File(ext + path.substring(p.length()));
                if (g.exists()) return g;
            }
        }
        return null;
    }

    private static void readSidecar(Book b) {
        File book = b.file;
        String name = book.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File sdr = new File(book.getParentFile(), base + ".sdr");
        File meta = new File(sdr, "metadata." + b.extension() + ".lua");
        if (!meta.exists()) {
            File[] list = sdr.listFiles();
            if (list != null) for (File x : list) {
                if (x.getName().startsWith("metadata.") && x.getName().endsWith(".lua")) { meta = x; break; }
            }
        }
        String lua = read(meta);
        if (lua == null) return;
        String title = luaString(lua, "title");
        String authors = luaString(lua, "authors");
        String pct = luaNumber(lua, "percent_finished");
        String pages = luaNumber(lua, "doc_pages");
        String status = luaString(lua, "status");
        if (title != null && !title.trim().isEmpty()) b.title = title.trim();
        if (authors != null && !authors.trim().isEmpty()) b.author = authors.replace("\\n", ", ").trim();
        try { if (pct != null) b.progress = Float.parseFloat(pct); } catch (NumberFormatException ignored) {}
        try { if (pages != null) b.pages = Integer.parseInt(pages); } catch (NumberFormatException ignored) {}
        b.finished = "complete".equals(status) || b.progress >= 0.995f;
    }

    public static final class Stats {
        public final long[] days = new long[7];
        public long todaySeconds;
        public long weekSeconds;
        public int streakDays;
        public boolean available;
    }

    public static Stats stats(Context ctx) {
        Stats s = new Stats();
        File home = home();
        if (home == null) return s;
        File db = new File(home, "settings/statistics.sqlite3");
        if (!db.exists()) return s;
        File copy = new File(ctx.getCacheDir(), "koreader-stats.sqlite3");
        if (!copyFile(db, copy)) return s;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis() / 1000L;
        long weekStart = todayStart - 6 * 86400L;
        long streakStart = todayStart - 365 * 86400L;

        SQLiteDatabase d = null;
        Cursor c = null;
        try {
            d = SQLiteDatabase.openDatabase(copy.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            c = d.rawQuery("SELECT start_time, duration FROM page_stat_data WHERE start_time >= ?",
                    new String[]{String.valueOf(streakStart)});
            boolean[] active = new boolean[366];
            long now = System.currentTimeMillis() / 1000L;
            while (c.moveToNext()) {
                long start = c.getLong(0);
                long dur = c.getLong(1);
                if (start > now + 3600) continue;
                int dayIndexFromToday = start >= todayStart ? 0
                        : (int) Math.ceil((todayStart - start) / 86400.0);
                if (dayIndexFromToday < active.length) active[dayIndexFromToday] = true;
                if (start >= weekStart) {
                    int idx = 6 - dayIndexFromToday;
                    if (idx >= 0 && idx < 7) s.days[idx] += dur;
                    s.weekSeconds += dur;
                }
            }
            s.todaySeconds = s.days[6];
            int streak = 0;
            int i = active[0] ? 0 : 1;
            for (; i < active.length && active[i]; i++) streak++;
            s.streakDays = streak;
            s.available = true;
        } catch (Throwable t) {
            s.available = false;
        } finally {
            if (c != null) c.close();
            if (d != null) d.close();
        }
        return s;
    }

    private static String luaString(String lua, String key) {
        Matcher m = Pattern.compile("\\[\"" + key + "\"\\]\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(lua);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String luaNumber(String lua, String key) {
        Matcher m = Pattern.compile("\\[\"" + key + "\"\\]\\s*=\\s*([0-9.eE+-]+)").matcher(lua);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String read(File f) {
        if (f == null || !f.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (IOException e) {
            return null;
        }
        return sb.toString();
    }

    private static boolean copyFile(File from, File to) {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
