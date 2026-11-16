package tech.tachion.inkios.data;

import java.io.File;

public final class Book {
    public final File file;
    public String title;
    public String author;
    public float progress = -1f;
    public int pages;
    public long lastOpened;
    public boolean finished;

    public Book(File file) {
        this.file = file;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        this.title = dot > 0 ? name.substring(0, dot) : name;
    }

    public String extension() {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1).toLowerCase() : "";
    }

    public String mimeType() {
        switch (extension()) {
            case "epub": return "application/epub+zip";
            case "pdf": return "application/pdf";
            case "mobi": case "azw": case "azw3": return "application/x-mobipocket-ebook";
            case "fb2": return "application/x-fb2";
            case "djvu": return "image/vnd.djvu";
            case "cbz": return "application/x-cbz";
            case "txt": return "text/plain";
            case "html": case "htm": return "text/html";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default: return "*/*";
        }
    }

    public int currentPage() {
        if (pages <= 0 || progress < 0) return 0;
        return Math.max(1, Math.round(progress * pages));
    }
}
