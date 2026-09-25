/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.Icon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.ui.icons.IconProvider.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * IconProviderTest. renders every icon to target/icons-*.png.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
class IconProviderTest {

    static boolean sourceTreeInstalled() {
        // the asset catalog is read through AppKit, GitUpKit is loaded (on the main thread) for that
        return SourceTreeIconProvider.locate() != null && GitUpKitLocator.find() != null;
    }

    @Test
    void builtin() throws Exception {
        IconProvider p = new BuiltinIconProvider();
        for (Key k : Key.values()) {
            Icon i = p.icon(k, 24);
            assertNotNull(i, k.name());
            assertEquals(24, i.getIconWidth());
        }
        sheet(p, "target/icons-builtin.png");
    }

    @Test
    @EnabledIf("sourceTreeInstalled")
    void sourceTree() throws Exception {
        vavi.apps.gitup.jna.LibGit2.INSTANCE.hashCode(); // AppKit comes with GitUpKit
        IconProvider p = SourceTreeIconProvider.find();
        List<Key> missing = new ArrayList<>();
        for (Key k : Key.values()) {
            if (p.icon(k, 24) == null) missing.add(k);
        }
        assertTrue(missing.isEmpty(), "missing: " + missing);
        sheet(p, "target/icons-sourcetree.png");
    }

    /** every icon at 16, 24 and 32 */
    static void sheet(IconProvider p, String file) throws Exception {
        Key[] keys = Key.values();
        BufferedImage img = new BufferedImage(keys.length * 36, 3 * 36, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.white);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        int[] sizes = {16, 24, 32};
        for (int r = 0; r < sizes.length; r++) {
            for (int c = 0; c < keys.length; c++) {
                Icon i = p.icon(keys[c], sizes[r]);
                if (i != null) i.paintIcon(null, g, c * 36 + 2, r * 36 + 2);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", Path.of(file).toFile());
    }
}
