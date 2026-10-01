/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;

import vavi.apps.gitup.model.GitHooks;
import vavi.apps.gitup.model.GitHooks.Hook;
import vavi.apps.gitup.model.GitHooks.Parameter;
import vavi.apps.gitup.model.GitHooks.Preset;
import vavi.apps.gitup.model.GitHooks.Scope;
import vavi.apps.gitup.model.HookPresets;
import vavi.apps.gitup.ui.icons.IconProvider;

import static java.lang.System.getLogger;


/**
 * git hook manager, the lower part of the sidebar.
 * <pre>
 *  GLOBAL HOOKS   (core.hooksPath in the global config)
 *    [x] pre-push
 *  LOCAL HOOKS    (.git/hooks)
 *    [ ] pre-commit
 * </pre>
 * the check box is the executable bit of the hook file, git runs executable hooks only.
 * a hook made by "New Hook…" has no file until a preset is applied or a script is written.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-28 nsano initial version <br>
 */
public class HooksPanel extends JPanel {

    private static final System.Logger logger = getLogger(HooksPanel.class.getName());

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("hooks");
    private final Map<Scope, DefaultMutableTreeNode> scopes = new EnumMap<>(Scope.class);
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private GitHooks hooks;
    /** the built-in and the user presets */
    private final HookPresets presets = new HookPresets();
    /** hooks made by "New Hook…" without a file yet */
    private final Set<Hook> pending = new LinkedHashSet<>();
    /** the state of the last refresh */
    private Snapshot state;

    /** what the tree shows */
    private record Snapshot(Map<Scope, Path> dirs, Map<Scope, Boolean> active, Map<Scope, List<Hook>> hooks, Map<Hook, Info> infos) {}

    /** a hook file as read at the last refresh, the renderer does not touch the file */
    private record Info(boolean enabled, List<String> presets) {}

    public HooksPanel() {
        super(new BorderLayout());
        for (Scope s : Scope.values()) {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(s);
            scopes.put(s, n);
            root.add(n);
        }
        model.reload();
        tree.expandPath(new TreePath(root)); // the hidden root, scopes without hooks are leaves and do not expand it
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(tree.getFontMetrics(tree.getFont()).getHeight() + 10);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) { popup(e); return; }
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null || !(((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Hook h)) return;
                Rectangle r = tree.getPathBounds(p);
                if (h.exists() && e.getX() < r.x + checkWidth()) {
                    setEnabled(h, !h.enabled());
                } else if (e.getClickCount() == 2) {
                    edit(h);
                }
            }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    /** the repository whose hooks are shown */
    public void setHooks(GitHooks hooks) {
        this.hooks = hooks;
        pending.clear();
        refresh();
    }

