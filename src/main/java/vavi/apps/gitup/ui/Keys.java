/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;


/**
 * keyboard helpers.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
final class Keys {

    private Keys() {}

    /** the menu shortcut modifier (⌘ on macOS), also usable headless */
    static int menu() {
        return GraphicsEnvironment.isHeadless() ? InputEvent.META_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }
}
