package tech.tachion.inkios.games;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import tech.tachion.inkios.eink.Eink;
import tech.tachion.inkios.ui.Theme;

final class ChessView extends GameView {
    private static final String[] MODES = {"Computer · Easy", "Computer · Medium", "Computer · Hard", "Two players"};
    private static final String WHITE = " ♔♕♖♗♘♙", FILLED = " ♚♛♜♝♞♟";
    private static final int[] GLYPH = {0, 6, 5, 4, 3, 2, 1};

    private Chess game = new Chess();
    private int mode = 1;
    private final List<Integer> moves = new ArrayList<>();
    private int selected = -1;
    private List<Integer> legal = new ArrayList<>();
    private boolean thinking, over;
    private float sq;
    private final Paint light, dark, piece, outline, dot, ring, selP, lastP;

    ChessView(Activity a) {
        super(a);
        Typeface sym = GamesActivity.symbols(a);
        light = t.fill(Theme.PAPER);
        dark = t.fill(0xFFBBBBBB);
        piece = t.textPaint(sym, 40, Theme.INK);
        outline = t.textPaint(sym, 40, Theme.INK);
        dot = t.fill(Theme.INK);
        ring = t.stroke(Theme.INK, 3f);
        selP = t.stroke(Theme.INK, 4f);
        lastP = t.stroke(0xFF555555, 2f);
        load();
        refreshLegal();
        updateStatus();
        if (!over && vsComputer() && game.side == -1) main.post(this::computerMove);
    }

    @Override String title() { return "Chess"; }

    @Override String[] buttons() { return new String[]{"New game", "Undo"}; }

    @Override void onButton(int i) {
        if (thinking) return;
        if (i == 0) {
            choose("New game", MODES, mode, m -> {
                mode = m;
                game = new Chess();
                moves.clear();
                selected = -1;
                over = false;
                refreshLegal();
                updateStatus();
                invalidate();
                clean();
            });
        } else {
            undo();
        }
    }

    private boolean vsComputer() { return mode < 3; }

    private void undo() {
        if (moves.isEmpty()) return;
        int n = vsComputer() && moves.size() >= 2 && game.side == 1 ? 2 : 1;
        for (int k = 0; k < n && !moves.isEmpty(); k++) {
            game.unmake();
            moves.remove(moves.size() - 1);
        }
        selected = -1;
        over = false;
        refreshLegal();
        updateStatus();
        refreshRect(board, Eink.Mode.TEXT);
    }

    private void refreshLegal() { legal = game.legal(); }

    private void updateStatus() {
        String who = game.side == 1 ? "White" : "Black";
        if (legal.isEmpty()) {
            over = true;
            if (game.inCheck(game.side)) {
                String winner = game.side == 1 ? "Black" : "White";
                status = vsComputer() ? (game.side == 1 ? "Checkmate. The computer wins." : "Checkmate. You win!")
                        : "Checkmate. " + winner + " wins.";
            } else {
                status = "Stalemate. It’s a draw.";
            }
        } else if (thinking) {
            status = "Thinking…";
        } else {
            String base = vsComputer() ? (game.side == 1 ? "Your move" : "Computer’s move") : who + " to move";
            status = base + (game.inCheck(game.side) ? " · Check" : "") + "  ·  " + MODES[mode];
        }
    }

    @Override
    void layoutBoard() {
        sq = board.width() / 8f;
        piece.setTextSize(sq * 0.82f);
        outline.setTextSize(sq * 0.82f);
        for (int i = 0; i < 64; i++) {
            final int s = i;
            RectF r = sqR(i);
            target(r.left, r.top, r.right, r.bottom, 0, () -> tap(s)).highlight = false;
        }
    }

    private RectF sqR(int i) {
        float l = board.left + (i % 8) * sq, tp = board.top + (i / 8) * sq;
        return new RectF(l, tp, l + sq, tp + sq);
    }