    /** reads the hook directories again */
    public void refresh() {
        GitHooks h = hooks;
        if (h == null) return;
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() {
                Map<Scope, Path> dirs = new EnumMap<>(Scope.class);
                Map<Scope, Boolean> active = new EnumMap<>(Scope.class);
                Map<Scope, List<Hook>> list = new EnumMap<>(Scope.class);
                Map<Hook, Info> infos = new HashMap<>();
                for (Scope s : Scope.values()) {
                    dirs.put(s, h.dir(s));
                    active.put(s, h.active(s));
                    list.put(s, h.list(s));
                    for (Hook hook : list.get(s)) {
                        try {
                            infos.put(hook, new Info(hook.enabled(), GitHooks.presetsIn(GitHooks.read(hook))));
                        } catch (RuntimeException e) {
                            logger.log(System.Logger.Level.WARNING, "hooks: " + hook.file() + ": " + e.getMessage());
                        }
                    }
                }
                return new Snapshot(dirs, active, list, infos);
            }
            @Override protected void done() {
                try {
                    if (h == hooks) show(get());
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "hooks: " + e.getMessage(), e);
                }
            }
        }.execute();
    }

    private void show(Snapshot s) {
        state = s;
        for (Scope scope : Scope.values()) {
            DefaultMutableTreeNode n = scopes.get(scope);
            n.removeAllChildren();
            List<Hook> list = new ArrayList<>(s.hooks().get(scope));
            pending.removeIf(h -> h.scope() == scope && list.stream().anyMatch(x -> x.name().equals(h.name())));
            pending.stream().filter(h -> h.scope() == scope).forEach(list::add);
            list.sort((a, b) -> Integer.compare(GitHooks.CATEGORIES.indexOf(a.name()), GitHooks.CATEGORIES.indexOf(b.name())));
            for (Hook h : list) n.add(new DefaultMutableTreeNode(h, false));
            model.reload(n);
            tree.expandPath(new TreePath(n.getPath()));
        }
    }

    private void popup(MouseEvent e) {
        TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p == null || hooks == null) return;
        HookPresets.remote(); // starts loading the web presets
        tree.setSelectionPath(p);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) p.getLastPathComponent();
        JPopupMenu menu = new JPopupMenu();
        if (node.getUserObject() instanceof Scope scope) {
            item(menu, "New Hook…", () -> newHook(scope));
            menu.addSeparator();
            Path dir = state != null ? state.dirs().get(scope) : null;
            item(menu, "Reveal in Finder", () -> reveal(dir)).setEnabled(dir != null);
            item(menu, "Refresh", this::refresh);
        } else if (node.getUserObject() instanceof Hook h) {
            JMenu presets = new JMenu("Preset Hooks");
            String script = h.exists() ? GitHooks.read(h) : "";
            List<String> applied = GitHooks.presetsIn(script);
            for (Preset preset : this.presets.presets(h.name())) {
                presets.add(presetItem(h, preset, applied));
            }
            for (HookPresets.Remote remote : HookPresets.remote()) {
                JMenu sub = new JMenu(remote.provider().name());
                if (!remote.presets().isDone()) {
                    sub.add(new JMenuItem("Loading…")).setEnabled(false);
                } else if (remote.presets().isCompletedExceptionally()) {
                    sub.add(new JMenuItem("Failed to load")).setEnabled(false);
                } else {
                    for (Preset preset : remote.presets().join()) {
                        if (preset.category().equals(h.name())) sub.add(presetItem(h, preset, applied));
                    }
                    if (sub.getItemCount() == 0) sub.add(new JMenuItem("None for " + h.name())).setEnabled(false);
                }
                if (presets.getItemCount() > 0 && !(presets.getMenuComponent(presets.getMenuComponentCount() - 1) instanceof JMenu)) presets.addSeparator();
                presets.add(sub);
            }
            presets.setEnabled(presets.getItemCount() > 0);
            menu.add(presets);
            if (h.exists()) {
                item(menu, "Edit…", () -> edit(h));
            } else {
                item(menu, "Write New…", () -> edit(h));
            }
            menu.addSeparator();
            JCheckBoxMenuItem enabled = new JCheckBoxMenuItem("Enabled", h.enabled());
            enabled.setEnabled(h.exists());
            enabled.addActionListener(ev -> setEnabled(h, enabled.isSelected()));
            menu.add(enabled);
            item(menu, "Reveal in Finder", () -> reveal(h.file())).setEnabled(h.exists());
            menu.addSeparator();
            item(menu, "Delete " + h.name() + "…", () -> delete(h));
        } else {
            return;
        }
        menu.show(tree, e.getX(), e.getY());
    }

    private JCheckBoxMenuItem presetItem(Hook h, Preset preset, List<String> applied) {
        JCheckBoxMenuItem i = new JCheckBoxMenuItem(preset.title(), applied.contains(preset.id()));
        i.setToolTipText("<html>" + escape(preset.description()) + "<br><small>" + escape(preset.source()) + "</small></html>");
        i.addActionListener(ev -> {
            if (i.isSelected()) applyPreset(h, preset);
            else removePreset(h, preset);
        });
        return i;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JMenuItem item(JPopupMenu menu, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(ev -> r.run());
        menu.add(i);
        return i;
    }

    // actions

    /** asks the hook category and adds a node for it */
    private void newHook(Scope scope) {
        if (scope == Scope.GLOBAL && hooks.globalDir() == null) {
            Path dir = hooks.defaultGlobalDir();
            int r = JOptionPane.showConfirmDialog(this,
                    "<html>There is no global hooks directory.<br>Set <code>core.hooksPath</code> in the global config to " + dir + "?<br><br>"
                            + "<b>git then runs the global hooks only, .git/hooks of every repository are no longer run.</b></html>",
                    "New Global Hook", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            if (!run(() -> hooks.setGlobalDir(dir))) return;
        }
        Set<String> present = new LinkedHashSet<>();
        for (int i = 0; i < scopes.get(scope).getChildCount(); i++) {
            present.add(((Hook) ((DefaultMutableTreeNode) scopes.get(scope).getChildAt(i)).getUserObject()).name());
        }
        List<String> choices = GitHooks.CATEGORIES.stream().filter(c -> !present.contains(c)).toList();
        if (choices.isEmpty()) return;
        JComboBox<String> category = new JComboBox<>(choices.toArray(String[]::new));
        category.setSelectedItem(choices.contains("pre-push") ? "pre-push" : choices.getFirst());
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(new JLabel("Hook:"), BorderLayout.NORTH);
        p.add(category, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, p, "New " + label(scope) + " Hook", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        Hook h = hooks.hook(scope, (String) category.getSelectedItem());
        if (h == null) return;
        pending.add(h);
        refresh();
    }

    private void applyPreset(Hook h, Preset preset) {
        String script = h.exists() ? GitHooks.read(h) : "";
        if (!script.isBlank() && !GitHooks.isManaged(script)) {
            int r = JOptionPane.showConfirmDialog(this, h.file() + " was not written by presets.\nReplace it with the preset?",
                    preset.title(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            script = "";
        }
        List<Parameter> params = preset.parameters();
        List<String> values = null;
        if (!params.isEmpty()) {
            List<String> current = GitHooks.parametersOf(script, preset);
            JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
            List<JTextField> fields = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                JTextField field = new JTextField(current != null ? current.get(i) : params.get(i).defaultValue(), 30);
                p.add(new JLabel(params.get(i).label() + ":"));
                p.add(field);
                fields.add(field);
            }
            if (JOptionPane.showConfirmDialog(this, p, preset.title(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            values = fields.stream().map(f -> f.getText().strip().replaceAll("[\"`$\\\\]", "")).toList();
        }
        String result = GitHooks.applyPreset(script, preset, values);
        write(h, result);
    }

    private void removePreset(Hook h, Preset preset) {
        write(h, GitHooks.removePreset(GitHooks.read(h), preset));
    }

    /** the window state key of the hook editor */
    private static final String EDITOR = "hookEditor";

    /** "Write New…" or "Edit…", the dialog bounds are remembered */
    private void edit(Hook h) {
        String script = h.exists() ? GitHooks.read(h) : GitHooks.template(h.name());
        JTextArea text = new JTextArea(script, 24, 80);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
        text.setTabSize(4);
        text.setCaretPosition(0);
        text.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) editorPopup(text, h, e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) editorPopup(text, h, e); }
        });
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(new JLabel(h.file().toString()), BorderLayout.NORTH);
        p.add(new JScrollPane(text), BorderLayout.CENTER);
        String title = (h.exists() ? "Edit " : "Write ") + label(h.scope()) + " " + h.name() + " Hook";
        JOptionPane pane = new JOptionPane(p, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = pane.createDialog(this, title);
        dialog.setResizable(true);
        boolean saved = WindowState.bounds(EDITOR) != null;
        WindowState.remember(dialog, EDITOR, dialog.getSize());
        if (!saved) dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        dialog.dispose();
        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue())) return;
        write(h, text.getText());
    }

    /** the hook editor popup */
    private void editorPopup(JTextArea text, Hook h, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        boolean crosses = start < end && GitHooks.touchesPreset(text.getText(), start, end);
        JMenuItem i = item(menu, "Make Preset from Selection…", () -> makePreset(text, h.name(), GitHooks.lines(text.getText(), start, end)));
        i.setEnabled(start < end && !crosses);
        if (crosses) i.setToolTipText("the selection must be outside of the preset blocks");
        menu.show(text, e.getX(), e.getY());
    }

    /** saves the lines as a user preset */
    private void makePreset(Component parent, String category, String lines) {
        JTextField id = new JTextField(20);
        JComboBox<String> categories = new JComboBox<>(GitHooks.CATEGORIES.toArray(String[]::new));
        categories.setSelectedItem(category);
        JTextField title = new JTextField(30);
        JTextField description = new JTextField(30);
        JTextArea body = new JTextArea(lines, 12, 70);
        body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, body.getFont().getSize()));
        body.setTabSize(4);
        JPanel fields = new JPanel(new GridLayout(0, 2, 4, 4));
        fields.add(new JLabel("Id (file name):"));
        fields.add(id);
        fields.add(new JLabel("Hook:"));
        fields.add(categories);
        fields.add(new JLabel("Title:"));
        fields.add(title);
        fields.add(new JLabel("Description:"));
        fields.add(description);
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(fields, BorderLayout.NORTH);
        p.add(new JScrollPane(body), BorderLayout.CENTER);
        p.add(new JLabel("<html>a parameter is written as <code>@PARAM:label:default@</code><br>saved in " + presets.saveDir() + "</html>"), BorderLayout.SOUTH);
        while (true) {
            if (JOptionPane.showConfirmDialog(parent, p, "Make Preset", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String error = null;
            Preset existing = presets.get(id.getText().strip());
            if (!id.getText().strip().matches("[\\w.-]+")) error = "the id must be letters, digits, '.', '_' or '-'";
            else if (body.getText().isBlank()) error = "the script is empty";
            else if (existing != null && !existing.source().equals(presets.saveDir().resolve(id.getText().strip() + ".sh").toString())) error = "the id is used by " + existing.source();
            else if (existing != null && JOptionPane.showConfirmDialog(parent, "Replace the preset " + existing.source() + "?", "Make Preset",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) continue;
            if (error != null) {
                JOptionPane.showMessageDialog(parent, error, "Make Preset", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            String t = title.getText().strip();
            if (run(() -> presets.save(id.getText().strip(), (String) categories.getSelectedItem(), t.isEmpty() ? id.getText().strip() : t,
                    description.getText().strip(), body.getText()))) return;
        }
    }

    private void write(Hook h, String script) {
        run(() -> GitHooks.write(h, script));
        refresh();
    }

    private void setEnabled(Hook h, boolean enabled) {
        run(() -> GitHooks.setEnabled(h, enabled));
        refresh();
    }

    private void delete(Hook h) {
        if (h.exists()) {
            int r = JOptionPane.showConfirmDialog(this, "Delete " + h.file() + "?", "Delete Hook", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            if (!run(() -> GitHooks.delete(h))) return;
        }
        pending.remove(h);
        refresh();
    }

    private void reveal(Path file) {
        if (file == null) return;
        try {
            new ProcessBuilder("open", "-R", file.toString()).start();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "reveal: " + e.getMessage());
        }
    }

    /** @return false when failed (the error is shown) */
    private boolean run(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, e.getMessage(), e);
            JOptionPane.showMessageDialog(this, e.getMessage(), "Hooks", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static String label(Scope s) {
        return s == Scope.GLOBAL ? "Global" : "Local";
    }

    // rendering

    private final JCheckBox check = new JCheckBox();

    private int checkWidth() {
        return check.getPreferredSize().width;
    }

    private class Renderer implements TreeCellRenderer {
        private final DefaultTreeCellRenderer label = new DefaultTreeCellRenderer();
        private final JPanel hookCell = new JPanel(new BorderLayout());
        private final JLabel hookLabel = new JLabel();

        Renderer() {
            hookCell.setOpaque(false);
            check.setOpaque(false);
            hookLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
            hookCell.add(check, BorderLayout.WEST);
            hookCell.add(hookLabel, BorderLayout.CENTER);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
            Object o = ((DefaultMutableTreeNode) v).getUserObject();
            if (o instanceof Hook h) {
                Info info = state != null ? state.infos().get(h) : null; // null: pending (no file)
                boolean exists = info != null;
                boolean enabled = exists && info.enabled();
                check.setSelected(enabled);
                check.setEnabled(exists);
                List<String> presets = exists ? info.presets() : List.of();
                hookLabel.setText(h.name() + (exists ? "" : "  (empty)"));
                hookLabel.setFont(t.getFont());
                hookLabel.setForeground(exists ? label.getTextNonSelectionColor() : java.awt.Color.GRAY);
                hookCell.setToolTipText("<html>" + h.file() + (presets.isEmpty() ? "" : "<br>presets: " + String.join(", ", presets))
                        + (exists && !enabled ? "<br>disabled (not executable)" : "") + "</html>");
                return hookCell;
            }
            label.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
            label.setToolTipText(null);
            if (o instanceof Scope s) {
                label.setFont(t.getFont().deriveFont(Font.BOLD, t.getFont().getSize2D() - 1));
                label.setIcon(IconProvider.get().icon(IconProvider.Key.HOOKS, 16));
                String text = s == Scope.GLOBAL ? "GLOBAL HOOKS" : "LOCAL HOOKS";
                Path dir = state != null ? state.dirs().get(s) : null;
                boolean active = state == null || state.active().get(s);
                if (dir == null) {
                    label.setToolTipText("core.hooksPath is not set globally");
                } else if (!active) {
                    text += "  (not run)";
                    label.setToolTipText("<html>" + dir + "<br>git does not run these, core.hooksPath points elsewhere</html>");
                } else {
                    label.setToolTipText(dir.toString());
                }
                label.setText(text);
            }
            return label;
        }
    }
}
