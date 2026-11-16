package tech.tachion.inkios.games;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Theme;

final class GoView extends GameView {
    private static final int N = 9, EMPTY = 0, BLACK = 1, WHITE = 2;
    private static final float KOMI = 6.5f;
    private static final String[] MODES = {"Computer", "Two players"};

    private int[] b = new int[N * N];
    private int turn = BLACK, passes, mode, koPoint = -1, lastMove = -1;
    private int capturedByBlack, capturedByWhite;
    private boolean over, thinking;
    private final List<int[]> history = new ArrayList<>();
    private float cell;
    private final Paint line, star, blackStone, whiteStone, whiteEdge, mark;
    private final Random rnd = new Random();

    GoView(Activity a) {
        super(a);
        line = t.stroke(Theme.INK, 1.5f);
        star = t.fill(Theme.INK);
        blackStone = t.fill(Theme.INK);
        whiteStone = t.fill(Theme.PAPER);
        whiteEdge = t.stroke(Theme.INK, 2f);
        mark = t.stroke(0xFF888888, 2.5f);
        load();
        updateStatus();
    }

    @Override String title() { return "Go"; }

    @Override String[] buttons() { return new String[]{"New", "Pass", "Undo"}; }

    @Override void onButton(int i) {
        if (thinking) return;
        if (i == 0) {
            choose("New game", MODES, mode, m -> {
                mode = m;
                b = new int[N * N];
                turn = BLACK;
                passes = 0;
                koPoint = lastMove = -1;
                capturedByBlack = capturedByWhite = 0;
                over = false;
                history.clear();
                updateStatus();
                invalidate();
                clean();
            });
        } else if (i == 1) {
            if (!over) pass();
        } else {
            undo();
        }
    }

    private int[] neighbours(int p) {
        int r = p / N, c = p % N;
        int[] out = new int[4];
        int k = 0;
        if (r > 0) out[k++] = p - N;
        if (r < N - 1) out[k++] = p + N;
        if (c > 0) out[k++] = p - 1;
        if (c < N - 1) out[k++] = p + 1;
        return Arrays.copyOf(out, k);
    }

    private int liberties(int[] board, int p, boolean[] group) {
        int colour = board[p];
        boolean[] seen = new boolean[N * N], libs = new boolean[N * N];
        int[] stack = new int[N * N];
        int sp = 0, count = 0;
        stack[sp++] = p;
        seen[p] = true;
        while (sp > 0) {
            int q = stack[--sp];
            if (group != null) group[q] = true;
            for (int n : neighbours(q)) {
                if (board[n] == EMPTY && !libs[n]) { libs[n] = true; count++; }
                else if (board[n] == colour && !seen[n]) { seen[n] = true; stack[sp++] = n; }
            }
        }
        return count;
    }

    private int[] tryPlay(int[] board, int p, int colour, int ko, int[] caps) {
        if (board[p] != EMPTY || p == ko) return null;
        int[] nb = board.clone();
        nb[p] = colour;
        int opp = 3 - colour, captured = 0;
        for (int n : neighbours(p)) {
            if (nb[n] == opp) {
                boolean[] g = new boolean[N * N];
                if (liberties(nb, n, g) == 0) {
                    for (int q = 0; q < N * N; q++) if (g[q]) { nb[q] = EMPTY; captured++; }
                }
            }
        }
        if (liberties(nb, p, null) == 0) return null;
        if (caps != null) caps[0] = captured;
        return nb;
    }

    private void play(int p) {
        int[] caps = new int[1];
        int[] nb = tryPlay(b, p, turn, koPoint, caps);
        if (nb == null) return;
        snapshot();
        koPoint = -1;
        if (caps[0] == 1) {
            for (int n : neighbours(p)) if (b[n] == 3 - turn && nb[n] == EMPTY && liberties(nb, p, null) == 1) koPoint = n;
        }
        if (turn == BLACK) capturedByBlack += caps[0]; else capturedByWhite += caps[0];
        b = nb;
        lastMove = p;
        passes = 0;
        turn = 3 - turn;
        afterMove();
    }

