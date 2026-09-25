/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import javax.swing.Icon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;


/**
 * BadgeIconTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
class BadgeIconTest {

    @Test
    void badge() {
        Icon plain = new BuiltinIconProvider().icon(IconProvider.Key.PULL, 24);
        assertSame(plain, BadgeIcon.of(plain, 0), "no badge without count");
        assertNull(BadgeIcon.of(null, 3));
        Icon b = BadgeIcon.of(plain, 3);
        assertInstanceOf(BadgeIcon.class, b);
        assertEquals(24, b.getIconWidth(), "same size as the icon, the toolbar does not move");
        assertEquals("3", BadgeIcon.text(3));
        assertEquals("99", BadgeIcon.text(99));
        assertEquals("99+", BadgeIcon.text(100));
    }
}
