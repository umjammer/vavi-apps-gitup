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
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.model.HistorySearch.Hit;
import vavi.apps.gitup.model.HistorySearch.Kind;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * HistorySearchTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class HistorySearchTest {

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
    void messageFileContent() throws Exception {
        sh("init", "-q", "-b", "main");
        Files.writeString(dir.resolve("readme.txt"), "hello\n");
        sh("add", ".");
        sh("commit", "-q", "-m", "initial");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Parser.java"), "class Parser {\n  int answer = 42;\n}\n");
        sh("add", ".");
        sh("commit", "-q", "-m", "add the parser");
        Files.writeString(dir.resolve("readme.txt"), "hello\nthe Answer is here\n");
        sh("commit", "-q", "-am", "document it");
        String c2 = sh("rev-parse", "HEAD~1");
        String c3 = sh("rev-parse", "HEAD");

        try (GitRepo repo = new GitRepo(dir)) {
            List<Hit> hits = new ArrayList<>();
            int n = HistorySearch.search(repo, "PARSER", HistorySearch.all(), 100, hits::add, x -> {}, () -> false);
            assertEquals(3, n, hits.toString());
            assertEquals(new Hit(Kind.MESSAGE, c2, "add the parser", null, 0, ' ', "add the parser"), hits.get(0));
            assertEquals(Kind.FILE, hits.get(1).kind());
            assertEquals("src/Parser.java", hits.get(1).path());
            assertEquals(Kind.CONTENT, hits.get(2).kind());
            assertEquals(1, hits.get(2).line());

            hits.clear();
            HistorySearch.search(repo, "answer", EnumSet.of(Kind.CONTENT), 100, hits::add, x -> {}, () -> false);
            assertEquals(2, hits.size(), hits.toString());
            assertEquals(c3, hits.get(0).oid());
            assertEquals(2, hits.get(0).line());
            assertEquals('+', hits.get(0).origin());
            assertEquals("the Answer is here", hits.get(0).text());
            assertEquals("src/Parser.java", hits.get(1).path());

            hits.clear();
            assertEquals(1, HistorySearch.search(repo, "e", HistorySearch.all(), 1, hits::add, x -> {}, () -> false), "limit");
            assertEquals(0, HistorySearch.search(repo, "e", HistorySearch.all(), 100, hits::add, x -> {}, () -> true), "cancelled");
        }
    }
}
