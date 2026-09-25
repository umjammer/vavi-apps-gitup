/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui.icons;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;
import javax.swing.Icon;


/**
 * the application's own icons, simple vector glyphs drawn with Java2D (sharp at any scale).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class BuiltinIconProvider implements IconProvider {

    private static final Color BLUE = new Color(0x0969da);
    private static final Color GREEN = new Color(0x2da44e);
    private static final Color RED = new Color(0xcf222e);
    private static final Color PURPLE = new Color(0x8250df);
    private static final Color ORANGE = new Color(0xbc4c00);
    private static final Color YELLOW = new Color(0xbf8700);
    private static final Color GRAY = new Color(0x57606a);

    @Override
    public String name() {
        return "builtin";
    }

    @Override
    public Icon icon(Key key, int size) {
        return switch (key) {
            case COMMIT -> glyph(size, BLUE, g -> { line(g, 2, 12, 22, 12); g.fillOval(7, 7, 10, 10); });
            case PULL -> glyph(size, BLUE, g -> { arrow(g, 12, 2, 12, 16); tray(g); });
            case PUSH -> glyph(size, GREEN, g -> { arrow(g, 12, 16, 12, 2); tray(g); });
            case FETCH -> glyph(size, PURPLE, g -> { g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {2.5f, 3f}, 0)); line(g, 12, 2, 12, 14); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); head(g, 12, 18, true); tray(g); });
            case BRANCH, BRANCHES, LOCAL_BRANCH -> glyph(size, key == Key.LOCAL_BRANCH ? GRAY : BLUE, BuiltinIconProvider::branch);
            case REMOTE_BRANCH -> glyph(size, GRAY, BuiltinIconProvider::branch);
            case REMOTES -> glyph(size, PURPLE, BuiltinIconProvider::cloud);
            case STASH, STASHES, STASH_ITEM -> glyph(size, key == Key.STASH_ITEM ? GRAY : ORANGE, g -> { g.drawRoundRect(3, 9, 18, 12, 3, 3); line(g, 3, 13, 21, 13); line(g, 6, 5, 18, 5); line(g, 8, 2, 16, 2); });
            case TAG, TAGS, TAG_ITEM -> glyph(size, key == Key.TAG_ITEM ? GRAY : YELLOW, BuiltinIconProvider::tag);
            case DISCARD -> glyph(size, RED, g -> { g.drawArc(4, 5, 16, 16, 90, 270); head(g, 12, 5, false); });
            case REFRESH -> glyph(size, BLUE, g -> { g.drawArc(4, 4, 16, 16, 60, 300); arrowHead(g, 20, 6); });
            case MERGE -> glyph(size, GREEN, g -> { g.fillOval(4, 2, 5, 5); g.fillOval(4, 17, 5, 5); g.fillOval(15, 9, 5, 5); line(g, 6.5, 7, 6.5, 17); g.draw(curve(6.5, 7, 6.5, 12, 17.5, 9)); });
            case FOLDER -> glyph(size, BLUE, g -> { Path2D p = new Path2D.Double(); p.moveTo(2, 6); p.lineTo(9, 6); p.lineTo(11, 8); p.lineTo(22, 8); p.lineTo(22, 20); p.lineTo(2, 20); p.closePath(); g.draw(p); });
            case REPOSITORY -> glyph(size, ORANGE, g -> { g.drawRoundRect(4, 2, 16, 20, 3, 3); line(g, 8, 2, 8, 22); line(g, 11, 7, 17, 7); line(g, 11, 11, 17, 11); });
            case FILE_ADDED -> badge(size, GREEN, "+");
            case FILE_MODIFIED -> badge(size, BLUE, "…");
            case FILE_DELETED -> badge(size, RED, "−");
            case FILE_RENAMED -> badge(size, PURPLE, "→");
            case FILE_UNTRACKED -> badge(size, GRAY, "?");
            case FILE_CONFLICTED -> badge(size, YELLOW, "!");
            case FILE_TYPECHANGE -> badge(size, ORANGE, "T");
        };
    }

    // drawing on a 24 x 24 canvas

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
    }

    private static void arrow(Graphics2D g, double x1, double y1, double x2, double y2) {
        line(g, x1, y1, x2, y2);
        head(g, x2, y2, y2 > y1);
    }

    private static void head(Graphics2D g, double x, double y, boolean down) {
        double d = down ? -5 : 5;
        line(g, x - 5, y + d, x, y);
        line(g, x + 5, y + d, x, y);
    }

    private static void arrowHead(Graphics2D g, double x, double y) {
        line(g, x, y, x - 5, y);
        line(g, x, y, x, y + 5);
    }

    private static void tray(Graphics2D g) {
        Path2D p = new Path2D.Double();
        p.moveTo(3, 16);
        p.lineTo(3, 21);
        p.lineTo(21, 21);
        p.lineTo(21, 16);
        g.draw(p);
    }

    private static void branch(Graphics2D g) {
        g.fillOval(4, 2, 5, 5);
        g.fillOval(4, 17, 5, 5);
        g.fillOval(15, 5, 5, 5);
        line(g, 6.5, 7, 6.5, 17);
        g.draw(curve(17.5, 10, 17.5, 15, 6.5, 17));
    }

    private static void cloud(Graphics2D g) {
        Path2D p = new Path2D.Double();
        p.moveTo(6, 19);
        p.curveTo(1, 19, 1, 12, 6, 12);
        p.curveTo(6, 6, 14, 5, 16, 10);
        p.curveTo(22, 9, 23, 19, 17, 19);
        p.closePath();
        g.draw(p);
    }

    private static void tag(Graphics2D g) {
        Path2D p = new Path2D.Double();
        p.moveTo(3, 3);
        p.lineTo(12, 3);
        p.lineTo(21, 12);
        p.lineTo(12, 21);
        p.lineTo(3, 12);
        p.closePath();
        g.draw(p);
        g.fillOval(6, 6, 4, 4);
    }

    private static java.awt.Shape curve(double x1, double y1, double cx, double cy, double x2, double y2) {
        return new java.awt.geom.QuadCurve2D.Double(x1, y1, cx, cy, x2, y2);
    }

    /** a glyph drawn on a 24 x 24 canvas with a 2px stroke, scaled to size */
    static Icon glyph(int size, Color color, Consumer<Graphics2D> painter) {
        return new ScaledIcon(size, (g, s) -> {
            g.scale(s / 24.0, s / 24.0);
            g.setColor(color);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            painter.accept(g);
        });
    }

    /** a colored rounded square with a symbol, for file status */
    static Icon badge(int size, Color color, String symbol) {
        return new ScaledIcon(size, (g, s) -> {
            g.setColor(color);
            g.fill(new RoundRectangle2D.Double(1, 1, s - 2, s - 2, s / 3.0, s / 3.0));
            g.setColor(Color.white);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, s - 5)));
            var fm = g.getFontMetrics();
            g.drawString(symbol, (s - fm.stringWidth(symbol)) / 2f, (s - fm.getHeight()) / 2f + fm.getAscent());
        });
    }

    /** an icon painted by a function of (graphics, size) */
    record ScaledIcon(int size, java.util.function.BiConsumer<Graphics2D, Integer> painter) implements Icon {
        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                painter.accept(g, size);
            } finally {
                g.dispose();
            }
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }
}
