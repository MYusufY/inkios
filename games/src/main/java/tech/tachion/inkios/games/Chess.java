package tech.tachion.inkios.games;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class Chess {
    static final int P = 1, N = 2, B = 3, R = 4, Q = 5, K = 6;
    static final int F_EP = 1, F_CASTLE = 2, F_DOUBLE = 4;

    final int[] b = new int[64];
    int side = 1;
    int castle = 15;
    int ep = -1;

    private final int[] undoCap = new int[1024], undoCastle = new int[1024], undoEp = new int[1024], undoMove = new int[1024];
    private int ply;

    Chess() {
        int[] back = {R, N, B, Q, K, B, N, R};
        for (int i = 0; i < 8; i++) {
            b[i] = -back[i];
            b[8 + i] = -P;
            b[48 + i] = P;
            b[56 + i] = back[i];
        }
    }

    static int from(int m) { return m & 63; }
    static int to(int m) { return (m >> 6) & 63; }
    static int promo(int m) { return (m >> 12) & 7; }
    static int flag(int m) { return (m >> 16) & 7; }
    private static int mv(int f, int t, int promo, int flag) { return f | t << 6 | promo << 12 | flag << 16; }

    private static final int[] KN = {-17, -15, -10, -6, 6, 10, 15, 17};
    private static final int[] KG = {-9, -8, -7, -1, 1, 7, 8, 9};
    private static final int[][] DIRS = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static boolean on(int r, int c) { return r >= 0 && r < 8 && c >= 0 && c < 8; }

    boolean attacked(int sq, int by) {
        int r = sq / 8, c = sq % 8;
        int pr = r + by;
        for (int dc = -1; dc <= 1; dc += 2) if (on(pr, c + dc) && b[pr * 8 + c + dc] == by * P) return true;
        for (int d : KN) {
            int t = sq + d, tr = t / 8, tc = t % 8;
            if (t >= 0 && t < 64 && Math.abs(tr - r) <= 2 && Math.abs(tc - c) <= 2 && b[t] == by * N) return true;
        }
        for (int d : KG) {
            int t = sq + d, tr = t / 8, tc = t % 8;
            if (t >= 0 && t < 64 && Math.abs(tr - r) <= 1 && Math.abs(tc - c) <= 1 && b[t] == by * K) return true;
        }
        for (int i = 0; i < 8; i++) {
            int dr = DIRS[i][0], dc = DIRS[i][1];
            int rr = r + dr, cc = c + dc;
            while (on(rr, cc)) {
                int p = b[rr * 8 + cc];
                if (p != 0) {
                    if (p * by > 0) {
                        int a = Math.abs(p);
                        if (a == Q || (i < 4 && a == B) || (i >= 4 && a == R)) return true;
                    }
                    break;
                }
                rr += dr;
                cc += dc;
            }
        }
        return false;
    }

    int king(int s) {
        for (int i = 0; i < 64; i++) if (b[i] == s * K) return i;
        return -1;
    }

    boolean inCheck(int s) { return attacked(king(s), -s); }

    List<Integer> pseudo(boolean capturesOnly) {
        List<Integer> out = new ArrayList<>(48);
        int s = side;
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p * s <= 0) continue;
            int a = Math.abs(p), r = sq / 8, c = sq % 8;
            if (a == P) {
                int dir = -s, nr = r + dir, last = s == 1 ? 0 : 7, start = s == 1 ? 6 : 1;
                if (!capturesOnly && on(nr, c) && b[nr * 8 + c] == 0) {
                    addPawn(out, sq, nr * 8 + c, nr == last, 0);
                    if (r == start && b[(r + 2 * dir) * 8 + c] == 0) out.add(mv(sq, (r + 2 * dir) * 8 + c, 0, F_DOUBLE));
                }
                for (int dc = -1; dc <= 1; dc += 2) {
                    if (!on(nr, c + dc)) continue;
                    int t = nr * 8 + c + dc;
                    if (b[t] * s < 0) addPawn(out, sq, t, nr == last, 0);
                    else if (t == ep) out.add(mv(sq, t, 0, F_EP));
                }
            } else if (a == N || a == K) {
                for (int d : a == N ? KN : KG) {
                    int t = sq + d;
                    if (t < 0 || t >= 64 || Math.abs(t / 8 - r) > 2 || Math.abs(t % 8 - c) > 2) continue;
                    if (a == K && (Math.abs(t / 8 - r) > 1 || Math.abs(t % 8 - c) > 1)) continue;
                    if (b[t] * s > 0) continue;
                    if (capturesOnly && b[t] == 0) continue;
                    out.add(mv(sq, t, 0, 0));
                }
                if (a == K && !capturesOnly) castles(out, sq);
            } else {
                int lo = a == B ? 0 : a == R ? 4 : 0, hi = a == B ? 4 : 8;
                for (int i = lo; i < hi; i++) {
                    int rr = r + DIRS[i][0], cc = c + DIRS[i][1];
                    while (on(rr, cc)) {
                        int t = rr * 8 + cc;
                        if (b[t] * s > 0) break;
                        if (b[t] == 0) { if (!capturesOnly) out.add(mv(sq, t, 0, 0)); }
                        else { out.add(mv(sq, t, 0, 0)); break; }
                        rr += DIRS[i][0];
                        cc += DIRS[i][1];
                    }
                }
            }
        }
        return out;
    }

    private void addPawn(List<Integer> out, int f, int t, boolean promote, int flag) {
        if (promote) for (int pr : new int[]{Q, N, R, B}) out.add(mv(f, t, pr, flag));
        else out.add(mv(f, t, 0, flag));
    }

    private void castles(List<Integer> out, int k) {
        int s = side;
        if (s == 1 && k == 60 && !attacked(60, -1)) {
            if ((castle & 1) != 0 && b[61] == 0 && b[62] == 0 && b[63] == R && !attacked(61, -1) && !attacked(62, -1))
                out.add(mv(60, 62, 0, F_CASTLE));
            if ((castle & 2) != 0 && b[59] == 0 && b[58] == 0 && b[57] == 0 && b[56] == R && !attacked(59, -1) && !attacked(58, -1))
                out.add(mv(60, 58, 0, F_CASTLE));
        } else if (s == -1 && k == 4 && !attacked(4, 1)) {
            if ((castle & 4) != 0 && b[5] == 0 && b[6] == 0 && b[7] == -R && !attacked(5, 1) && !attacked(6, 1))
                out.add(mv(4, 6, 0, F_CASTLE));
            if ((castle & 8) != 0 && b[3] == 0 && b[2] == 0 && b[1] == 0 && b[0] == -R && !attacked(3, 1) && !attacked(2, 1))
                out.add(mv(4, 2, 0, F_CASTLE));
        }
    }

    List<Integer> legal() {
        List<Integer> out = new ArrayList<>();
        for (int m : pseudo(false)) {
            make(m);
            if (!inCheck(-side)) out.add(m);
            unmake();
        }
        return out;
    }

    void make(int m) {
        int f = from(m), t = to(m), fl = flag(m), p = b[f];
        undoMove[ply] = m;
        undoCastle[ply] = castle;
        undoEp[ply] = ep;
        undoCap[ply] = b[t];
        if (fl == F_EP) {
            int capSq = t + 8 * side;
            undoCap[ply] = b[capSq];
            b[capSq] = 0;
        }
        b[t] = promo(m) != 0 ? side * promo(m) : p;
        b[f] = 0;
        if (fl == F_CASTLE) {
            if (t == 62) { b[61] = b[63]; b[63] = 0; }
            else if (t == 58) { b[59] = b[56]; b[56] = 0; }
            else if (t == 6) { b[5] = b[7]; b[7] = 0; }
            else if (t == 2) { b[3] = b[0]; b[0] = 0; }
        }
        ep = fl == F_DOUBLE ? (f + t) / 2 : -1;
        if (f == 60 || t == 60) castle &= ~3;
        if (f == 4 || t == 4) castle &= ~12;
        if (f == 63 || t == 63) castle &= ~1;
        if (f == 56 || t == 56) castle &= ~2;
        if (f == 7 || t == 7) castle &= ~4;
        if (f == 0 || t == 0) castle &= ~8;
        side = -side;
        ply++;
    }

    void unmake() {
        ply--;
        side = -side;
        int m = undoMove[ply], f = from(m), t = to(m), fl = flag(m);
        int moved = promo(m) != 0 ? side * P : b[t];
        b[f] = moved;
        b[t] = fl == F_EP ? 0 : undoCap[ply];
        if (fl == F_EP) b[t + 8 * side] = undoCap[ply];
        if (fl == F_CASTLE) {
            if (t == 62) { b[63] = b[61]; b[61] = 0; }
            else if (t == 58) { b[56] = b[59]; b[59] = 0; }
            else if (t == 6) { b[7] = b[5]; b[5] = 0; }
            else if (t == 2) { b[0] = b[3]; b[3] = 0; }
        }
        castle = undoCastle[ply];
        ep = undoEp[ply];
    }

    int lastMove() { return ply > 0 ? undoMove[ply - 1] : -1; }

    int plies() { return ply; }

    private static final int[] VALUE = {0, 100, 320, 330, 500, 900, 0};
    private static final int[] PAWN_PST = {
            0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] KNIGHT_PST = {
            -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40, -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30, -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50};
    private static final int[] CENTER = {
            -10, -5, -5, -5, -5, -5, -5, -10, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 5, 5, 5, 5, 0, -5,
            -5, 0, 5, 10, 10, 5, 0, -5, -5, 0, 5, 10, 10, 5, 0, -5, -5, 0, 5, 5, 5, 5, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5, -10, -5, -5, -5, -5, -5, -5, -10};

    int evaluate() {
        int score = 0;
        for (int sq = 0; sq < 64; sq++) {
            int p = b[sq];
            if (p == 0) continue;
            int a = Math.abs(p), s = p > 0 ? 1 : -1;
            int idx = s == 1 ? sq : (7 - sq / 8) * 8 + sq % 8;
            int v = VALUE[a];
            if (a == P) v += PAWN_PST[idx];
            else if (a == N) v += KNIGHT_PST[idx];
            else if (a == B || a == Q) v += CENTER[idx];
            score += s * v;
        }
        return score * side;
    }

    private int order(int m) {
        int cap = b[to(m)];
        return (cap != 0 ? 10 * VALUE[Math.abs(cap)] - VALUE[Math.abs(b[from(m)])] : 0) + (promo(m) == Q ? 800 : 0);
    }

    private void sort(List<Integer> ms) {
        java.util.Collections.sort(ms, (x, y) -> order(y) - order(x));
    }

    private int quiesce(int alpha, int beta, int depth) {
        int stand = evaluate();
        if (stand >= beta || depth == 0) return stand;
        if (stand > alpha) alpha = stand;
        List<Integer> caps = pseudo(true);
        sort(caps);
        for (int m : caps) {
            make(m);
            if (inCheck(-side)) { unmake(); continue; }
            int sc = -quiesce(-beta, -alpha, depth - 1);
            unmake();
            if (sc >= beta) return beta;
            if (sc > alpha) alpha = sc;
        }
        return alpha;
    }

    private int search(int depth, int alpha, int beta) {
        if (depth == 0) return quiesce(alpha, beta, 4);
        List<Integer> ms = pseudo(false);
        sort(ms);
        boolean any = false;
        for (int m : ms) {
            make(m);
            if (inCheck(-side)) { unmake(); continue; }
            any = true;
            int sc = -search(depth - 1, -beta, -alpha);
            unmake();
            if (sc >= beta) return beta;
            if (sc > alpha) alpha = sc;
        }
        if (!any) return inCheck(side) ? -100000 + (100 - depth) : 0;
        return alpha;
    }

    int best(int level, Random rnd) {
        List<Integer> ms = legal();
        if (ms.isEmpty()) return -1;
        int depth = level == 0 ? 1 : level == 1 ? 2 : 3;
        sort(ms);
        int bestScore = Integer.MIN_VALUE;
        List<Integer> bestMoves = new ArrayList<>();
        int slack = level == 0 ? 60 : 0;
        int[] scores = new int[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            make(ms.get(i));
            scores[i] = -search(depth - 1, -1000000, 1000000);
            unmake();
            bestScore = Math.max(bestScore, scores[i]);
        }
        for (int i = 0; i < ms.size(); i++) if (scores[i] >= bestScore - slack) bestMoves.add(ms.get(i));
        return bestMoves.get(rnd.nextInt(bestMoves.size()));
    }
}
