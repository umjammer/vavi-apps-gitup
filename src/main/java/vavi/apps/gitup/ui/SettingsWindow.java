/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;

import vavi.apps.gitup.model.Accounts;
import vavi.apps.gitup.model.Accounts.Account;
import vavi.apps.gitup.model.Accounts.Protocol;
import vavi.apps.gitup.model.Accounts.Service;
import vavi.apps.gitup.model.ExternalTool;
import vavi.apps.gitup.model.MavenSettings;
import vavi.apps.gitup.model.MavenSettings.Server;
import vavi.apps.gitup.model.Settings;
import vavi.apps.gitup.model.Settings.DiffColor;


/**
 * SourceTree-like settings: accounts (with the maven settings.xml import) and diff
 * (colors, context lines, external diff / merge tools).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public class SettingsWindow extends JFrame {

    private static SettingsWindow instance;

    /** shows the (single) settings window */
    public static void open() {
        if (instance == null || !instance.isDisplayable()) instance = new SettingsWindow();
        instance.setVisible(true);
        instance.toFront();
    }

    private final Accounts accounts = Accounts.get();
    private final Settings settings = Settings.get();

    private SettingsWindow() {
        super("Settings");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTabbedPane tabs = new JTabbedPane();
        // SourceTree's preferences: the icon above the title
        tabs.putClientProperty("JTabbedPane.tabIconPlacement", javax.swing.SwingConstants.TOP);
        vavi.apps.gitup.ui.icons.IconProvider icons = vavi.apps.gitup.ui.icons.IconProvider.get();
        tabs.addTab("General", icons.icon(vavi.apps.gitup.ui.icons.IconProvider.Key.PREFS_GENERAL, 24), generalTab());
        tabs.addTab("Accounts", icons.icon(vavi.apps.gitup.ui.icons.IconProvider.Key.PREFS_ACCOUNTS, 24), accountsTab());
        tabs.addTab("Diff", icons.icon(vavi.apps.gitup.ui.icons.IconProvider.Key.PREFS_DIFF, 24), diffTab());
        tabs.addTab("History", icons.icon(vavi.apps.gitup.ui.icons.IconProvider.Key.PREFS_HISTORY, 24), historyTab());
        setContentPane(tabs);
        WindowState.remember(this, "settings", new Dimension(720, 460));
    }

    // accounts

    private final AccountModel accountModel = new AccountModel();
    private final JTable accountTable = new JTable(accountModel);

    private class AccountModel extends AbstractTableModel {
        List<Account> list = accounts.list();
        private final String[] names = {"Service", "Host", "Username", "Protocol"};

        void reload() {
            list = accounts.list();
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return list.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }
        @Override public Object getValueAt(int r, int c) {
            Account a = list.get(r);
            return switch (c) {
                case 0 -> a.service();
                case 1 -> a.host();
                case 2 -> a.username();
                default -> a.protocol();
            };
        }
    }

    private JComponent accountsTab() {
        accountTable.setShowGrid(false);
        accountTable.setRowHeight(accountTable.getFontMetrics(accountTable.getFont()).getHeight() + 8);
        accountTable.setToolTipText("double click to edit");
        accountTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2 || !javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
                int r = accountTable.rowAtPoint(e.getPoint());
                if (r >= 0) editAccount(accountModel.list.get(accountTable.convertRowIndexToModel(r)));
            }
        });
        JButton add = new JButton("Add…");
        add.addActionListener(e -> editAccount(null));
        JButton edit = new JButton("Edit…");
        edit.addActionListener(e -> {
            int r = accountTable.getSelectedRow();
            if (r >= 0) editAccount(accountModel.list.get(r));
        });
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            int r = accountTable.getSelectedRow();
            if (r < 0) return;
            Account a = accountModel.list.get(r);
            int answer = JOptionPane.showConfirmDialog(this, "Remove the account " + a + "?\nAlso delete its password / token from the Keychain?",
                    "Remove Account", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) return;
            try {
                accounts.remove(a, answer == JOptionPane.YES_OPTION);
            } catch (RuntimeException ex) {
                error(ex);
            }
            accountModel.reload();
        });
        JButton maven = new JButton("Import from Maven settings.xml…");
        maven.addActionListener(e -> importMaven());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(add);
        buttons.add(edit);
        buttons.add(remove);
        buttons.add(maven);
        JLabel note = new JLabel("<html><font color='gray'>passwords and tokens are kept in the macOS Keychain (internet passwords, "
                + "shared with git's osxkeychain credential helper). they are used when a fetch / push asks for credentials.</font></html>");
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(new JScrollPane(accountTable), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.NORTH);
        south.add(note, BorderLayout.SOUTH);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    /** @param a null for a new account */
    private void editAccount(Account a) {
        JComboBox<Service> service = new JComboBox<>(Service.values());
        JTextField host = new JTextField(a != null ? a.host() : Service.GITHUB.defaultHost, 24);
        JTextField user = new JTextField(a != null ? a.username() : "", 24);
        JPasswordField secret = new JPasswordField(24);
        secret.putClientProperty("JTextField.placeholderText", a != null ? "unchanged" : "password or personal access token");
        JComboBox<Protocol> protocol = new JComboBox<>(Protocol.values());
        if (a != null) {
            service.setSelectedItem(a.service());
            protocol.setSelectedItem(a.protocol());
        }
        service.addActionListener(e -> {
            Service s = (Service) service.getSelectedItem();
            if (s != null && !s.defaultHost.isEmpty()) host.setText(s.defaultHost);
        });
        JPanel p = form(new String[] {"Hosting service:", "Host:", "Username:", "Password / token:", "Protocol:"},
                new JComponent[] {service, host, user, secret, protocol});
        if (JOptionPane.showConfirmDialog(this, p, a != null ? "Edit Account" : "Add Account", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (host.getText().isBlank() || user.getText().isBlank()) {
            error(new IllegalArgumentException("a host and a username are needed"));
            return;
        }
        try {
            if (a != null && (!a.host().equalsIgnoreCase(host.getText().strip()) || !a.username().equals(user.getText().strip()))) {
                // moved: carry the old secret over when no new one is given
                String old = secret.getPassword().length == 0 ? accounts.secret(a) : null;
                accounts.remove(a, true);
                accounts.put(new Account((Service) service.getSelectedItem(), host.getText().strip(), user.getText().strip(),
                        (Protocol) protocol.getSelectedItem()), old != null ? old : new String(secret.getPassword()));
            } else {
                accounts.put(new Account((Service) service.getSelectedItem(), host.getText().strip(), user.getText().strip(),
                        (Protocol) protocol.getSelectedItem()), new String(secret.getPassword()));
            }
        } catch (RuntimeException ex) {
            error(ex);
        }
        accountModel.reload();
    }

    /**
     * imports the servers of ~/.m2/settings.xml: a table with a checkbox per server,
     * the service and host guessed from the server id (editable), the username editable.
     */
    private void importMaven() {
        Path file = MavenSettings.defaultFile();
        if (!Files.exists(file)) {
            JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
            chooser.setFileHidingEnabled(false);
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            file = chooser.getSelectedFile().toPath();
        }
        List<Server> servers;
        try {
            servers = MavenSettings.read(file);
        } catch (Exception e) {
            error(e);
            return;
        }
        if (servers.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No servers in " + file, "Import", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ImportModel model = new ImportModel(servers);
        JTable table = new JTable(model);
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 8);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JComboBox<>(Service.values())));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(680, Math.min(320, 40 + 28 * servers.size())));
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel("Import the checked servers of " + file + " as accounts (username, service and host are editable):"), BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(new JLabel("<html><font color='gray'>encrypted passwords ({…}) need maven's master password and cannot be imported, "
                + "servers without a password or token neither.</font></html>"), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, p, "Import from Maven settings.xml", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        // the keychain may already have a password for the same host and user (e.g. from git's credential helper)
        List<String> existing = new ArrayList<>();
        for (ImportModel.Row r : model.rows) {
            if (!r.checked || r.username == null || r.username.isBlank() || r.host == null || r.host.isBlank()) continue;
            try {
                if (accounts.hasSecret(r.host.strip(), r.username.strip())) existing.add(r.username.strip() + "@" + r.host.strip());
            } catch (RuntimeException ignored) {
            }
        }
        boolean replace = false;
        if (!existing.isEmpty()) {
            int answer = JOptionPane.showOptionDialog(this, "The Keychain already has a password / token for:\n  " + String.join("\n  ", existing)
                            + "\n\n(maybe saved by git or another application)\nReplace them with the ones in settings.xml?",
                    "Import", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new String[] {"Replace", "Keep the Keychain's", "Cancel"}, "Keep the Keychain's");
            if (answer == 2 || answer == JOptionPane.CLOSED_OPTION) return;
            replace = answer == 0;
        }
        int n = 0;
        List<String> skipped = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (ImportModel.Row r : model.rows) {
            if (!r.checked) continue;
            if (r.username == null || r.username.isBlank() || r.host == null || r.host.isBlank()) {
                skipped.add(r.server.id() + " (no username / host)");
                continue;
            }
            try {
                String key = r.username.strip() + "@" + r.host.strip();
                boolean keep = existing.contains(key) && !replace;
                if (keep) kept.add(key);
                accounts.put(new Account(r.service, r.host.strip(), r.username.strip(), Protocol.HTTPS), keep ? null : r.server.secret());
                n++;
            } catch (RuntimeException e) {
                skipped.add(r.server.id() + " (" + e.getMessage() + ")");
            }
        }
        accountModel.reload();
        JOptionPane.showMessageDialog(this, "Imported " + n + " account(s)."
                        + (kept.isEmpty() ? "" : "\nThe Keychain's existing password is used for: " + String.join(", ", kept))
                        + (skipped.isEmpty() ? "" : "\nSkipped: " + String.join(", ", skipped)),
                "Import", JOptionPane.INFORMATION_MESSAGE);
    }

    /** servers to import: [import] id, username, secret, service, host */
    private static class ImportModel extends AbstractTableModel {
        static class Row {
            final Server server;
            boolean checked;
            String username;
            Service service;
            String host;

            Row(Server s) {
                server = s;
                service = Service.guess(s.id());
                host = service.defaultHost.isEmpty() ? s.id() : service.defaultHost;
                username = s.username() != null ? s.username() : service == Service.GITLAB && s.usable() ? "oauth2" : "";
                checked = s.usable() && !username.isBlank();
            }
        }

        final List<Row> rows = new ArrayList<>();
        private final String[] names = {"", "Server id", "Username", "Secret", "Service", "Host"};

        ImportModel(List<Server> servers) {
            servers.forEach(s -> rows.add(new Row(s)));
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 4 ? Service.class : String.class; }

        @Override public boolean isCellEditable(int r, int c) {
            return (c == 0 && rows.get(r).server.usable()) || c == 2 || c == 4 || c == 5;
        }

        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.checked;
                case 1 -> row.server.id();
                case 2 -> row.username;
                case 3 -> switch (row.server.kind()) {
                    case PASSWORD -> "password";
                    case TOKEN -> "token (http header)";
                    case ENCRYPTED -> "encrypted, not importable";
                    case NONE -> "none";
                };
                case 4 -> row.service;
                default -> row.host;
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Row row = rows.get(r);
            switch (c) {
                case 0 -> row.checked = (Boolean) v;
                case 2 -> row.username = (String) v;
                case 4 -> {
                    row.service = (Service) v;
                    if (!row.service.defaultHost.isEmpty()) row.host = row.service.defaultHost;
                    fireTableRowsUpdated(r, r);
                }
                case 5 -> row.host = (String) v;
                default -> {}
            }
        }
    }

    // diff

    // history

    // general

    private JComponent generalTab() {
        javax.swing.JCheckBox fetch = new javax.swing.JCheckBox("Check default remotes for updates every", settings.fetchInterval() > 0);
        JSpinner minutes = new JSpinner(new SpinnerNumberModel(settings.fetchInterval() > 0 ? settings.fetchInterval() : 10, 1, 1440, 1));
        minutes.setEnabled(fetch.isSelected());
        Runnable storeFetch = () -> settings.setFetchInterval(fetch.isSelected() ? (Integer) minutes.getValue() : 0);
        fetch.addActionListener(e -> {
            minutes.setEnabled(fetch.isSelected());
            storeFetch.run();
        });
        minutes.addChangeListener(e -> storeFetch.run());
        JPanel fetchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fetchRow.add(fetch);
        fetchRow.add(minutes);
        fetchRow.add(new JLabel("minutes"));
        JLabel fetchNote = new JLabel("<html><font color='gray'>a quiet fetch (saved accounts only, never asks) so that the ahead / behind<br>"
                + "badges and the log show what happened on the server, also after checking out a branch</font></html>");
        fetchNote.setBorder(BorderFactory.createEmptyBorder(0, 28, 8, 0));

        javax.swing.JCheckBox spell = new javax.swing.JCheckBox("Check spelling of commit messages", settings.spellCheck());
        spell.addActionListener(e -> settings.setSpellCheck(spell.isSelected()));
        JLabel spellNote = new JLabel("<html><font color='gray'>macOS's spell checker (its languages and learned words), "
                + "right click a word for guesses</font></html>");
        spellNote.setBorder(BorderFactory.createEmptyBorder(0, 28, 8, 0));

        javax.swing.JCheckBox avatars = new javax.swing.JCheckBox("Show avatars in commit details", settings.avatars());
        avatars.addActionListener(e -> settings.setAvatars(avatars.isSelected()));
        JLabel avatarsNote = new JLabel("<html><font color='gray'>from GitHub (the commits of a github.com remote, noreply emails), "
                + "then Gravatar (a hash of the email)</font></html>");
        avatarsNote.setBorder(BorderFactory.createEmptyBorder(0, 28, 0, 0));

        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 12));
        for (JComponent c : new JComponent[] {fetchRow, fetchNote, spell, spellNote, avatars, avatarsNote}) {
            c.setAlignmentX(0);
            p.add(c);
        }
        JPanel north = new JPanel(new BorderLayout());
        north.add(p, BorderLayout.NORTH);
        return north;
    }

    private JComponent historyTab() {
        javax.swing.JCheckBox protect = new javax.swing.JCheckBox("Protect pushed commits", settings.protectPushed());
        protect.addActionListener(e -> settings.setProtectPushed(protect.isSelected()));
        JLabel note = new JLabel("<html>Amend, edit message / author, squash, fixup, move, delete, reset and undo of commits<br>"
                + "already on a remote branch are stopped: overriding needs an explicit confirmation.<br>"
                + "Off: they only warn. (A rewritten pushed commit needs a force push, others must reconcile.)</html>");
        note.setBorder(BorderFactory.createEmptyBorder(4, 24, 0, 0));
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        protect.setAlignmentX(0);
        note.setAlignmentX(0);
        p.add(protect);
        p.add(note);
        JPanel north = new JPanel(new BorderLayout());
        north.add(p, BorderLayout.NORTH);
        return north;
    }

    /** the font of the diff view: family (fixed width ones by default), size, preview */
    private JComponent fontPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Font"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;

        String[] all = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        javax.swing.JComboBox<String> family = new javax.swing.JComboBox<>();
        javax.swing.JCheckBox fixedOnly = new javax.swing.JCheckBox("Fixed width only", true);
        JSpinner size = new JSpinner(new SpinnerNumberModel(12, 6, 72, 1));
        JLabel preview = new JLabel();
        JLabel status = new JLabel();
        boolean[] adjusting = {false};

        Runnable update = () -> {
            adjusting[0] = true;
            try {
                java.awt.Font f = DiffView.settingsFont();
                String current = f.getFamily().equals(java.awt.Font.MONOSPACED) ? f.getName() : f.getFamily();
                family.removeAllItems();
                for (String name : all) {
                    if (!fixedOnly.isSelected() || name.equals(current) || isFixedWidth(name)) family.addItem(name);
                }
                if (((javax.swing.DefaultComboBoxModel<String>) family.getModel()).getIndexOf(current) < 0) family.addItem(current);
                family.setSelectedItem(current);
                size.setValue(f.getSize());
                preview.setFont(f);
                preview.setText("+ added line   - removed line   0Oo1lI {}");
                status.setText(settings.diffFontName() == null && settings.diffFontSize() == 0 ? "default (SourceTree's)" : "");
            } finally {
                adjusting[0] = false;
            }
        };
        Runnable store = () -> {
            if (adjusting[0]) return;
            String name = (String) family.getSelectedItem();
            int sz = (Integer) size.getValue();
            settings.setDiffFont(name == null || name.equals(DiffView.defaultFontName()) ? null : name, sz == DiffView.DEFAULT_FONT_SIZE ? 0 : sz);
            update.run();
        };
        family.addActionListener(e -> store.run());
        size.addChangeListener(e -> store.run());
        fixedOnly.addActionListener(e -> update.run());
        JButton reset = new JButton("Default");
        reset.addActionListener(e -> {
            settings.setDiffFont(null, 0);
            update.run();
        });
        update.run();

        c.gridy = 0;
        c.gridx = 0;
        p.add(new JLabel("Family:"), c);
        c.gridx = 1;
        p.add(family, c);
        c.gridx = 2;
        p.add(fixedOnly, c);
        c.gridy = 1;
        c.gridx = 0;
        p.add(new JLabel("Size:"), c);
        c.gridx = 1;
        p.add(size, c);
        c.gridx = 2;
        p.add(reset, c);
        c.gridx = 3;
        c.weightx = 1;
        p.add(status, c);
        c.weightx = 0;
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 4;
        p.add(preview, c);
        return p;
    }

    /** fixed width fonts: 'i' as wide as 'm' */
    private boolean isFixedWidth(String family) {
        return fixedWidth.computeIfAbsent(family, n -> {
            java.awt.Font f = new java.awt.Font(n, java.awt.Font.PLAIN, 12);
            java.awt.FontMetrics fm = getFontMetrics(f);
            return f.canDisplay('m') && fm.charWidth('m') > 0 && fm.charWidth('i') == fm.charWidth('m');
        });
    }

    private final java.util.Map<String, Boolean> fixedWidth = new java.util.HashMap<>();

    private JComponent diffTab() {
        JPanel colors = new JPanel(new GridBagLayout());
        colors.setBorder(BorderFactory.createTitledBorder("Colors"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;
        for (DiffColor key : DiffColor.values()) {
            c.gridy = row++;
            c.gridx = 0;
            colors.add(new JLabel(switch (key) {
                case ADDED -> "Added lines (background):";
                case REMOVED -> "Removed lines (background):";
                case ADDED_TEXT -> "Added lines (text):";
                case REMOVED_TEXT -> "Removed lines (text):";
                case HUNK_HEADER -> "Hunk headers:";
                case SELECTION -> "Selected lines:";
            }), c);
            JButton swatch = new JButton("      ");
            JLabel status = new JLabel();
            Runnable update = () -> {
                Color col = settings.diffColor(key);
                swatch.setBackground(col != null ? col : DiffView.defaultColor(key));
                swatch.setOpaque(true);
                status.setText(col != null ? String.format("#%06x", col.getRGB() & 0xffffff) : "default");
            };
            update.run();
            swatch.addActionListener(e -> {
                Color current = settings.diffColor(key);
                Color chosen = JColorChooser.showDialog(this, "Diff color", current != null ? current : DiffView.defaultColor(key));
                if (chosen != null) {
                    settings.setDiffColor(key, chosen);
                    update.run();
                }
            });
            JButton reset = new JButton("Default");
            reset.addActionListener(e -> {
                settings.setDiffColor(key, null);
                update.run();
            });
            c.gridx = 1;
            colors.add(swatch, c);
            c.gridx = 2;
            colors.add(reset, c);
            c.gridx = 3;
            c.weightx = 1; // left aligned rows, the rest of the width is empty
            colors.add(status, c);
            c.weightx = 0;
        }

        JSpinner context = new JSpinner(new SpinnerNumberModel(settings.contextLines(), 0, vavi.apps.gitup.model.Settings.MAX_CONTEXT_LINES, 1));
        context.addChangeListener(e -> settings.setContextLines((Integer) context.getValue()));
        JPanel general = new JPanel(new FlowLayout(FlowLayout.LEFT));
        general.add(new JLabel("Lines of context:"));
        general.add(context);

        JPanel tools = new JPanel(new GridBagLayout());
        tools.setBorder(BorderFactory.createTitledBorder("External Diff / Merge"));
        toolRow(tools, 0, "Visual Diff Tool:", settings.diffToolId(), settings.customDiffCommand(), true);
        toolRow(tools, 1, "Merge Tool:", settings.mergeToolId(), settings.customMergeCommand(), false);
        GridBagConstraints h = new GridBagConstraints();
        h.gridy = 2;
        h.gridx = 0;
        h.gridwidth = 3;
        h.anchor = GridBagConstraints.WEST;
        h.insets = new Insets(4, 6, 4, 6);
        tools.add(new JLabel("<html><font color='gray'>a custom command runs with /bin/sh, the files are in "
                + "\"$LOCAL\" \"$REMOTE\" (and \"$BASE\" \"$MERGED\" for merging)</font></html>"), h);

        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (JComponent x : new JComponent[] {colors, fontPanel(), general, tools}) {
            x.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(x);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(wrap, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        return scroll;
    }

    private void toolRow(JPanel panel, int row, String label, String id, String custom, boolean diff) {
        List<ExternalTool> tools = ExternalTool.PRESETS.stream().filter(t -> t.id().equals(ExternalTool.CUSTOM) || (diff ? t.diff() : t.merge()) != null).toList();
        JComboBox<ExternalTool> combo = new JComboBox<>(tools.toArray(ExternalTool[]::new));
        combo.setSelectedItem(ExternalTool.preset(id));
        JTextField command = new JTextField(custom.isBlank() ? commandOf(ExternalTool.preset(id), diff) : custom, 24);
        command.setToolTipText("<html>the command, run by /bin/sh</html>");
        command.setEditable(id.equals(ExternalTool.CUSTOM));
        Runnable save = () -> {
            ExternalTool t = (ExternalTool) combo.getSelectedItem();
            boolean isCustom = t != null && t.id().equals(ExternalTool.CUSTOM);
            if (diff) settings.setDiffTool(t != null ? t.id() : "filemerge", isCustom ? command.getText() : "");
            else settings.setMergeTool(t != null ? t.id() : "filemerge", isCustom ? command.getText() : "");
        };
        combo.addActionListener(e -> {
            ExternalTool t = (ExternalTool) combo.getSelectedItem();
            boolean isCustom = t != null && t.id().equals(ExternalTool.CUSTOM);
            command.setEditable(isCustom);
            if (!isCustom && t != null) command.setText(commandOf(t, diff));
            save.run();
        });
        command.addActionListener(e -> save.run());
        command.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { save.run(); }
        });
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        panel.add(combo, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(command, c);
    }

    private static String commandOf(ExternalTool t, boolean diff) {
        String s = diff ? t.diff() : t.merge();
        return s != null ? s : "";
    }

    private static JPanel form(String[] labels, JComponent[] fields) {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labels.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            p.add(new JLabel(labels[i]), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            p.add(fields[i], c);
            c.fill = GridBagConstraints.NONE;
        }
        return p;
    }

    private void error(Exception e) {
        JOptionPane.showMessageDialog(this, e.getMessage() != null ? e.getMessage() : e.toString(), "Error", JOptionPane.ERROR_MESSAGE);
    }
}
