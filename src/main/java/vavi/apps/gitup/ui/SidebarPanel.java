/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Remote;
import vavi.apps.gitup.model.GitRepo.Stash;
import vavi.apps.gitup.ui.icons.IconProvider;


/**
 * left sidebar: branches, remotes (each with its branches), tags, stashes.
 * double click checks out (or applies a stash), right click shows SourceTree-like menus.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class SidebarPanel extends JPanel {

    public interface Listener {
        void checkout(Ref ref);
        /** jumps to the commit in the log */
        void reveal(Ref ref);
        void stashApply(Stash stash, boolean drop);
        void stashDrop(Stash stash);
        /** shows the stash's changes */
        void showStash(Stash stash);
        void newBranch();
        void renameBranch(Ref branch);
        /** asks which local branches to delete, the given one checked */
        void deleteBranch(Ref branch);
        /** deletes the branch on its remote */
        void deleteRemoteBranch(Ref branch);
        void newRemote();
        void editRemote(Remote remote);
        void removeRemote(Remote remote);
    }

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("repository");
    private final DefaultMutableTreeNode branches = new DefaultMutableTreeNode("BRANCHES");
    private final DefaultMutableTreeNode remotes = new DefaultMutableTreeNode("REMOTES");
    private final DefaultMutableTreeNode tags = new DefaultMutableTreeNode("TAGS");
    private final DefaultMutableTreeNode stashes = new DefaultMutableTreeNode("STASHES");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private String headBranch;
    private Listener listener;
    /** remotes the user collapsed, kept over refreshes */
    private final Set<String> collapsedRemotes = new HashSet<>();

    public SidebarPanel() {
        super(new BorderLayout());
        root.add(branches);
        root.add(remotes);
        root.add(tags);
        root.add(stashes);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(tree.getFontMetrics(tree.getFont()).getHeight() + 10); // roomier lines
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { popup(e); return; }
                Object o = objectAt(e);
                if (listener == null) return;
                if (o instanceof Stash stash) {
                    if (e.getClickCount() == 2) listener.stashApply(stash, false);
                    else listener.showStash(stash);
                } else if (o instanceof Ref ref) {
                    if (e.getClickCount() == 2) listener.checkout(ref);
                    else listener.reveal(ref);
                }
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        });
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            @Override public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { remoteExpansion(e.getPath(), true); }
            @Override public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { remoteExpansion(e.getPath(), false); }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private boolean updating;

    private void remoteExpansion(TreePath p, boolean expanded) {
        if (updating) return;
        if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Remote r) {
            if (expanded) collapsedRemotes.remove(r.name());
            else collapsedRemotes.add(r.name());
        }
    }

    /** @param remoteList remotes, their branches are grouped under them */
    public void setRefs(List<Ref> refs, List<Remote> remoteList, String headBranch) {
        this.headBranch = headBranch;
        updating = true;
        try {
            branches.removeAllChildren();
            remotes.removeAllChildren();
            tags.removeAllChildren();
            // longest remote name first, a remote name may contain '/'
            List<Remote> byLength = remoteList.stream().sorted(Comparator.comparingInt((Remote r) -> r.name().length()).reversed()).toList();
            java.util.Map<String, DefaultMutableTreeNode> remoteNodes = new java.util.LinkedHashMap<>();
            for (Remote r : remoteList) remoteNodes.put(r.name(), new DefaultMutableTreeNode(r));
            for (Ref r : refs) {
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(r, false);
                switch (r.kind()) {
                    case LOCAL -> branches.add(n);
                    case REMOTE -> {
                        Remote owner = byLength.stream().filter(x -> r.shorthand().startsWith(x.name() + "/")).findFirst().orElse(null);
                        if (owner != null) remoteNodes.get(owner.name()).add(n);
                        else remotes.add(n);
                    }
                    case TAG -> tags.add(n);
                    default -> {}
                }
            }
            remoteNodes.values().forEach(remotes::add);
            model.reload(branches);
            model.reload(remotes);
            model.reload(tags);
            tree.expandPath(new TreePath(branches.getPath()));
            tree.expandPath(new TreePath(remotes.getPath()));
            for (DefaultMutableTreeNode n : remoteNodes.values()) {
                if (!collapsedRemotes.contains(((Remote) n.getUserObject()).name())) tree.expandPath(new TreePath(n.getPath()));
            }
        } finally {
            updating = false;
        }
    }

    public void setStashes(List<Stash> list) {
        stashes.removeAllChildren();
        for (Stash s : list) stashes.add(new DefaultMutableTreeNode(s, false));
        model.reload(stashes);
        tree.expandPath(new TreePath(stashes.getPath()));
    }

    private DefaultMutableTreeNode nodeAt(MouseEvent e) {
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        return p == null ? null : (DefaultMutableTreeNode) p.getLastPathComponent();
    }

    private Object objectAt(MouseEvent e) {
        DefaultMutableTreeNode n = nodeAt(e);
        return n == null ? null : n.getUserObject();
    }

    private void popup(MouseEvent e) {
        DefaultMutableTreeNode node = nodeAt(e);
        if (node == null || listener == null) return;
        tree.setSelectionPath(new TreePath(node.getPath()));
        Object o = node.getUserObject();
        JPopupMenu menu = new JPopupMenu();
        if (node == branches) {
            item(menu, "New Branch…", listener::newBranch);
        } else if (node == remotes) {
            item(menu, "New Remote…", listener::newRemote);
        } else if (o instanceof Remote r) {
            item(menu, "Edit Remote…", () -> listener.editRemote(r));
            item(menu, "Remove Remote…", () -> listener.removeRemote(r));
            menu.addSeparator();
            item(menu, "New Remote…", listener::newRemote);
            menu.addSeparator();
            item(menu, "Copy URL", () -> LogPanel.copy(r.url()));
            item(menu, "Copy Name", () -> LogPanel.copy(r.name()));
        } else if (o instanceof Stash stash) {
            item(menu, "Apply Stash", () -> listener.stashApply(stash, false));
            item(menu, "Apply and Delete (Pop)", () -> listener.stashApply(stash, true));
            item(menu, "Delete Stash…", () -> listener.stashDrop(stash));
            menu.addSeparator();
            item(menu, "Copy Message", () -> LogPanel.copy(stash.message()));
            item(menu, "Copy SHA", () -> LogPanel.copy(stash.oid()));
        } else if (o instanceof Ref ref) {
            boolean head = ref.kind() == Ref.Kind.LOCAL && ref.shorthand().equals(headBranch);
            switch (ref.kind()) {
                case LOCAL -> {
                    item(menu, "Checkout " + ref.shorthand(), () -> listener.checkout(ref)).setEnabled(!head);
                    menu.addSeparator();
                    item(menu, "Rename " + ref.shorthand() + "…", () -> listener.renameBranch(ref));
                    item(menu, "Delete " + ref.shorthand() + "…", () -> listener.deleteBranch(ref)).setEnabled(!head);
                    menu.addSeparator();
                }
                case REMOTE -> {
                    item(menu, "Checkout as Local Branch", () -> listener.checkout(ref));
                    menu.addSeparator();
                    item(menu, "Delete " + ref.shorthand() + " from the Remote…", () -> listener.deleteRemoteBranch(ref));
                    menu.addSeparator();
                }
                default -> {}
            }
            item(menu, "Copy Name", () -> LogPanel.copy(ref.shorthand()));
            item(menu, "Copy SHA", () -> LogPanel.copy(ref.target())).setEnabled(ref.target() != null);
        } else {
            return;
        }
        menu.show(tree, e.getX(), e.getY());
    }

    private static JMenuItem item(JPopupMenu menu, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(ev -> r.run());
        menu.add(i);
        return i;
    }

    /** commit → the color of its lane in the log graph (null: unknown) */
    private java.util.function.Function<String, java.awt.Color> laneColor = oid -> null;

    /** branch icons take the color of their tip's lane in the graph */
    public void setLaneColor(java.util.function.Function<String, java.awt.Color> laneColor) {
        this.laneColor = laneColor;
        tree.repaint();
    }

    private class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) v;
            Object o = n.getUserObject();
            setToolTipText(null);
            if (o instanceof Stash st) {
                setText(st.message());
                setFont(t.getFont());
                setIcon(IconProvider.get().icon(IconProvider.Key.STASH_ITEM, 16));
                setToolTipText("stash@{" + st.index() + "} " + st.oid().substring(0, 7));
            } else if (o instanceof Remote r) {
                setText(r.name());
                setFont(t.getFont());
                setIcon(IconProvider.get().icon(IconProvider.Key.REMOTES, 16));
                setToolTipText(r.url() + (r.pushUrl() != null ? " (push: " + r.pushUrl() + ")" : ""));
            } else if (o instanceof Ref r) {
                boolean head = r.kind() == Ref.Kind.LOCAL && r.shorthand().equals(headBranch);
                // remote branches are shown under their remote, without its name
                String text = r.shorthand();
                if (r.kind() == Ref.Kind.REMOTE && n.getParent() instanceof DefaultMutableTreeNode p && p.getUserObject() instanceof Remote rm) {
                    text = text.substring(rm.name().length() + 1);
                }
                setText(text);
                setFont(t.getFont().deriveFont(head ? Font.BOLD : Font.PLAIN));
                javax.swing.Icon icon = IconProvider.get().icon(switch (r.kind()) {
                    case REMOTE -> IconProvider.Key.REMOTE_BRANCH;
                    case TAG -> IconProvider.Key.TAG_ITEM;
                    default -> IconProvider.Key.LOCAL_BRANCH;
                }, 16);
                java.awt.Color lane = r.kind() == Ref.Kind.TAG ? null : laneColor.apply(r.target());
                setIcon(lane != null ? IconProvider.tinted(icon, lane) : icon);
                setToolTipText(r.name());
            } else {
                setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() - 1));
                IconProvider.Key key = n == branches ? IconProvider.Key.BRANCHES : n == remotes ? IconProvider.Key.REMOTES
                        : n == tags ? IconProvider.Key.TAGS : n == stashes ? IconProvider.Key.STASHES : null;
                setIcon(key != null ? IconProvider.get().icon(key, 16) : null);
            }
            return this;
        }
    }
}
