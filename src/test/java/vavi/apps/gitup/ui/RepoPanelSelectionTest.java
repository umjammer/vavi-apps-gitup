/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.model.LazyPatch;
import vavi.apps.gitup.model.MessageHistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * the commit / file selection of a whole {@link RepoPanel}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class RepoPanelSelectionTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    void sh(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Object o, String name) throws ReflectiveOperationException {
        Field f = RepoPanel.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }

    static <T> T onEdt(Callable<T> c) throws Exception {
        Object[] r = new Object[1];
        Exception[] e = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                r[0] = c.call();
            } catch (Exception x) {
                e[0] = x;
            }
        });
        if (e[0] != null) throw e[0];
        @SuppressWarnings("unchecked") T t = (T) r[0];
        return t;
    }

    static void await(String what, Callable<Boolean> condition) throws Exception {
        long limit = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < limit) {
            if (onEdt(condition)) return;
            Thread.sleep(50);
        }
        fail("timed out: " + what);
    }

    /** the path of the shown diff, null for none */
    static String shown(DiffView diff) {
        LazyPatch p = diff.getPatch();
        return p == null ? null : p.file().path();
    }

    /** SESSION.md: working copy file → a commit → back to the working copy → the same file */
    @Test
    void backToWorkingCopy() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "a\n");
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n");
        sh("init", "-q", "-b", "main");
        sh("add", ".");
        sh("commit", "-q", "-m", "one");
        Files.writeString(dir.resolve("a.txt"), "a\nb\n");
        sh("commit", "-q", "-am", "two");
        Files.writeString(dir.resolve("pom.xml"), "<project>\n</project>\n");

        RepoPanel panel = onEdt(() -> new RepoPanel(dir, new RepoPanel.Host() {
            @Override public void titleChanged(RepoPanel panel) {}
            @Override public void failed(RepoPanel panel) {}
            @Override public MessageHistory messageHistory() { return new MessageHistory(dir.resolve(".git/test-history"), 10); }
        }));
        try {
            LogPanel log = field(panel, "logPanel");
            StagingPanel staging = field(panel, "staging");
            DiffView diff = field(panel, "diff");
            FileTable unstaged = staging.unstagedTable;

            await("working copy loaded", () -> log.hasUncommitted() && log.getTable().getSelectedRow() == 0 && unstaged.getRowCount() == 1);

            // 1. the only uncommitted file
            onEdt(() -> { unstaged.setRowSelectionInterval(0, 0); return null; });
            await("pom.xml diff", () -> "pom.xml".equals(shown(diff)) && diff.getMode() == DiffView.Mode.UNSTAGED);

            // 2. another commit
            onEdt(() -> { log.getTable().setRowSelectionInterval(1, 1); return null; });
            await("commit diff", () -> "a.txt".equals(shown(diff)) && diff.getMode() == DiffView.Mode.COMMIT);

            // back to the working copy: the file is re-selected and shown again
            onEdt(() -> { log.getTable().setRowSelectionInterval(0, 0); return null; });
            await("pom.xml diff again", () -> "pom.xml".equals(shown(diff)) && diff.getMode() == DiffView.Mode.UNSTAGED);
            assertTrue(onEdt(() -> unstaged.isRowSelected(0)));

            // nothing selected on the working copy: clicking the file shows it
            onEdt(() -> { unstaged.clearSelection(); log.getTable().setRowSelectionInterval(1, 1); return null; });
            await("commit diff", () -> diff.getMode() == DiffView.Mode.COMMIT);
            onEdt(() -> { log.getTable().setRowSelectionInterval(0, 0); return null; });
            onEdt(() -> { unstaged.setRowSelectionInterval(0, 0); return null; });
            await("pom.xml diff after click", () -> "pom.xml".equals(shown(diff)) && diff.getMode() == DiffView.Mode.UNSTAGED);
        } finally {
            onEdt(() -> { panel.close(); return null; });
        }
    }
}
