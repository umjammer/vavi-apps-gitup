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
    void rename() {
        Bookmarks b = new Bookmarks();
        Group work = new Group("work");
        Repo app = new Repo("app", Path.of("/src/app"));
        work.children().add(app);
        b.root().children().add(work);

        Repo renamed = (Repo) b.rename(app, " My\tApp ");
        assertEquals("My App", renamed.name());
        assertEquals(renamed, work.children().getFirst());
        assertEquals("app", b.rename(renamed, " ").name()); // blank: the folder name

        assertEquals("job", b.rename(work, "job").name());
        assertEquals("job", b.rename(work, "").name()); // blank: kept
    }

    @Test
    void relocate() {
        Bookmarks b = new Bookmarks();
        Group work = new Group("work");
        work.children().add(new Repo("app", Path.of("/src/app")));
        work.children().add(new Repo("My Lib", Path.of("/src/lib")));
        b.root().children().add(work);
        b.addIfAbsent(Path.of("/src/other"));

        assertTrue(b.relocate(Path.of("/src/app"), Path.of("/src/app2")));
        assertEquals(new Repo("app2", Path.of("/src/app2")), work.children().getFirst()); // the folder name follows
        assertTrue(b.relocate(Path.of("/src/lib"), Path.of("/src/lib2")));
        assertEquals(new Repo("My Lib", Path.of("/src/lib2")), work.children().get(1)); // named by the user: kept
        assertFalse(b.relocate(Path.of("/src/none"), Path.of("/src/x")));

        assertTrue(b.relocate(Path.of("/src/app2"), Path.of("/src/other"))); // bookmarked already
        assertEquals(1, work.children().size());
        assertNotNull(b.find(Path.of("/src/other")));
    }

    @Test
    void collapsed() throws Exception {
        Bookmarks b = new Bookmarks();
        Group work = new Group("work");
        Group sub = new Group("sub");
        sub.setCollapsed(true);
        work.children().add(sub);
        b.root().children().add(work);
        Path file = dir.resolve("bookmarks.txt");
        b.save(file);
        assertEquals("G\twork\n\tG\tsub\tcollapsed\n", Files.readString(file));

        Group w = (Group) Bookmarks.load(file).root().children().getFirst();
        assertFalse(w.isCollapsed());
        assertTrue(((Group) w.children().getFirst()).isCollapsed());
    }

    @Test
    void missingFile() {
        assertEquals(0, Bookmarks.load(dir.resolve("none.txt")).root().children().size());
    }
}
