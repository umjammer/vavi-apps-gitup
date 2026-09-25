/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.model.GitRepo.RefSnapshot;
import vavi.apps.gitup.model.GitRepo.Restore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * UndoStoreTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
class UndoStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTrip() throws Exception {
        UndoStore store = new UndoStore(dir, Path.of("/src/repo"));
        assertTrue(store.load().undo().isEmpty());
        RefSnapshot a = new RefSnapshot("Commit", "refs/heads/main", Map.of("refs/heads/main", "aaa"), Restore.REFS);
        RefSnapshot b = new RefSnapshot("Reset", "0123", Map.of("refs/heads/main", "bbb", "refs/heads/x", "ccc"), Restore.INDEX);
        RefSnapshot c = new RefSnapshot("Delete Commit", null, Map.of(), Restore.ALL);
        store.save(List.of(a, b), List.of(c));

        UndoStore.History h = new UndoStore(dir, Path.of("/src/repo")).load();
        assertEquals(List.of(a, b), h.undo());
        assertEquals(List.of(c), h.redo());
        // another repository has its own history
        assertTrue(new UndoStore(dir, Path.of("/src/other")).load().undo().isEmpty());

        try (var files = Files.list(dir)) {
            Path f = files.findFirst().orElseThrow();
            Files.writeString(f, "garbage\tNOPE\n");
        }
        assertTrue(new UndoStore(dir, Path.of("/src/repo")).load().undo().isEmpty(), "a broken file is dropped");
    }
}
