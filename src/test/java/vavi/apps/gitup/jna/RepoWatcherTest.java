/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


/**
 * RepoWatcherTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledOnOs(OS.MAC)
class RepoWatcherTest {

    @TempDir
    Path dir;

    @Test
    void relevantGitPaths() {
        assertEquals(RepoWatcher.GIT_INDEX, RepoWatcher.classifyGitPath("index"));
        assertEquals(RepoWatcher.GIT_REFS, RepoWatcher.classifyGitPath("refs/heads/main"));
        assertEquals(RepoWatcher.GIT_REFS, RepoWatcher.classifyGitPath("HEAD"));
        assertNull(RepoWatcher.classifyGitPath("index.lock"));
        assertNull(RepoWatcher.classifyGitPath("objects/ab/cdef"));
        assertNull(RepoWatcher.classifyGitPath("logs/HEAD"));
    }

    @Test
    void events() throws Exception {
        Path git = Files.createDirectories(dir.resolve(".git"));
        Files.createDirectories(git.resolve("objects"));
        BlockingQueue<List<String>> q = new LinkedBlockingQueue<>();
        try (RepoWatcher w = new RepoWatcher(dir, git, 0.05, q::add)) {
            Thread.sleep(300); // FSEvents needs a moment before it reports
            Files.writeString(dir.resolve("a.txt"), "a");
            List<String> e = poll(q, "a.txt");
            assertNotNull(e, "workdir change reported");

            Files.writeString(git.resolve("objects/x"), "x");
            Files.writeString(git.resolve("index"), "i");
            assertNotNull(poll(q, RepoWatcher.GIT_INDEX), "index change reported");
        }
    }

    /** @return the first batch containing the path within 5 s, null otherwise */
    private static List<String> poll(BlockingQueue<List<String>> q, String path) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            List<String> e = q.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (e != null && e.contains(path)) return e;
        }
        return null;
    }
}
