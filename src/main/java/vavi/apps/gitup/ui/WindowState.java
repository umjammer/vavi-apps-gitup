/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.prefs.Preferences;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumnModel;


/**
 * remembers window bounds, split pane dividers and table column widths in the user preferences.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class WindowState {

    private WindowState() {}

    static Preferences prefs = Preferences.userNodeForPackage(WindowState.class).node("layout");

    /** restores the bounds (when still on a screen) or uses the default size, then keeps them saved */
    public static void remember(Window w, String key, Dimension defaultSize) {
        Rectangle r = bounds(key);
        if (r != null && isOnScreen(r)) {
            w.setBounds(r);
        } else {
            w.setSize(defaultSize);
            w.setLocationByPlatform(true);
        }
        if (w instanceof Frame f && prefs.getBoolean(key + ".maximized", false)) f.setExtendedState(Frame.MAXIMIZED_BOTH);
        w.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { save(w, key); }
            @Override public void componentMoved(ComponentEvent e) { save(w, key); }
        });
    }

    private static void save(Window w, String key) {
        if (!w.isShowing()) return;
        boolean maximized = w instanceof Frame f && (f.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        prefs.putBoolean(key + ".maximized", maximized);
        if (maximized) return; // keep the normal bounds
        Rectangle r = w.getBounds();
        prefs.put(key + ".bounds", r.x + "," + r.y + "," + r.width + "," + r.height);
    }

    static Rectangle bounds(String key) {
        String s = prefs.get(key + ".bounds", null);
        if (s == null) return null;
        try {
            String[] v = s.split(",");
            return new Rectangle(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** true when at least the title bar area is visible on some screen */
    static boolean isOnScreen(Rectangle r) {
        if (GraphicsEnvironment.isHeadless()) return false;
        Rectangle title = new Rectangle(r.x, r.y, r.width, 30);
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (d.getDefaultConfiguration().getBounds().intersects(title)) return true;
        }
        return false;
    }

    /** restores the divider location, then keeps it saved */
    public static void remember(JSplitPane split, String key) {
        int loc = prefs.getInt(key + ".divider", -1);
        if (loc > 0) split.setDividerLocation(loc);
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (split.isShowing() && split.getDividerLocation() > 0) prefs.putInt(key + ".divider", split.getDividerLocation());
        });
    }

    /** restores the column widths, then keeps them saved */
    public static void remember(JTable table, String key) {
        TableColumnModel m = table.getColumnModel();
        String s = prefs.get(key + ".columns", null);
        if (s != null) {
            String[] v = s.split(",");
            for (int i = 0; i < Math.min(v.length, m.getColumnCount()); i++) {
                try {
                    int w = Integer.parseInt(v[i]);
                    if (w > 0) m.getColumn(i).setPreferredWidth(w);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        m.addColumnModelListener(new TableColumnModelListener() {
            @Override public void columnMarginChanged(ChangeEvent e) {
                if (!table.isShowing()) return;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < m.getColumnCount(); i++) sb.append(i > 0 ? "," : "").append(m.getColumn(i).getWidth());
                prefs.put(key + ".columns", sb.toString());
            }
            @Override public void columnAdded(TableColumnModelEvent e) {}
            @Override public void columnRemoved(TableColumnModelEvent e) {}
            @Override public void columnMoved(TableColumnModelEvent e) {}
            @Override public void columnSelectionChanged(ListSelectionEvent e) {}
        });
    }

    /** a boolean flag, e.g. whether the repository browser was shown */
    public static boolean getFlag(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    public static void setFlag(String key, boolean value) {
        prefs.putBoolean(key, value);
    }

    public static String getString(String key, String def) {
        return prefs.get(key, def);
    }

    public static void putString(String key, String value) {
        prefs.put(key, value);
    }
}
