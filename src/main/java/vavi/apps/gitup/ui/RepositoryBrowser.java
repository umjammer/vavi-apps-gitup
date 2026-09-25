/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.TransferHandler;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import vavi.apps.gitup.model.Bookmarks;
import vavi.apps.gitup.model.Bookmarks.Entry;
import vavi.apps.gitup.model.Bookmarks.Group;
import vavi.apps.gitup.model.Bookmarks.Repo;
import vavi.apps.gitup.model.SourceTreeImport;


/**
 * SourceTree-like repository browser: bookmarked repositories in groups.
 * <p>
 * double click (or return) opens a repository in a tab, drag entries onto a group
 * to move them, drop folders from the Finder to add them, F2 renames a group.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class RepositoryBrowser extends JFrame {

    private final Bookmarks bookmarks;
    private final Path file;
    private final Consumer<Path> opener;

    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(model);
    private final JTextField search = new JTextField();

    /** @param opener opens a repository in a tab */
    public RepositoryBrowser(Bookmarks bookmarks, Path file, Consumer<Path> opener) {
        super("Repository Browser");
        this.bookmarks = bookmarks;
        this.file = file;
        this.opener = opener;
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0); // variable, repositories show two lines
        tree.setCellRenderer(new Renderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new Handler());
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { popup(e); return; }
                if (e.getClickCount() == 2 && selectedEntry() instanceof Repo r) opener.accept(r.path());
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        tree.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (selectedEntry() instanceof Repo r) opener.accept(r.path());
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename");
        tree.getActionMap().put("rename", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (selectedEntry() instanceof Group g) rename(g);
            }
        });
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, Keys.menu()), "remove");
        tree.getActionMap().put("remove", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { removeSelected(); }
        });

        search.putClientProperty("JTextField.placeholderText", "Search");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuild(); }
        });

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton add = new JButton("Add…");
        add.setToolTipText("Add an existing local repository");
        add.addActionListener(e -> addExisting());
        JButton group = new JButton("New Group");
        group.addActionListener(e -> newGroup());
        JButton remove = new JButton("Remove");
        remove.setToolTipText("Remove the bookmark (files are kept)");
        remove.addActionListener(e -> removeSelected());
        bar.add(add);
        bar.add(group);
        bar.add(remove);

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        top.add(search, BorderLayout.CENTER);

        setJMenuBar(buildMenuBar());
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(tree), BorderLayout.CENTER);
        getContentPane().add(bar, BorderLayout.SOUTH);
        WindowState.remember(this, "browser", new Dimension(380, 560));
        rebuild();
    }

    /** reflects the bookmarks (after an outside change, e.g. a repository opened from the menu) */
    public void reload() {
        rebuild();
    }

    private void save() {
        try {
            bookmarks.save(file);
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // tree

    private void rebuild() {
        String q = search.getText().strip().toLowerCase();
        rootNode.removeAllChildren();
        add(rootNode, bookmarks.root(), q);
        model.reload();
        expandAll();
    }

    /** @return true when something below matched */
    private boolean add(DefaultMutableTreeNode parent, Group g, String q) {
        boolean any = false;
        for (Entry e : g.children()) {
            switch (e) {
                case Group c -> {
                    DefaultMutableTreeNode n = new DefaultMutableTreeNode(c);
                    boolean groupMatches = q.isEmpty() || c.name().toLowerCase().contains(q);
                    boolean below = add(n, c, groupMatches ? "" : q);
                    if (groupMatches || below) {
                        parent.add(n);
                        any = true;
                    }
                }
                case Repo r -> {
                    if (q.isEmpty() || r.name().toLowerCase().contains(q) || r.path().toString().toLowerCase().contains(q)) {
                        parent.add(new DefaultMutableTreeNode(r, false));
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private Entry selectedEntry() {
        TreePath p = tree.getSelectionPath();
        return p == null ? null : (Entry) ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
    }

    /** the group new entries go to: the selected group, the selected repository's group, or the top */
    private Group targetGroup() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return bookmarks.root();
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) p.getLastPathComponent();
        if (n.getUserObject() instanceof Group g) return g;
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) n.getParent();
        return parent != null && parent.getUserObject() instanceof Group g ? g : bookmarks.root();
    }

    private javax.swing.JMenuBar buildMenuBar() {
        javax.swing.JMenuBar bar = new javax.swing.JMenuBar();
        javax.swing.JMenu menu = new javax.swing.JMenu("Repository Browser");
        JMenuItem add = new JMenuItem("Add Existing Local Repository…");
        add.addActionListener(e -> addExisting());
        JMenuItem group = new JMenuItem("New Group…");
        group.addActionListener(e -> newGroup());
        JMenuItem importSt = new JMenuItem("Import SourceTree Bookmarks…");
        importSt.addActionListener(e -> importSourceTree());
        menu.add(add);
        menu.add(group);
        menu.addSeparator();
        menu.add(importSt);
        bar.add(menu);
        return bar;
    }

    /** imports SourceTree's repository browser (groups and git repositories) */
    void importSourceTree() {
        Path plist = SourceTreeImport.defaultFile();
        if (!Files.exists(plist)) {
            JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
            chooser.setDialogTitle("SourceTree browser.plist");
            chooser.setFileHidingEnabled(false);
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            plist = chooser.getSelectedFile().toPath();
        }
        try {
            Group source = SourceTreeImport.read(plist);
            if (JOptionPane.showConfirmDialog(this, "Import the bookmarks of SourceTree?\n" + plist
                    + "\nGroups with the same name are merged, repositories already here are skipped.",
                    "Import SourceTree Bookmarks", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            SourceTreeImport.Result r = SourceTreeImport.importInto(bookmarks, source);
            save();
            rebuild();
            JOptionPane.showMessageDialog(this, "Imported " + r.added() + " repositories" + (r.skipped() > 0 ? ", " + r.skipped() + " already bookmarked" : "") + ".",
                    "Import SourceTree Bookmarks", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(this, "Cannot import " + plist + ":\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // actions

    private void addExisting() {
        JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
        chooser.setDialogTitle("Add Existing Local Repository");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        List<Path> paths = new ArrayList<>();
        for (File f : chooser.getSelectedFiles()) paths.add(f.toPath());
        addRepositories(paths, targetGroup(), -1);
    }

    /** adds git working directories, @return the number added */
    int addRepositories(List<Path> paths, Group group, int index) {
        List<String> notRepos = new ArrayList<>();
        int added = 0;
        for (Path p : paths) {
            Path n = p.toAbsolutePath().normalize();
            if (!Files.exists(n.resolve(".git"))) {
                notRepos.add(n.toString());
                continue;
            }
            if (bookmarks.find(n) != null) continue;
            Repo r = new Repo(n.getFileName().toString(), n);
            if (index < 0 || index > group.children().size()) group.children().add(r);
            else group.children().add(index + added, r);
            added++;
        }
        save();
        rebuild();
        if (!notRepos.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Not a git working directory:\n" + String.join("\n", notRepos), "Add", JOptionPane.WARNING_MESSAGE);
        }
        return added;
    }

    private void newGroup() {
        String name = JOptionPane.showInputDialog(this, "Group name:", "New Group", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        targetGroup().children().add(new Group(name.strip()));
        save();
        rebuild();
    }

    private void rename(Group g) {
        String name = (String) JOptionPane.showInputDialog(this, "Group name:", "Rename Group", JOptionPane.PLAIN_MESSAGE, null, null, g.name());
        if (name == null || name.isBlank()) return;
        g.setName(name.strip());
        save();
        rebuild();
    }

    private void removeSelected() {
        Entry e = selectedEntry();
        if (e == null) return;
        String what = e instanceof Group g ? "the group \"" + g.name() + "\" and its bookmarks" : "the bookmark \"" + e.name() + "\"";
        if (JOptionPane.showConfirmDialog(this, "Remove " + what + "?\nFiles on disk are not touched.", "Remove",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        bookmarks.remove(e);
        save();
        rebuild();
    }

    private void popup(MouseEvent e) {
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p != null) tree.setSelectionPath(p);
        Entry entry = selectedEntry();
        JPopupMenu menu = new JPopupMenu();
        if (entry instanceof Repo r) {
            item(menu, "Open", () -> opener.accept(r.path()));
            item(menu, "Show in Finder", () -> {
                try {
                    new ProcessBuilder("open", r.path().toString()).start();
                } catch (IOException ex) {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                }
            });
            item(menu, "Copy Path", () -> LogPanel.copy(r.path().toString()));
            menu.addSeparator();
        }
        if (entry instanceof Group g) {
            item(menu, "Rename Group…", () -> rename(g));
        }
        item(menu, "New Group…", this::newGroup);
        item(menu, "Add Existing Local Repository…", this::addExisting);
        item(menu, "Import SourceTree Bookmarks…", this::importSourceTree);
        if (entry != null) {
            menu.addSeparator();
            item(menu, "Remove…", this::removeSelected);
        }
        menu.show(tree, e.getX(), e.getY());
    }

    private static void item(JPopupMenu menu, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(ev -> r.run());
        menu.add(i);
    }

    // rendering

    /** reads the branch from .git/HEAD without libgit2 */
    static String branchOf(Path repo) {
        try {
            Path head = repo.resolve(".git/HEAD");
            if (!Files.isRegularFile(head)) return null;
            String s = Files.readString(head).strip();
            return s.startsWith("ref: refs/heads/") ? s.substring("ref: refs/heads/".length()) : s.length() >= 7 ? s.substring(0, 7) : s;
        } catch (IOException e) {
            return null;
        }
    }

    private static class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
            Object o = ((DefaultMutableTreeNode) v).getUserObject();
            setIcon(null);
            if (o instanceof Repo r) {
                boolean exists = Files.isDirectory(r.path());
                String branch = exists ? branchOf(r.path()) : null;
                setText("<html><b>" + esc(r.name()) + "</b>" + (branch != null ? " <font color='#0969da'>" + esc(branch) + "</font>" : "")
                        + "<br><font color='gray' size='-2'>" + esc(r.path().toString()) + (exists ? "" : " (missing)") + "</font></html>");
                setToolTipText(r.path().toString());
            } else if (o instanceof Group g) {
                setText(g.name());
                setFont(t.getFont().deriveFont(java.awt.Font.BOLD));
                setToolTipText(null);
                return this;
            }
            setFont(t.getFont());
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }

    // drag and drop

    private static final DataFlavor ENTRY = localFlavor();

    private static DataFlavor localFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + Entry.class.getName(), "bookmark", Entry.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private class Handler extends TransferHandler {
        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override protected Transferable createTransferable(JComponent c) {
            Entry e = selectedEntry();
            if (e == null) return null;
            return new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {ENTRY}; }
                @Override public boolean isDataFlavorSupported(DataFlavor f) { return ENTRY.equals(f); }
                @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (!ENTRY.equals(f)) throw new UnsupportedFlavorException(f);
                    return e;
                }
            };
        }

        @Override public boolean canImport(TransferSupport s) {
            if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return true;
            if (!s.isDataFlavorSupported(ENTRY) || !s.isDrop()) return false;
            Group target = dropGroup(s);
            try {
                Entry moving = (Entry) s.getTransferable().getTransferData(ENTRY);
                return !(moving instanceof Group g && (g == target || contains(g, target)));
            } catch (Exception ex) {
                return false;
            }
        }

        @Override public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            Group target = dropGroup(s);
            int index = dropIndex(s);
            try {
                if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    addRepositories(files.stream().map(File::toPath).toList(), target, index);
                    return true;
                }
                Entry moving = (Entry) s.getTransferable().getTransferData(ENTRY);
                move(moving, target, index);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }

        private Group dropGroup(TransferSupport s) {
            if (!s.isDrop()) return bookmarks.root();
            TreePath p = ((JTree.DropLocation) s.getDropLocation()).getPath();
            if (p == null) return bookmarks.root();
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof Group g) return g;
            if (o instanceof Repo) {
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) ((DefaultMutableTreeNode) p.getLastPathComponent()).getParent();
                return parent != null && parent.getUserObject() instanceof Group g ? g : bookmarks.root();
            }
            return bookmarks.root();
        }

        /** insert position among the (unfiltered) children, -1 for the end */
        private int dropIndex(TransferSupport s) {
            if (!s.isDrop()) return -1;
            JTree.DropLocation l = (JTree.DropLocation) s.getDropLocation();
            if (l.getChildIndex() < 0 || l.getPath() == null) return -1;
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) l.getPath().getLastPathComponent();
            if (l.getChildIndex() >= parent.getChildCount()) return -1;
            Object before = ((DefaultMutableTreeNode) parent.getChildAt(l.getChildIndex())).getUserObject();
            return dropGroup(s).children().indexOf(before);
        }
    }

    private static boolean contains(Group g, Group target) {
        for (Entry e : g.children()) {
            if (e == target || e instanceof Group c && contains(c, target)) return true;
        }
        return false;
    }

    /** moves an entry into a group at the index (-1: at the end) */
    void move(Entry entry, Group target, int index) {
        int old = target.children().indexOf(entry);
        bookmarks.remove(entry);
        if (old >= 0 && index > old) index--;
        if (index < 0 || index > target.children().size()) target.children().add(entry);
        else target.children().add(index, entry);
        save();
        rebuild();
    }
}
