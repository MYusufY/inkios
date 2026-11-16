package tech.tachion.inkios.notes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Environment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class NoteStore {
    public static final class Note {
        public final File dir;
        public String title;
        public int template = Page.DOTS;
        public int pages = 1;
        public long created, modified;

        Note(File dir) { this.dir = dir; }

        public File pageFile(int index) { return new File(dir, "page-" + (index + 1) + ".ink"); }

        public File thumbFile() { return new File(dir, "thumb.png"); }
    }

    private NoteStore() {}

    public static File root() {
        File f = new File(Environment.getExternalStorageDirectory(), "Inkios/Notes");
        f.mkdirs();
        return f;
    }

    public static List<Note> list() {
        List<Note> out = new ArrayList<>();
        File[] dirs = root().listFiles();
        if (dirs != null) for (File d : dirs) {
            Note n = load(d);
            if (n != null) out.add(n);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    public static Note create(int template) {
        long now = System.currentTimeMillis();
        File dir = new File(root(), "note-" + now);
        dir.mkdirs();
        Note n = new Note(dir);
        n.title = "";
        n.template = template;
        n.created = n.modified = now;
        saveMeta(n);
        return n;
    }

    public static Note load(File dir) {
        File meta = new File(dir, "note.properties");
        if (!meta.isFile()) return null;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(meta)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        Note n = new Note(dir);
        n.title = p.getProperty("title", "");
        n.template = parse(p.getProperty("template"), Page.DOTS);
        n.pages = Math.max(1, parse(p.getProperty("pages"), 1));
        n.created = parseLong(p.getProperty("created"), dir.lastModified());
        n.modified = parseLong(p.getProperty("modified"), dir.lastModified());
        return n;
    }

    public static void saveMeta(Note n) {
        Properties p = new Properties();
        p.setProperty("title", n.title == null ? "" : n.title);
        p.setProperty("template", String.valueOf(n.template));
        p.setProperty("pages", String.valueOf(n.pages));
        p.setProperty("created", String.valueOf(n.created));
        p.setProperty("modified", String.valueOf(n.modified));
        try (FileOutputStream out = new FileOutputStream(new File(n.dir, "note.properties"))) {
            p.store(out, "Inkios note");
        } catch (IOException ignored) {}
    }

    public static Page loadPage(Note n, int index) {
        File f = n.pageFile(index);
        if (!f.isFile()) return new Page();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            return Page.read(in);
        } catch (IOException e) {
            return new Page();
        }
    }

    public static void savePage(Note n, int index, Page page) {
        File tmp = new File(n.dir, "page-" + (index + 1) + ".ink.tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            page.write(out);
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(n.pageFile(index));
        n.modified = System.currentTimeMillis();
        saveMeta(n);
    }

    public static void saveThumb(Note n, Page first) {
        int w = 300, h = Math.round(w * Page.HEIGHT / Page.WIDTH);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFFFFFFF);
        float scale = w / Page.WIDTH;
        Page.drawTemplate(c, n.template, scale);
        first.drawStrokes(c, scale);
        try (FileOutputStream out = new FileOutputStream(n.thumbFile())) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) {}
        b.recycle();
    }

    public static Bitmap thumb(Note n) {
        return n.thumbFile().isFile() ? BitmapFactory.decodeFile(n.thumbFile().getPath()) : null;
    }

    public static void delete(Note n) {
        File[] fs = n.dir.listFiles();
        if (fs != null) for (File f : fs)
            f.delete();
        n.dir.delete();
    }

    public static void deletePage(Note n, int index) {
        n.pageFile(index).delete();
        for (int i = index + 1; i < n.pages; i++) {
            n.pageFile(i).renameTo(n.pageFile(i - 1));
        }
        n.pages = Math.max(1, n.pages - 1);
        n.modified = System.currentTimeMillis();
        saveMeta(n);
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
}
