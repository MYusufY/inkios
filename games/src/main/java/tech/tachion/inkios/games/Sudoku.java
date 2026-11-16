package tech.tachion.inkios.games;

import java.util.Random;

final class Sudoku {
    static final String[] LEVELS = {"Easy", "Medium", "Hard"};
    private static final int[] CLUES = {40, 32, 26};

    final int[] solution = new int[81];
    final int[] givens = new int[81];

    static Sudoku generate(int level, Random rnd) {
        Sudoku s = new Sudoku();
        int[] g = new int[81];
        fill(g, 0, rnd);
        System.arraycopy(g, 0, s.solution, 0, 81);
        System.arraycopy(g, 0, s.givens, 0, 81);
        int[] order = new int[81];
        for (int i = 0; i < 81; i++) order[i] = i;
        for (int i = 80; i > 0; i--) { int j = rnd.nextInt(i + 1); int x = order[i]; order[i] = order[j]; order[j] = x; }
        int clues = 81;
        for (int k = 0; k < 81 && clues > CLUES[level]; k++) {
            int i = order[k];
            int keep = s.givens[i];
            s.givens[i] = 0;
            int[] copy = s.givens.clone();
            if (count(copy, 2) != 1) s.givens[i] = keep;
            else clues--;
        }
        return s;
    }

    private static boolean fill(int[] g, int pos, Random rnd) {
        if (pos == 81) return true;
        int[] digits = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int i = 8; i > 0; i--) { int j = rnd.nextInt(i + 1); int x = digits[i]; digits[i] = digits[j]; digits[j] = x; }
        for (int d : digits) {
            if (allowed(g, pos, d)) {
                g[pos] = d;
                if (fill(g, pos + 1, rnd)) return true;
                g[pos] = 0;
            }
        }
        return false;
    }

    static boolean allowed(int[] g, int pos, int d) {
        int r = pos / 9, c = pos % 9, br = r / 3 * 3, bc = c / 3 * 3;
        for (int k = 0; k < 9; k++) {
            if (g[r * 9 + k] == d || g[k * 9 + c] == d) return false;
            if (g[(br + k / 3) * 9 + bc + k % 3] == d) return false;
        }
        return true;
    }

    static int count(int[] g, int limit) {
        int best = -1, bestMask = 0, bestBits = 10;
        for (int i = 0; i < 81; i++) {
            if (g[i] != 0) continue;
            int mask = candidates(g, i);
            int bits = Integer.bitCount(mask);
            if (bits == 0) return 0;
            if (bits < bestBits) { best = i; bestMask = mask; bestBits = bits; if (bits == 1) break; }
        }
        if (best < 0) return 1;
        int total = 0;
        for (int d = 1; d <= 9 && total < limit; d++) {
            if ((bestMask & (1 << d)) == 0) continue;
            g[best] = d;
            total += count(g, limit - total);
            g[best] = 0;
        }
        return total;
    }

    private static int candidates(int[] g, int pos) {
        int r = pos / 9, c = pos % 9, br = r / 3 * 3, bc = c / 3 * 3, used = 0;
        for (int k = 0; k < 9; k++) {
            used |= 1 << g[r * 9 + k];
            used |= 1 << g[k * 9 + c];
            used |= 1 << g[(br + k / 3) * 9 + bc + k % 3];
        }
        return ~used & 0x3FE;
    }
}
