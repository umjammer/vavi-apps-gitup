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
import vavi.apps.gitup.model.GitRepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * RemoteOpsTest. push / fetch / fast-forward pull against a local bare repository.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class RemoteOpsTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    String sh(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    static final RemoteOps.Prompter NO_PROMPT = new RemoteOps.Prompter() {
        @Override public String[] userPassword(String url, String user) { return null; }
        @Override public String passphrase(String url, String key) { return null; }
    };

    @Test
    void pushFetchPull() throws Exception {
        Path bare = dir.resolve("remote.git");
        Path a = dir.resolve("a");
        Path b = dir.resolve("b");
        Files.createDirectories(bare);
        sh(bare, "init", "-q", "--bare", "-b", "main");
        sh(dir, "clone", "-q", bare.toString(), "a");
        sh(a, "checkout", "-q", "-b", "main");
        Files.writeString(a.resolve("f.txt"), "1\n");
        sh(a, "add", "f.txt");
        sh(a, "commit", "-q", "-m", "one");

        // push without upstream sets it
        try (RemoteOps ops = new RemoteOps(a, NO_PROMPT, System.err::println)) {
            ops.push("main", false);
        }
        assertEquals(sh(a, "rev-parse", "HEAD"), sh(bare, "rev-parse", "main"));
        assertEquals("origin/main", sh(a, "rev-parse", "--abbrev-ref", "main@{upstream}").trim());

        sh(dir, "clone", "-q", bare.toString(), "b");
        Files.writeString(a.resolve("f.txt"), "2\n");
        sh(a, "commit", "-q", "-am", "two");
        try (RemoteOps ops = new RemoteOps(a, NO_PROMPT, System.err::println)) {
            ops.push("main", true);
        }

        // pull = fetch + fast-forward
        try (RemoteOps ops = new RemoteOps(b, NO_PROMPT, System.err::println);
             GitRepo repo = new GitRepo(b)) {
            ops.fetchAll();
            assertTrue(repo.fastForwardToUpstream());
        }
        assertEquals("2\n", Files.readString(b.resolve("f.txt")));
        assertEquals(sh(a, "rev-parse", "HEAD"), sh(b, "rev-parse", "HEAD"));
        assertEquals("", sh(b, "status", "--porcelain"));
    }
}
