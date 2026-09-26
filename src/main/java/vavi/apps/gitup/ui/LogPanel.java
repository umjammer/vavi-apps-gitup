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
        /** several commits selected (newest first), the changes of the whole range are shown */
        void selectedMany(List<CommitRow> commits);
        /** GitUp's "Edit Message": rewrites the commit and its descendants */
        void editMessage(CommitRow commit);
        /** rewrites the author of the commit, descendants are rewritten with their trees */
        void editAuthor(CommitRow commit);
        /** GitUp's other history rewrites */
        void rewrite(CommitRow commit, Rewrite rewrite);
        /** SourceTree's "Reset current branch to this commit" */
        void resetTo(CommitRow commit);
        void checkoutCommit(CommitRow commit);
        void mergeCommit(CommitRow commit);
        void cherryPick(CommitRow commit);
        /** one of the dropdowns above the log changed */
        void optionsChanged(vavi.apps.gitup.model.CommitLog.Options options);
    }

    /** GitUp's history rewrites offered in the log */
    public enum Rewrite { SQUASH, FIXUP, MOVE_DOWN, MOVE_UP, DELETE }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** SourceTree's graph colors (its STColors.graph), in its order */
    static final Color[] LANE_COLORS = {
        rgb(0.00, 0.28, 0.69), rgb(0.75, 0.15, 0.00), rgb(1.00, 0.54, 0.00), rgb(0.00, 0.40, 0.26),
        rgb(0.25, 0.19, 0.58), rgb(0.00, 0.55, 0.65), rgb(0.00, 0.39, 1.00), rgb(1.00, 0.33, 0.18),
        rgb(1.00, 0.67, 0.00), rgb(0.21, 0.70, 0.49), rgb(0.39, 0.33, 0.75), rgb(0.00, 0.72, 0.85),
    };

    private static Color rgb(double r, double g, double b) {
        return new Color((float) r, (float) g, (float) b);
    }

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
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
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
            List<CommitRow> selected = selectedCommits();
            if (selected.size() > 1) listener.selectedMany(selected);
            else listener.selected(commitAt(r));
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
        add(optionsBar(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    // SourceTree's dropdowns above the log

    private final javax.swing.JComboBox<String> branchesBox = new javax.swing.JComboBox<>(new String[] {"All Branches", "Current Branch"});
    private final javax.swing.JComboBox<String> remotesBox = new javax.swing.JComboBox<>(new String[] {"Show Remote Branches", "Hide Remote Branches"});
    private final javax.swing.JComboBox<String> orderBox = new javax.swing.JComboBox<>(new String[] {"Date Order", "Ancestor Order"});
    private boolean settingOptions;

    private JComponent optionsBar() {
        JPanel bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        branchesBox.setToolTipText("the history of all branches, or of the current branch only");
        remotesBox.setToolTipText("show the commits and labels of remote branches");
        orderBox.setToolTipText("<html>Date Order: by commit date (git log --date-order)<br>"
                + "Ancestor Order: the commits of a branch kept together (git log --topo-order)</html>");
        for (javax.swing.JComboBox<String> box : List.of(branchesBox, remotesBox, orderBox)) {
            box.putClientProperty("JComponent.sizeVariant", "small");
            box.setFocusable(false);
            box.addActionListener(e -> {
                remotesBox.setEnabled(branchesBox.getSelectedIndex() == 0); // current branch: no other branches at all
                if (!settingOptions && listener != null) listener.optionsChanged(options());
            });
            bar.add(box);
        }
        return bar;
    }

    /** @return the options chosen in the dropdowns */
    public vavi.apps.gitup.model.CommitLog.Options options() {
        return new vavi.apps.gitup.model.CommitLog.Options(branchesBox.getSelectedIndex() == 0,
                remotesBox.getSelectedIndex() == 0, orderBox.getSelectedIndex() == 0);
    }

    /** shows the options without telling the listener */
    public void setOptions(vavi.apps.gitup.model.CommitLog.Options o) {
        settingOptions = true;
        try {
            branchesBox.setSelectedIndex(o.allBranches() ? 0 : 1);
            remotesBox.setSelectedIndex(o.remotes() ? 0 : 1);
            orderBox.setSelectedIndex(o.dateOrder() ? 0 : 1);
            remotesBox.setEnabled(o.allBranches());
        } finally {
            settingOptions = false;
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public JTable getTable() {
        return table;
    }

    /** commit → lane of the loaded rows */
    private final Map<String, Integer> lanes = new java.util.HashMap<>();
    /** told when the lanes change (rows reset or appended) */
    private Runnable lanesListener;

    public void setLanesListener(Runnable lanesListener) {
        this.lanesListener = lanesListener;
    }

    /** @return the color of the commit's lane in the graph, null when the commit is not loaded (yet) */
    public Color laneColor(String oid) {
        Integer lane = oid != null ? lanes.get(oid) : null;
        return lane != null ? LANE_COLORS[lane % LANE_COLORS.length] : null;
    }

    /** clears rows, keeps nothing selected */
    public void reset(boolean uncommitted, Map<String, List<Ref>> refs, String headBranch) {
        this.uncommitted = uncommitted;
        this.refs = refs;
        this.headBranch = headBranch;
        rows.clear();
        lanes.clear();
        if (lanesListener != null) lanesListener.run();
        more = true;
        loading = false;
        model.fireTableDataChanged();
    }

    /** appends a loaded page */
    public void append(List<CommitRow> page, boolean hasMore) {
        int first = model.getRowCount();
        rows.addAll(page);
        for (CommitRow r : page) lanes.put(r.oid(), r.lane());
        if (!page.isEmpty() && lanesListener != null) lanesListener.run();
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
        if (!table.isRowSelected(r)) table.setRowSelectionInterval(r, r);
        List<CommitRow> selected = selectedCommits();
        if (selected.isEmpty()) return;
        JPopupMenu menu = new JPopupMenu();
        if (selected.size() == 1) {
            CommitRow c = selected.getFirst();
            boolean single = c.parents().size() == 1;
            String head = headBranch != null ? headBranch : "HEAD";
            // git
            item(menu, "Checkout…", () -> { if (listener != null) listener.checkoutCommit(c); });
            item(menu, "Merge into " + head + "…", () -> { if (listener != null) listener.mergeCommit(c); });
            item(menu, "Cherry-pick…", () -> { if (listener != null) listener.cherryPick(c); });
            item(menu, "New Branch Here…", () -> { if (listener != null) listener.createBranch(c); });
            item(menu, "Reset " + head + " to This Commit…", () -> { if (listener != null) listener.resetTo(c); });
            menu.addSeparator();
            // GitUp's history rewriting
            item(menu, "Edit Message…", () -> { if (listener != null) listener.editMessage(c); });
            item(menu, "Edit Author…", () -> { if (listener != null) listener.editAuthor(c); });
            rewriteItem(menu, "Squash Into Parent…", c, Rewrite.SQUASH, single);
            rewriteItem(menu, "Fixup Into Parent", c, Rewrite.FIXUP, single);
            rewriteItem(menu, "Move Up (Swap with Child)", c, Rewrite.MOVE_UP, single);
            rewriteItem(menu, "Move Down (Swap with Parent)", c, Rewrite.MOVE_DOWN, single);
            rewriteItem(menu, "Delete Commit…", c, Rewrite.DELETE, single);
            menu.addSeparator();
        }
        javax.swing.JMenu copy = new javax.swing.JMenu("Copy");
        copyItem(copy, "SHA", selected, CommitRow::oid);
        copyItem(copy, "Short SHA", selected, CommitRow::shortOid);
        copyItem(copy, "Summary", selected, CommitRow::summary);
        copyItem(copy, "Message", selected, c -> c.message().strip());
        copyItem(copy, "Author", selected, c -> c.author() + " <" + c.email() + ">");
        copyItem(copy, "Row", selected, c -> String.join("\t", c.shortOid(), c.summary(), c.author() + " <" + c.email() + ">", DATE.format(c.time())));
        menu.add(copy);
        menu.show(table, e.getX(), e.getY());
    }

    /** copies the value of every selected commit, one per line (messages separated by a blank line) */
    private static void copyItem(javax.swing.JMenu menu, String label, List<CommitRow> rows, java.util.function.Function<CommitRow, String> f) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> copy(rows.stream().map(f).collect(Collectors.joining(label.equals("Message") ? "\n\n" : "\n"))));
        menu.add(i);
    }

    /** @return selected commits in table order (newest first), without the uncommitted row */
    public List<CommitRow> selectedCommits() {
        List<CommitRow> list = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            CommitRow c = commitAt(r);
            if (c != null) list.add(c);
        }
        return list;
    }

    private static JMenuItem item(JPopupMenu menu, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        menu.add(i);
        return i;
    }

    private void rewriteItem(JPopupMenu menu, String label, CommitRow c, Rewrite r, boolean enabled) {
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

    /** summary with SourceTree-like ref labels: a rounded badge with an icon (branch, current branch, tag) and the name */
    private class DescriptionRenderer extends JComponent implements TableCellRenderer {
        private CommitRow row;
        private String placeholder;
        private boolean selected;
        private Color background, foreground;

        DescriptionRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            setFont(t.getFont());
            selected = sel;
            background = sel ? t.getSelectionBackground() : t.getBackground();
            foreground = sel ? t.getSelectionForeground() : t.getForeground();
            row = v instanceof CommitRow x ? x : null;
            placeholder = row == null ? String.valueOf(v) : null;
            setToolTipText(row != null && row.parents().size() > 1
                    ? "merge: " + row.parents().stream().map(p -> p.substring(0, 7)).collect(Collectors.joining(" ")) : null);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setColor(background);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font font = getFont();
                java.awt.FontMetrics fm = g.getFontMetrics(font);
                int h = getHeight();
                int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
                int x = 4;
                if (row == null) {
                    g.setFont(font.deriveFont(Font.ITALIC));
                    g.setColor(foreground);
                    g.drawString(placeholder, x, baseline);
                    return;
                }
                for (Ref ref : refs.getOrDefault(row.oid(), List.of())) {
                    x = paintLabel(g, ref, x, h) + 4;
                }
                g.setFont(font);
                g.setColor(foreground);
                g.drawString(row.summary(), x, baseline);
            } finally {
                g.dispose();
            }
        }

        /** @return the right end of the label */
        private int paintLabel(Graphics2D g, Ref ref, int x, int h) {
            boolean head = ref.kind() == Ref.Kind.LOCAL && ref.shorthand().equals(headBranch);
            Color[] c = labelColors(ref.kind());
            Font font = head ? getFont().deriveFont(Font.BOLD) : getFont();
            java.awt.FontMetrics fm = g.getFontMetrics(font);
            int iconSize = Math.max(12, fm.getHeight() - 2);
            javax.swing.Icon icon = vavi.apps.gitup.ui.icons.IconProvider.get().icon(switch (ref.kind()) {
                case TAG -> vavi.apps.gitup.ui.icons.IconProvider.Key.LABEL_TAG;
                default -> head ? vavi.apps.gitup.ui.icons.IconProvider.Key.LABEL_HEAD : vavi.apps.gitup.ui.icons.IconProvider.Key.LABEL_BRANCH;
            }, iconSize);
            String text = ref.shorthand();
            int pad = 4, gap = 3;
            int w = pad + (icon != null ? icon.getIconWidth() + gap : 0) + fm.stringWidth(text) + pad + 1;
            int lh = Math.min(h - 2, fm.getHeight() + 2);
            int y = (h - lh) / 2;
            g.setColor(c[0]);
            g.fillRoundRect(x, y, w, lh, 6, 6);
            g.setColor(c[1]);
            g.drawRoundRect(x, y, w, lh, 6, 6);
            int ix = x + pad;
            if (icon != null) {
                icon.paintIcon(this, g, ix, y + (lh - icon.getIconHeight()) / 2 + 1);
                ix += icon.getIconWidth() + gap;
            }
            g.setFont(font);
            g.setColor(new Color(0x111111));
            g.drawString(text, ix, y + (lh - fm.getHeight()) / 2 + fm.getAscent() + 1);
            return x + w;
        }
    }

    /** fill and border of a ref label */
    private static Color[] labelColors(Ref.Kind kind) {
        return switch (kind) {
            case LOCAL -> new Color[] {new Color(0xdbeafe), new Color(0x93b4e6)};
            case REMOTE -> new Color[] {new Color(0xe9d5ff), new Color(0xb89ae0)};
            case TAG -> new Color[] {new Color(0xfef3c7), new Color(0xd9bf6a)};
            default -> new Color[] {new Color(0xe5e7eb), new Color(0xb0b4bb)};
        };
    }

    /** paints lanes, edges and the commit node */
    static class GraphRenderer extends JComponent implements TableCellRenderer {
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

        /**
         * the lines from the commit down to its parents: {lane, 1 when it joins a lane already
         * passing through (a short stub), 0 when it goes down to the next row}.
         * a parent the commit's own lane goes on to is not joined to another lane too:
         * the first commit of a branch would have two lines to its parent (the fork point).
         */
        static List<int[]> edges(CommitRow row) {
            String[] before = row.before(), after = row.after();
            int lane = row.lane();
            String own = lane < after.length ? after[lane] : null;
            List<int[]> edges = new ArrayList<>();
            for (int j = 0; j < after.length; j++) {
                if (after[j] == null || !row.parents().contains(after[j])) continue;
                boolean passthrough = j < before.length && after[j].equals(before[j]) && j != lane;
                if (passthrough && after[j].equals(own)) continue;
                edges.add(new int[] {j, passthrough ? 1 : 0});
            }
            return edges;
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
            for (int[] e : edges(row)) {
                int j = e[0];
                boolean passthrough = e[1] == 1;
                g.setColor(color(passthrough ? lane : j));
                g.drawLine(x(lane), mid, x(j), passthrough ? mid + h / 4 : h);
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
