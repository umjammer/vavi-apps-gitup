/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Rectangle;
import java.util.prefs.Preferences;
import javax.swing.JLabel;
import javax.swing.JSplitPane;
import javax.swing.JTable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * WindowStateTest. uses a throwaway preferences node.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
class WindowStateTest {

    Preferences saved;

    @BeforeEach
    void setup() {
        saved = WindowState.prefs;
        WindowState.prefs = Preferences.userRoot().node("vavi-apps-gitup-test-" + System.nanoTime());
    }

    @AfterEach
    void teardown() throws Exception {
        WindowState.prefs.removeNode();
        WindowState.prefs = saved;
    }

    @Test
    void restoreDividerAndColumns() {
        WindowState.prefs.putInt("s.divider", 123);
        WindowState.prefs.put("t.columns", "10,20,30");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JLabel("a"), new JLabel("b"));
        WindowState.remember(split, "s");
        assertEquals(123, split.getDividerLocation());

        JTable table = new JTable(1, 3);
        WindowState.remember(table, "t");
        assertEquals(20, table.getColumnModel().getColumn(1).getPreferredWidth());
    }

    @Test
    void boundsAndFlags() {
        assertNull(WindowState.bounds("w"));
        WindowState.prefs.put("w.bounds", "1,2,300,400");
        assertEquals(new Rectangle(1, 2, 300, 400), WindowState.bounds("w"));
        WindowState.prefs.put("x.bounds", "broken");
        assertNull(WindowState.bounds("x"));

        assertFalse(WindowState.getFlag("browser.visible", false));
        WindowState.setFlag("browser.visible", true);
        assertTrue(WindowState.getFlag("browser.visible", false));
    }
}
