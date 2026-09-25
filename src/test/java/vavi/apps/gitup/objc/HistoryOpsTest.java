/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 * HistoryOpsTest. "Edit Message" of a commit in the middle of the history.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class HistoryOpsTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    String sh(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out.strip();
    }

    /** commits one, two, three adding a file each, on main */
    void threeCommits() throws Exception {
        sh("init", "-q", "-b", "main");
        sh("config", "user.name", "t");
        sh("config", "user.email", "t@example.com");
        for (String n : List.of("one", "two", "three")) {
            Files.writeString(dir.resolve(n + ".txt"), n + "\n");
            sh("add", ".");
            sh("commit", "-q", "-m", n);
        }
    }

    @Test
    void squashAndFixup() throws Exception {
        threeCommits();
        String three = sh("rev-parse", "HEAD");
        String squashed = HistoryOps.squashWithParent(dir, three, "two and three\n");
        assertEquals("two and three\none", sh("log", "--format=%s"));
        assertEquals(squashed, sh("rev-parse", "HEAD"));
        assertEquals("three.txt\ntwo.txt", sh("show", "--name-only", "--format=", "HEAD").lines().sorted().reduce((a, b) -> a + "\n" + b).orElse(""));

        String fixed = HistoryOps.fixupWithParent(dir, squashed);
        assertEquals("one", sh("log", "--format=%s"), "keeps the parent's message");
        assertEquals(fixed, sh("rev-parse", "HEAD"));
        assertEquals("", sh("status", "--porcelain"), "trees did not change, the working copy is in sync");
    }

    @Test
    void deleteAndReset() throws Exception {
        threeCommits();
        HistoryOps.delete(dir, sh("rev-parse", "HEAD~1"));
        assertEquals("three\none", sh("log", "--format=%s"));
        // HEAD moved to a tree without two.txt, the working copy still has it: the app resets after the rewrite
        try (vavi.apps.gitup.model.GitRepo repo = new vavi.apps.gitup.model.GitRepo(dir)) {
            repo.resetHardToHead();
        }
        assertEquals("", sh("status", "--porcelain"));
        assertFalse(Files.exists(dir.resolve("two.txt")));
    }

    @Test
    void swap() throws Exception {
        threeCommits();
        Files.writeString(dir.resolve("four.txt"), "four\n");
        sh("add", ".");
        sh("commit", "-q", "-m", "four");
        // GitUp does not swap with a root commit
        String three = sh("rev-parse", "HEAD~1");
        HistoryOps.swapWithParent(dir, three); // three below two
        assertEquals("four\ntwo\nthree\none", sh("log", "--format=%s"));
        String three2 = sh("rev-parse", "HEAD~2");
        HistoryOps.swapWithChild(dir, three2); // back up
        assertEquals("four\nthree\ntwo\none", sh("log", "--format=%s"));
        assertEquals("", sh("status", "--porcelain"), "the final tree is the same");
    }

    @Test
    void conflictingRewriteChangesNothing() throws Exception {
        sh("init", "-q", "-b", "main");
        for (String n : List.of("a", "b", "c")) {
            Files.writeString(dir.resolve("f.txt"), n + "\n");
            sh("add", ".");
            sh("commit", "-q", "-m", n);
        }
        String before = sh("rev-parse", "HEAD");
        String b = sh("rev-parse", "HEAD~1");
        assertThrows(vavi.apps.gitup.model.GitException.class, () -> HistoryOps.delete(dir, b));
        assertEquals(before, sh("rev-parse", "HEAD"));
    }

    @Test
    void editMessageInTheMiddle() throws Exception {
        sh("init", "-q", "-b", "main");
        sh("config", "user.name", "t");
        sh("config", "user.email", "t@example.com");
        for (String n : List.of("one", "two", "three")) {
            Files.writeString(dir.resolve(n + ".txt"), n + "\n");
            sh("add", ".");
            sh("commit", "-q", "-m", n);
        }
        sh("branch", "topic", "HEAD~1"); // topic contains "two" too
        Files.writeString(dir.resolve("wip.txt"), "uncommitted\n"); // the working copy is untouched
        String two = sh("rev-parse", "HEAD~1");
        String treesBefore = sh("log", "--format=%T", "main");

        String newTwo = HistoryOps.editMessage(dir, two, "two, edited\n\nwith a body\n");

        assertNotEquals(two, newTwo);
        assertEquals("three\ntwo, edited\none", sh("log", "--format=%s", "main"));
        assertEquals(treesBefore, sh("log", "--format=%T", "main"), "trees are copied");
        assertEquals(newTwo, sh("rev-parse", "main~1"));
        assertEquals(newTwo, sh("rev-parse", "topic"), "other local branches containing the commit move too");
        assertEquals("main", sh("rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals("?? wip.txt", sh("status", "--porcelain"));
    }
}
