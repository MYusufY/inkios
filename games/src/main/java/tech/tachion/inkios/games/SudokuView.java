package tech.tachion.inkios.games;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayDeque;
import java.util.Random;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Draw;
import tech.tachion.inkios.ui.Theme;

final class SudokuView extends GameView {
    private Sudoku puzzle;
    private int level = 1;
    private final int[] cells = new int[81];
    private final int[] notes = new int[81];
    private int selected = -1;
    private boolean notesMode, solved, generating;
    private final ArrayDeque<int[]> history = new ArrayDeque<>();

    private float cell;
    private final RectF[] keys = new RectF[12];
    private final Paint given, entry, small, keyText, keyTextInv, thin, thick, sel, same, ink;

    SudokuView(Activity a) {
        super(a);
        given = t.textPaint(t.displaySemibold, 26, Theme.INK);
        entry = t.textPaint(t.text, 26, Theme.INK);
        small = t.textPaint(t.textMedium, 10, Theme.SECONDARY);
        keyText = t.textPaint(t.displaySemibold, 26, Theme.INK);
        keyTextInv = t.textPaint(t.textSemibold, 15, Theme.PAPER);
        thin = t.stroke(0xFF888888, 1f);
        thick = t.stroke(Theme.INK, 2.4f);
        sel = t.fill(Theme.GROUPED);
        same = t.fill(Theme.FILL);
        ink = t.fill(Theme.INK);
        for (int i = 0; i < keys.length; i++) keys[i] = new RectF();
        if (!load()) newGame(1);
    }

    @Override String title() { return "Sudoku"; }

    @Override String[] buttons() { return new String[]{"New game", "Undo"}; }

    @Override void onButton(int i) {
        if (i == 0) choose("New game", Sudoku.LEVELS, level, this::newGame);
        else undo();
    }

    @Override float footerPt() { return 150; }

    private void newGame(final int lvl) {
        level = lvl;
        generating = true;
        setStatus("Making a " + Sudoku.LEVELS[lvl].toLowerCase() + " puzzle…");
        new Thread(() -> {
            final Sudoku p = Sudoku.generate(lvl, new Random());
            main.post(() -> {
                puzzle = p;
                System.arraycopy(p.givens, 0, cells, 0, 81);
                java.util.Arrays.fill(notes, 0);
                history.clear();
                selected = -1;
                solved = false;
                generating = false;
                updateStatus();
                invalidate();
                clean();
            });
        }).start();
    }

    private void updateStatus() {
        if (puzzle == null) return;
        int empty = 0;
        for (int v : cells) if (v == 0) empty++;
        status = solved ? "Solved. Beautifully done." : Sudoku.LEVELS[level] + "  ·  " + empty + " to go"
                + (notesMode ? "  ·  Notes on" : "");
    }

    @Override
    void layoutBoard() {
        cell = board.width() / 9f;
        for (int i = 0; i < 81; i++) {
            final int idx = i;
            RectF r = cellR(i);
            target(r.left, r.top, r.right, r.bottom, 0, () -> select(idx)).highlight = false;
        }
        float gap = t.px(6);
        float kw = (footer.width() - gap * 8) / 9f, kh = t.px(60);
        for (int d = 0; d < 9; d++) {
            float l = footer.left + d * (kw + gap);
            keys[d].set(l, footer.top + t.px(6), l + kw, footer.top + t.px(6) + kh);
        }
        float aw = (footer.width() - gap * 2) / 3f, at = keys[0].bottom + t.px(12);
        for (int k = 0; k < 3; k++) {
            float l = footer.left + k * (aw + gap);
            keys[9 + k].set(l, at, l + aw, at + t.px(46));
        }
        for (int k = 0; k < keys.length; k++) {
            final int key = k;
            target(keys[k].left, keys[k].top, keys[k].right, keys[k].bottom, t.px(12), () -> press(key));
        }
    }

    private RectF cellR(int i) {
        float l = board.left + (i % 9) * cell, tp = board.top + (i / 9) * cell;
        return new RectF(l, tp, l + cell, tp + cell);
    }

    private void select(int i) {
        if (puzzle == null || generating) return;
        selected = i;
        refreshRect(board, Eink.Mode.TEXT);
    }

    private void press(int key) {
        if (puzzle == null || generating || solved) return;
        if (key == 9) {
            notesMode = !notesMode;
            updateStatus();
            refreshRect(footer, Eink.Mode.FAST);
            refreshRect(statusR, Eink.Mode.TEXT);
            return;
        }
        if (selected < 0 || puzzle.givens[selected] != 0) return;
        int i = selected;
        if (key == 10) {
            set(i, 0, 0);
        } else if (key == 11) {
            set(i, puzzle.solution[i], 0);
        } else {
            int d = key + 1;
            if (notesMode && cells[i] == 0) set(i, 0, notes[i] ^ (1 << d));
            else set(i, cells[i] == d ? 0 : d, 0);
        }
    }

    private void set(int i, int value, int noteBits) {
        history.push(new int[]{i, cells[i], notes[i]});
        cells[i] = value;
        notes[i] = noteBits;
        if (value != 0) clearNotesFor(i, value);
        boolean full = true;
        for (int k = 0; k < 81; k++) if (cells[k] != puzzle.solution[k]) { full = false; break; }
        solved = full;
        updateStatus();
        refreshRect(board, Eink.Mode.TEXT);
        refreshRect(statusR, Eink.Mode.TEXT);
        refreshRect(footer, Eink.Mode.FAST);
        if (solved) prefs.edit().putInt("sudoku_solved_" + level, prefs.getInt("sudoku_solved_" + level, 0) + 1).apply();
    }

