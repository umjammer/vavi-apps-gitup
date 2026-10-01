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

    static final Preset GUARD = HookPresets.builtin().stream().filter(p -> p.id().equals("main-branch-guard")).findFirst().get();
    static final Preset SNAPSHOT = HookPresets.builtin().stream().filter(p -> p.id().equals("bump-version-snapshot")).findFirst().get();

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
        String s = GitHooks.applyPreset("", GUARD, List.of("main"));
        assertTrue(GitHooks.isManaged(s));
        assertTrue(s.startsWith("#!/bin/sh\n"));
        s = GitHooks.applyPreset(s, SNAPSHOT, null);
        assertEquals(List.of(GUARD.id(), SNAPSHOT.id()), GitHooks.presetsIn(s));
        assertEquals(List.of("main"), GitHooks.parametersOf(s, GUARD));
        assertEquals(List.of(), GitHooks.parametersOf(s, SNAPSHOT));
        String t = GitHooks.applyPreset(s, GUARD, List.of("main release"));
        assertEquals(List.of(GUARD.id(), SNAPSHOT.id()), GitHooks.presetsIn(t));
        assertEquals(List.of("main release"), GitHooks.parametersOf(t, GUARD));
        assertEquals(s.length() + " release".length(), t.length());
        String u = GitHooks.removePreset(t, GUARD);
        assertEquals(List.of(SNAPSHOT.id()), GitHooks.presetsIn(u));
        assertNotEquals(t, u);
        // a foreign script is replaced
        assertFalse(GitHooks.applyPreset("#!/bin/sh\necho hi\n", GUARD, List.of("main")).contains("echo hi"));
    }

    @Test
    void mainBranchGuard() throws Exception {
        Hook hook = hooks().hook(Scope.LOCAL, "pre-push");
        GitHooks.write(hook, GitHooks.applyPreset("", GUARD, List.of("main")));
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

    @Test
    void defaultParameter() {
        assertEquals(List.of(new GitHooks.Parameter("Protected branches (space separated)", "main")), GUARD.parameters());
        String s = GitHooks.applyPreset("", GUARD, null);
        assertTrue(s.contains("gitup_protected=\"main\"\n"));
        assertFalse(s.contains("@PARAM"));
    }

    @Test
    void parameters() {
        Preset p = new Preset("p", "pre-commit", "t", "d", "a=\"@PARAM:A:1@\"\nb=\"@PARAM:B:2@\" a2=\"@PARAM:A:1@\"\n", "test");
        assertEquals(2, p.parameters().size());
        String s = GitHooks.applyPreset("", p, List.of("x y", "$z"));
        assertTrue(s.contains("a=\"x y\"\nb=\"$z\" a2=\"x y\"\n"));
        assertEquals(List.of("x y", "$z"), GitHooks.parametersOf(s, p));
        assertNull(GitHooks.parametersOf(s.replace("b=", "c="), p)); // edited
    }

    @Test
    void userPresets() throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.writeString(b.resolve("main-branch-guard.sh"), "# category: pre-commit\n\nexit 1\n"); // shadowed by the built-in
        Files.writeString(b.resolve("x.sh"), "# category: pre-commit\n# title: X\n\necho x\n");
        Files.writeString(b.resolve("bad.sh"), "# category: nothing\necho x\n");
        HookPresets presets = new HookPresets(List.of(a, b));
        assertEquals(a, presets.saveDir());
        assertEquals("pre-push", presets.get("main-branch-guard").category());
        assertEquals("X", presets.get("x").title());
        assertEquals("echo x\n", presets.get("x").body());
        assertNull(presets.get("bad"));
        Preset y = presets.save("y", "pre-commit", "Y", "why", "echo @PARAM:Word:y@\n");
        assertEquals(y, presets.get("y"));
        assertEquals(List.of("y", "x"), presets.presets("pre-commit").stream().map(Preset::id).toList());
    }

    @Test
    void markers() {
        String s = GitHooks.applyPreset(GitHooks.applyPreset("", GUARD, null), SNAPSHOT, null);
        int i = s.indexOf("gitup_protected");
        assertTrue(GitHooks.touchesPreset(s, i, i + 10)); // inside a block
        assertTrue(GitHooks.touchesPreset(s, i - 5, i + 10)); // the begin marker line
        assertTrue(GitHooks.touchesPreset(s, i, s.indexOf(GitHooks.END) + 3));
        String u = s + "\necho mine\n";
        int j = u.indexOf("echo mine");
        assertFalse(GitHooks.touchesPreset(u, j, j + 4)); // outside
        assertTrue(GitHooks.touchesPreset(u, j - 3, j + 4)); // over the end marker
        assertEquals("gitup_protected=\"main\"\n", GitHooks.lines(s, s.indexOf("gitup_protected") + 3, s.indexOf("gitup_protected") + 5));
    }

    @Test
    void wrapped() throws Exception {
        // a python and a bash script in one hook
        Preset py = new Preset("py", "pre-push", "py", "", GitHooks.wrap("""
                #!/usr/bin/env python3
                import sys
                local_ref, local_sha, remote_ref, remote_sha = sys.stdin.read().split()
                sys.exit(1 if remote_ref == "refs/heads/main" else 0)
                """, "pre-push"), "test");
        Preset sh = new Preset("sh", "pre-push", "sh", "", GitHooks.wrap("""
                #!/bin/bash
                [[ "$1" == origin ]] || exit 1
                exit 0
                """, "pre-push"), "test");
        Hook hook = hooks().hook(Scope.LOCAL, "pre-push");
        GitHooks.write(hook, GitHooks.applyPreset(GitHooks.applyPreset("", sh, null), py, null));
        commit("first", "1.0");
        assertNotEquals(0, git(work, "push", "-q", "origin", "main"));
        assertEquals(0, git(work, "push", "-q", "origin", "main:topic"));
        assertNotEquals(0, git(work, "push", "-q", origin.toString(), "main:topic2"));
    }

    @Test
    void gitHubPaths() {
        String json = "{\"tree\":[{\"path\":\"pre-push/pre-push-protect-branches\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"x\"},"
                + "{\"path\":\"pre-push\",\"mode\":\"040000\",\"type\":\"tree\",\"sha\":\"y\"},"
                + "{\"path\":\"pre-push/README.md\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"z\"}]}";
        assertEquals(List.of("pre-push/pre-push-protect-branches", "pre-push/README.md"), GitHubHookPresetProvider.paths(json));
        var aitemr = new GitHubHookPresetProvider.Aitemr();
        assertEquals("pre-push", aitemr.category("pre-push/pre-push-protect-branches"));
        assertNull(aitemr.category("pre-push/README.md"));
        var lauren = new GitHubHookPresetProvider.CompSciLauren();
        assertEquals("post-update", lauren.category("post-update-hooks/update-server-info.hook"));
        assertEquals("fsmonitor-watchman", lauren.category("query-watchman-hooks/fsmonitor-watchman.hook"));
        assertEquals("compscilauren.prevent-bad-push", lauren.id("pre-push-hooks/prevent-bad-push.hook"));
        assertEquals("Deletes all .pyc files every time a new branch is checked out.",
                GitHubHookPresetProvider.description("#!/usr/bin/env python3\n# Based on a hook\n# Source: x\n#\n# Deletes all .pyc files every time a new branch is checked out.\n"));
    }
}
