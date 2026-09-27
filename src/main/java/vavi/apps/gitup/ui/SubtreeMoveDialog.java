/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import vavi.apps.gitup.model.CommandLog;
import vavi.apps.gitup.model.SubtreeMove;
import vavi.apps.gitup.model.SubtreeMove.GitException;
import vavi.apps.gitup.model.SubtreeMove.Plan;
import vavi.apps.gitup.model.SubtreeMove.SourceCleanup;
import vavi.apps.gitup.ui.icons.IconProvider;

import static java.lang.System.getLogger;


/**
 * moves a folder with its history from a repository to another one ({@link SubtreeMove}).
 * <p>
 * the committed folders of the two repositories side by side, drag a folder from the left
 * onto a folder (or the top) of the right, confirm, and the commands run with their output below.
 * the commands also go to the command history of the repositories open in tabs.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
public class SubtreeMoveDialog extends JDialog {

    private static final System.Logger logger = getLogger(SubtreeMoveDialog.class.getName());

    private static final String DELETE_SPLIT = "subtreeMove.deleteSplit";
    private static final String CLEANUP = "subtreeMove.cleanup";
    private static final String AT_TOP = "subtreeMove.atTop";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Path left, right;
    private final Function<Path, CommandLog> commandLogs;

    private final Side leftSide = new Side(true);
    private final Side rightSide = new Side(false);
    private final JTextArea log = new JTextArea();
    private final JButton move = new JButton("Move →");
    private final JButton swap = new JButton("⇄ Swap");
    private final JButton close = new JButton("Close");
    private boolean running;

    /**
     * @param left the source repository
     * @param right the target repository
     * @param commandLogs the command history of a repository open in a tab, null when not open
     */
    public SubtreeMoveDialog(Window owner, Path left, Path right, Function<Path, CommandLog> commandLogs) {
        super(owner, "Move Folder Between Repositories", ModalityType.MODELESS);
        this.left = left;
        this.right = right;
        this.commandLogs = commandLogs;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { if (!running) dispose(); }
        });

        JSplitPane trees = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSide, rightSide);
        trees.setResizeWeight(0.5);
        trees.setBorder(null);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        log.setRows(8);
        JScrollPane logPane = new JScrollPane(log);
        logPane.setBorder(BorderFactory.createTitledBorder("Log"));

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, trees, logPane);
        center.setResizeWeight(0.75);
        center.setBorder(null);

        JLabel hint = new JLabel("Drag a folder from the left onto a folder (or the top) on the right to move it with its history.");
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1));
        hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        move.setToolTipText("Move the selected folder on the left into the selected folder on the right");
        move.addActionListener(e -> {
            String dir = leftSide.selected();
            String target = rightSide.selected();
            if (dir != null && !dir.isEmpty() && target != null) confirm(dir, target);
        });
        swap.setToolTipText("Swap the source and the target");
        swap.addActionListener(e -> swap());
        close.addActionListener(e -> dispose());
        JButton copy = new JButton("Copy Log");
        copy.addActionListener(e -> LogPanel.copy(log.getText()));
        JPanel buttons = new JPanel(new BorderLayout());
        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT));
        west.add(swap);
        west.add(copy);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        east.add(move);
        east.add(close);
        buttons.add(west, BorderLayout.WEST);
        buttons.add(east, BorderLayout.EAST);

        getContentPane().add(hint, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        WindowState.remember(this, "subtreeMove", new Dimension(760, 620));

        leftSide.tree.addTreeSelectionListener(e -> updateButtons());
        rightSide.tree.addTreeSelectionListener(e -> updateButtons());
        reload();
    }

    private void swap() {
        Path p = left;
        left = right;
        right = p;
        reload();
    }

    private void reload() {
        leftSide.load(left);
        rightSide.load(right);
        updateButtons();
    }

    private void updateButtons() {
        String dir = leftSide.selected();
        move.setEnabled(!running && dir != null && !dir.isEmpty() && rightSide.selected() != null);
        swap.setEnabled(!running);
        close.setEnabled(!running);
    }

    // confirmation

    /**
     * asks where and how, then moves.
     *
     * @param dir the folder of the left
     * @param target the folder of the right dropped on, "" for the top
     */
    void confirm(String dir, String target) {
        if (running) return;
        String name = dir.substring(dir.lastIndexOf('/') + 1);
        String asFolder = target.isEmpty() ? name : target + "/" + name;

        JRadioButton folder = new JRadioButton("as the folder \"" + asFolder + "\" (git subtree add)");
        JRadioButton top = new JRadioButton("its contents at the top, merging the unrelated history (git checkout --orphan, pull, merge)");
        ButtonGroup g = new ButtonGroup();
        g.add(folder);
        g.add(top);
        top.setEnabled(target.isEmpty()); // into a folder, the contents would go to an existing prefix
        (target.isEmpty() && WindowState.getFlag(AT_TOP, true) ? top : folder).setSelected(true);

        JCheckBox deleteSplit = new JCheckBox("Delete the temporary split branch in " + left.getFileName() + " afterwards");
        deleteSplit.setSelected(WindowState.getFlag(DELETE_SPLIT, true));
        JRadioButton keep = new JRadioButton("Keep \"" + dir + "\" in " + left.getFileName());
        JRadioButton untrack = new JRadioButton("Stop tracking \"" + dir + "\" in " + left.getFileName() + " (git rm -r --cached, the files stay)");
        JRadioButton delete = new JRadioButton("Remove \"" + dir + "\" from " + left.getFileName() + " (git rm -r)");
        ButtonGroup c = new ButtonGroup();
        c.add(keep);
        c.add(untrack);
        c.add(delete);
        SourceCleanup saved;
        try {
            saved = SourceCleanup.valueOf(WindowState.getString(CLEANUP, SourceCleanup.NONE.name()));
        } catch (IllegalArgumentException e) {
            saved = SourceCleanup.NONE;
        }
        (switch (saved) { case UNTRACK -> untrack; case DELETE -> delete; case NONE -> keep; }).setSelected(true);

        JTextArea preview = new JTextArea(8, 70);
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        Runnable update = () -> {
            Plan plan = plan(dir, folder.isSelected() ? asFolder : "", deleteSplit.isSelected(),
                    untrack.isSelected() ? SourceCleanup.UNTRACK : delete.isSelected() ? SourceCleanup.DELETE : SourceCleanup.NONE);
            StringBuilder sb = new StringBuilder();
            Path cd = null;
            for (SubtreeMove.Command cmd : new SubtreeMove(plan, null).commands()) {
                if (!cmd.repo().equals(cd)) {
                    sb.append("cd ").append(CommandLog.quote(cmd.repo().toString())).append('\n');
                    cd = cmd.repo();
                }
                sb.append("  ").append(cmd.command()).append('\n');
            }
            preview.setText(sb.toString());
            preview.setCaretPosition(0);
        };
        for (javax.swing.AbstractButton b : List.of(folder, top, deleteSplit, keep, untrack, delete)) b.addActionListener(e -> update.run());
        update.run();

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(aligned("<html>Move <b>" + esc(dir) + "</b> of <b>" + esc(left.getFileName().toString()) + "</b> with its history<br>into <b>"
                + esc(right.getFileName().toString()) + "</b> (" + esc(nullToEmpty(RepositoryBrowser.branchOf(right))) + ")</html>"));
        p.add(Box.createVerticalStrut(8));
        p.add(group("Place it", folder, top));
        p.add(group("Afterwards", deleteSplit, keep, untrack, delete));
        p.add(Box.createVerticalStrut(8));
        p.add(aligned(new JLabel("Commands:")));
        JScrollPane sp = new JScrollPane(preview);
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(sp);

        if (JOptionPane.showConfirmDialog(this, p, "Move Folder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        SourceCleanup cleanup = untrack.isSelected() ? SourceCleanup.UNTRACK : delete.isSelected() ? SourceCleanup.DELETE : SourceCleanup.NONE;
        WindowState.setFlag(DELETE_SPLIT, deleteSplit.isSelected());
        WindowState.putString(CLEANUP, cleanup.name());
        if (target.isEmpty()) WindowState.setFlag(AT_TOP, top.isSelected());
        run(plan(dir, folder.isSelected() ? asFolder : "", deleteSplit.isSelected(), cleanup));
    }

    private Plan plan(String dir, String prefix, boolean deleteSplit, SourceCleanup cleanup) {
        return new Plan(left, dir, right, prefix, deleteSplit, cleanup);
    }

    private static JComponent aligned(Object o) {
        JComponent c = o instanceof JComponent j ? j : new JLabel(String.valueOf(o));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel group(String title, JComponent... items) {
        JPanel p = new JPanel(new GridLayout(0, 1));
        p.setBorder(BorderFactory.createTitledBorder(title));
        for (JComponent i : items) p.add(i);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // run

    private void run(Plan plan) {
        running = true;
        updateButtons();
        leftSide.tree.setEnabled(false);
        rightSide.tree.setEnabled(false);
        append("# " + LocalTime.now().format(TIME) + " move " + plan.dir() + " of " + plan.source() + " into " + plan.target()
                + (plan.prefix().isEmpty() ? " (at the top)" : " as " + plan.prefix()));
        // the command history of the tabs, looked up on the EDT beforehand
        Map<Path, CommandLog> logs = new HashMap<>();
        for (Path p : List.of(plan.source(), plan.target())) {
            CommandLog l = commandLogs != null ? commandLogs.apply(p) : null;
            if (l != null) logs.put(p, l);
        }
        String note = "move \"" + plan.dir() + "\" " + plan.source().getFileName() + " → " + plan.target().getFileName();
        SubtreeMove m = new SubtreeMove(plan, (repo, line) -> {
            if (repo != null) {
                CommandLog l = logs.get(repo);
                if (l != null) l.add(line, note);
                append("$ cd " + CommandLog.quote(repo.toString()) + " && " + line);
            } else {
                append("  " + line);
            }
        });
        Thread.ofPlatform().name("subtree move").daemon().start(() -> {
            Throwable error = null;
            try {
                m.run();
            } catch (Throwable t) {
                logger.log(System.Logger.Level.DEBUG, t.getMessage(), t);
                error = t;
            }
            Throwable e = error;
            SwingUtilities.invokeLater(() -> done(plan, e));
        });
    }

    private void done(Plan plan, Throwable e) {
        running = false;
        leftSide.tree.setEnabled(true);
        rightSide.tree.setEnabled(true);
        if (e == null) {
            append("# done");
            reload();
            rightSide.selectLater(plan.prefix());
            JOptionPane.showMessageDialog(this, "Moved \"" + plan.dir() + "\" into " + plan.target().getFileName()
                    + (plan.prefix().isEmpty() ? "" : " as \"" + plan.prefix() + "\"") + "."
                    + (plan.cleanup() != SourceCleanup.NONE ? "\nThe removal in " + plan.source().getFileName() + " is staged, commit it there." : ""),
                    "Move Folder", JOptionPane.INFORMATION_MESSAGE);
        } else {
            append("# failed: " + e.getMessage());
            reload();
            JOptionPane.showMessageDialog(this, e instanceof GitException ? e.getMessage() : String.valueOf(e), "Move Folder", JOptionPane.ERROR_MESSAGE);
        }
        updateButtons();
    }

    /** thread safe */
    private void append(String line) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> append(line));
            return;
        }
        log.append(line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    // trees

    /** a folder of a repository, "" for the top */
    private record Folder(String path) {
        String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    private record Drag(Path repo, String dir) {}

    private static final DataFlavor DRAG = localFlavor();

    private static DataFlavor localFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + Drag.class.getName(), "folder", Drag.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** a repository's committed folders, the left is a drag source, the right a drop target */
    private class Side extends JPanel {
        final boolean source;
        final JLabel title = new JLabel();
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Folder(""));
        final DefaultTreeModel model = new DefaultTreeModel(root);
        final JTree tree = new JTree(model);
        Path repo;

        Side(boolean source) {
            super(new BorderLayout());
            this.source = source;
            title.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setShowsRootHandles(true);
            tree.setCellRenderer(new DefaultTreeCellRenderer() {
                @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                    super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
                    Object o = ((DefaultMutableTreeNode) v).getUserObject();
                    if (o instanceof Folder f) {
                        boolean top = f.path().isEmpty();
                        setIcon(IconProvider.tinted(IconProvider.get().icon(top ? IconProvider.Key.REPOSITORY : IconProvider.Key.FOLDER, 16), IconProvider.LIGHT_BLUE));
                        setText(top ? (repo != null ? repo.getFileName().toString() : "") : f.name());
                    } else {
                        setIcon(null);
                    }
                    return this;
                }
            });
            if (source) {
                tree.setDragEnabled(true);
                tree.setTransferHandler(new TransferHandler() {
                    @Override public int getSourceActions(JComponent c) { return running ? NONE : MOVE; }
                    @Override protected Transferable createTransferable(JComponent c) {
                        String dir = selected();
                        if (dir == null || dir.isEmpty()) return null; // not the whole repository
                        Drag d = new Drag(repo, dir);
                        return new Transferable() {
                            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {DRAG}; }
                            @Override public boolean isDataFlavorSupported(DataFlavor f) { return DRAG.equals(f); }
                            @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                                if (!DRAG.equals(f)) throw new UnsupportedFlavorException(f);
                                return d;
                            }
                        };
                    }
                });
            } else {
                tree.setDropMode(DropMode.ON);
                tree.setTransferHandler(new TransferHandler() {
                    @Override public boolean canImport(TransferSupport s) {
                        if (running || !s.isDrop() || !s.isDataFlavorSupported(DRAG)) return false;
                        return ((JTree.DropLocation) s.getDropLocation()).getPath() != null;
                    }
                    @Override public boolean importData(TransferSupport s) {
                        if (!canImport(s)) return false;
                        try {
                            Drag d = (Drag) s.getTransferable().getTransferData(DRAG);
                            TreePath p = ((JTree.DropLocation) s.getDropLocation()).getPath();
                            Folder f = (Folder) ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
                            if (!d.repo().equals(left)) return false;
                            tree.setSelectionPath(p);
                            // not inside the drop: the dialog would block the drag and drop
                            SwingUtilities.invokeLater(() -> confirm(d.dir(), f.path()));
                            return true;
                        } catch (Exception e) {
                            logger.log(System.Logger.Level.DEBUG, e.getMessage(), e);
                            return false;
                        }
                    }
                });
            }
            add(title, BorderLayout.NORTH);
            add(new JScrollPane(tree), BorderLayout.CENTER);
        }

        /** reads the folders in the background */
        void load(Path repo) {
            this.repo = repo;
            String branch = RepositoryBrowser.branchOf(repo);
            title.setText("<html>" + (source ? "From: " : "To: ") + "<b>" + esc(repo.getFileName().toString()) + "</b>"
                    + (branch != null ? " <font color='#0969da'>" + esc(branch) + "</font>" : "")
                    + "<br><font color='gray' size='-2'>" + esc(repo.toString()) + "</font></html>");
            root.removeAllChildren();
            model.reload();
            Thread.ofVirtual().start(() -> {
                List<String> folders;
                String error = null;
                try {
                    folders = SubtreeMove.folders(repo);
                } catch (RuntimeException e) {
                    folders = List.of();
                    error = e.getMessage();
                }
                List<String> f = folders;
                String err = error;
                SwingUtilities.invokeLater(() -> {
                    if (this.repo != repo) return; // swapped meanwhile
                    build(f);
                    if (err != null) append("# " + repo.getFileName() + ": " + err);
                });
            });
        }

        private void build(List<String> folders) {
            Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
            nodes.put("", root);
            for (String f : folders) { // sorted, a parent comes before its children
                int i = f.lastIndexOf('/');
                DefaultMutableTreeNode parent = nodes.getOrDefault(i < 0 ? "" : f.substring(0, i), root);
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Folder(f));
                parent.add(n);
                nodes.put(f, n);
            }
            model.reload();
            tree.expandRow(0);
            if (!source) tree.setSelectionRow(0);
            if (pending != null) {
                select(pending);
                pending = null;
            }
        }

        /** selected after the next load */
        private String pending;

        /** the selected folder, "" for the top, null for none */
        String selected() {
            TreePath p = tree.getSelectionPath();
            return p == null ? null : ((Folder) ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject()).path();
        }

        /** selects the folder */
        void select(String path) {
            java.util.Enumeration<javax.swing.tree.TreeNode> e = root.depthFirstEnumeration();
            while (e.hasMoreElements()) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
                if (((Folder) n.getUserObject()).path().equals(path)) {
                    TreePath p = new TreePath(n.getPath());
                    tree.setSelectionPath(p);
                    tree.scrollPathToVisible(p);
                    return;
                }
            }
        }

        /** selects the folder after the loading */
        void selectLater(String path) {
            pending = path;
        }
    }
}
