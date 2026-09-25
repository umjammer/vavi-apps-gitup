/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;


/**
 * SourceTree's toolbar count badge: a pill with a number over the top right corner of an icon.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public record BadgeIcon(Icon icon, int count, Color color) implements Icon {

    /** SourceTree's red */
    public static final Color RED = new Color(0xe5484d);

    /** the icon with the count, the icon itself when the count is 0 (or less) */
    public static Icon of(Icon icon, int count) {
        return icon == null || count <= 0 ? icon : new BadgeIcon(icon, count, RED);
    }

    /** "99+" over 99 */
    static String text(int count) {
        return count > 99 ? "99+" : String.valueOf(count);
    }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        icon.paintIcon(c, g0, x, y);
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String s = text(count);
            int h = Math.max(11, icon.getIconHeight() / 2);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, h - 3));
            FontMetrics fm = g.getFontMetrics();
            int w = Math.max(h, fm.stringWidth(s) + 6);
            // over the top right corner, a little outside like SourceTree's
            int bx = x + icon.getIconWidth() - w + w / 3, by = y - h / 4;
            g.setColor(color);
            g.fillRoundRect(bx, by, w, h, h, h);
            g.setColor(Color.white);
            g.drawString(s, bx + (w - fm.stringWidth(s)) / 2f, by + (h - fm.getHeight()) / 2f + fm.getAscent());
        } finally {
            g.dispose();
        }
    }

    @Override public int getIconWidth() { return icon.getIconWidth(); }
    @Override public int getIconHeight() { return icon.getIconHeight(); }
}
