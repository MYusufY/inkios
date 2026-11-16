package tech.tachion.inkios.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.TapView;
import tech.tachion.inkios.ui.Theme;

public final class EditorActivity extends Activity implements InkView.Listener {
    public static final String EXTRA_DIR = "dir";

    private NoteStore.Note note;
    private int pageIndex;
    private InkView ink;
    private Toolbar toolbar;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean dirty;

    private final Runnable autosave = this::save;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);

        note = NoteStore.load(new File(getIntent().getStringExtra(EXTRA_DIR)));
        if (note == null) { finish(); return; }

        Theme t = Theme.get(this);
        Page.setTypefaces(t.text, t.textBold);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        toolbar = new Toolbar(this);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, t.pxi(Toolbar.HEIGHT_PT)));
        View rule = new View(this);
        rule.setBackgroundColor(Theme.SEPARATOR);
        root.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, t.pxi(1))));
        ink = new InkView(this);
        ink.setListener(this);
        root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        showPage(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Eink.fullRefreshSoon(ink, 80);
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    private void showPage(int index) {
        pageIndex = index;
        ink.setPage(NoteStore.loadPage(note, index), note.template);
        toolbar.invalidate();
    }

    private void goToPage(int index) {
        save();
        if (index >= note.pages) {
            note.pages = index + 1;
            NoteStore.saveMeta(note);
        }
        showPage(Math.max(0, index));
    }

    private void save() {
        main.removeCallbacks(autosave);
        if (!dirty || note == null) return;
        dirty = false;
        NoteStore.savePage(note, pageIndex, ink.page());
        NoteStore.saveThumb(note, pageIndex == 0 ? ink.page() : NoteStore.loadPage(note, 0));
    }

    @Override
    public void onPageChanged() {
        dirty = true;
        toolbar.refreshTools();
        main.removeCallbacks(autosave);
        main.postDelayed(autosave, 2500);
    }

    @Override
    public void onTextTap(final float x, final float y, final Page.Text existing) {
        Theme t = Theme.get(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = t.pxi(14);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setMinLines(3);
        field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        field.setTypeface(t.text);
        final android.widget.CheckBox bold = new android.widget.CheckBox(this);
        bold.setText("Bold");
        bold.setTypeface(t.textBold);
        if (existing != null) {
            field.setText(existing.text);
            field.setSelection(field.getText().length());
            bold.setChecked(existing.bold);
        }
        bold.setOnCheckedChangeListener((b, on) -> field.setTypeface(on ? t.textBold : t.text));
        field.setTypeface(bold.isChecked() ? t.textBold : t.text);
        box.addView(field);
        box.addView(bold);
        AlertDialog.Builder dlg = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle(existing == null ? "Add text" : "Edit text")
                .setView(box)
                .setPositiveButton(existing == null ? "Add" : "Save", (d, w) -> {
                    String text = field.getText().toString();
                    if (existing == null) ink.addText(x, y, text, bold.isChecked());
                    else ink.replaceText(existing, text, bold.isChecked());
                    toolbar.refreshTools();
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) {
            dlg.setNeutralButton("Delete", (d, w) -> {
                ink.replaceText(existing, "", false);
                toolbar.refreshTools();
            });
        }
        AlertDialog shown = dlg.create();
        shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        shown.show();
    }

    @Override
    public void onTwoFingerTap() {
        ink.undo();
        toolbar.refreshTools();
    }

    private void showMenu() {
        String[] items = {"Page style…", "Rename…", "Clear page", "Delete page"};
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: chooseTemplate(); break;
                        case 1: rename(); break;
                        case 2: ink.clear(); break;
                        case 3: deletePage(); break;
                        default:
                    }
                }).show();
    }

    private void chooseTemplate() {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle("Page style")
                .setSingleChoiceItems(Page.TEMPLATE_NAMES, note.template, (d, which) -> {
                    note.template = which;
                    NoteStore.saveMeta(note);
                    ink.setTemplate(which);
                    dirty = true;
                    save();
                    d.dismiss();
                }).show();
    }

    private void rename() {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(note.title);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_LIGHT)
                .setTitle("Rename note")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    note.title = field.getText().toString().trim();
                    NoteStore.saveMeta(note);
                    toolbar.invalidate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deletePage() {
        if (note.pages <= 1) {
            ink.clear();
            return;
        }
        dirty = false;
        NoteStore.deletePage(note, pageIndex);
        showPage(Math.min(pageIndex, note.pages - 1));
        NoteStore.saveThumb(note, NoteStore.loadPage(note, 0));
    }

    static String displayTitle(NoteStore.Note n) {
        if (n.title != null && !n.title.isEmpty()) return n.title;
        return new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(new Date(n.created));
    }

    final class Toolbar extends TapView {
        static final float HEIGHT_PT = 58f;
        private final Paint back, pageP, ink1, line;
        private final RectF[] toolR = new RectF[3];
        private final RectF widthR = new RectF(), undoR = new RectF(), redoR = new RectF(), moreR = new RectF(),
                prevR = new RectF(), nextR = new RectF(), backR = new RectF(), toolsR = new RectF();

        Toolbar(Context c) {
            super(c);
            back = t.textPaint(t.textSemibold, 18, Theme.INK);
            pageP = t.textPaint(t.textMedium, 15, Theme.SECONDARY);
            ink1 = t.fill(Theme.INK);
            line = t.stroke(Theme.INK, 2.2f);
            for (int i = 0; i < 3; i++) toolR[i] = new RectF();
        }

        void refreshTools() {
            refreshRect(toolsR, Eink.Mode.FAST);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            targets.clear();
            float m = t.px(12), b = t.px(42), cy = h / 2f, gap = t.px(2);
            backR.set(m, cy - b / 2, m + t.px(84), cy + b / 2);
            target(backR.left, backR.top, backR.right, backR.bottom, t.px(12), EditorActivity.this::finish);

            float x = w - m - b;
            moreR.set(x, cy - b / 2, x + b, cy + b / 2);
            target(moreR.left, moreR.top, moreR.right, moreR.bottom, t.px(12), EditorActivity.this::showMenu);
            x -= b + t.px(10);
            redoR.set(x, cy - b / 2, x + b, cy + b / 2);
            target(redoR.left, redoR.top, redoR.right, redoR.bottom, t.px(12), () -> { ink.redo(); refreshTools(); });
            x -= b + gap;
            undoR.set(x, cy - b / 2, x + b, cy + b / 2);
            target(undoR.left, undoR.top, undoR.right, undoR.bottom, t.px(12), () -> { ink.undo(); refreshTools(); });
            x -= t.px(10);
            x -= b;
            widthR.set(x, cy - b / 2, x + b, cy + b / 2);
            target(widthR.left, widthR.top, widthR.right, widthR.bottom, t.px(12), () -> {
                ink.setPenSize((ink.penSize() + 1) % InkView.PEN_WIDTHS.length);
                ink.setTool(InkView.TOOL_PEN);
                refreshTools();
            });
            x -= gap;
            for (int i = 2; i >= 0; i--) {
                x -= b;
                toolR[i].set(x, cy - b / 2, x + b, cy + b / 2);
                final int tool = i;
                Target tg = target(x, cy - b / 2, x + b, cy + b / 2, t.px(12), () -> selectTool(tool));
                tg.highlight = false;
                x -= gap;
            }
            toolsR.set(toolR[0].left - 4, 0, moreR.left, h);

            float mid = (backR.right + toolR[0].left) / 2f, half = t.px(56);
            prevR.set(mid - half, cy - b / 2, mid - half + t.px(34), cy + b / 2);
            nextR.set(mid + half - t.px(34), cy - b / 2, mid + half, cy + b / 2);
            target(prevR.left, prevR.top, prevR.right, prevR.bottom, t.px(12), () -> {
                if (pageIndex > 0) goToPage(pageIndex - 1);
            });
            target(nextR.left, nextR.top, nextR.right, nextR.bottom, t.px(12), () -> goToPage(pageIndex + 1));
        }

        private void selectTool(int tool) {
            ink.setTool(tool);
            refreshTools();
        }

        @Override
        protected void drawContent(Canvas c) {
            int h = getHeight();
            float cy = h / 2f;
            Path chev = new Path();
            float bx = backR.left + t.px(8);
            chev.moveTo(bx + t.px(8), cy - t.px(9));
            chev.lineTo(bx, cy);
            chev.lineTo(bx + t.px(8), cy + t.px(9));
            c.drawPath(chev, line);
            c.drawText("Notes", bx + t.px(16), cy + t.px(6), back);

            Draw.chevron(c, prevR.centerX(), cy, t.px(16), -1, pageIndex > 0 ? line : t.stroke(Theme.SEPARATOR, 2.2f));
            String pg = (pageIndex + 1) + " / " + Math.max(note.pages, pageIndex + 1);
            float pw = pageP.measureText(pg);
            c.drawText(pg, (prevR.right + nextR.left - pw) / 2f, cy + t.px(5), pageP);
            if (pageIndex >= note.pages - 1) {
                float s = t.px(8), x = nextR.centerX();
                c.drawLine(x - s, cy, x + s, cy, line);
                c.drawLine(x, cy - s, x, cy + s, line);
            } else {
                Draw.chevron(c, nextR.centerX(), cy, t.px(16), 1, line);
            }

            for (int i = 0; i < 3; i++) {
                RectF r = toolR[i];
                boolean sel = ink.tool() == i;
                if (sel) Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(12), ink1);
                drawTool(c, i, r, sel);
            }
            float lw = t.px(1.8f + ink.penSize() * 2.2f);
            Paint wp = t.stroke(Theme.INK, 1f);
            wp.setStrokeWidth(lw);
            c.drawLine(widthR.left + t.px(10), widthR.centerY() - t.px(4), widthR.right - t.px(10),
                    widthR.centerY() - t.px(4), wp);
            Paint wl = t.textPaint(t.textMedium, 10, Theme.SECONDARY);
            String wn = InkView.PEN_NAMES[ink.penSize()];
            c.drawText(wn, widthR.centerX() - wl.measureText(wn) / 2, widthR.bottom - t.px(5), wl);
            drawUndo(c, undoR, false, ink.canUndo());
            drawUndo(c, redoR, true, ink.canRedo());
            float d = t.px(2.6f);
            for (int i = -1; i <= 1; i++) c.drawCircle(moreR.centerX() + i * t.px(8), cy, d, ink1);
        }

        private void drawTool(Canvas c, int tool, RectF r, boolean sel) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(sel ? Theme.PAPER : Theme.INK);
            float cx = r.centerX(), cy = r.centerY(), s = r.width() * 0.28f;
            if (tool == InkView.TOOL_ERASER) {
                p.setStrokeWidth(t.px(2));
                c.save();
                c.rotate(-35, cx, cy);
                Draw.roundRect(c, cx - s * 1.1f, cy - s * 0.55f, cx + s * 1.1f, cy + s * 0.55f, s * 0.25f, p);
                c.drawLine(cx - s * 0.2f, cy - s * 0.55f, cx - s * 0.2f, cy + s * 0.55f, p);
                c.restore();
                return;
            }
            if (tool == InkView.TOOL_TEXT) {
                Paint tp = t.textPaint(t.displaySemibold, 21, sel ? Theme.PAPER : Theme.INK);
                String aa = "Aa";
                c.drawText(aa, cx - tp.measureText(aa) / 2, cy + t.px(7.5f), tp);
                return;
            }
            p.setStrokeWidth(t.px(2.6f));
            Path q = new Path();
            q.moveTo(cx - s, cy + s * 0.3f);
            q.cubicTo(cx - s * 0.4f, cy - s, cx + s * 0.1f, cy + s, cx + s, cy - s * 0.3f);
            c.drawPath(q, p);
        }

        private void drawUndo(Canvas c, RectF r, boolean mirror, boolean enabled) {
            Paint p = t.stroke(enabled ? Theme.INK : Theme.SEPARATOR, 2.2f);
            float cx = r.centerX(), cy = r.centerY(), s = r.width() * 0.22f;
            c.save();
            if (mirror) c.scale(-1, 1, cx, cy);
            RectF arc = new RectF(cx - s, cy - s * 0.8f, cx + s * 1.2f, cy + s * 1.2f);
            c.drawArc(arc, 180, 200, false, p);
            Path head = new Path();
            head.moveTo(cx - s - s * 0.55f, cy - s * 0.15f);
            head.lineTo(cx - s, cy + s * 0.45f);
            head.lineTo(cx - s + s * 0.6f, cy - s * 0.05f);
            c.drawPath(head, p);
            c.restore();
        }
    }
}
