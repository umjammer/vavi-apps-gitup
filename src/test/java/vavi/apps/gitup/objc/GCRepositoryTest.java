/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rococoa.ObjCObjectByReference;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;
import org.rococoa.cocoa.foundation.NSError;

import vavi.apps.gitup.jna.GitUpKitLocator;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * GCRepositoryTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class GCRepositoryTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @Test
    void open() {
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            String path = Path.of("").toAbsolutePath().toString();
            ObjCObjectByReference e = new ObjCObjectByReference();
            GCRepository repo = GCRepository.CLASS.alloc().initWithExistingLocalRepository_error(path, e);
            NSError error = e.getValueAs(NSError.class);
            assertNotNull(repo, error == null ? null : error.localizedDescription());
            assertTrue(repo.workingDirectoryPath().startsWith(path));
            GCRepository.GCBranch b = repo.findLocalBranchWithName_error("main", e);
            System.err.println("branch: " + (b == null ? null : b.fullName()));
        } finally {
            pool.drain();
        }
    }
}
