/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import vavi.apps.gitup.jna.GitUpKitLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * SpellCheckerTest. macOS's spell checker.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class SpellCheckerTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @Test
    void check() {
        SpellChecker c = new SpellChecker();
        String text = "fix the café 日本語 recieve buffer\n\nwhen it is emtpy";
        List<SpellChecker.Range> r = c.check(text);
        List<String> words = r.stream().map(x -> text.substring(x.start(), x.end())).toList();
        assertEquals(List.of("recieve", "emtpy"), words);
        assertTrue(c.guesses(text, r.getFirst()).contains("receive"), "suggestions");

        c.ignore("recieve");
        assertEquals(List.of("emtpy"), c.check(text).stream().map(x -> text.substring(x.start(), x.end())).toList(), "ignored in this document");
        assertEquals(2, new SpellChecker().check(text).size(), "not in another one");
        assertEquals(List.of(), c.check(""));
    }
}
