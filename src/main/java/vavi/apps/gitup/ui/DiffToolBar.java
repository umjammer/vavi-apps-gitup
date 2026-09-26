/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.UIManager;

import vavi.apps.gitup.model.FileChange;
import vavi.apps.gitup.model.LazyPatch;
import vavi.apps.gitup.model.Settings;


/**
 * SourceTree-like bar above the hunk diff: the file shown (with the status icon of the
 * file lists) on the left, a menu of the diff options (whitespace, lines of context) on the right.
 * the options are {@link Settings}, their listeners reopen the shown diff.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
public class DiffToolBar extends JPanel {

    private final JLabel file = new JLabel(" ");
    private final JButton menuButton = new JButton(new EllipsisIcon());

    public DiffToolBar(DiffView view) {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(2, 6, 2, 2)));
        file.setIconTextGap(6);
        menuButton.setToolTipText("Diff options");
        menuButton.putClientProperty("JButton.buttonType", "toolBarButton");
        menuButton.setFocusable(false);
        menuButton.addActionListener(e -> {
            JPopupMenu menu = menu();
            menu.show(menuButton, menuButton.getWidth() - menu.getPreferredSize().width, menuButton.getHeight());
        });
        add(file, BorderLayout.CENTER);
        add(menuButton, BorderLayout.EAST);
        view.addPropertyChangeListener("patch", e -> show((LazyPatch) e.getNewValue()));
    }

    /** shows the file of the patch, nothing for null */
    private void show(LazyPatch patch) {
        if (patch == null) {
            file.setIcon(null);
            file.setText(" ");
            file.setToolTipText(null);
            return;
        }
        FileChange f = patch.file();
        String rename = f.oldPath() != null && !f.oldPath().equals(f.path()) ? f.oldPath() + " → " : "";
        file.setIcon(FileTable.statusIcon(f.kind()));
        file.setText(rename + f.path());
        file.setToolTipText(rename + f.path());
    }

    /** SourceTree's diff menu */
    private static JPopupMenu menu() {
        Settings s = Settings.get();
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup whitespace = new ButtonGroup();
        radio(menu, whitespace, "Show Whitespace", !s.ignoreWhitespace(), () -> s.setIgnoreWhitespace(false));
        radio(menu, whitespace, "Ignore Whitespace", s.ignoreWhitespace(), () -> s.setIgnoreWhitespace(true));
        menu.addSeparator();
        JMenu context = new JMenu("Lines of Context");
        ButtonGroup lines = new ButtonGroup();
        for (int n : Settings.CONTEXT_LINES_CHOICES) {
            radio(context, lines, String.valueOf(n), s.contextLines() == n, () -> s.setContextLines(n));
        }
        menu.add(context);
        return menu;
    }

    private static void radio(Component menu, ButtonGroup group, String label, boolean selected, Runnable r) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label, selected);
        item.addActionListener(e -> { if (!selected) r.run(); });
        group.add(item);
        if (menu instanceof JMenu m) m.add(item);
        else ((JPopupMenu) menu).add(item);
    }

    /** a horizontal ellipsis in a circle (SourceTree's "…" button) */
    private static class EllipsisIcon implements Icon {
        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.translate(x, y);
                g.setColor(c.getForeground());
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new java.awt.geom.Ellipse2D.Double(1.5, 1.5, 13, 13));
                for (int i = -1; i <= 1; i++) {
                    g.fill(new java.awt.geom.Ellipse2D.Double(8 + i * 3.5 - 1.1, 8 - 1.1, 2.2, 2.2));
                }
            } finally {
                g.dispose();
            }
        }

        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
    }
}
