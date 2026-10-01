/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.util.ServiceLoader;


/**
 * LafPlugin.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-01 nsano initial version <br>
 */
public interface LafPlugin {

    /**
     * @param name plaf name
     */
    boolean accepts(String name);

    /** */
    void init();

    /**
     * @return nullable
     */
    static LafPlugin factory(String name) {
        for (var plugin : ServiceLoader.load(LafPlugin.class)) {
            if (plugin.accepts(name)) {
                return plugin;
            }
        }
        return null;
    }
}
