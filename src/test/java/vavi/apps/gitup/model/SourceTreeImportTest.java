/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.model.Bookmarks.Group;
import vavi.apps.gitup.model.Bookmarks.Repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


/**
 * SourceTreeImportTest. an NSKeyedArchiver plist shaped like SourceTree's browser.plist.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledOnOs(OS.MAC)
class SourceTreeImportTest {

    @TempDir
    Path dir;

    static String uid(int i) {
        return "<dict><key>CF$UID</key><integer>" + i + "</integer></dict>";
    }

    static String node(int name, int path, int children, int type) {
        return "<dict><key>$class</key>" + uid(17) + "<key>name</key>" + uid(name) + "<key>path</key>" + uid(path)
                + (children >= 0 ? "<key>children</key>" + uid(children) : "")
                + "<key>repositoryType</key>" + uid(type) + "<key>isLeaf</key>" + uid(0) + "</dict>";
    }

    static final String PLIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
            <key>$archiver</key><string>NSKeyedArchiver</string>
            <key>$top</key><dict><key>root</key>%s</dict>
            <key>$objects</key><array>
            <string>$null</string>
            <dict><key>NS.objects</key><array>%s%s%s</array></dict>
            %s
            <string>work</string>
            <dict><key>NS.objects</key><array>%s</array></dict>
            <integer>255</integer>
            %s
            %s
            <string>dotfiles</string>
            <string>/home/dotfiles</string>
            <integer>1</integer>
            <string>app</string>
            <string>/src/app</string>
            <string>hgrepo</string>
            <string>/src/hg</string>
            <integer>2</integer>
            <dict><key>$classname</key><string>STBrowserNode</string></dict>
            %s
            </array></dict></plist>
            """.formatted(uid(1), uid(2), uid(6), uid(18),
            node(3, 0, 4, 5),        // 2: group "work"
            uid(7),                  // 4: children of work
            node(8, 9, -1, 10),      // 6: repo dotfiles
            node(11, 12, -1, 10),    // 7: repo app in work
            node(13, 14, -1, 15));   // 18: hg repo, skipped

    @Test
    void readAndMerge() throws Exception {
        Path plist = dir.resolve("browser.plist");
        Files.writeString(plist, PLIST);
        Group root = SourceTreeImport.read(plist);
        assertEquals(2, root.children().size(), "group and git repository, hg skipped");
        Group work = (Group) root.children().getFirst();
        assertEquals("work", work.name());
        assertEquals(new Repo("app", Path.of("/src/app")), work.children().getFirst());
        assertEquals(new Repo("dotfiles", Path.of("/home/dotfiles")), root.children().get(1));

        Bookmarks b = new Bookmarks();
        Group existing = new Group("work");
        existing.children().add(new Repo("mine", Path.of("/src/mine")));
        b.root().children().add(existing);
        b.addIfAbsent(Path.of("/home/dotfiles"));
        SourceTreeImport.Result r = SourceTreeImport.importInto(b, root);
        assertEquals(1, r.added());
        assertEquals(1, r.skipped());
        assertEquals(2, existing.children().size(), "merged into the group with the same name");
        assertNotNull(b.find(Path.of("/src/app")));

        // importing again adds nothing
        assertEquals(0, SourceTreeImport.importInto(b, SourceTreeImport.read(plist)).added());
    }
}
