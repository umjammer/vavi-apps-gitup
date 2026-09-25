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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

import vavi.apps.gitup.model.GitRepo.Remote;


/**
 * SourceTree-like "Push" dialog: the remote, the branches to push (with the remote branch name
 * and whether to track it), push all tags, force push.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public class PushDialog extends JDialog {

    /** a branch to push */
    public record Branch(String local, String remote, boolean track) {}

    /** the user's choice */
    public record Result(String remote, List<Branch> branches, boolean tags, boolean force) {}

    private static class Row {
        boolean push;
        final String local;
        String remote;
        boolean track;

        Row(String local, boolean push) {
            this.local = local;
            this.push = push;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JComboBox<String> remote = new JComboBox<>();
    private final JLabel url = new JLabel();
    private final JCheckBox selectAll = new JCheckBox("Select All");
    private final JCheckBox tags = new JCheckBox("Push all tags");
    private final JCheckBox force = new JCheckBox("Force push");
    private final Map<String, String> upstreams;
    private final List<Remote> remotes;
    private Result result;

    /**
     * @param upstreams local branch to its upstream ("remote/branch"), absent when none
     */
    public PushDialog(Component parent, List<Remote> remotes, List<String> localBranches, Map<String, String> upstreams, String current) {
        super(parent != null ? SwingUtilities.getWindowAncestor(parent) : null, "Push", ModalityType.APPLICATION_MODAL);
        this.remotes = remotes;
        this.upstreams = upstreams;
        for (String b : localBranches) rows.add(new Row(b, b.equals(current)));

        remotes.forEach(r -> remote.addItem(r.name()));
        String up = current != null ? upstreams.get(current) : null;
        String preferred = up != null ? remoteOf(up) : remotes.stream().anyMatch(r -> r.name().equals("origin")) ? "origin" : null;
        if (preferred != null) remote.setSelectedItem(preferred);
        remote.addActionListener(e -> remoteChanged());

        JTable table = new JTable(model);
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 8);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(3).setMaxWidth(60);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(560, Math.min(300, 40 + 28 * rows.size())));

        selectAll.addActionListener(e -> {
            rows.forEach(r -> r.push = selectAll.isSelected());
            model.fireTableDataChanged();
        });
        force.setForeground(new Color(0xcf222e));
        force.setToolTipText("overwrites the remote branches, commits there that are not here are lost");

        JPanel top = new JPanel(new BorderLayout(6, 4));
        JPanel remoteRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        remoteRow.add(new JLabel("Push to repository:"));
        remoteRow.add(remote);
        top.add(remoteRow, BorderLayout.NORTH);
        url.setForeground(Color.gray);
        top.add(url, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(new JLabel("Branches to push"), BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        options.add(selectAll);
        options.add(tags);
        options.add(force);
        center.add(options, BorderLayout.SOUTH);

        JButton ok = new JButton("Push");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            List<Branch> list = rows.stream().filter(r -> r.push && r.remote != null && !r.remote.isBlank())
                    .map(r -> new Branch(r.local, r.remote.strip(), r.track)).toList();
            if (list.isEmpty() && !tags.isSelected()) return;
            result = new Result((String) remote.getSelectedItem(), list, tags.isSelected(), force.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(top, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        remoteChanged();
        pack();
        setLocationRelativeTo(parent);
    }

    private static String remoteOf(String upstream) {
        int i = upstream.indexOf('/');
        return i > 0 ? upstream.substring(0, i) : upstream;
    }

    /** remote branch names and tracking defaults follow the chosen remote */
    private void remoteChanged() {
        String r = (String) remote.getSelectedItem();
        remotes.stream().filter(x -> x.name().equals(r)).findFirst()
                .ifPresent(x -> url.setText(x.url() + (x.pushUrl() != null ? "  (push: " + x.pushUrl() + ")" : "")));
        for (Row row : rows) {
            String up = upstreams.get(row.local);
            if (up != null && r != null && up.startsWith(r + "/")) {
                row.remote = up.substring(r.length() + 1);
                row.track = true; // already tracking it
            } else {
                row.remote = row.local;
                row.track = up == null; // track when there is no upstream yet
            }
        }
        model.fireTableDataChanged();
    }

    /** @return null when cancelled */
    public Result showDialog() {
        setVisible(true);
        return result;
    }

    private class Model extends AbstractTableModel {
        private final String[] names = {"Push?", "Local branch", "Remote branch", "Track?"};

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 || c == 3 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c != 1; }

        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> row.push;
                case 1 -> row.local;
                case 2 -> row.remote;
                default -> row.track;
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Row row = rows.get(r);
            switch (c) {
                case 0 -> row.push = (Boolean) v;
                case 2 -> row.remote = (String) v;
                case 3 -> row.track = (Boolean) v;
                default -> {}
            }
        }
    }
}
