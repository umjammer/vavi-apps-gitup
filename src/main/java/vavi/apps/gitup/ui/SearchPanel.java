/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.EnumSet;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import vavi.apps.gitup.model.HistorySearch.Hit;
import vavi.apps.gitup.model.HistorySearch.Kind;


/**
 * search results under the log: commit messages, file names and changed lines.
 * selecting a result jumps to the commit (and the file, and the line).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class SearchPanel extends JPanel {

    public interface Listener {
        void reveal(Hit hit);
        void stop();
        /** the kinds changed, search again */
        void research();
        void close();
    }

    private final DefaultListModel<Hit> model = new DefaultListModel<>();
    private final JList<Hit> list = new JList<>(model);
    private final JLabel status = new JLabel();
    private final JButton stop = new JButton("Stop");
    private final JCheckBox messages = new JCheckBox("Messages", true);
    private final JCheckBox files = new JCheckBox("File names", true);
    private final JCheckBox contents = new JCheckBox("Contents", true);
    private Listener listener;
    private String query = "";
    private int scanned;

    public SearchPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(100, 200));
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color.lightGray));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && listener != null) listener.reveal(list.getSelectedValue());
        });

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        header.add(status, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        for (JCheckBox b : new JCheckBox[] {messages, files, contents}) {
            b.addActionListener(e -> { if (listener != null) listener.research(); });
            right.add(b);
        }
        stop.addActionListener(e -> { if (listener != null) listener.stop(); });
        JButton close = new JButton("×");
        close.setToolTipText("Close the search results (esc in the search field)");
        close.putClientProperty("JButton.buttonType", "toolBarButton");
        close.addActionListener(e -> { if (listener != null) listener.close(); });
        right.add(stop);
        right.add(close);
        header.add(right, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Set<Kind> kinds() {
        Set<Kind> k = EnumSet.noneOf(Kind.class);
        if (messages.isSelected()) k.add(Kind.MESSAGE);
        if (files.isSelected()) k.add(Kind.FILE);
        if (contents.isSelected()) k.add(Kind.CONTENT);
        return k;
    }

    public void start(String query) {
        this.query = query;
        model.clear();
        scanned = 0;
        stop.setEnabled(true);
        updateStatus(true);
    }

    public void add(Hit hit) {
        model.addElement(hit);
        updateStatus(true);
    }

    public void progress(int scanned) {
        this.scanned = scanned;
        updateStatus(true);
    }

    public void done(boolean stopped, int limit) {
        stop.setEnabled(false);
        updateStatus(false);
        if (model.size() >= limit) status.setText(status.getText() + " (first " + limit + " shown)");
        else if (stopped) status.setText(status.getText() + " (stopped)");
    }

    private void updateStatus(boolean running) {
        status.setText((running ? "Searching \"" : "\"") + query + "\": " + model.size() + " results in " + scanned + " commits" + (running ? "…" : ""));
    }

    /** kind badge, short sha, then the matching text */
    private static class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object v, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, "", index, sel, focus);
            Hit h = (Hit) v;
            String badge = switch (h.kind()) {
                case MESSAGE -> "<font color='#0969da'>message</font>";
                case FILE -> "<font color='#8250df'>file</font>";
                case CONTENT -> "<font color='" + (h.origin() == '+' ? "#2da44e" : "#cf222e") + "'>" + h.origin() + "line</font>";
            };
            String text = switch (h.kind()) {
                case MESSAGE -> esc(h.summary());
                case FILE -> esc(h.path()) + " <font color='gray'>— " + esc(h.summary()) + "</font>";
                case CONTENT -> esc(h.path()) + ":" + h.line() + " <tt>" + esc(h.text().length() > 120 ? h.text().substring(0, 119) + "…" : h.text()) + "</tt>";
            };
            setText("<html>" + badge + " <tt>" + h.oid().substring(0, 7) + "</tt> " + text + "</html>");
            return this;
        }

        private static String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }
}