    private void pass() {
        snapshot();
        passes++;
        koPoint = -1;
        lastMove = -1;
        turn = 3 - turn;
        if (passes >= 2) over = true;
        afterMove();
    }

    private void afterMove() {
        updateStatus();
        refreshRect(board, Eink.Mode.FAST);
        refreshRect(statusR, Eink.Mode.TEXT);
        if (!over && mode == 0 && turn == WHITE) computerMove();
    }

    private void snapshot() {
        int[] s = Arrays.copyOf(b, N * N + 6);
        s[N * N] = turn; s[N * N + 1] = passes; s[N * N + 2] = koPoint;
        s[N * N + 3] = capturedByBlack; s[N * N + 4] = capturedByWhite; s[N * N + 5] = lastMove;
        history.add(s);
    }

    private void undo() {
        if (history.isEmpty()) return;
        int steps = mode == 0 && history.size() >= 2 && turn == BLACK ? 2 : 1;
        int[] s = null;
        for (int k = 0; k < steps && !history.isEmpty(); k++) s = history.remove(history.size() - 1);
        b = Arrays.copyOf(s, N * N);
        turn = s[N * N]; passes = s[N * N + 1]; koPoint = s[N * N + 2];
        capturedByBlack = s[N * N + 3]; capturedByWhite = s[N * N + 4]; lastMove = s[N * N + 5];
        over = false;
        updateStatus();
        refreshRect(board, Eink.Mode.TEXT);
    }

    private float[] score() {
        float black = 0, white = KOMI;
        boolean[] seen = new boolean[N * N];
        for (int p = 0; p < N * N; p++) {
            if (b[p] == BLACK) black++;
            else if (b[p] == WHITE) white++;
            else if (!seen[p]) {
                List<Integer> region = new ArrayList<>();
                boolean touchB = false, touchW = false;
                int[] stack = new int[N * N];
                int sp = 0;
                stack[sp++] = p;
                seen[p] = true;
                while (sp > 0) {
                    int q = stack[--sp];
                    region.add(q);
                    for (int n : neighbours(q)) {
                        if (b[n] == EMPTY && !seen[n]) { seen[n] = true; stack[sp++] = n; }
                        else if (b[n] == BLACK) touchB = true;
                        else if (b[n] == WHITE) touchW = true;
                    }
                }
                if (touchB && !touchW) black += region.size();
                if (touchW && !touchB) white += region.size();
            }
        }
        return new float[]{black, white};
    }

    private void updateStatus() {
        if (over) {
            float[] s = score();
            String winner = s[0] > s[1] ? "Black" : "White";
            status = "Game over · Black " + fmt(s[0]) + ", White " + fmt(s[1]) + " · " + winner + " wins";
        } else if (thinking) {
            status = "Thinking…";
        } else {
            String who = turn == BLACK ? "Black" : "White";
            String base = mode == 0 ? (turn == BLACK ? "Your move (Black)" : "Computer’s move") : who + " to move";
            status = base + (passes == 1 ? " · opponent passed" : "") + "  ·  captured " + capturedByBlack + " / " + capturedByWhite;
        }
    }

    private static String fmt(float f) { return f == (int) f ? String.valueOf((int) f) : String.valueOf(f); }

    private void computerMove() {
        thinking = true;
        updateStatus();
        refreshRect(statusR, Eink.Mode.TEXT);
        final int[] board = b.clone();
        final int ko = koPoint;
        new Thread(() -> {
            int best = -1;
            float bestScore = 0.5f;
            for (int p = 0; p < N * N; p++) {
                int[] caps = new int[1];
                int[] nb = tryPlay(board, p, WHITE, ko, caps);
                if (nb == null || ownEye(board, p, WHITE)) continue;
                float s = caps[0] * 12;
                int libs = liberties(nb, p, null);
                s += Math.min(libs, 4) * 1.5f;
                if (libs == 1) s -= 10;
                for (int n : neighbours(p)) {
                    if (board[n] == WHITE && liberties(board, n, null) == 1) s += 9;
                    if (board[n] == BLACK && liberties(nb, n, null) == 1) s += 4;
                    if (board[n] != EMPTY) s += 1;
                }
                int r = p / N, c = p % N;
                if (r == 0 || r == N - 1 || c == 0 || c == N - 1) s -= 2;
                if ((r == 2 || r == 6) && (c == 2 || c == 6)) s += 2;
                s += rnd.nextFloat() * 1.5f;
                if (s > bestScore) { bestScore = s; best = p; }
            }
            final int move = best;
            main.post(() -> {
                thinking = false;
                if (move >= 0) play(move); else pass();
            });
        }).start();
    }

