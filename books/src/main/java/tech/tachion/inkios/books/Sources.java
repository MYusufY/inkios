package tech.tachion.inkios.books;

import tech.tachion.inkios.net.Net;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class Sources {
    public static final int KIND_ARCHIVE = 1, KIND_WIKISOURCE = 2, KIND_OPDS = 3;

    public static final String GUTENBERG_SEARCH = "https://www.gutenberg.org/ebooks/search.opds/?query=";
    public static final String GUTENBERG_POPULAR = "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads";

    private Sources() {}

    static final String[] TURKISH_CLASSICS = {
            "Seyfettin", "Uşaklıgil", "\"Sabahattin Ali\"", "Gürpınar", "\"Namık Kemal\"",
            "\"Tevfik Fikret\"", "\"Mehmet Akif\"", "\"Ahmet Mithat\"", "Recaizade", "\"Sait Faik\"",
            "\"Orhan Veli\"", "\"Ahmet Rasim\"", "\"Ziya Gökalp\"", "\"Ahmet Haşim\"", "\"Fatma Aliye\"",
            "\"Halit Ziya\"", "\"Mehmet Rauf\"", "Şinasi",
    };

    public static List<Item> archive(String query, boolean turkish, int page, int rows) throws Exception {
        StringBuilder q = new StringBuilder();
        if (!query.trim().isEmpty()) {
            String w = query.trim().replace("\"", " ");
            q.append("(title:(").append(w).append(") OR creator:(").append(w).append(") OR subject:(")
                    .append(w).append(")) AND ");
        } else if (turkish) {
            q.append("creator:(");
            for (int i = 0; i < TURKISH_CLASSICS.length; i++) {
                if (i > 0) q.append(" OR ");
                q.append(TURKISH_CLASSICS[i]);
            }
            q.append(") AND ");
        }
        q.append(turkish ? "mediatype:texts AND (format:EPUB OR format:PDF)" : "mediatype:texts AND format:EPUB");
        q.append(" AND NOT access-restricted-item:true");
        if (turkish && !query.trim().isEmpty()) q.append(" AND language:(tur OR turkish OR Türkçe)");
        String url = "https://archive.org/advancedsearch.php?q=" + Net.enc(q.toString())
                + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=format&sort[]=downloads+desc"
                + "&rows=" + rows + "&page=" + (page + 1) + "&output=json";
        JSONArray docs = new JSONObject(Net.get(url)).getJSONObject("response").getJSONArray("docs");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.getJSONObject(i);
            Item it = new Item();
            it.kind = KIND_ARCHIVE;
            it.id = d.getString("identifier");
            it.title = firstString(d.opt("title"), it.id);
            it.author = firstString(d.opt("creator"), null);
            it.source = "Internet Archive";
            it.format = String.valueOf(d.opt("format")).contains("EPUB") ? "epub" : "pdf";
            it.coverUrl = "https://archive.org/services/img/" + it.id;
            out.add(it);
        }
        return out;
    }

    private static void archiveDetails(Item it) throws Exception {
        JSONObject meta = new JSONObject(Net.get("https://archive.org/metadata/" + Net.enc(it.id)));
        JSONArray files = meta.getJSONArray("files");
        JSONObject epub = null, pdf = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            String low = f.optString("name").toLowerCase();
            if (low.endsWith("_encrypted.epub") || low.endsWith("_lcp.epub")) continue;
            if (epub == null && low.endsWith(".epub")) epub = f;
            if (pdf == null && low.endsWith(".pdf") && !low.endsWith("_text.pdf")) pdf = f;
        }
        JSONObject file = epub != null ? epub : pdf;
        if (file == null) throw new IOException("No EPUB or PDF in this item");
        it.format = epub != null ? "epub" : "pdf";
        it.size = file.optLong("size", -1);
        it.downloadUrl = "https://archive.org/download/" + Net.enc(it.id) + "/"
                + Net.enc(file.optString("name")).replace("+", "%20");
        JSONObject md = meta.optJSONObject("metadata");
        if (md != null) it.description = plain(firstString(md.opt("description"), null));
    }

    private static void wikisourceDetails(Item it) throws Exception {
        String url = "https://tr.wikisource.org/w/api.php?action=query&prop=extracts&explaintext=1&exchars=700"
                + "&format=json&titles=" + Net.enc(it.id);
        JSONObject pages = new JSONObject(Net.get(url)).getJSONObject("query").getJSONObject("pages");
        java.util.Iterator<String> k = pages.keys();
        if (k.hasNext()) it.description = plain(pages.getJSONObject(k.next()).optString("extract", null));
    }

    static String plain(String s) {
        if (s == null) return null;
        s = s.replaceAll("(?s)<[^>]*>", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstString(Object o, String def) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            return a.length() > 0 ? a.optString(0, def) : def;
        }
        return o instanceof String ? (String) o : def;
    }

    public static List<Item> wikisource(String query, int page, int rows) throws Exception {
        List<Item> out = new ArrayList<>();
        if (query.trim().isEmpty()) return out;
        String url = "https://tr.wikisource.org/w/api.php?action=query&list=search&format=json&srnamespace=0"
                + "&srlimit=" + rows + "&sroffset=" + (page * rows) + "&srsearch=" + Net.enc(query.trim());
        JSONArray res = new JSONObject(Net.get(url)).getJSONObject("query").getJSONArray("search");
        for (int i = 0; i < res.length(); i++) {
            String title = res.getJSONObject(i).getString("title");
            Item it = new Item();
            it.kind = KIND_WIKISOURCE;
            it.id = title;
            int p = title.lastIndexOf(" (");
            if (p > 0 && title.endsWith(")")) {
                it.title = title.substring(0, p);
                it.author = title.substring(p + 2, title.length() - 1);
            } else {
                it.title = title;
            }
            it.source = "Vikikaynak";
            it.downloadUrl = "https://ws-export.wmcloud.org/?format=epub&lang=tr&page="
                    + Net.enc(title.replace(' ', '_'));
            out.add(it);
        }
        return out;
    }

    private static final java.util.regex.Pattern GUTENBERG_ID =
            java.util.regex.Pattern.compile("gutenberg\\.org/ebooks/(\\d+)");

    public static List<Item> gutenberg(String query, int page) throws Exception {
        String base = query.trim().isEmpty() ? GUTENBERG_POPULAR : GUTENBERG_SEARCH + Net.enc(query.trim());
        Feed f = opds(base + (base.contains("?") ? "&" : "?") + "start_index=" + (1 + page * 25));
        List<Item> out = new ArrayList<>();
        for (Item it : f.items) {
            String link = it.browseUrl != null ? it.browseUrl : it.downloadUrl;
            if (link == null) continue;
            java.util.regex.Matcher m = GUTENBERG_ID.matcher(link);
            if (!m.find()) continue;
            String id = m.group(1);
            it.id = id;
            it.browseUrl = null;
            it.downloadUrl = "https://www.gutenberg.org/ebooks/" + id + ".epub3.images";
            it.format = "epub";
            it.coverUrl = "https://www.gutenberg.org/cache/epub/" + id + "/pg" + id + ".cover.medium.jpg";
            it.source = "Project Gutenberg";
            out.add(it);
        }
        return out;
    }

    public static final class Feed {
        public String title;
        public final List<Item> items = new ArrayList<>();
        public String next;
        public String searchTemplate;
    }

    public static Feed opds(String url) throws Exception {
        Feed f = new Feed();
        XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(new StringReader(Net.get(url)));
        Item cur = null;
        boolean inAuthor = false;
        String text = null;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String name = local(x.getName());
                text = null;
                if (name.equals("entry")) {
                    cur = new Item();
                    cur.kind = KIND_OPDS;
                } else if (name.equals("author")) {
                    inAuthor = true;
                } else if (name.equals("link")) {
                    String rel = attr(x, "rel"), type = attr(x, "type"), href = attr(x, "href");
                    if (href == null) continue;
                    String abs = new URL(new URL(url), href).toString();
                    if (cur == null) {
                        if ("next".equals(rel)) f.next = abs;
                        if ("search".equals(rel) && type != null && type.contains("atom")) f.searchTemplate = abs;
                    } else if (rel != null && rel.startsWith("http://opds-spec.org/acquisition")) {
                        String fmt = formatOf(type);
                        if (fmt != null && (cur.downloadUrl == null || "epub".equals(fmt))) {
                            if ("epub".equals(fmt) && href.contains(".images")) {
                                cur.downloadUrl = abs;
                                cur.format = fmt;
                            } else if (cur.downloadUrl == null || !"epub".equals(cur.format)) {
                                cur.downloadUrl = abs;
                                cur.format = fmt;
                            }
                        }
                    } else if (rel != null && (rel.equals("http://opds-spec.org/image/thumbnail")
                            || rel.equals("http://opds-spec.org/thumbnail"))) {
                        cur.coverUrl = abs;
                    } else if (rel != null && rel.equals("http://opds-spec.org/image") && cur.coverUrl == null) {
                        cur.coverUrl = abs;
                    } else if (type != null && type.contains("profile=opds-catalog")) {
                        cur.browseUrl = abs;
                    } else if (type != null && type.startsWith("application/atom+xml") && cur.browseUrl == null
                            && (rel == null || rel.equals("subsection") || rel.equals("alternate"))) {
                        cur.browseUrl = abs;
                    }
                }
            } else if (ev == XmlPullParser.TEXT) {
                text = x.getText();
            } else if (ev == XmlPullParser.END_TAG) {
                String name = local(x.getName());
                if (name.equals("author")) inAuthor = false;
                else if (name.equals("name") && inAuthor && cur != null && text != null && cur.author == null) {
                    cur.author = text.trim();
                } else if ((name.equals("summary") || name.equals("content")) && cur != null && text != null
                        && cur.description == null) {
                    cur.description = plain(text);
                } else if (name.equals("title") && text != null) {
                    if (cur != null) cur.title = text.trim();
                    else if (f.title == null) f.title = text.trim();
                } else if (name.equals("entry") && cur != null) {
                    if (cur.downloadUrl != null) cur.browseUrl = null;
                    if (cur.downloadUrl != null || cur.browseUrl != null) {
                        if (cur.source == null) cur.source = cur.downloadUrl != null ? cur.format.toUpperCase() : "Catalog";
                        f.items.add(cur);
                    }
                    cur = null;
                }
            }
        }
        if (f.searchTemplate != null && !f.searchTemplate.contains("{searchTerms}")) {
            f.searchTemplate = openSearchTemplate(f.searchTemplate);
        }
        return f;
    }

    private static String openSearchTemplate(String descriptionUrl) {
        try {
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setInput(new StringReader(Net.get(descriptionUrl)));
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG && local(x.getName()).equals("Url")) {
                    String type = attr(x, "type"), t = attr(x, "template");
                    if (t != null && type != null && type.contains("atom")) {
                        return new URL(new URL(descriptionUrl), t).toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String formatOf(String type) {
        if (type == null) return null;
        if (type.contains("epub")) return "epub";
        if (type.contains("pdf")) return "pdf";
        if (type.contains("mobipocket")) return "mobi";
        if (type.contains("fb2")) return "fb2";
        if (type.contains("djvu")) return "djvu";
        if (type.contains("cbz")) return "cbz";
        if (type.startsWith("text/plain")) return "txt";
        return null;
    }

    private static String local(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private static String attr(XmlPullParser x, String name) {
        for (int i = 0; i < x.getAttributeCount(); i++) {
            if (local(x.getAttributeName(i)).equals(name)) return x.getAttributeValue(i);
        }
        return null;
    }

    public static void details(Item it) throws Exception {
        if (it.detailsLoaded) return;
        if (it.kind == KIND_ARCHIVE) archiveDetails(it);
        else if (it.kind == KIND_WIKISOURCE) wikisourceDetails(it);
        it.detailsLoaded = true;
    }

    public static void resolve(Item it) throws Exception {
        if (it.downloadUrl == null) details(it);
        if (it.downloadUrl == null) throw new IOException("Nothing to download");
    }
}