    private void clearNotesFor(int i, int d) {
        int r = i / 9, c = i % 9, br = r / 3 * 3, bc = c / 3 * 3;
        for (int k = 0; k < 9; k++) {
            notes[r * 9 + k] &= ~(1 << d);
            notes[k * 9 + c] &= ~(1 << d);
            notes[(br + k / 3) * 9 + bc + k % 3] &= ~(1 << d);
        }
    }

    private void undo() {
        if (history.isEmpty()) return;
        int[] h = history.pop();
        cells[h[0]] = h[1];
        notes[h[0]] = h[2];
        solved = false;
        updateStatus();
        refreshRect(board, Eink.Mode.TEXT);
        refreshRect(statusR, Eink.Mode.TEXT);
    }

    @Override
    void drawBoard(Canvas c) {
        if (puzzle == null) return;
        int selValue = selected >= 0 ? cells[selected] : 0;
        for (int i = 0; i < 81; i++) {
            RectF r = cellR(i);
            if (i == selected) c.drawRect(r, sel);
            else if (selValue != 0 && cells[i] == selValue) c.drawRect(r, same);
            else if (selected >= 0 && peers(i, selected)) c.drawRect(r, same);
            int v = cells[i];
            if (v != 0) {
                Paint p = puzzle.givens[i] != 0 ? given : entry;
                String s = String.valueOf(v);
                c.drawText(s, r.centerX() - p.measureText(s) / 2, r.centerY() + p.getTextSize() * 0.36f, p);
                if (puzzle.givens[i] == 0 && conflicts(i)) {
                    c.drawRect(r.centerX() - cell * 0.22f, r.bottom - cell * 0.16f, r.centerX() + cell * 0.22f,
                            r.bottom - cell * 0.16f + t.px(2.5f), ink);
                }
            } else if (notes[i] != 0) {
                for (int d = 1; d <= 9; d++) {
                    if ((notes[i] & (1 << d)) == 0) continue;
                    float nx = r.left + ((d - 1) % 3 + 0.5f) * cell / 3, ny = r.top + ((d - 1) / 3 + 0.5f) * cell / 3;
                    String s = String.valueOf(d);
                    c.drawText(s, nx - small.measureText(s) / 2, ny + small.getTextSize() * 0.36f, small);
                }
            }
        }
        for (int k = 0; k <= 9; k++) {
            Paint p = k % 3 == 0 ? thick : thin;
            c.drawLine(board.left + k * cell, board.top, board.left + k * cell, board.bottom, p);
            c.drawLine(board.left, board.top + k * cell, board.right, board.top + k * cell, p);
        }
    }

    private static boolean peers(int a, int b) {
        int ra = a / 9, ca = a % 9, rb = b / 9, cb = b % 9;
        return ra == rb || ca == cb || (ra / 3 == rb / 3 && ca / 3 == cb / 3);
    }

    private boolean conflicts(int i) {
        int v = cells[i];
        for (int k = 0; k < 81; k++) if (k != i && cells[k] == v && peers(i, k)) return true;
        return false;
    }

    @Override
    void drawFooter(Canvas c, RectF area) {
        int[] placed = new int[10];
        for (int v : cells) placed[v]++;
        Paint edge = t.stroke(Theme.SEPARATOR, 1.5f);
        for (int d = 0; d < 9; d++) {
            RectF r = keys[d];
            boolean done = placed[d + 1] >= 9;
            Draw.roundRect(c, r.left, r.top, r.right, r.bottom, t.px(12), done ? t.fill(Theme.FILL) : edge);
            String s = String.valueOf(d + 1);
            Paint p = done ? t.textPaint(t.displaySemibold, 26, Theme.SEPARATOR) : keyText;
            c.drawText(s, r.centerX() - p.measureText(s) / 2, r.centerY() + t.px(9), p);
        }
        String[] labels = {"Notes", "Erase", "Hint"};
        for (int k = 0; k < 3; k++) {
            RectF r = keys[9 + k];
            boolean on = k == 0 && notesMode;
            Draw.roundRect(c, r.left, r.top, r.right, r.bottom, r.height() / 2, on ? ink : t.stroke(Theme.INK, 1.5f));
            Paint p = on ? keyTextInv : t.textPaint(t.textSemibold, 15, Theme.INK);
            c.drawText(labels[k], r.centerX() - p.measureText(labels[k]) / 2, r.centerY() + t.px(5.5f), p);
        }
    }

    @Override
    void save() {
        if (puzzle == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(level).append('|');
        for (int v : puzzle.solution) sb.append(v);
        sb.append('|');
        for (int v : puzzle.givens) sb.append(v);
        sb.append('|');
        for (int v : cells) sb.append(v);
        sb.append('|');
        for (int n : notes) sb.append(Integer.toHexString(n)).append(',');
        prefs.edit().putString("sudoku", sb.toString()).apply();
    }

    private boolean load() {
        String s = prefs.getString("sudoku", null);
        if (s == null) return false;
        try {
            String[] p = s.split("\\|");
            level = Integer.parseInt(p[0]);
            Sudoku z = new Sudoku();
            for (int i = 0; i < 81; i++) {
                z.solution[i] = p[1].charAt(i) - '0';
                z.givens[i] = p[2].charAt(i) - '0';
                cells[i] = p[3].charAt(i) - '0';
            }
            String[] n = p[4].split(",");
            for (int i = 0; i < 81; i++) notes[i] = Integer.parseInt(n[i], 16);
            puzzle = z;
            solved = true;
            for (int i = 0; i < 81; i++) if (cells[i] != z.solution[i]) { solved = false; break; }
            updateStatus();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
