/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import vavi.apps.gitup.model.CommitLog.CommitRow;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * GraphEdgesTest. the lines from a commit down to its parents in the log graph.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
class GraphEdgesTest {

    static CommitRow row(String oid, List<String> parents, int lane, String[] before, String[] after) {
        return new CommitRow(oid, parents, oid, oid, "t", "t@example.com", Instant.EPOCH, lane, before, after);
    }

    static String edges(CommitRow row) {
        return LogPanel.GraphRenderer.edges(row).stream().map(e -> e[0] + (e[1] == 1 ? "stub" : "down")).toList().toString();
    }

    /** the first commit of a branch: its lane goes down to the fork point, no second line into the main lane */
    @Test
    void branchStart() {
        // lane 0 (main) waits for the fork point p, the branch's first commit g1 in lane 1
        CommitRow g1 = row("g1", List.of("p"), 1, new String[] {"p", "g1"}, new String[] {"p", "p"});
        assertEquals("[1down]", edges(g1));
    }

    /** a merge whose second parent is already on another lane joins it with a stub */
    @Test
    void mergeIntoPassingLane() {
        CommitRow m = row("m", List.of("a", "b"), 0, new String[] {"m", "b"}, new String[] {"a", "b"});
        assertEquals("[0down, 1stub]", edges(m));
    }

    @Test
    void plainAndMerge() {
        assertEquals("[0down]", edges(row("c", List.of("p"), 0, new String[] {"c"}, new String[] {"p"})));
        // a merge whose second parent gets a new lane
        assertEquals("[0down, 1down]", edges(row("m", List.of("a", "b"), 0, new String[] {"m"}, new String[] {"a", "b"})));
    }
}
