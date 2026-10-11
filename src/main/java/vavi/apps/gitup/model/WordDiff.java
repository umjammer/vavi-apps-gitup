/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.util.ArrayList;
import java.util.List;


/**
 * the changed words of a removed line and the added line replacing it (git diff --word-diff),
 * for highlighting them in the line diff.
 * <p>
 * the lines are split into words (letters, digits, '_'), runs of whitespace and single other
 * characters (a CJK character is a word of its own, those texts have no spaces), the longest common subsequence of them is kept, the rest is changed.
 * common words at both ends are skipped first, so a typical edit costs little; a pair
 * whose middle is still too big for the LCS table is left without highlights.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-11 nsano initial version <br>
 */
public final class WordDiff {

    private WordDiff() {}

    /** the LCS table of the middle part is at most this many cells */
    static final int MAX_CELLS = 250_000;

    /**
     * changed character ranges of both lines
     *
     * @param removed {start, end} pairs (end exclusive) in the old line
     * @param added   {start, end} pairs (end exclusive) in the new line
     */
    public record Result(int[] removed, int[] added) {}

    /**
     * @return null when there is nothing worth highlighting: the lines are equal, have no word
     *         in common (a rewritten line, all of it is changed anyway) or are too long
     */
    public static Result diff(String oldLine, String newLine) {
        if (oldLine.equals(newLine)) return null;
        List<int[]> a = tokens(oldLine), b = tokens(newLine);
        int pre = 0;
        while (pre < a.size() && pre < b.size() && same(oldLine, a.get(pre), newLine, b.get(pre))) pre++;
        int suf = 0;
        while (suf < a.size() - pre && suf < b.size() - pre
                && same(oldLine, a.get(a.size() - 1 - suf), newLine, b.get(b.size() - 1 - suf))) suf++;
        int n = a.size() - pre - suf, m = b.size() - pre - suf;
        boolean[] keepA = new boolean[a.size()], keepB = new boolean[b.size()];
        for (int i = 0; i < pre; i++) keepA[i] = keepB[i] = true;
        for (int i = 0; i < suf; i++) keepA[a.size() - 1 - i] = keepB[b.size() - 1 - i] = true;
        if (n > 0 && m > 0) {
            if ((long) (n + 1) * (m + 1) > MAX_CELLS) return null;
            int[][] t = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    t[i][j] = same(oldLine, a.get(pre + i), newLine, b.get(pre + j)) ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
                }
            }
            for (int i = 0, j = 0; i < n && j < m; ) {
                if (same(oldLine, a.get(pre + i), newLine, b.get(pre + j))) {
                    keepA[pre + i++] = true;
                    keepB[pre + j++] = true;
                } else if (t[i + 1][j] >= t[i][j + 1]) {
                    i++;
                } else {
                    j++;
                }
            }
        }
        if (!hasCommonWord(oldLine, a, keepA)) return null;
        return new Result(ranges(a, keepA), ranges(b, keepB));
    }

    /** a kept token that is not whitespace or a lone punctuation: the lines are related */
    private static boolean hasCommonWord(String s, List<int[]> tokens, boolean[] keep) {
        for (int i = 0; i < tokens.size(); i++) {
            if (keep[i] && isWord(s.charAt(tokens.get(i)[0]))) return true;
        }
        return false;
    }

    /** the not kept tokens as ranges, adjacent ones merged */
    private static int[] ranges(List<int[]> tokens, boolean[] keep) {
        List<int[]> list = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (keep[i]) continue;
            int[] t = tokens.get(i);
            int[] last = list.isEmpty() ? null : list.getLast();
            if (last != null && last[1] == t[0]) last[1] = t[1];
            else list.add(new int[] {t[0], t[1]});
        }
        int[] r = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            r[i * 2] = list.get(i)[0];
            r[i * 2 + 1] = list.get(i)[1];
        }
        return r;
    }

    private static boolean same(String s, int[] a, String t, int[] b) {
        int len = a[1] - a[0];
        return len == b[1] - b[0] && s.regionMatches(a[0], t, b[0], len);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }

    /** {start, end} of words, whitespace runs and single other characters */
    static List<int[]> tokens(String s) {
        List<int[]> list = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int j = i + 1;
            if (isCjk(c)) {
                // one character
            } else if (isWord(c)) {
                while (j < s.length() && isWord(s.charAt(j)) && !isCjk(s.charAt(j))) j++;
            } else if (Character.isWhitespace(c)) {
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
            } else if (Character.isHighSurrogate(c) && j < s.length()) {
                j++;
            }
            list.add(new int[] {i, j});
            i = j;
        }
        return list;
    }
}
