package tech.tachion.inkios.games;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Theme;

final class ReversiView extends GameView {
    private static final int EMPTY = 0, BLACK = 1, WHITE = 2;
    private static final int[] DIRS = {-9, -8, -7, -1, 1, 7, 8, 9};
    private static final int[] WEIGHT = {
            100, -20, 10, 5, 5, 10, -20, 100,
            -20, -50, -2, -2, -2, -2, -50, -20,
            10, -2, 1, 1, 1, 1, -2, 10,
            5, -2, 1, 0, 0, 1, -2, 5,
            5, -2, 1, 0, 0, 1, -2, 5,
            10, -2, 1, 1, 1, 1, -2, 10,
            -20, -50, -2, -2, -2, -2, -50, -20,
            100, -20, 10, 5, 5, 10, -20, 100};

    private int[] b = new int[64];
    private int turn = BLACK, lastMove = -1;
    private boolean over, thinking;
    private final List<int[]> history = new ArrayList<>();
    private float sq;
    private final Paint green, line, black, white, whiteEdge, hint;

    ReversiView(Activity a) {
        super(a);
        green = t.fill(0xFFDDDDDD);
        line = t.stroke(Theme.INK, 1.5f);
        black = t.fill(Theme.INK);
        white = t.fill(Theme.PAPER);
        whiteEdge = t.stroke(Theme.INK, 2f);
        hint = t.fill(0xFF888888);
        if (!load()) reset();
        updateStatus();
    }

    private void reset() {
        b = new int[64];
        b[27] = b[36] = WHITE;
        b[28] = b[35] = BLACK;
        turn = BLACK;
        lastMove = -1;
        over = false;
        history.clear();
    }

    @Override String title() { return "Reversi"; }

    @Override String[] buttons() { return new String[]{"New game", "Undo"}; }

    @Override void onButton(int i) {
        if (thinking) return;
        if (i == 0) {
            confirm("New game", "Start over?", () -> {
                reset();
                updateStatus();
                invalidate();
                clean();
            });
        } else if (!history.isEmpty()) {
            int[] s = history.remove(history.size() - 1);
            b = Arrays.copyOf(s, 64);
            turn = BLACK;
            lastMove = -1;
            over = false;
            updateStatus();
            refreshRect(board, Eink.Mode.TEXT);
        }
    }

    private static boolean onBoard(int from, int to, int dir) {
        if (to < 0 || to >= 64) return false;
        int dc = Math.abs(to % 8 - from % 8);
        return dc <= 1;
    }

    private static List<Integer> flips(int[] board, int p, int colour) {
        List<Integer> out = new ArrayList<>();
        if (board[p] != EMPTY) return out;
        int opp = 3 - colour;
        for (int d : DIRS) {
            List<Integer> line = new ArrayList<>();
            int prev = p, q = p + d;
            while (onBoard(prev, q, d) && board[q] == opp) { line.add(q); prev = q; q += d; }
            if (!line.isEmpty() && onBoard(prev, q, d) && board[q] == colour) out.addAll(line);
        }
        return out;
    }

    private static List<Integer> moves(int[] board, int colour) {
        List<Integer> out = new ArrayList<>();
        for (int p = 0; p < 64; p++) if (!flips(board, p, colour).isEmpty()) out.add(p);
        return out;
    }

    private static int[] apply(int[] board, int p, int colour) {
        int[] nb = board.clone();
        for (int q : flips(board, p, colour)) nb[q] = colour;
        nb[p] = colour;
        return nb;
    }

    private int count(int colour) {
        int n = 0;
        for (int v : b) if (v == colour) n++;
        return n;
    }

    private void play(int p) {
        if (flips(b, p, turn).isEmpty()) return;
        if (turn == BLACK) history.add(b.clone());
        b = apply(b, p, turn);
        lastMove = p;
        advance();
    }

    private void advance() {
        int next = 3 - turn;
        if (!moves(b, next).isEmpty()) turn = next;
        else if (moves(b, turn).isEmpty()) over = true;
        updateStatus();
        refreshRect(board, Eink.Mode.FAST);
        refreshRect(statusR, Eink.Mode.TEXT);
        if (!over && turn == WHITE) computerMove();
    }

