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

    /**
     * GitUpKit asks for credentials on the main thread (dispatch_sync to the main queue): a prompt must not
     * wait for the EDT there. needs the network, run with -Dvavi.test.network=true
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vavi.test.network", matches = "true")
    void credentialsAskedOnMainThread() throws Exception {
        Path a = dir.resolve("a");
        Files.createDirectories(a);
        sh(a, "init", "-q", "-b", "main");
        Files.writeString(a.resolve("f.txt"), "1\n");
        sh(a, "add", "f.txt");
        sh(a, "commit", "-q", "-m", "one");
        sh(a, "remote", "add", "origin", "https://github.com/umjammer/vavi-apps-gitup-no-such-repository-for-test.git");
        java.util.List<Boolean> onMain = new java.util.concurrent.CopyOnWriteArrayList<>();
        RemoteOps.Prompter cancel = new RemoteOps.Prompter() {
            @Override public String[] userPassword(String url, String user) {
                onMain.add(org.rococoa.Foundation.isMainThread());
                return null; // cancel
            }
            @Override public String passphrase(String url, String key) { return null; }
        };
        try (RemoteOps ops = new RemoteOps(a, cancel, System.err::println)) {
            org.junit.jupiter.api.Assertions.assertThrows(vavi.apps.gitup.model.GitException.class, () -> ops.push("main", false));
        }
        assertTrue(!onMain.isEmpty(), "credentials were asked");
        assertTrue(onMain.stream().allMatch(b -> b), "on the main thread: " + onMain);
    }

    /** SourceTree's push dialog: other remote branch names, tags, force, tracking */
    @Test
    void pushBranchesTagsForce() throws Exception {
        Path bare = dir.resolve("remote.git");
        Path a = dir.resolve("a");
        Files.createDirectories(bare);
        sh(bare, "init", "-q", "--bare", "-b", "main");
        sh(dir, "clone", "-q", bare.toString(), "a");
        sh(a, "checkout", "-q", "-b", "main");
        Files.writeString(a.resolve("f.txt"), "1\n");
        sh(a, "add", "f.txt");
        sh(a, "commit", "-q", "-m", "one");
        sh(a, "tag", "v1");
        sh(a, "branch", "topic");

        try (RemoteOps ops = new RemoteOps(a, NO_PROMPT, System.err::println)) {
            ops.pushBranches("origin", java.util.List.of(new RemoteOps.BranchPush("main", "main"),
                    new RemoteOps.BranchPush("topic", "feature/topic")), true, false);
        }
        assertEquals(sh(a, "rev-parse", "main"), sh(bare, "rev-parse", "main"));
        assertEquals(sh(a, "rev-parse", "topic"), sh(bare, "rev-parse", "feature/topic"));
        assertEquals(sh(a, "rev-parse", "v1"), sh(bare, "rev-parse", "v1"));

        try (GitRepo repo = new GitRepo(a)) {
            repo.setUpstream("topic", "origin/feature/topic");
            assertEquals("origin/feature/topic", repo.upstream("topic"));
        }

        // a rewritten main needs force
        sh(a, "commit", "-q", "--amend", "-m", "one, amended");
        try (RemoteOps ops = new RemoteOps(a, NO_PROMPT, System.err::println)) {
            org.junit.jupiter.api.Assertions.assertThrows(vavi.apps.gitup.model.GitException.class,
                    () -> ops.pushBranches("origin", java.util.List.of(new RemoteOps.BranchPush("main", "main")), false, false));
            ops.pushBranches("origin", java.util.List.of(new RemoteOps.BranchPush("main", "main")), false, true);
        }
        assertEquals(sh(a, "rev-parse", "main"), sh(bare, "rev-parse", "main"));
    }

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
            assertEquals(GitRepo.PullResult.FAST_FORWARD, repo.pullFromUpstream());
        }
        assertEquals("2\n", Files.readString(b.resolve("f.txt")));

        // delete a branch on the remote
        sh(a, "push", "-q", "origin", "main:topic");
        sh(a, "fetch", "-q");
        try (RemoteOps ops = new RemoteOps(a, NO_PROMPT, System.err::println)) {
            ops.deleteRemoteBranch("origin/topic");
        }
        assertEquals("", sh(bare, "branch", "--list", "topic").strip());
        assertEquals("", sh(a, "branch", "-r", "--list", "origin/topic").strip(), "the remote branch reference too");
        assertEquals(sh(a, "rev-parse", "HEAD"), sh(b, "rev-parse", "HEAD"));
        assertEquals("", sh(b, "status", "--porcelain"));
    }
}
