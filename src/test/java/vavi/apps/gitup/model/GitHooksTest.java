/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.model.GitHooks.Hook;
import vavi.apps.gitup.model.GitHooks.Preset;
import vavi.apps.gitup.model.GitHooks.Scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * GitHooksTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-28 nsano initial version <br>
 */
class GitHooksTest {

    @TempDir
    Path dir;

    Path home;
    Path work;
    Path origin;

    static final Preset GUARD = GitHooks.PRESETS.get(0);
    static final Preset SNAPSHOT = GitHooks.PRESETS.get(1);

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(home.resolve(".gitconfig"), "[user]\n\tname = test\n\temail = test@example.com\n[init]\n\tdefaultBranch = main\n");
        origin = dir.resolve("origin.git");
        work = dir.resolve("work");
        git(dir, "init", "-q", "--bare", origin.toString());
        git(dir, "init", "-q", work.toString());
        git(work, "remote", "add", "origin", origin.toString());
    }

    /** @return exit code */
    int git(Path cwd, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().put("GIT_CONFIG_GLOBAL", home.resolve(".gitconfig").toString());
        pb.environment().remove("XDG_CONFIG_HOME");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int r = p.waitFor();
        System.err.print(out);
        return r;
    }

    void commit(String message, String version) throws Exception {
        Files.writeString(work.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><version>1-SNAPSHOT</version></parent>
                  <artifactId>x</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version));
        git(work, "add", "pom.xml");
        assertEquals(0, git(work, "commit", "-q", "-m", message));
    }

    GitHooks hooks() {
        return new GitHooks(work, work.resolve(".git"), home);
    }

    @Test
    void directories() throws Exception {
        GitHooks h = hooks();
        assertNull(h.globalDir());
        assertEquals(work.resolve(".git/hooks").toRealPath(), h.localDir().toRealPath());
        assertTrue(h.active(Scope.LOCAL));
        assertFalse(h.active(Scope.GLOBAL));

        h.setGlobalDir(h.defaultGlobalDir());
        assertEquals(home.resolve(".config/git/hooks"), h.globalDir());
        assertTrue(Files.readString(home.resolve(".gitconfig")).contains("hooksPath = ~/.config/git/hooks"));
        assertTrue(h.active(Scope.GLOBAL));
        assertFalse(h.active(Scope.LOCAL));
    }

    @Test
    void writeEnableDelete() throws Exception {
        GitHooks h = hooks();
        assertTrue(h.list(Scope.LOCAL).isEmpty()); // samples are not hooks
        Hook hook = h.hook(Scope.LOCAL, "pre-commit");
        assertFalse(hook.exists());
        GitHooks.write(hook, GitHooks.template("pre-commit"));
        assertTrue(hook.enabled());
        assertEquals(List.of(hook), h.list(Scope.LOCAL));
        GitHooks.setEnabled(hook, false);
        assertFalse(hook.enabled());
        GitHooks.setEnabled(hook, true);
        assertTrue(hook.enabled());
        GitHooks.delete(hook);
        assertTrue(h.list(Scope.LOCAL).isEmpty());
    }

    @Test
    void presetBlocks() {
        String s = GitHooks.applyPreset("", GUARD, "main");
        assertTrue(GitHooks.isManaged(s));
        assertTrue(s.startsWith("#!/bin/sh\n"));
        s = GitHooks.applyPreset(s, SNAPSHOT, null);
        assertEquals(List.of(GUARD.id(), SNAPSHOT.id()), GitHooks.presetsIn(s));
        assertEquals("main", GitHooks.parameterOf(s, GUARD));
        String t = GitHooks.applyPreset(s, GUARD, "main release");
        assertEquals(List.of(GUARD.id(), SNAPSHOT.id()), GitHooks.presetsIn(t));
        assertEquals("main release", GitHooks.parameterOf(t, GUARD));
        assertEquals(s.length() + " release".length(), t.length());
        String u = GitHooks.removePreset(t, GUARD);
        assertEquals(List.of(SNAPSHOT.id()), GitHooks.presetsIn(u));
        assertNotEquals(t, u);
        // a foreign script is replaced
        assertFalse(GitHooks.applyPreset("#!/bin/sh\necho hi\n", GUARD, "main").contains("echo hi"));
    }

    @Test
    void mainBranchGuard() throws Exception {
        Hook hook = hooks().hook(Scope.LOCAL, "pre-push");
        GitHooks.write(hook, GitHooks.applyPreset("", GUARD, "main"));
        commit("first", "1.0");
        assertNotEquals(0, git(work, "push", "-q", "origin", "main"));
        assertEquals(0, git(work, "push", "-q", "origin", "main:topic"));
        GitHooks.setEnabled(hook, false);
        assertEquals(0, git(work, "push", "-q", "origin", "main"));
    }

    @Test
    void bumpVersionSnapshot() throws Exception {
        Hook hook = hooks().hook(Scope.LOCAL, "pre-push");
        GitHooks.write(hook, GitHooks.applyPreset("", SNAPSHOT, null));
        commit("first", "1.0-SNAPSHOT");
        assertEquals(0, git(work, "push", "-q", "origin", "main"));  // not a bump
        commit("🥺 bump version", "1.1-SNAPSHOT");
        assertNotEquals(0, git(work, "push", "-q", "origin", "main"));
        commit("fix", "1.1");
        assertNotEquals(0, git(work, "push", "-q", "origin", "main")); // the bump commit is still pushed
        git(work, "reset", "-q", "--hard", "HEAD~2");
        commit("Bump version", "1.1");
        assertEquals(0, git(work, "push", "-q", "origin", "main"));
        commit("bump version", "1.2-SNAPSHOT");
        assertNotEquals(0, git(work, "push", "-q", "origin", "main:new-branch")); // a new remote branch
    }
}