    private void updateStatus() {
        String score = "Black " + count(BLACK) + " · White " + count(WHITE);
        if (over) {
            int bl = count(BLACK), wh = count(WHITE);
            status = (bl > wh ? "You win! " : bl < wh ? "The computer wins. " : "A draw. ") + score;
        } else if (thinking) {
            status = "Thinking…  ·  " + score;
        } else {
            status = (turn == BLACK ? "Your move" : "Computer’s move") + "  ·  " + score;
        }
    }

    private void computerMove() {
        thinking = true;
        updateStatus();
        refreshRect(statusR, Eink.Mode.TEXT);
        final int[] board = b.clone();
        new Thread(() -> {
            int best = -1, bestScore = Integer.MIN_VALUE;
            for (int p : moves(board, WHITE)) {
                int s = -search(apply(board, p, WHITE), BLACK, 3, -1000000, 1000000);
                if (s > bestScore) { bestScore = s; best = p; }
            }
            final int move = best;
            main.post(() -> {
                thinking = false;
                if (move >= 0) play(move);
            });
        }).start();
    }

    private static int search(int[] board, int colour, int depth, int alpha, int beta) {
        List<Integer> ms = moves(board, colour);
        if (depth == 0 || (ms.isEmpty() && moves(board, 3 - colour).isEmpty())) return eval(board, colour);
        if (ms.isEmpty()) return -search(board, 3 - colour, depth - 1, -beta, -alpha);
        for (int p : ms) {
            int s = -search(apply(board, p, colour), 3 - colour, depth - 1, -beta, -alpha);
            if (s >= beta) return beta;
            if (s > alpha) alpha = s;
        }
        return alpha;
    }

    private static int eval(int[] board, int colour) {
        int s = 0;
        for (int p = 0; p < 64; p++) {
            if (board[p] == colour) s += WEIGHT[p];
            else if (board[p] == 3 - colour) s -= WEIGHT[p];
        }
        s += 5 * (moves(board, colour).size() - moves(board, 3 - colour).size());
        return s;
    }

    @Override
    void layoutBoard() {
        sq = board.width() / 8f;
        for (int p = 0; p < 64; p++) {
            final int q = p;
            RectF r = sqR(p);
            target(r.left, r.top, r.right, r.bottom, 0, () -> {
                if (!over && !thinking && turn == BLACK) play(q);
            }).highlight = false;
        }
    }

    private RectF sqR(int p) {
        float l = board.left + (p % 8) * sq, tp = board.top + (p / 8) * sq;
        return new RectF(l, tp, l + sq, tp + sq);
    }

    @Override
    void drawBoard(Canvas c) {
        c.drawRect(board, green);
        for (int k = 0; k <= 8; k++) {
            c.drawLine(board.left + k * sq, board.top, board.left + k * sq, board.bottom, line);
            c.drawLine(board.left, board.top + k * sq, board.right, board.top + k * sq, line);
        }
        float r0 = sq * 0.4f;
        for (int p = 0; p < 64; p++) {
            RectF r = sqR(p);
            if (b[p] == BLACK) c.drawCircle(r.centerX(), r.centerY(), r0, black);
            else if (b[p] == WHITE) {
                c.drawCircle(r.centerX(), r.centerY(), r0, white);
                c.drawCircle(r.centerX(), r.centerY(), r0 - t.px(1), whiteEdge);
            }
            if (p == lastMove) {
                Paint m = t.fill(b[p] == BLACK ? Theme.PAPER : Theme.INK);
                c.drawCircle(r.centerX(), r.centerY(), t.px(3.5f), m);
            }
        }
        if (!over && !thinking && turn == BLACK) {
            for (int p : moves(b, BLACK)) {
                RectF r = sqR(p);
                c.drawCircle(r.centerX(), r.centerY(), t.px(4), hint);
            }
        }
    }

    @Override
    void save() {
        StringBuilder sb = new StringBuilder().append(turn).append('|').append(over ? 1 : 0).append('|');
        for (int v : b) sb.append(v);
        prefs.edit().putString("reversi", sb.toString()).apply();
    }

    private boolean load() {
        String s = prefs.getString("reversi", null);
        if (s == null) return false;
        try {
            String[] p = s.split("\\|");
            turn = Integer.parseInt(p[0]);
            over = p[1].equals("1");
            for (int i = 0; i < 64; i++) b[i] = p[2].charAt(i) - '0';
            if (!over && turn == WHITE) main.post(this::computerMove);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
