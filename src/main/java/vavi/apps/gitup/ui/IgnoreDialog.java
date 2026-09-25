/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import vavi.apps.gitup.model.FileChange;
import vavi.apps.gitup.model.GitRepo.IgnorePatterns;
import vavi.apps.gitup.model.GitRepo.IgnoreTarget;


/**
 * SourceTree-like "Ignore" dialog.
 * <ol>
 * <li>ignore exact filename(s)</li>
 * <li>ignore all files with this extension</li>
 * <li>ignore everything beneath a folder</li>
 * <li>ignore a custom pattern</li>
 * </ol>
 * and where to add the entry: the repository's .gitignore, .git/info/exclude (not shared)
 * or the global ignore list.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class IgnoreDialog extends JDialog {

    /** the user's choice */
    public record Result(List<String> patterns, IgnoreTarget target, boolean stopTracking) {}

    private final JRadioButton exact = new JRadioButton("Ignore exact filename(s)");
    private final JRadioButton extension = new JRadioButton("Ignore all files with this extension");
    private final JRadioButton beneath = new JRadioButton("Ignore everything beneath:");
    private final JRadioButton custom = new JRadioButton("Ignore custom pattern:");
    private final JComboBox<String> folders = new JComboBox<>();
    private final JTextField pattern = new JTextField(30);
    private final JComboBox<IgnoreTarget> target = new JComboBox<>(IgnoreTarget.values());
    private final JCheckBox stopTracking = new JCheckBox("Stop tracking the file(s) (keep them on disk)");
    private final JLabel preview = new JLabel();

    private final List<FileChange> files;
    private Result result;

    /**
     * @param targetFiles the file each target stands for, shown in the combo box
     */
    public IgnoreDialog(Component parent, List<FileChange> files, Map<IgnoreTarget, Path> targetFiles) {
        super(SwingUtilities.getWindowAncestor(parent), "Ignore", ModalityType.APPLICATION_MODAL);
        this.files = files;

        ButtonGroup g = new ButtonGroup();
        for (JRadioButton b : List.of(exact, extension, beneath, custom)) {
            g.add(b);
            b.addActionListener(e -> update());
        }

        List<String> exts = extensions();
        if (exts.isEmpty()) {
            extension.setEnabled(false);
        } else {
            extension.setText("Ignore all files with " + (exts.size() == 1 ? "this extension" : "these extensions") + ": " + String.join(" ", exts));
        }
        Set<String> dirs = new LinkedHashSet<>();
        for (FileChange f : files) dirs.addAll(IgnorePatterns.parents(f.path()));
        dirs.forEach(folders::addItem);
        if (dirs.isEmpty()) {
            beneath.setEnabled(false);
            folders.setEnabled(false);
        }
        folders.addActionListener(e -> update());
        pattern.setText(IgnorePatterns.exact(files.getFirst().path()));
        pattern.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });
        target.setRenderer((list, value, index, selected, focus) -> {
            String label = switch (value) {
                case REPOSITORY -> "This repository (.gitignore)";
                case LOCAL -> "This repository only, not shared (.git/info/exclude)";
                case GLOBAL -> "Global ignore list (" + targetFiles.get(IgnoreTarget.GLOBAL) + ")";
            };
            return new javax.swing.DefaultListCellRenderer().getListCellRendererComponent(list, label, index, selected, focus);
        });
        boolean tracked = files.stream().anyMatch(f -> f.kind() != FileChange.Kind.UNTRACKED && f.kind() != FileChange.Kind.ADDED);
        stopTracking.setSelected(tracked);
        stopTracking.setEnabled(tracked);
        stopTracking.setToolTipText("an ignore pattern has no effect on files git already tracks");

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        String names = files.size() == 1 ? files.getFirst().path() : files.size() + " files";
        p.add(new JLabel("Ignore " + names), c);
        c.gridy++;
        p.add(exact, c);
        c.gridy++;
        p.add(extension, c);
        c.gridy++;
        p.add(beneath, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(folders, c);
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridy++;
        p.add(custom, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(pattern, c);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        p.add(preview, c);
        c.gridy++;
        c.insets = new Insets(10, 0, 2, 8);
        p.add(new JLabel("Add this ignore entry to:"), c);
        c.gridy++;
        c.insets = new Insets(2, 0, 2, 8);
        p.add(target, c);
        c.gridy++;
        p.add(stopTracking, c);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            List<String> ps = patterns();
            if (ps.isEmpty()) return;
            result = new Result(ps, (IgnoreTarget) target.getSelectedItem(), stopTracking.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);

        getContentPane().add(p, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        exact.setSelected(true);
        update();
        pack();
        setLocationRelativeTo(parent);
    }

    private List<String> extensions() {
        return files.stream().map(f -> IgnorePatterns.extension(f.path())).filter(x -> x != null)
                .map(x -> x.substring(1)).distinct().toList();
    }

    /** @return the patterns of the current choice */
    List<String> patterns() {
        List<String> list = new ArrayList<>();
        if (exact.isSelected()) {
            files.forEach(f -> list.add(IgnorePatterns.exact(f.path())));
        } else if (extension.isSelected()) {
            extensions().forEach(x -> list.add("*" + x));
        } else if (beneath.isSelected()) {
            if (folders.getSelectedItem() != null) list.add(IgnorePatterns.beneath((String) folders.getSelectedItem()));
        } else {
            String s = pattern.getText().strip();
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    private void update() {
        folders.setEnabled(beneath.isSelected() && beneath.isEnabled());
        pattern.setEnabled(custom.isSelected());
        List<String> ps = patterns();
        preview.setText("<html><font color='gray'>adds: </font><code>" + ps.stream().map(IgnoreDialog::esc).collect(Collectors.joining(", ")) + "</code></html>");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /** shows the dialog, @return null when cancelled */
    public Result showDialog() {
        setVisible(true);
        return result;
    }
}
