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
import static org.junit.jupiter.api.Assertions.assertNotEquals;


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
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out.strip();
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
