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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.LazyPatch.Row;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * GitRepoTest. compares results with the git command line.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class GitRepoTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    GitRepo repo;

    static final String BASE = IntStream.rangeClosed(1, 30).mapToObj(i -> "line" + i + "\n").collect(Collectors.joining());

    String sh(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "core.autocrlf=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @BeforeEach
    void setup() throws Exception {
        sh("init", "-q", "-b", "main");
        sh("config", "user.name", "t");
        sh("config", "user.email", "t@example.com");
        write("a.txt", BASE);
        sh("add", "a.txt");
        sh("commit", "-q", "-m", "first");
        repo = new GitRepo(dir);
    }

    @AfterEach
    void teardown() {
        repo.close();
    }

    /** changes line3 and line20, so the unstaged patch has two hunks */
    void modify() throws IOException {
        write("a.txt", BASE.replace("line3\n", "LINE3\n").replace("line20\n", "LINE20\nextra\n"));
    }

    FileChange unstaged(String path) {
        return repo.status().unstaged().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    FileChange staged(String path) {
        return repo.status().staged().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void status() throws Exception {
        modify();
        write("new.txt", "hello\n");
        GitRepo.Status s = repo.status();
        assertEquals(0, s.staged().size());
        assertEquals(2, s.unstaged().size());
        assertEquals(FileChange.Kind.MODIFIED, unstaged("a.txt").kind());
        assertEquals(FileChange.Kind.UNTRACKED, unstaged("new.txt").kind());
        assertEquals("main", repo.headBranch());
    }

    @Test
    void lazyPatch() throws Exception {
        modify();
        try (LazyPatch p = repo.openPatch(unstaged("a.txt"))) {
            assertEquals(2, p.hunks().size());
            assertEquals(0, p.fetchCount(), "nothing fetched on open");
            Row h = p.row(0);
            assertTrue(h.isHeader());
            assertTrue(h.content().startsWith("@@ -1,6 +1,6 @@"), h.content());
            Row r = p.row(p.hunkRow(1) + 4);
            assertEquals(1, p.fetchCount());
            assertNotNull(r);
        }
    }

    @Test
    void stageWholeFileAndUnstage() throws Exception {
        modify();
        repo.stage(List.of(unstaged("a.txt")));
        assertEquals("M  a.txt\n", sh("status", "--porcelain"));
        repo.unstage(List.of(staged("a.txt")));
        assertEquals(" M a.txt\n", sh("status", "--porcelain"));
    }

    @Test
    void stageHunk() throws Exception {
        modify();
        try (LazyPatch p = repo.openPatch(unstaged("a.txt"))) {
            BitSet sel = new BitSet();
            sel.set(p.hunkRow(1)); // second hunk header
            repo.stageLines(p, sel);
        }
        String cached = sh("diff", "--cached", "-U0");
        assertTrue(cached.contains("+LINE20\n+extra\n"), cached);
        assertFalse(cached.contains("LINE3"), cached);
        String wt = sh("diff", "-U0");
        assertTrue(wt.contains("+LINE3"), wt);
        assertFalse(wt.contains("LINE20"), wt);
    }

    /** select only the '+extra' line of the second hunk */
    @Test
    void stageSingleLine() throws Exception {
        modify();
        try (LazyPatch p = repo.openPatch(unstaged("a.txt"))) {
            BitSet sel = new BitSet();
            int base = p.hunkRow(1);
            for (int i = 1; i <= p.hunks().get(1).lineCount(); i++) {
                if (p.row(base + i).content().equals("extra\n")) sel.set(base + i);
            }
            assertEquals(1, sel.cardinality());
            repo.stageLines(p, sel);
        }
        String cached = sh("diff", "--cached", "-U0");
        assertTrue(cached.contains("+extra\n"), cached);
        assertFalse(cached.contains("LINE20"), cached);
        assertFalse(cached.contains("-line20"), cached);
    }

    /** select only the '-line20' line: deletion staged without the replacement */
    @Test
    void stageSingleDeletion() throws Exception {
        modify();
        try (LazyPatch p = repo.openPatch(unstaged("a.txt"))) {
            BitSet sel = new BitSet();
            int base = p.hunkRow(1);
            for (int i = 1; i <= p.hunks().get(1).lineCount(); i++) {
                Row r = p.row(base + i);
                if (r.origin() == '-') sel.set(base + i);
            }
            repo.stageLines(p, sel);
        }
        String cached = sh("diff", "--cached", "-U0");
        assertTrue(cached.contains("-line20\n"), cached);
        assertFalse(cached.contains("+"+"LINE20"), cached);
    }

    @Test
    void unstageLines() throws Exception {
        modify();
        repo.stage(List.of(unstaged("a.txt")));
        try (LazyPatch p = repo.openPatch(staged("a.txt"))) {
            assertEquals(2, p.hunks().size());
            BitSet sel = new BitSet();
            sel.set(p.hunkRow(0)); // first hunk
            repo.unstageLines(p, sel);
        }
        String cached = sh("diff", "--cached", "-U0");
        assertFalse(cached.contains("LINE3"), cached);
        assertTrue(cached.contains("+LINE20"), cached);
        assertTrue(sh("diff", "-U0").contains("+LINE3"));
    }

    @Test
    void discardLines() throws Exception {
        modify();
        try (LazyPatch p = repo.openPatch(unstaged("a.txt"))) {
            BitSet sel = new BitSet();
            sel.set(p.hunkRow(0));
            repo.discardLines(p, sel);
        }
        String content = Files.readString(dir.resolve("a.txt"));
        assertTrue(content.contains("line3\n"));
        assertTrue(content.contains("LINE20\nextra\n"));
    }

    @Test
    void discardFiles() throws Exception {
        modify();
        write("new.txt", "hello\n");
        repo.discard(repo.status().unstaged());
        assertEquals("", sh("status", "--porcelain"));
    }

    @Test
    void untrackedPartial() throws Exception {
        write("new.txt", "one\ntwo\nthree\n");
        try (LazyPatch p = repo.openPatch(unstaged("new.txt"))) {
            BitSet sel = new BitSet();
            sel.set(1); // "one"
            sel.set(3); // "three"
            repo.stageLines(p, sel);
        }
        assertEquals("one\nthree\n", sh("show", ":new.txt"));
    }

    @Test
    void commitAndLog() throws Exception {
        modify();
        repo.stage(repo.status().unstaged());
        String oid = repo.commit("second\n\nbody");
        assertEquals(oid, sh("rev-parse", "HEAD").trim());
        assertEquals("second", sh("log", "-1", "--format=%s").trim());

        try (CommitLog log = repo.log()) {
            List<CommitRow> rows = log.next(1);
            assertEquals(1, rows.size());
            assertEquals("second", rows.getFirst().summary());
            assertEquals(1, log.next(10).size());
            assertTrue(log.isDone());
        }
        assertEquals(List.of(new FileChange("a.txt", "a.txt", FileChange.Kind.MODIFIED, true)), repo.commitFiles(oid));
        try (LazyPatch p = repo.openPatch(oid, repo.commitFiles(oid).getFirst())) {
            assertEquals(2, p.hunks().size());
        }
    }

    @Test
    void branches() throws Exception {
        sh("branch", "topic");
        repo.checkout("topic");
        assertEquals("topic", repo.headBranch());
        assertTrue(repo.refs().stream().anyMatch(r -> r.kind() == GitRepo.Ref.Kind.LOCAL && r.shorthand().equals("main")));
        assertNull(repo.upstream("topic"));
    }

    /** a huge change: opening the patch reads no line, painting reads only visible rows */
    @Test
    void hugeFile() throws Exception {
        write("big.txt", IntStream.range(0, 200_000).mapToObj(i -> "l" + i + "\n").collect(Collectors.joining()));
        sh("add", "big.txt");
        sh("commit", "-q", "-m", "big");
        write("big.txt", IntStream.range(0, 200_000).mapToObj(i -> (i % 2 == 0 ? "x" : "l") + i + "\n").collect(Collectors.joining()));
        try (LazyPatch p = repo.openPatch(unstaged("big.txt"))) {
            assertTrue(p.rowCount() > 200_000, "rows: " + p.rowCount());
            assertEquals(0, p.fetchCount());
            for (int r = 150_000; r < 150_050; r++) p.row(r);
            assertTrue(p.fetchCount() <= 50, "only visible rows fetched: " + p.fetchCount());
        }
    }
}