    private void tap(int s) {
        if (thinking || over) return;
        if (vsComputer() && game.side != 1) return;
        if (selected >= 0) {
            for (int m : legal) {
                if (Chess.from(m) == selected && Chess.to(m) == s && (Chess.promo(m) == 0 || Chess.promo(m) == Chess.Q)) {
                    play(m);
                    return;
                }
            }
        }
        int old = selected;
        selected = game.b[s] * game.side > 0 ? s : -1;
        if (old != selected) refreshRect(board, Eink.Mode.FAST);
    }

    private void play(int m) {
        game.make(m);
        moves.add(m);
        selected = -1;
        refreshLegal();
        updateStatus();
        refreshRect(board, Eink.Mode.FAST);
        refreshRect(statusR, Eink.Mode.TEXT);
        if (!over && vsComputer() && game.side == -1) computerMove();
    }

    private void computerMove() {
        thinking = true;
        updateStatus();
        refreshRect(statusR, Eink.Mode.TEXT);
        final int level = mode;
        new Thread(() -> {
            final int m = game.best(level, new Random());
            main.post(() -> {
                thinking = false;
                if (m >= 0) {
                    game.make(m);
                    moves.add(m);
                }
                refreshLegal();
                updateStatus();
                refreshRect(board, Eink.Mode.FAST);
                refreshRect(statusR, Eink.Mode.TEXT);
            });
        }).start();
    }

    @Override
    void drawBoard(Canvas c) {
        int last = game.lastMove();
        for (int i = 0; i < 64; i++) {
            RectF r = sqR(i);
            c.drawRect(r, ((i / 8 + i % 8) % 2 == 0) ? light : dark);
            if (last >= 0 && (Chess.from(last) == i || Chess.to(last) == i)) {
                c.drawRect(r.left + t.px(2), r.top + t.px(2), r.right - t.px(2), r.bottom - t.px(2), lastP);
            }
            int p = game.b[i];
            if (p != 0) drawPiece(c, p, r);
        }
        if (selected >= 0) {
            RectF r = sqR(selected);
            c.drawRect(r.left + t.px(2.5f), r.top + t.px(2.5f), r.right - t.px(2.5f), r.bottom - t.px(2.5f), selP);
            for (int m : legal) {
                if (Chess.from(m) != selected) continue;
                RectF d = sqR(Chess.to(m));
                if (game.b[Chess.to(m)] != 0) c.drawCircle(d.centerX(), d.centerY(), sq * 0.44f, ring);
                else c.drawCircle(d.centerX(), d.centerY(), sq * 0.13f, dot);
            }
        }
        c.drawRect(board, t.stroke(Theme.INK, 2f));
    }

    private void drawPiece(Canvas c, int p, RectF r) {
        int idx = GLYPH[Math.abs(p)];
        String filled = FILLED.substring(idx, idx + 1);
        float x = r.centerX() - piece.measureText(filled) / 2, y = r.centerY() + piece.getTextSize() * 0.34f;
        if (p > 0) {
            piece.setColor(Theme.PAPER);
            c.drawText(filled, x, y, piece);
            piece.setColor(Theme.INK);
            String hollow = WHITE.substring(idx, idx + 1);
            c.drawText(hollow, r.centerX() - outline.measureText(hollow) / 2, y, outline);
        } else {
            c.drawText(filled, x, y, piece);
        }
    }

    @Override
    void save() {
        StringBuilder sb = new StringBuilder().append(mode);
        for (int m : moves) sb.append(',').append(m);
        prefs.edit().putString("chess", sb.toString()).apply();
    }

    private void load() {
        String s = prefs.getString("chess", null);
        if (s == null) return;
        try {
            String[] p = s.split(",");
            mode = Integer.parseInt(p[0]);
            for (int i = 1; i < p.length; i++) {
                int m = Integer.parseInt(p[i]);
                game.make(m);
                moves.add(m);
            }
        } catch (Exception e) {
            game = new Chess();
            moves.clear();
        }
    }
}
