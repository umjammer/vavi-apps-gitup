/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.model.Bookmarks.Group;
import vavi.apps.gitup.model.Bookmarks.Repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * BookmarksTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
class BookmarksTest {

    @TempDir
    Path dir;

    @Test
    void roundTrip() throws Exception {
        Bookmarks b = new Bookmarks();
        Group work = new Group("work");
        Group sub = new Group("sub group");
        work.children().add(new Repo("app", Path.of("/src/app")));
        work.children().add(sub);
        sub.children().add(new Repo("lib", Path.of("/src/lib")));
        b.root().children().add(work);
        b.root().children().add(new Repo("dotfiles", Path.of("/home/dotfiles")));
        Path file = dir.resolve("x/bookmarks.txt");
        b.save(file);
        assertEquals("G\twork\n\tR\tapp\t/src/app\n\tG\tsub group\n\t\tR\tlib\t/src/lib\nR\tdotfiles\t/home/dotfiles\n", Files.readString(file));

        Bookmarks c = Bookmarks.load(file);
        assertEquals(2, c.root().children().size());
        Group w = (Group) c.root().children().getFirst();
        assertEquals("work", w.name());
        assertEquals("lib", ((Group) w.children().get(1)).children().getFirst().name());
        assertNotNull(c.find(Path.of("/src/lib")));
    }

    @Test
    void addAndRemove() {
        Bookmarks b = new Bookmarks();
        assertTrue(b.addIfAbsent(Path.of("/a/repo")));
        assertFalse(b.addIfAbsent(Path.of("/a/./repo")));
        Repo r = b.find(Path.of("/a/repo"));
        assertEquals("repo", r.name());
        assertTrue(b.remove(r));
        assertEquals(0, b.root().children().size());
    }

    @Test
    void missingFile() {
        assertEquals(0, Bookmarks.load(dir.resolve("none.txt")).root().children().size());
    }
}
