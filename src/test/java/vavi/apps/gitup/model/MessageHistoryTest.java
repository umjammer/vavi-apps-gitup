/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * MessageHistoryTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
class MessageHistoryTest {

    @TempDir
    Path dir;

    @Test
    void newestFirstDedupedCapped() {
        Path f = dir.resolve("h/messages.txt");
        MessageHistory h = new MessageHistory(f, 3);
        h.add("one");
        h.add("two\n\nwith body\n");
        h.add("three");
        h.add("one"); // moves to the top
        h.add("four"); // drops the oldest ("two")
        assertEquals(List.of("four", "one", "three"), h.messages());

        h.add("multi\nline");
        MessageHistory reloaded = new MessageHistory(f, 3);
        assertEquals(List.of("multi\nline", "four", "one"), reloaded.messages());
    }

    @Test
    void defaults() {
        assertEquals(50, MessageHistory.DEFAULT_SIZE);
        assertEquals(50, MessageHistory.configuredSize());
        assertEquals(List.of(), new MessageHistory(dir.resolve("none.txt"), 50).messages());
    }
}
