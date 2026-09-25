/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * CommandLogTest. operations log their equivalent git command, a logged command reproduces the operation.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class CommandLogTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    String sh(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        cmd.addAll(List.of(args));
        return run(cmd);
    }

    String run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test
    void quote() {
        assertEquals("a.txt", CommandLog.quote("a.txt"));
        assertEquals("'a b'", CommandLog.quote("a b"));
        assertEquals("'it'\\''s'", CommandLog.quote("it's"));
        assertEquals("-- a 'b c'", CommandLog.paths(List.of("a", "b c")));
    }

    @Test
    void operationsAndReplay() throws Exception {
        sh("init", "-q", "-b", "main");
        sh("config", "user.name", "t");
        sh("config", "user.email", "t@example.com");
        Files.writeString(dir.resolve("f.txt"), "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n");
        sh("add", ".");
        sh("commit", "-q", "-m", "one");
        Files.writeString(dir.resolve("f.txt"), "ONE\n2\n3\n4\n5\n6\n7\n8\n9\nTEN\n");

        CommandLog log = new CommandLog();
        try (GitRepo repo = new GitRepo(dir)) {
            repo.setCommandLog(log);
            FileChange f = repo.status().unstaged().getFirst();
            try (LazyPatch p = repo.openPatch(f)) {
                BitSet first = new BitSet();
                first.set(p.hunkRow(0)); // the hunk changing line 1
                repo.stageLines(p, first);
            }
            String staged = sh("diff", "--cached");
            repo.commit("partial\n");
            repo.branchesAt(repo.headOid()); // a read: not logged
            repo.merge(repo.revparse("HEAD"), "x\n", false, true); // up to date, one command
        }
        List<CommandLog.Entry> e = log.entries();
        assertEquals(3, e.size(), e.toString());
        assertTrue(e.get(0).command().startsWith("git apply --cached <<'EOF'\n"), e.get(0).command());
        assertEquals("git commit -m partial", e.get(1).command());
        assertTrue(e.get(2).command().startsWith("git merge -m x "), e.get(2).command());

        // the logged command does the same with the real git
        sh("reset", "-q", "HEAD~1");
        run(List.of("bash", "-c", e.get(0).command()));
        assertTrue(sh("diff", "--cached").contains("+ONE"));
        assertTrue(!sh("diff", "--cached").contains("TEN"));
    }
}
