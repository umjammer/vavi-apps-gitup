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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.model.SubtreeMove.GitException;
import vavi.apps.gitup.model.SubtreeMove.Plan;
import vavi.apps.gitup.model.SubtreeMove.SourceCleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * SubtreeMoveTest. two repositories made with the git command line.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
class SubtreeMoveTest {

    @TempDir
    Path dir;

    Path r1, r2;

    final List<String> log = new ArrayList<>();

    String sh(Path repo, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "core.autocrlf=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    void write(Path repo, String name, String content) throws IOException {
        Path p = repo.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    Path init(String name) throws Exception {
        Path r = Files.createDirectory(dir.resolve(name));
        sh(r, "init", "-q", "-b", "main");
        sh(r, "config", "user.name", "t");
        sh(r, "config", "user.email", "t@example.com");
        return r;
    }

    @BeforeEach
    void setup() throws Exception {
        r1 = init("r1");
        write(r1, "d/a.txt", "a\n");
        write(r1, "d/sub/b.txt", "b\n");
        write(r1, "x.txt", "x\n");
        sh(r1, "add", ".");
        sh(r1, "commit", "-q", "-m", "c1");
        write(r1, "d/a.txt", "a\na2\n");
        sh(r1, "commit", "-q", "-am", "c2 on d");
        write(r1, "x.txt", "x\nx2\n");
        sh(r1, "commit", "-q", "-am", "c3 not on d");

        r2 = init("r2");
        write(r2, "y.txt", "y\n");
        sh(r2, "add", ".");
        sh(r2, "commit", "-q", "-m", "r2c1");
    }

    SubtreeMove move(String dir, String prefix, boolean deleteSplit, SourceCleanup cleanup) {
        return new SubtreeMove(new Plan(r1, dir, r2, prefix, deleteSplit, cleanup), (r, l) -> log.add(r == null ? "  " + l : r.getFileName() + "$ " + l));
    }

    @Test
    void toTop() throws Exception {
        SubtreeMove m = move("d", "", true, SourceCleanup.NONE);
        m.run();
        log.forEach(System.err::println);

        assertEquals("a\na2\n", Files.readString(r2.resolve("a.txt")));
        assertEquals("b\n", Files.readString(r2.resolve("sub/b.txt")));
        assertEquals("main", sh(r2, "branch", "--show-current").strip());
        assertEquals("", sh(r2, "status", "--porcelain"));
        // the history of d: c1, c2 (not c3), merged
        String subjects = sh(r2, "log", "--format=%s");
        assertTrue(subjects.contains("c2 on d"), subjects);
        assertTrue(subjects.contains("c1"), subjects);
        assertFalse(subjects.contains("c3 not on d"), subjects);
        assertEquals(2, sh(r2, "rev-list", "--parents", "-n", "1", "HEAD").strip().split(" ").length - 1); // a merge
        // no temporary branch left, the split branch deleted
        assertEquals("main", sh(r2, "branch", "--format=%(refname:short)").strip());
        assertEquals("main", sh(r1, "branch", "--format=%(refname:short)").strip());
        // the source is untouched
        assertTrue(Files.exists(r1.resolve("d/a.txt")));
        assertEquals("", sh(r1, "status", "--porcelain"));
    }

    @Test
    void intoFolder() throws Exception {
        SubtreeMove m = move("d", "lib/d", false, SourceCleanup.UNTRACK);
        m.run();
        log.forEach(System.err::println);

        assertEquals("a\na2\n", Files.readString(r2.resolve("lib/d/a.txt")));
        assertEquals("b\n", Files.readString(r2.resolve("lib/d/sub/b.txt")));
        assertEquals("", sh(r2, "status", "--porcelain"));
        assertTrue(sh(r2, "log", "--format=%s").contains("c2 on d"));
        // the split branch is kept
        assertTrue(sh(r1, "branch", "--format=%(refname:short)").contains(m.splitBranch()));
        // untracked in the source, the files stay
        assertTrue(Files.exists(r1.resolve("d/a.txt")));
        assertTrue(sh(r1, "status", "--porcelain").contains("D  d/a.txt"));
    }

    @Test
    void deleteInSource() throws Exception {
        move("d/sub", "", true, SourceCleanup.DELETE).run();

        assertEquals("b\n", Files.readString(r2.resolve("b.txt")));
        assertFalse(Files.exists(r1.resolve("d/sub")));
        assertTrue(sh(r1, "status", "--porcelain").contains("D  d/sub/b.txt"));
    }

    @Test
    void conflictAtTop() throws Exception {
        write(r2, "a.txt", "other\n");
        sh(r2, "add", ".");
        sh(r2, "commit", "-q", "-m", "r2c2");
        GitException e = assertThrows(GitException.class, () -> move("d", "", true, SourceCleanup.NONE).run());
        assertTrue(e.getMessage().contains("a.txt"), e.getMessage());
        assertEquals("main", sh(r1, "branch", "--format=%(refname:short)").strip()); // not split
    }

    @Test
    void dirtyTarget() throws Exception {
        write(r2, "y.txt", "changed\n");
        assertThrows(GitException.class, () -> move("d", "", true, SourceCleanup.NONE).check());
    }

    @Test
    void existingPrefix() throws Exception {
        assertThrows(GitException.class, () -> move("d", "y.txt", true, SourceCleanup.NONE).check());
    }

    @Test
    void notAFolder() throws Exception {
        assertThrows(GitException.class, () -> move("nothing", "", true, SourceCleanup.NONE).check());
    }

    @Test
    void sameRepository() throws Exception {
        SubtreeMove m = new SubtreeMove(new Plan(r1, "d", r1.resolve("d"), "", true, SourceCleanup.NONE), null);
        assertThrows(GitException.class, m::check);
    }

    /** a failure in the middle puts the target back on its branch */
    @Test
    void rollback() throws Exception {
        // untracked in the target where a moved file comes: the pull cannot check it out
        write(r2, "a.txt", "untracked\n");
        SubtreeMove m = move("d", "", true, SourceCleanup.NONE);
        assertThrows(GitException.class, m::run);
        log.forEach(System.err::println);

        assertEquals("main", sh(r2, "branch", "--show-current").strip());
        assertEquals("main", sh(r2, "branch", "--format=%(refname:short)").strip());
        assertEquals("r2c1", sh(r2, "log", "-1", "--format=%s").strip());
        assertEquals("main", sh(r1, "branch", "--format=%(refname:short)").strip());
    }

    @Test
    void normalize() {
        assertEquals("a/b", SubtreeMove.normalize("/a//b/"));
        assertEquals("", SubtreeMove.normalize("."));
        assertEquals("", SubtreeMove.normalize(null));
    }
}
