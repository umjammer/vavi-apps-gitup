/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * word diff of a removed / added line pair.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-11 nsano initial version <br>
 */
class WordDiffTest {

    @Test
    void changedWord() {
        WordDiff.Result r = WordDiff.diff("int count = 10;", "int total = 10;");
        assertNotNull(r);
        assertArrayEquals(new int[] {4, 9}, r.removed());
        assertArrayEquals(new int[] {4, 9}, r.added());
    }

    @Test
    void insertedWords() {
        WordDiff.Result r = WordDiff.diff("foo(a, b);", "foo(a, x, b);");
        assertNotNull(r);
        assertArrayEquals(new int[] {}, r.removed());
        assertArrayEquals(new int[] {7, 10}, r.added()); // "x, ": "x" "," " " merged
    }

    @Test
    void severalChanges() {
        WordDiff.Result r = WordDiff.diff("a quick brown fox", "a slow brown dog");
        assertNotNull(r);
        assertArrayEquals(new int[] {2, 7, 14, 17}, r.removed());
        assertArrayEquals(new int[] {2, 6, 13, 16}, r.added());
    }

    @Test
    void cjk() {
        WordDiff.Result r = WordDiff.diff("今日は晴れです", "今日は雨です");
        assertNotNull(r);
        assertArrayEquals(new int[] {3, 5}, r.removed());
        assertArrayEquals(new int[] {3, 4}, r.added());
    }

    @Test
    void nothingToHighlight() {
        assertNull(WordDiff.diff("same", "same"));
        assertNull(WordDiff.diff("completely different", "nothing alike"), "no word in common");
    }

    @Test
    void tooLong() {
        String a = "x ".repeat(2000), b = "y ".repeat(2000);
        assertNull(WordDiff.diff("k " + a, "k " + b));
    }

    /** the view diffs only the painted rows, a screen of lines must be quick */
    @Test
    void speed() {
        String a = "        String text = marker ? \"\\\\ No newline at end of file\" : expandTabs(row.content());";
        String b = "        String text = marker ? \"\\\\ No newline\" : expandTabs(row.content(), TAB);";
        for (int i = 0; i < 2000; i++) WordDiff.diff(a, b); // warm up
        long t = System.nanoTime();
        for (int i = 0; i < 100; i++) WordDiff.diff(a + i, b + i);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 100, "100 line pairs took " + ms + " ms");
    }
}