    private boolean ownEye(int[] board, int p, int colour) {
        for (int n : neighbours(p)) if (board[n] != colour) return false;
        return true;
    }

    @Override
    void layoutBoard() {
        cell = board.width() / N;
        for (int p = 0; p < N * N; p++) {
            final int q = p;
            RectF r = ptR(p);
            target(r.left, r.top, r.right, r.bottom, 0, () -> {
                if (over || thinking || (mode == 0 && turn != BLACK)) return;
                play(q);
            }).highlight = false;
        }
    }

    private RectF ptR(int p) {
        float l = board.left + (p % N) * cell, tp = board.top + (p / N) * cell;
        return new RectF(l, tp, l + cell, tp + cell);
    }

    @Override
    void drawBoard(Canvas c) {
        c.drawRect(board, t.fill(Theme.FILL));
        float h = cell / 2;
        for (int k = 0; k < N; k++) {
            c.drawLine(board.left + h, board.top + h + k * cell, board.right - h, board.top + h + k * cell, line);
            c.drawLine(board.left + h + k * cell, board.top + h, board.left + h + k * cell, board.bottom - h, line);
        }
        for (int p : new int[]{20, 24, 40, 56, 60}) {
            RectF r = ptR(p);
            c.drawCircle(r.centerX(), r.centerY(), t.px(3.5f), star);
        }
        float sr = cell * 0.46f;
        for (int p = 0; p < N * N; p++) {
            if (b[p] == EMPTY) continue;
            RectF r = ptR(p);
            if (b[p] == BLACK) {
                c.drawCircle(r.centerX(), r.centerY(), sr, blackStone);
            } else {
                c.drawCircle(r.centerX(), r.centerY(), sr, whiteStone);
                c.drawCircle(r.centerX(), r.centerY(), sr - t.px(1), whiteEdge);
            }
            if (p == lastMove) {
                Paint m = t.stroke(b[p] == BLACK ? Theme.PAPER : Theme.INK, 2.5f);
                c.drawCircle(r.centerX(), r.centerY(), sr * 0.4f, m);
            }
        }
        if (koPoint >= 0) {
            RectF r = ptR(koPoint);
            c.drawRect(r.centerX() - cell * 0.15f, r.centerY() - cell * 0.15f, r.centerX() + cell * 0.15f,
                    r.centerY() + cell * 0.15f, mark);
        }
    }

    @Override
    void save() {
        StringBuilder sb = new StringBuilder();
        sb.append(mode).append('|').append(turn).append('|').append(passes).append('|').append(koPoint).append('|')
                .append(capturedByBlack).append('|').append(capturedByWhite).append('|').append(over ? 1 : 0).append('|');
        for (int v : b) sb.append(v);
        prefs.edit().putString("go", sb.toString()).apply();
    }

    private void load() {
        String s = prefs.getString("go", null);
        if (s == null) return;
        try {
            String[] p = s.split("\\|");
            mode = Integer.parseInt(p[0]);
            turn = Integer.parseInt(p[1]);
            passes = Integer.parseInt(p[2]);
            koPoint = Integer.parseInt(p[3]);
            capturedByBlack = Integer.parseInt(p[4]);
            capturedByWhite = Integer.parseInt(p[5]);
            over = p[6].equals("1");
            for (int i = 0; i < N * N; i++) b[i] = p[7].charAt(i) - '0';
            if (!over && mode == 0 && turn == WHITE) main.post(this::computerMove);
        } catch (Exception e) {
            b = new int[N * N];
            turn = BLACK;
        }
    }
}
