/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import javax.swing.Icon;

import static java.lang.System.getLogger;


/**
 * supplies the icons of the application.
 * <p>
 * {@code -Dgitup.icons=sourcetree} uses the icons of an installed SourceTree.app, read from
 * its bundle at runtime (nothing of SourceTree is distributed with this application),
 * {@code builtin} the application's own drawn icons. by default SourceTree's are used when
 * SourceTree.app is found.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public interface IconProvider {

    /** the icons the application asks for */
    enum Key {
        // toolbar
        COMMIT, PULL, PUSH, FETCH, BRANCH, STASH, DISCARD, REFRESH, TAG, MERGE,
        // sidebar
        BRANCHES, REMOTES, TAGS, STASHES, LOCAL_BRANCH, REMOTE_BRANCH, TAG_ITEM, STASH_ITEM,
        // repository browser
        FOLDER, REPOSITORY,
        // ref labels in the log
        LABEL_BRANCH, LABEL_HEAD, LABEL_TAG,
        // settings tabs
        PREFS_GENERAL, PREFS_ACCOUNTS, PREFS_DIFF, PREFS_HISTORY,
        // file status
        FILE_ADDED, FILE_MODIFIED, FILE_DELETED, FILE_RENAMED, FILE_UNTRACKED, FILE_CONFLICTED, FILE_TYPECHANGE
    }

    /** @return the icon of about size x size, null when this provider has none */
    Icon icon(Key key, int size);

    String name();

    /** loads what must not be loaded on the EDT, call before the UI starts */
    default void preload() {}

    /** the provider chosen by {@code gitup.icons} */
    static IconProvider get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        private Holder() {}

        static final IconProvider INSTANCE = create();

        private static IconProvider create() {
            String p = System.getProperty("gitup.icons", "auto");
            if (!p.equals("builtin")) {
                try {
                    IconProvider st = SourceTreeIconProvider.find();
                    if (st != null) return new FallbackIconProvider(st, new BuiltinIconProvider());
                } catch (Throwable t) {
                    getLogger(IconProvider.class.getName()).log(System.Logger.Level.WARNING, "SourceTree icons: " + t.getMessage(), t);
                }
            }
            return new BuiltinIconProvider();
        }
    }

    /** SourceTree's light blue of the repository browser icons */
    java.awt.Color LIGHT_BLUE = new java.awt.Color(0x4fa3e8);

    /** the icon painted in one color (keeping its alpha: shapes and cut-outs), null for null */
    static Icon tinted(Icon icon, java.awt.Color color) {
        return icon == null ? null : new TintedIcon(icon, color);
    }

    /** an icon recolored at the device resolution */
    record TintedIcon(Icon icon, java.awt.Color color) implements Icon {
        @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g0, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) g0;
            double scale = Math.max(1, g.getTransform().getScaleX());
            int w = (int) Math.ceil(icon.getIconWidth() * scale), h = (int) Math.ceil(icon.getIconHeight() * scale);
            if (w <= 0 || h <= 0) return;
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D ig = image.createGraphics();
            try {
                ig.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                ig.scale(scale, scale);
                icon.paintIcon(c, ig, 0, 0);
            } finally {
                ig.dispose();
            }
            colorize(image, color);
            g.drawImage(image, x, y, icon.getIconWidth(), icon.getIconHeight(), null);
        }

        @Override public int getIconWidth() { return icon.getIconWidth(); }
        @Override public int getIconHeight() { return icon.getIconHeight(); }

        /**
         * keeps the alpha and the shading: the darkest pixels become the color, lighter ones
         * a paler tint of it (an outline stays stronger than a light fill)
         */
        static void colorize(java.awt.image.BufferedImage image, java.awt.Color color) {
            int w = image.getWidth(), h = image.getHeight();
            int[] px = image.getRGB(0, 0, w, h, null, 0, w);
            double min = 1;
            for (int p : px) if ((p >>> 24) > 0x20) min = Math.min(min, luminance(p));
            double range = Math.max(1e-3, 1 - min);
            for (int i = 0; i < px.length; i++) {
                int a = px[i] >>> 24;
                if (a == 0) continue;
                double t = Math.min(1, (1 - luminance(px[i])) / range); // 1: darkest → full color
                int r = (int) Math.round(255 + (color.getRed() - 255) * t);
                int g = (int) Math.round(255 + (color.getGreen() - 255) * t);
                int b = (int) Math.round(255 + (color.getBlue() - 255) * t);
                px[i] = a << 24 | r << 16 | g << 8 | b;
            }
            image.setRGB(0, 0, w, h, px, 0, w);
        }

        private static double luminance(int p) {
            return (0.299 * (p >> 16 & 0xff) + 0.587 * (p >> 8 & 0xff) + 0.114 * (p & 0xff)) / 255;
        }
    }

    /** asks the first, then the second */
    record FallbackIconProvider(IconProvider first, IconProvider second) implements IconProvider {
        @Override public Icon icon(Key key, int size) {
            Icon i = first.icon(key, size);
            return i != null ? i : second.icon(key, size);
        }

        @Override public String name() {
            return first.name() + "+" + second.name();
        }

        @Override public void preload() {
            first.preload();
            second.preload();
        }
    }
}
