/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import vavi.apps.gitup.model.Settings;


/**
 * stops rewriting commits already pushed (amend, GitUp's rewrites, reset, undo).
 * <p>
 * with {@link Settings#protectPushed()} the rewrite needs an explicit override (a checkbox then a button,
 * Cancel is the default), otherwise it is only a warning.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
final class PushedGuard {

    private PushedGuard() {}

    enum Choice { CANCEL, NEW_COMMIT, REWRITE }

    /**
     * @param action e.g. "Amend"
     * @param what what is rewritten, e.g. "the last commit 1234567"
     * @param remotes the remote branches containing it, nothing is asked when empty
     * @param offerNewCommit offers "Commit as New Commit" (for amend)
     */
    static Choice ask(Component parent, String action, String what, List<String> remotes, boolean offerNewCommit) {
        if (remotes.isEmpty()) return Choice.REWRITE;
        String where = String.join(", ", remotes);
        String consequence = "<br>The remote keeps the old commit: pushing again needs a force push,<br>"
                + "and everybody who pulled it will have to reconcile.";
        if (!Settings.get().protectPushed()) {
            int r = JOptionPane.showConfirmDialog(parent, "<html>" + escape(action) + " rewrites " + escape(what)
                            + ", it is already pushed to <b>" + escape(where) + "</b>." + consequence + "</html>",
                    action, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            return r == JOptionPane.OK_OPTION ? Choice.REWRITE : Choice.CANCEL;
        }

        JCheckBox override = new JCheckBox("I know, rewrite the pushed history");
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.add(new JLabel("<html><b>" + escape(what) + " is already pushed</b> to " + escape(where) + ".<br>"
                + escape(action) + " would rewrite it." + consequence
                + (offerNewCommit ? "<br><br>Commit the changes as a new commit on top instead." : "<br><br>Make a new commit on top instead (e.g. a revert or a fix).")
                + "<br><font color='#6e7781'>Protect pushed commits: Settings (⌘,) › History</font></html>"), BorderLayout.CENTER);
        p.add(override, BorderLayout.SOUTH);

        JOptionPane pane = new JOptionPane(p, JOptionPane.WARNING_MESSAGE);
        JButton cancel = new JButton("Cancel");
        JButton newCommit = new JButton("Commit as New Commit");
        JButton rewrite = new JButton(action + " Anyway");
        rewrite.setEnabled(false);
        override.addActionListener(e -> rewrite.setEnabled(override.isSelected()));
        cancel.addActionListener(e -> pane.setValue(Choice.CANCEL));
        newCommit.addActionListener(e -> pane.setValue(Choice.NEW_COMMIT));
        rewrite.addActionListener(e -> pane.setValue(Choice.REWRITE));
        List<Object> options = new ArrayList<>();
        options.add(rewrite);
        if (offerNewCommit) options.add(newCommit);
        options.add(cancel);
        pane.setOptions(options.toArray());
        pane.setInitialValue(offerNewCommit ? newCommit : cancel);
        JDialog dialog = pane.createDialog(parent, "Pushed Commit");
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder());
        dialog.setVisible(true);
        dialog.dispose();
        return pane.getValue() instanceof Choice c ? c : Choice.CANCEL;
    }

    /** yes / no version of {@link #ask} */
    static boolean allow(Component parent, String action, String what, List<String> remotes) {
        return ask(parent, action, what, remotes, false) == Choice.REWRITE;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
