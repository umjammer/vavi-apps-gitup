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
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Stash;


/**
 * left sidebar: branches, remotes, tags. double click checks out.
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

    public SidebarPanel() {
        super(new BorderLayout());
        root.add(branches);
        root.add(remotes);
        root.add(tags);
        root.add(stashes);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { popup(e); return; }
                Stash stash = stashAt(e);
                if (stash != null && listener != null) {
                    if (e.getClickCount() == 2) listener.stashApply(stash, false);
                    else listener.showStash(stash);
                    return;
                }
                Ref ref = refAt(e);
                if (ref == null || listener == null) return;
                if (e.getClickCount() == 2) listener.checkout(ref);
                else listener.reveal(ref);
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRefs(List<Ref> refs, String headBranch) {
        this.headBranch = headBranch;
        branches.removeAllChildren();
        remotes.removeAllChildren();
        tags.removeAllChildren();
        for (Ref r : refs) {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(r);
            switch (r.kind()) {
                case LOCAL -> branches.add(n);
                case REMOTE -> remotes.add(n);
                case TAG -> tags.add(n);
                default -> {}
            }
        }
        model.reload(branches);
        model.reload(remotes);
        model.reload(tags);
        tree.expandPath(new TreePath(branches.getPath()));
        tree.expandPath(new TreePath(remotes.getPath()));
    }

    public void setStashes(List<Stash> list) {
        stashes.removeAllChildren();
        for (Stash s : list) stashes.add(new DefaultMutableTreeNode(s));
        model.reload(stashes);
        tree.expandPath(new TreePath(stashes.getPath()));
    }

    private Stash stashAt(MouseEvent e) {
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p == null) return null;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof Stash s ? s : null;
    }

    private Ref refAt(MouseEvent e) {
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p == null) return null;
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof Ref r ? r : null;
    }

    private void popup(MouseEvent e) {
        Stash stash = stashAt(e);
        if (stash != null) {
            stashPopup(e, stash);
            return;
        }
        Ref ref = refAt(e);
        if (ref == null) return;
        tree.setSelectionPath(tree.getPathForLocation(e.getX(), e.getY()));
        JPopupMenu menu = new JPopupMenu();
        if (ref.kind() != Ref.Kind.TAG) {
            JMenuItem checkout = new JMenuItem(ref.kind() == Ref.Kind.REMOTE ? "Checkout as Local Branch" : "Checkout");
            checkout.addActionListener(ev -> { if (listener != null) listener.checkout(ref); });
            checkout.setEnabled(!(ref.kind() == Ref.Kind.LOCAL && ref.shorthand().equals(headBranch)));
            menu.add(checkout);
            menu.addSeparator();
        }
        JMenuItem copy = new JMenuItem("Copy Name");
        copy.addActionListener(ev -> LogPanel.copy(ref.shorthand()));
        menu.add(copy);
        JMenuItem copyOid = new JMenuItem("Copy SHA");
        copyOid.setEnabled(ref.target() != null);
        copyOid.addActionListener(ev -> LogPanel.copy(ref.target()));
        menu.add(copyOid);
        menu.show(tree, e.getX(), e.getY());
    }

    private void stashPopup(MouseEvent e, Stash stash) {
        tree.setSelectionPath(tree.getPathForLocation(e.getX(), e.getY()));
        JPopupMenu menu = new JPopupMenu();
        JMenuItem apply = new JMenuItem("Apply Stash");
        apply.addActionListener(ev -> { if (listener != null) listener.stashApply(stash, false); });
        menu.add(apply);
        JMenuItem pop = new JMenuItem("Apply and Delete (Pop)");
        pop.addActionListener(ev -> { if (listener != null) listener.stashApply(stash, true); });
        menu.add(pop);
        JMenuItem drop = new JMenuItem("Delete Stash…");
        drop.addActionListener(ev -> { if (listener != null) listener.stashDrop(stash); });
        menu.add(drop);
        menu.addSeparator();
        JMenuItem copy = new JMenuItem("Copy Message");
        copy.addActionListener(ev -> LogPanel.copy(stash.message()));
        menu.add(copy);
        JMenuItem copyOid = new JMenuItem("Copy SHA");
        copyOid.addActionListener(ev -> LogPanel.copy(stash.oid()));
        menu.add(copyOid);
        menu.show(tree, e.getX(), e.getY());
    }

    private class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
            Object o = ((DefaultMutableTreeNode) v).getUserObject();
            if (o instanceof Stash st) {
                setText(st.message());
                setFont(t.getFont());
                setIcon(null);
                setToolTipText("stash@{" + st.index() + "} " + st.oid().substring(0, 7));
            } else if (o instanceof Ref r) {
                boolean head = r.kind() == Ref.Kind.LOCAL && r.shorthand().equals(headBranch);
                setText(r.shorthand());
                setFont(t.getFont().deriveFont(head ? Font.BOLD : Font.PLAIN));
                setIcon(null);
            } else {
                setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() - 1));
                setIcon(null);
            }
            return this;
        }
    }
}
