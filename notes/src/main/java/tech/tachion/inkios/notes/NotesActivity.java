package tech.tachion.inkios.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.StatusBar;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class NotesActivity extends Activity {
    private LibraryView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        view = new LibraryView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.reload();
        Eink.fullRefreshSoon(view, 80);
    }

    private void open(NoteStore.Note n) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_DIR, n.dir.getAbsolutePath());
        startActivity(i);
    }

    private void options(final NoteStore.Note n) {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle(EditorActivity.displayTitle(n))
                .setItems(new String[]{"Rename…", "Delete"}, (d, which) -> {
                    if (which == 0) rename(n);
                    else confirmDelete(n);
                }).show();
    }

    private void rename(final NoteStore.Note n) {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(n.title);
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle("Rename note")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    n.title = field.getText().toString().trim();
                    NoteStore.saveMeta(n);
                    view.reload();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(final NoteStore.Note n) {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle("Delete this note?")
                .setMessage("It will be removed from this device. This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    NoteStore.delete(n);
                    view.reload();
                })
                .setNegativeButton("Cancel", null).show();
    }

    final class LibraryView extends TapView {
        private static final int COLS = 3;
        private final StatusBar statusBar;
        private final StatusBar.State status = new StatusBar.State();
        private final List<NoteStore.Note> notes = new ArrayList<>();
        private final Map<NoteStore.Note, Bitmap> thumbs = new HashMap<>();
        private int page, rows = 2;
        private final Paint title, name, date, plus, edge, fill, dotOn, dotOff;
        private final RectF grid = new RectF();

        LibraryView(Context c) {
            super(c);
            statusBar = new StatusBar(t);
            title = t.textPaint(t.displayBold, 36, Theme.INK);
            name = t.textPaint(t.displaySemibold, 16, Theme.INK);
            date = t.textPaint(t.textMedium, 13, Theme.TERTIARY);
            plus = t.stroke(Theme.INK, 3f);
            edge = t.stroke(Theme.SEPARATOR, 1.5f);
            fill = t.fill(Theme.FILL);
            dotOn = t.fill(Theme.INK);
            dotOff = t.fill(Theme.SEPARATOR);
        }

        void reload() {
            for (Bitmap b : thumbs.values()) if (b != null) b.recycle();
            thumbs.clear();
            notes.clear();
            notes.addAll(NoteStore.list());
            for (NoteStore.Note n : notes) thumbs.put(n, NoteStore.thumb(n));
            status.leftText = "";
            page = Math.min(page, pages() - 1);
            rebuild();
            invalidate();
        }

        private int perPage() { return COLS * rows - (page == 0 ? 1 : 0); }

        private int pages() {
            int total = notes.size() + 1;
            return Math.max(1, (total + COLS * rows - 1) / (COLS * rows));
        }

        private RectF cell(int i) {
            float gap = t.px(18);
            float cw = (grid.width() - gap * (COLS - 1)) / COLS;
            float ch = grid.height() / rows;
            int col = i % COLS, row = i / COLS;
            float l = grid.left + col * (cw + gap), top = grid.top + row * ch;
            float thumbH = Math.min(cw * Page.HEIGHT / Page.WIDTH, ch - t.px(54));
            return new RectF(l, top, l + cw, top + thumbH);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float m = t.px(22);
            grid.set(m, t.px(124), w - m, h - t.px(64));
            float cw = (grid.width() - t.px(18) * (COLS - 1)) / COLS;
            float cellH = cw * Page.HEIGHT / Page.WIDTH + t.px(64);
            rows = Math.max(1, (int) (grid.height() / cellH));
            rebuild();
        }

        private int firstIndex() {
            return page == 0 ? 0 : COLS * rows - 1 + (page - 1) * COLS * rows;
        }

        private void rebuild() {
            targets.clear();
            if (getWidth() == 0) return;
            int slot = 0;
            if (page == 0) {
                RectF r = cell(0);
                target(r.left - 6, r.top - 6, r.right + 6, r.bottom + t.px(48), t.px(14), this::newNote);
                slot = 1;
            }
            int start = firstIndex();
            for (int i = 0; i < perPage() && start + i < notes.size(); i++, slot++) {
                final NoteStore.Note n = notes.get(start + i);
                RectF r = cell(slot);
                Target tg = target(r.left - 6, r.top - 6, r.right + 6, r.bottom + t.px(48), t.px(14), () -> open(n));
                tg.onLongPress = () -> options(n);
            }
            int w = getWidth(), h = getHeight();
            if (pages() > 1) {
                target(0, h - t.px(60), w / 2f, h, 0, () -> flip(-1)).highlight = false;
                target(w / 2f, h - t.px(60), w, h, 0, () -> flip(1)).highlight = false;
            }
        }

        private void newNote() {
            open(NoteStore.create(Page.DOTS));
        }

        private void flip(int d) {
            int np = Math.max(0, Math.min(pages() - 1, page + d));
            if (np == page) return;
            page = np;
            rebuild();
            Eink.refresh(this, Eink.Mode.CLEAN);
        }

        @Override
        protected void onSwipe(int direction) { flip(direction < 0 ? 1 : -1); }

        @Override
        protected void drawContent(Canvas c) {
            int w = getWidth(), h = getHeight();
            statusBar.draw(c, status, w);
            c.drawText("Notes", t.px(22), t.px(96), title);
            String count = notes.size() == 1 ? "1 note" : notes.size() + " notes";
            Draw.textRight(c, count, w - t.px(22), t.px(92), date);

            int slot = 0;
            if (page == 0) {
                RectF r = cell(0);
                Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(10), fill);
                float s = t.px(16);
                c.drawLine(r.centerX() - s, r.centerY(), r.centerX() + s, r.centerY(), plus);
                c.drawLine(r.centerX(), r.centerY() - s, r.centerX(), r.centerY() + s, plus);
                c.drawText("New note", r.left, r.bottom + t.px(24), name);
                slot = 1;
            }
            int start = firstIndex();
            for (int i = 0; i < perPage() && start + i < notes.size(); i++, slot++) {
                NoteStore.Note n = notes.get(start + i);
                RectF r = cell(slot);
                Bitmap b = thumbs.get(n);
                c.save();
                Path clip = new Path();
                clip.addRoundRect(r, t.px(10), t.px(10), Path.Direction.CW);
                c.clipPath(clip);
                c.drawColor(Theme.PAPER);
                if (b != null) c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), r, new Paint(Paint.FILTER_BITMAP_FLAG));
                c.restore();
                Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(10), edge);
                Draw.text(c, EditorActivity.displayTitle(n), r.left, r.bottom + t.px(24), r.width(), name);
                String when = DateUtils.getRelativeTimeSpanString(n.modified, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS).toString();
                if (n.pages > 1) when += "  ·  " + n.pages + " pages";
                Draw.text(c, when, r.left, r.bottom + t.px(42), r.width(), date);
            }
            int np = pages();
            if (np > 1) {
                float d = t.px(8), gap = t.px(12), x = (w - (np * d + (np - 1) * gap)) / 2f;
                for (int i = 0; i < np; i++) {
                    c.drawCircle(x + d / 2, h - t.px(34), d / 2, i == page ? dotOn : dotOff);
                    x += d + gap;
                }
            }
        }
    }
}
