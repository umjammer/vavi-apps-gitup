/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.GitRepo.Ref;


/**
 * the history (upper pane): graph, description with ref badges, date, author, commit.
 * <p>
 * rows are loaded page by page, the next page is requested when the view scrolls near the end.
 * an "Uncommitted changes" row comes first when the working copy is dirty.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class LogPanel extends JPanel {

    /** selection callback: null commit means "Uncommitted changes" */
    public interface Listener {
        void selected(CommitRow commit);
        void loadMore();
        void createBranch(CommitRow commit);
        /** GitUp's "Edit Message": rewrites the commit and its descendants */
        void editMessage(CommitRow commit);
        /** GitUp's other history rewrites */
        void rewrite(CommitRow commit, Rewrite rewrite);
    }

    /** GitUp's history rewrites offered in the log */
    public enum Rewrite { SQUASH, FIXUP, MOVE_DOWN, MOVE_UP, DELETE }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Color[] LANE_COLORS = {
        new Color(0x0969da), new Color(0x2da44e), new Color(0xbf3989), new Color(0xbc4c00),
        new Color(0x8250df), new Color(0x1b7c83), new Color(0xcf222e), new Color(0x9a6700),
    };

    private static final int LANE_WIDTH = 14;

    /** column indices: graph, description, commit, author, date */
    static final int COMMIT = 2, AUTHOR = 3, DATE_COLUMN = 4;

    private final List<CommitRow> rows = new ArrayList<>();
    private Map<String, List<Ref>> refs = Collections.emptyMap();
    private String headBranch;
    private boolean uncommitted;
    /** false until the first {@link #reset} */
    private boolean more;
    private boolean loading;
    private Listener listener;

    private final Model model = new Model();
    private final JTable table = new JTable(model);

    public LogPanel() {
        super(new BorderLayout());
        table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setCellRenderer(new GraphRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(LANE_WIDTH * 4);
        table.getColumnModel().getColumn(1).setCellRenderer(new DescriptionRenderer());
        table.getColumnModel().getColumn(1).setPreferredWidth(600);
        table.getColumnModel().getColumn(COMMIT).setPreferredWidth(80);
        table.getColumnModel().getColumn(AUTHOR).setPreferredWidth(260);
        table.getColumnModel().getColumn(DATE_COLUMN).setPreferredWidth(130);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        DefaultTableCellRenderer monoRenderer = new DefaultTableCellRenderer();
        monoRenderer.setFont(mono);
        table.getColumnModel().getColumn(COMMIT).setCellRenderer((t, v, s, f, r, c) -> {
            Component comp = monoRenderer.getTableCellRendererComponent(t, v, s, false, r, c);
            comp.setFont(mono);
            return comp;
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || listener == null) return;
            int r = table.getSelectedRow();
            if (r < 0) return;
            listener.selected(commitAt(r));
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        });
        int menu = Keys.menu();
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), "copyOid");
        table.getActionMap().put("copyOid", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                CommitRow c = table.getSelectedRow() < 0 ? null : commitAt(table.getSelectedRow());
                if (c != null) copy(c.oid());
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> maybeLoadMore());
        add(scroll, BorderLayout.CENTER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public JTable getTable() {
        return table;
    }

    /** clears rows, keeps nothing selected */
    public void reset(boolean uncommitted, Map<String, List<Ref>> refs, String headBranch) {
        this.uncommitted = uncommitted;
        this.refs = refs;
        this.headBranch = headBranch;
        rows.clear();
        more = true;
        loading = false;
        model.fireTableDataChanged();
    }

    /** appends a loaded page */
    public void append(List<CommitRow> page, boolean hasMore) {
        int first = model.getRowCount();
        rows.addAll(page);
        more = hasMore;
        loading = false;
        if (!page.isEmpty()) model.fireTableRowsInserted(first, model.getRowCount() - 1);
        int lanes = 1;
        for (CommitRow r : page) lanes = Math.max(lanes, Math.max(r.before().length, r.after().length));
        int w = Math.min(lanes, 20) * LANE_WIDTH + LANE_WIDTH;
        if (w > table.getColumnModel().getColumn(0).getPreferredWidth()) table.getColumnModel().getColumn(0).setPreferredWidth(w);
        maybeLoadMore();
    }

    public void setUncommitted(boolean uncommitted) {
        if (this.uncommitted == uncommitted) return;
        this.uncommitted = uncommitted;
        model.fireTableDataChanged();
    }

    public boolean hasUncommitted() {
        return uncommitted;
    }

    /** selects the first row (uncommitted changes or HEAD) */
    public void selectFirst() {
        if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
    }

    /** selects the commit if loaded, @return false when not found */
    public boolean select(String oid) {
        if (oid == null) {
            if (uncommitted) table.setRowSelectionInterval(0, 0);
            return uncommitted;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).oid().equals(oid)) {
                int r = i + (uncommitted ? 1 : 0);
                table.setRowSelectionInterval(r, r);
                table.scrollRectToVisible(table.getCellRect(r, 0, true));
                return true;
            }
        }
        return false;
    }

    /** @return the commit, null for the uncommitted row */
    private CommitRow commitAt(int viewRow) {
        int i = viewRow - (uncommitted ? 1 : 0);
        return i < 0 ? null : rows.get(i);
    }

    private void maybeLoadMore() {
        if (!more || loading || listener == null) return;
        int last = table.rowAtPoint(new java.awt.Point(0, table.getVisibleRect().y + table.getVisibleRect().height));
        if (last < 0 || last >= model.getRowCount() - 50) {
            loading = true;
            listener.loadMore();
        }
    }

    private void popup(MouseEvent e) {
        int r = table.rowAtPoint(e.getPoint());
        if (r < 0) return;
        table.setRowSelectionInterval(r, r);
        CommitRow c = commitAt(r);
        if (c == null) return;
        JPopupMenu menu = new JPopupMenu();
        item(menu, "Copy SHA", () -> copy(c.oid()));
        item(menu, "Copy Short SHA", () -> copy(c.shortOid()));
        item(menu, "Copy Summary", () -> copy(c.summary()));
        item(menu, "Copy Message", () -> copy(c.message()));
        item(menu, "Copy Author", () -> copy(c.author() + " <" + c.email() + ">"));
        item(menu, "Copy Row", () -> copy(String.join("\t", c.shortOid(), c.summary(), DATE.format(c.time()), c.author())));
        menu.addSeparator();
        item(menu, "Edit Message…", () -> { if (listener != null) listener.editMessage(c); });
        javax.swing.JMenu rewrite = new javax.swing.JMenu("Rewrite");
        boolean single = c.parents().size() == 1;
        rewriteItem(rewrite, "Squash Into Parent…", c, Rewrite.SQUASH, single);
        rewriteItem(rewrite, "Fixup Into Parent", c, Rewrite.FIXUP, single);
        rewrite.addSeparator();
        rewriteItem(rewrite, "Move Up (Swap with Child)", c, Rewrite.MOVE_UP, single);
        rewriteItem(rewrite, "Move Down (Swap with Parent)", c, Rewrite.MOVE_DOWN, single);
        rewrite.addSeparator();
        rewriteItem(rewrite, "Delete Commit…", c, Rewrite.DELETE, single);
        menu.add(rewrite);
        item(menu, "New Branch Here…", () -> { if (listener != null) listener.createBranch(c); });
        menu.show(table, e.getX(), e.getY());
    }

    private static void item(JPopupMenu menu, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        menu.add(i);
    }

    private void rewriteItem(javax.swing.JMenu menu, String label, CommitRow c, Rewrite r, boolean enabled) {
        JMenuItem i = new JMenuItem(label);
        i.setEnabled(enabled);
        i.addActionListener(e -> { if (listener != null) listener.rewrite(c, r); });
        menu.add(i);
    }

    static void copy(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private class Model extends AbstractTableModel {
        private final String[] names = {"Graph", "Description", "Commit", "Author", "Date"};

        @Override public int getRowCount() { return rows.size() + (uncommitted ? 1 : 0); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }

        @Override public Object getValueAt(int r, int c) {
            CommitRow row = commitAt(r);
            if (row == null) return c == 1 ? "Uncommitted changes" : c == 0 ? null : "";
            return switch (c) {
                case 0, 1 -> row;
                case COMMIT -> row.shortOid();
                case AUTHOR -> row.author() + " <" + row.email() + ">";
                default -> DATE.format(row.time());
            };
        }
    }

    /** summary with ref badges */
    private class DescriptionRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, "", sel, false, r, c);
            if (!(v instanceof CommitRow row)) {
                setText("<html><i>" + v + "</i></html>");
                return this;
            }
            StringBuilder sb = new StringBuilder("<html>");
            for (Ref ref : refs.getOrDefault(row.oid(), List.of())) {
                String color = switch (ref.kind()) {
                    case LOCAL -> "#dbeafe";
                    case REMOTE -> "#e9d5ff";
                    case TAG -> "#fef3c7";
                    default -> "#e5e7eb";
                };
                boolean head = ref.kind() == Ref.Kind.LOCAL && ref.shorthand().equals(headBranch);
                sb.append("<span style='background-color:").append(color).append(";color:#111'>&nbsp;")
                  .append(head ? "<b>" : "").append(esc(ref.shorthand())).append(head ? "</b>" : "")
                  .append("&nbsp;</span> ");
            }
            sb.append(esc(row.summary())).append("</html>");
            setText(sb.toString());
            setToolTipText(row.parents().size() > 1 ? "merge: " + row.parents().stream().map(p -> p.substring(0, 7)).collect(Collectors.joining(" ")) : null);
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }

    /** paints lanes, edges and the commit node */
    private static class GraphRenderer extends JComponent implements TableCellRenderer {
        private CommitRow row;
        private boolean selected;
        private Color background;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            row = (CommitRow) v;
            selected = sel;
            background = sel ? t.getSelectionBackground() : t.getBackground();
            return this;
        }

        private static int x(int lane) {
            return LANE_WIDTH / 2 + lane * LANE_WIDTH + 2;
        }

        private static Color color(int lane) {
            return LANE_COLORS[lane % LANE_COLORS.length];
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(background);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(2f));
            int h = getHeight(), mid = h / 2;
            if (row == null) { // uncommitted changes
                g.setColor(Color.gray);
                g.drawOval(x(0) - 4, mid - 4, 8, 8);
                g.drawLine(x(0), mid + 4, x(0), h);
                return;
            }
            String[] before = row.before(), after = row.after();
            int lane = row.lane();
            for (int i = 0; i < before.length; i++) {
                if (before[i] == null) continue;
                g.setColor(color(i));
                if (before[i].equals(row.oid())) {
                    g.drawLine(x(i), 0, x(lane), mid);
                } else {
                    g.drawLine(x(i), 0, x(i), h);
                }
            }
            for (int j = 0; j < after.length; j++) {
                if (after[j] != null && row.parents().contains(after[j])) {
                    boolean passthrough = j < before.length && after[j].equals(before[j]) && j != lane;
                    g.setColor(color(passthrough ? lane : j));
                    g.drawLine(x(lane), mid, x(j), passthrough ? mid + h / 4 : h);
                }
            }
            g.setColor(color(lane));
            g.fillOval(x(lane) - 4, mid - 4, 8, 8);
            if (selected) {
                g.setColor(Color.white);
                g.drawOval(x(lane) - 4, mid - 4, 8, 8);
            }
        }
    }
}
