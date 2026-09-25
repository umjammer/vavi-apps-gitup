/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rococoa.Foundation;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.jna.LibGit2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * NativePromptTest. builds the AppKit forms on the main thread (without running them modally).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class NativePromptTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @Test
    void forms() {
        LibGit2.INSTANCE.hashCode(); // AppKit comes with GitUpKit
        String[] result = Foundation.callOnMainThread(() -> {
            NSAutoreleasePool pool = NSAutoreleasePool.new_();
            try {
                NativePrompt.Form f = NativePrompt.form("Authentication", "https://example.com/r.git", "alice", "token", "Remember");
                assertNotNull(f.alert());
                Foundation.sendReturnsVoid(f.secret(), "setStringValue:", Foundation.cfString("s3cret"));
                NativePrompt.Form p = NativePrompt.form("SSH Key", "key", null, "Passphrase", null);
                assertNull(p.user());
                assertNull(p.remember());
                return new String[] {f.userText(), f.secretText(), String.valueOf(f.rememberChecked())};
            } finally {
                pool.drain();
            }
        });
        assertEquals("alice", result[0]);
        assertEquals("s3cret", result[1]);
        assertTrue(Boolean.parseBoolean(result[2]));
    }

    @Test
    void mainThreadOnly() {
        assertThrows(IllegalStateException.class, () -> NativePrompt.userPassword("u", "x", false));
    }
}
