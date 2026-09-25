/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import vavi.apps.gitup.model.CommitLog.CommitRow;


/**
 * lower left pane.
 * <p>
 * working copy: "Staged files" (top) / "Unstaged files" (bottom) and the commit message box.
 * a commit: its changed files and its message.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class StagingPanel extends JPanel {

    private final CardLayout cards = new CardLayout();

    final FileTable stagedTable = new FileTable(true, true);
    final FileTable unstagedTable = new FileTable(false, true);
    final FileTable commitTable = new FileTable(true, false);

    final JTextArea message = new JTextArea(4, 40);
    final JButton commitButton = new JButton("Commit");
    final JButton stageAllButton = new JButton("Stage All");
    final JButton unstageAllButton = new JButton("Unstage All");
    final JCheckBox amendBox = new JCheckBox("Amend last commit");
    final JButton historyButton = new JButton("History ▾");
    final JButton abortMergeButton = new JButton("Abort Merge");
    final JButton continueRebaseButton = new JButton("Continue Rebase");
    private final JLabel bannerLabel = new JLabel();
    private final JPanel mergeBanner = new JPanel(new BorderLayout(6, 0));

    private final JLabel stagedLabel = new JLabel();
    private final JLabel unstagedLabel = new JLabel();
    private final JTextArea commitInfo = new JTextArea();

    public StagingPanel() {
        setLayout(cards);

        // working copy
        JPanel staged = titled(stagedLabel, unstageAllButton, stagedTable);
        JPanel unstaged = titled(unstagedLabel, stageAllButton, unstagedTable);
        JSplitPane lists = new JSplitPane(JSplitPane.VERTICAL_SPLIT, staged, unstaged);
        lists.setResizeWeight(0.4);
        lists.setBorder(null);

        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        message.putClientProperty("JTextField.placeholderText", "Commit message");
        int menu = Keys.menu();
        message.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu), "commit");
        message.getActionMap().put("commit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { commitButton.doClick(); }
        });
        JPanel commitBox = new JPanel(new BorderLayout(4, 4));
        commitBox.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JScrollPane messageScroll = new JScrollPane(message);
        messageScroll.setMinimumSize(new java.awt.Dimension(100, 72));
        messageScroll.setPreferredSize(new java.awt.Dimension(300, 90));
        commitBox.add(messageScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new BorderLayout());
        commitButton.setToolTipText("Commit staged files (⌘↩)");
        amendBox.setToolTipText("Replace the last commit with the staged files and this message");
        historyButton.setToolTipText("Reuse a recent commit message");
        historyButton.putClientProperty("JButton.buttonType", "borderless");
        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        left.add(amendBox);
        left.add(historyButton);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(commitButton, BorderLayout.EAST);
        commitBox.add(buttons, BorderLayout.SOUTH);

        mergeBanner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
        mergeBanner.setBackground(new java.awt.Color(0xfff4ce));
        bannerLabel.setForeground(java.awt.Color.darkGray);
        mergeBanner.add(bannerLabel, BorderLayout.CENTER);
        JPanel bannerButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        bannerButtons.setOpaque(false);
        bannerButtons.add(continueRebaseButton);
        bannerButtons.add(abortMergeButton);
        mergeBanner.add(bannerButtons, BorderLayout.EAST);
        mergeBanner.setVisible(false);
        commitBox.add(mergeBanner, BorderLayout.NORTH);

        JSplitPane working = new JSplitPane(JSplitPane.VERTICAL_SPLIT, lists, commitBox);
        working.setResizeWeight(1);
        working.setBorder(null);
        add(working, "working");
        WindowState.remember(lists, "split.lists");
        WindowState.remember(working, "split.commitBox");

        // a commit
        commitInfo.setEditable(false);
        // a plain (non UIResource) color, so the look and feel does not gray out the read-only text
        java.awt.Color white = UIManager.getColor("TextArea.background");
        commitInfo.setBackground(new java.awt.Color(white != null ? white.getRGB() : 0xffffff));
        commitInfo.setLineWrap(true);
        commitInfo.setWrapStyleWord(true);
        commitInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane commit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(commitInfo), new JScrollPane(commitTable));
        commit.setResizeWeight(0.3);
        commit.setBorder(null);
        add(commit, "commit");
        WindowState.remember(commit, "split.commitInfo");

        setCounts(0, 0);
    }

    private static JPanel titled(JLabel label, JButton button, FileTable table) {
        JPanel p = new JPanel(new BorderLayout());
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        header.add(label, BorderLayout.WEST);
        button.putClientProperty("JButton.buttonType", "borderless");
        header.add(button, BorderLayout.EAST);
        p.add(header, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    public void setCounts(int staged, int unstaged) {
        stagedLabel.setText("Staged files (" + staged + ")");
        unstagedLabel.setText("Unstaged files (" + unstaged + ")");
        stageAllButton.setEnabled(unstaged > 0);
        unstageAllButton.setEnabled(staged > 0);
    }

    /** shows the merge / rebase banner, disables amend (and commit while rebasing) */
    public void setState(vavi.apps.gitup.model.GitRepo.State state) {
        boolean merging = state == vavi.apps.gitup.model.GitRepo.State.MERGE;
        boolean rebasing = state == vavi.apps.gitup.model.GitRepo.State.REBASE;
        mergeBanner.setVisible(merging || rebasing);
        bannerLabel.setText(rebasing ? "Rebasing: resolve conflicts, stage the files, then continue."
                : "Merging: resolve conflicts, stage the files, then commit.");
        abortMergeButton.setText(rebasing ? "Abort Rebase" : "Abort Merge");
        continueRebaseButton.setVisible(rebasing);
        amendBox.setEnabled(!merging && !rebasing);
        if (merging || rebasing) amendBox.setSelected(false);
        commitButton.setEnabled(!rebasing);
        commitButton.setText(merging ? "Commit Merge" : "Commit");
    }

    public void showWorking() {
        cards.show(this, "working");
    }

    /** a multiple selection: the commits of the range, oldest parent to newest */
    public void showCommits(java.util.List<CommitRow> commits) {
        StringBuilder sb = new StringBuilder(commits.size() + " commits selected, changes from "
                + commits.getLast().shortOid() + "^ to " + commits.getFirst().shortOid() + "\n\n");
        for (CommitRow c : commits) sb.append(c.shortOid()).append("  ").append(c.summary()).append("  (").append(c.author()).append(")\n");
        commitInfo.setText(sb.toString());
        commitInfo.setCaretPosition(0);
        cards.show(this, "commit");
    }

    public void showCommit(CommitRow c) {
        commitInfo.setText("commit " + c.oid() + "\n"
                + (c.parents().isEmpty() ? "" : "parents " + String.join(" ", c.parents().stream().map(p -> p.substring(0, 7)).toList()) + "\n")
                + "author " + c.author() + " <" + c.email() + ">\n"
                + "date   " + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
                        .withZone(java.time.ZoneId.systemDefault()).format(c.time()) + "\n\n"
                + c.message());
        commitInfo.setCaretPosition(0);
        cards.show(this, "commit");
    }
}
