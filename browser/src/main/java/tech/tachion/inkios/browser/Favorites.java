package tech.tachion.inkios.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Favorites {
    static final class Fav {
        final String name, url;
        Fav(String name, String url) { this.name = name; this.url = url; }
    }

    private static final String[][] DEFAULTS = {
            {"Vikipedi", "https://tr.wikipedia.org/"},
            {"Wikipedia", "https://en.wikipedia.org/"},
            {"DuckDuckGo", "https://lite.duckduckgo.com/lite/"},
            {"BBC Türkçe", "https://www.bbc.com/turkce"},
            {"Hacker News", "https://news.ycombinator.com/"},
            {"NPR Text", "https://text.npr.org/"},
            {"CNN Lite", "https://lite.cnn.com/"},
            {"68k News", "http://68k.news/"},
    };

    private Favorites() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("favorites", Context.MODE_PRIVATE);
    }

    static List<Fav> list(Context c) {
        List<Fav> out = new ArrayList<>();
        String raw = prefs(c).getString("list", null);
        if (raw == null) {
            for (String[] d : DEFAULTS) out.add(new Fav(d[0], d[1]));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Fav(o.getString("name"), o.getString("url")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void save(Context c, List<Fav> l) {
        JSONArray a = new JSONArray();
        try {
            for (Fav f : l) a.put(new JSONObject().put("name", f.name).put("url", f.url));
        } catch (Exception ignored) {}
        prefs(c).edit().putString("list", a.toString()).apply();
    }

    static void add(Context c, String name, String url) {
        List<Fav> l = list(c);
        for (Fav f : l) if (f.url.equals(url)) return;
        String n = name == null ? url : name.trim();
        if (n.length() > 24) n = n.substring(0, 24).trim();
        l.add(new Fav(n, url));
        save(c, l);
    }

    static void remove(Context c, Fav target) {
        List<Fav> l = list(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).url.equals(target.url)) { l.remove(i); break; }
        save(c, l);
    }
}
