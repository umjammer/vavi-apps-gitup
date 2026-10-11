/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import vavi.apps.gitup.model.GitRepo.Remote;


/**
 * SourceTree-like "Pull" dialog: the remote, the remote branch to pull into the current branch,
 * and how (commit immediately, merge log, no fast-forward, rebase).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-11 nsano initial version <br>
 */
public class PullDialog extends JDialog {

    /**
     * the user's choice
     *
     * @param remoteBranch "remote/branch"
     */
    public record Result(String remoteBranch, boolean commit, boolean log, boolean noFastForward, boolean rebase) {}

    /** fetches, then hands the remote branches over (on the EDT) */
    public interface Refresher {
        void refresh(Consumer<Map<String, List<String>>> done);
    }

    private final JComboBox<String> remote = new JComboBox<>();
    private final JLabel url = new JLabel();
    private final JComboBox<String> branch = new JComboBox<>();
    private final JButton refresh = new JButton("Refresh");
    private final JCheckBox commit = new JCheckBox("Commit merged changes immediately", true);
    private final JCheckBox log = new JCheckBox("Include messages from commits being merged in merge commit");
    private final JCheckBox noFF = new JCheckBox("Create new commit even if fast-forward merge");
    private final JCheckBox rebase = new JCheckBox("Rebase instead of merge (WARNING: make sure you haven't pushed your changes)");
    private final List<Remote> remotes;
    private final String current;
    private final String upstream;
    private Map<String, List<String>> remoteBranches;
    private Result result;

    /**
     * @param remoteBranches remote name to its branch names (without the remote name)
     * @param upstream       "remote/branch" of the current branch, null when none
     * @param rebaseDefault  pull.rebase / branch.&lt;name&gt;.rebase
     * @param refresher      null: no refresh button
     */
    public PullDialog(Component parent, List<Remote> remotes, Map<String, List<String>> remoteBranches, String current, String upstream,
                      boolean rebaseDefault, Refresher refresher) {
        super(parent != null ? SwingUtilities.getWindowAncestor(parent) : null, "Pull", ModalityType.APPLICATION_MODAL);
        this.remotes = remotes;
        this.remoteBranches = remoteBranches;
        this.current = current;
        this.upstream = upstream;

        remotes.forEach(r -> remote.addItem(r.name()));
        String preferred = upstream != null ? remoteOf(upstream) : remotes.stream().anyMatch(r -> r.name().equals("origin")) ? "origin" : null;
        if (preferred != null) remote.setSelectedItem(preferred);
        remote.addActionListener(e -> remoteChanged());
        url.setForeground(Color.gray);

        refresh.setToolTipText("fetch, then list the remote branches again");
        refresh.setEnabled(refresher != null);
        refresh.addActionListener(e -> {
            refresh.setEnabled(false);
            Object selected = branch.getSelectedItem();
            refresher.refresh(map -> {
                this.remoteBranches = map;
                remoteChanged();
                if (selected != null) branch.setSelectedItem(selected);
                refresh.setEnabled(true);
            });
        });

        rebase.setSelected(rebaseDefault);
        rebase.setForeground(new Color(0xcf222e));
        rebase.addActionListener(e -> optionsChanged());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints l = new GridBagConstraints();
        l.anchor = GridBagConstraints.LINE_END;
        l.insets = new Insets(3, 0, 3, 8);
        GridBagConstraints f = new GridBagConstraints();
        f.anchor = GridBagConstraints.LINE_START;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(3, 0, 3, 0);
        f.gridwidth = GridBagConstraints.REMAINDER;

        form.add(new JLabel("Pull from repository:"), l);
        JPanel remoteRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        remoteRow.add(remote);
        remoteRow.add(url);
        form.add(remoteRow, f);

        form.add(new JLabel("Remote branch to pull:"), l);
        JPanel branchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        branchRow.add(branch);
        branchRow.add(refresh);
        form.add(branchRow, f);

        form.add(new JLabel("Pull into local branch:"), l);
        JPanel intoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        intoRow.add(new JLabel(current));
        form.add(intoRow, f);

        JPanel options = new JPanel();
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.setBorder(BorderFactory.createTitledBorder("Options"));
        options.add(commit);
        options.add(log);
        options.add(noFF);
        options.add(rebase);

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String r = (String) remote.getSelectedItem();
            String b = (String) branch.getSelectedItem();
            if (r == null || b == null) return;
            result = new Result(r + "/" + b, commit.isSelected(), log.isSelected(), noFF.isSelected(), rebase.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        getRootPane().setDefaultButton(ok);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        content.add(form, BorderLayout.NORTH);
        content.add(options, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        remoteChanged();
        optionsChanged();
        pack();
        setLocationRelativeTo(parent);
    }

    private static String remoteOf(String upstream) {
        int i = upstream.indexOf('/');
        return i > 0 ? upstream.substring(0, i) : upstream;
    }

    /** the branches of the chosen remote, the upstream or the same name first */
    private void remoteChanged() {
        String r = (String) remote.getSelectedItem();
        remotes.stream().filter(x -> x.name().equals(r)).findFirst().ifPresent(x -> url.setText(x.url()));
        branch.removeAllItems();
        List<String> names = r != null ? remoteBranches.getOrDefault(r, List.of()) : List.of();
        names.forEach(branch::addItem);
        String preferred = upstream != null && upstream.startsWith(r + "/") ? upstream.substring(r.length() + 1) : current;
        if (names.contains(preferred)) branch.setSelectedItem(preferred);
        pack();
    }

    /** merge options do not apply to a rebase */
    private void optionsChanged() {
        boolean merge = !rebase.isSelected();
        commit.setEnabled(merge);
        log.setEnabled(merge);
        noFF.setEnabled(merge);
    }

    /** @return null when cancelled */
    public Result showDialog() {
        setVisible(true);
        return result;
    }
}
