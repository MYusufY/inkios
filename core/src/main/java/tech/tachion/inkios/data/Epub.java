package tech.tachion.inkios.data;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Epub {
    private Epub() {}

    private static final Pattern ROOTFILE = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern META_COVER = Pattern.compile(
            "<meta[^>]*name\\s*=\\s*\"cover\"[^>]*content\\s*=\\s*\"([^\"]+)\"|<meta[^>]*content\\s*=\\s*\"([^\"]+)\"[^>]*name\\s*=\\s*\"cover\"");
    private static final Pattern ITEM = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    public static byte[] coverBytes(File f) {
        try (ZipFile zip = new ZipFile(f)) {
            String opfPath = null;
            ZipEntry container = zip.getEntry("META-INF/container.xml");
            if (container != null) {
                Matcher m = ROOTFILE.matcher(readString(zip, container));
                if (m.find()) opfPath = m.group(1);
            }
            String href = null;
            if (opfPath != null && zip.getEntry(opfPath) != null) {
                String opf = readString(zip, zip.getEntry(opfPath));
                String coverId = null;
                Matcher mc = META_COVER.matcher(opf);
                if (mc.find()) coverId = mc.group(1) != null ? mc.group(1) : mc.group(2);
                Matcher items = ITEM.matcher(opf);
                String fallback = null;
                while (items.find()) {
                    String tag = items.group();
                    String id = attr(tag, "id");
                    String h = attr(tag, "href");
                    String type = attr(tag, "media-type");
                    String props = attr(tag, "properties");
                    if (h == null || type == null || !type.startsWith("image/")) continue;
                    if (props != null && props.contains("cover-image")) { href = h; break; }
                    if (coverId != null && coverId.equals(id)) { href = h; break; }
                    if (fallback == null && (h.toLowerCase().contains("cover")
                            || (id != null && id.toLowerCase().contains("cover")))) fallback = h;
                }
                if (href == null) href = fallback;
                if (href != null) {
                    String dir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
                    href = normalize(dir + java.net.URLDecoder.decode(href, "UTF-8"));
                }
            }
            ZipEntry e = href != null ? zip.getEntry(href) : null;
            if (e == null) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry z = all.nextElement();
                    String n = z.getName().toLowerCase();
                    if (n.contains("cover") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) {
                        e = z;
                        break;
                    }
                }
            }
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return readAll(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String normalize(String path) {
        java.util.LinkedList<String> parts = new java.util.LinkedList<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); } else parts.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) { if (sb.length() > 0) sb.append('/'); sb.append(p); }
        return sb.toString();
    }

    private static String readString(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new String(readAll(in), "UTF-8");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
