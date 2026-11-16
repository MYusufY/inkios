package tech.tachion.inkios.books;

public final class Item {
    public String title;
    public String author;
    public String source;
    public String coverUrl;
    public String downloadUrl;
    public String format = "epub";
    public String id;
    public int kind;
    public String browseUrl;

    public String description;
    public long size = -1;
    public boolean detailsLoaded;

    public int progress = -1;
    public java.io.File file;
    public String error;

    public boolean isFolder() { return browseUrl != null; }

    org.json.JSONObject toJson() throws org.json.JSONException {
        return new org.json.JSONObject().put("title", title).put("author", author).put("source", source)
                .put("cover", coverUrl).put("url", downloadUrl).put("format", format).put("id", id)
                .put("kind", kind).put("browse", browseUrl);
    }

    static Item fromJson(org.json.JSONObject o) {
        Item it = new Item();
        it.title = o.optString("title", null);
        it.author = o.optString("author", null);
        it.source = o.optString("source", null);
        it.coverUrl = o.optString("cover", null);
        it.downloadUrl = o.optString("url", null);
        it.format = o.optString("format", "epub");
        it.id = o.optString("id", null);
        it.kind = o.optInt("kind");
        it.browseUrl = o.optString("browse", null);
        return it;
    }
}
