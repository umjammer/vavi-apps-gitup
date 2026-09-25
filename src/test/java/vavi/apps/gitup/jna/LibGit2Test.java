/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import com.sun.jna.ptr.IntByReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import vavi.apps.gitup.jna.Structs.GitDiffOptions;
import vavi.apps.gitup.jna.Structs.GitStatusOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * LibGit2Test.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class LibGit2Test {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @Test
    void version() {
        IntByReference a = new IntByReference(), b = new IntByReference(), c = new IntByReference();
        LibGit2.INSTANCE.git_libgit2_version(a, b, c);
        assertEquals("1.4", a.getValue() + "." + b.getValue());
    }

    /** verifies the struct layouts with the values the init functions write */
    @Test
    void layouts() {
        GitDiffOptions o = new GitDiffOptions();
        assertEquals(0, LibGit2.INSTANCE.git_diff_options_init(o, 1));
        o.read();
        assertEquals(1, o.version);
        assertEquals(3, o.context_lines);

        GitStatusOptions s = new GitStatusOptions();
        assertEquals(0, LibGit2.INSTANCE.git_status_options_init(s, 1));
        s.read();
        assertEquals(1, s.version);
    }
}
