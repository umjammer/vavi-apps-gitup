/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.util.List;

import vavi.apps.gitup.model.GitHooks.Preset;


/**
 * a source of hook presets on the web, listed in {@code META-INF/services/vavi.apps.gitup.model.HookPresetProvider}.
 * <p>
 * whole hook scripts of any language are made presets by {@link GitHooks#wrap(String, String)}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-01 nsano initial version <br>
 */
public interface HookPresetProvider {

    /** for the menu */
    String name();

    /** fetches the presets, it takes time */
    List<Preset> presets() throws IOException;
}
