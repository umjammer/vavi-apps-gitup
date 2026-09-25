/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import vavi.apps.gitup.model.CommandLog;
import vavi.apps.gitup.model.CommandLog.Entry;


/**
 * SourceTree-like "Command History": the git commands equivalent to what was done in a repository.
 * ⌘C copies the selected commands, ready to paste into a terminal.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class CommandHistory extends JDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<Entry> entries = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final CommandLog log;
    private final Consumer<Entry> listener = e -> SwingUtilities.invokeLater(() -> add(e));

    public CommandHistory(Component owner, String repository, CommandLog log) {
        super(SwingUtilities.getWindowAncestor(owner), "Command History — " + repository, ModalityType.MODELESS);
        this.log = log;
        entries.addAll(log.entries());
        log.addListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { log.removeListener(listener); }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(620);
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
                String s = (String) v;
                // multi-line commands (a patch, several update-ref) show their first line, the rest in the tooltip
                String first = s.lines().findFirst().orElse("");
                super.getTableCellRendererComponent(t, s.contains("\n") ? first + " …" : first, sel, false, r, c);
                setFont(mono);
                setToolTipText(s.contains("\n") ? "<html><pre>" + s.replace("&", "&amp;").replace("<", "&lt;") + "</pre></html>" : null);
                return this;
            }
        });
        int menu = Keys.menu();
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), "copyCommands");
        table.getActionMap().put("copyCommands", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { copySelected(); }
        });

        JLabel hint = new JLabel("  equivalent git commands (the app uses libgit2 / GitUpKit), ⌘C copies the selected ones");
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1));
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(hint, BorderLayout.SOUTH);
        setSize(new Dimension(980, 360));
        setLocationRelativeTo(owner);
        scrollToEnd();
    }

    private void add(Entry e) {
        entries.add(e);
        model.fireTableRowsInserted(entries.size() - 1, entries.size() - 1);
        scrollToEnd();
    }

    private void scrollToEnd() {
        if (!entries.isEmpty()) table.scrollRectToVisible(table.getCellRect(entries.size() - 1, 0, true));
    }

    private void copySelected() {
        String s = java.util.Arrays.stream(table.getSelectedRows()).mapToObj(r -> entries.get(r).command()).collect(Collectors.joining("\n"));
        if (!s.isEmpty()) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private class Model extends AbstractTableModel {
        private final String[] names = {"Time", "Command", "Note"};

        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return names.length; }
        @Override public String getColumnName(int c) { return names[c]; }

        @Override public Object getValueAt(int r, int c) {
            Entry e = entries.get(r);
            return switch (c) {
                case 0 -> TIME.format(e.time());
                case 1 -> e.command();
                default -> e.note() != null ? e.note() : "";
            };
        }
    }
}
