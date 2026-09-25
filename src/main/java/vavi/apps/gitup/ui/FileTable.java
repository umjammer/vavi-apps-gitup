/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import vavi.apps.gitup.model.FileChange;


/**
 * a list of changed files: [checkbox] [status] [path].
 * <p>
 * the checkbox moves the file to the other side (stage / unstage), so does
 * dragging rows onto the other table.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class FileTable extends JTable {

    /** user requests from the table */
    public interface Listener {
        /** move files to the other side (stage when unstaged, unstage when staged) */
        void move(FileTable source, List<FileChange> files);
        void discard(List<FileChange> files);
    }

    /** the "Files" table of a commit has no checkbox and no actions */
    private final boolean checkable;
    private final boolean staged;
    private Listener listener;
    private Path workdir;
    private final Model model = new Model();

    /** transfer flavor for rows dragged between tables */
    private static final DataFlavor FLAVOR = localFlavor();

    record Moving(FileTable source, List<FileChange> files) {}

    private static DataFlavor localFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + Moving.class.getName(), "git files", Moving.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public FileTable(boolean staged, boolean checkable) {
        this.staged = staged;
        this.checkable = checkable;
        setModel(model);
        setShowGrid(false);
        setTableHeader(null);
        setFillsViewportHeight(true);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setRowHeight(getFontMetrics(getFont()).getHeight() + 6);

        int c = 0;
        if (checkable) {
            getColumnModel().getColumn(c).setMaxWidth(26);
            getColumnModel().getColumn(c).setMinWidth(26);
            c++;
        }
        getColumnModel().getColumn(c).setMaxWidth(24);
        getColumnModel().getColumn(c).setMinWidth(24);
        getColumnModel().getColumn(c).setCellRenderer(new StatusRenderer());
        getColumnModel().getColumn(c + 1).setCellRenderer(new PathRenderer());

        if (checkable) {
            setDragEnabled(true);
            setDropMode(DropMode.ON);
            setTransferHandler(new Handler());
        }

        int menu = Keys.menu();
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), "copyPaths");
        getActionMap().put("copyPaths", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { copy(selectedFiles(), false); }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "move");
        getActionMap().put("move", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (checkable) fireMove(selectedFiles()); }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setWorkdir(Path workdir) {
        this.workdir = workdir;
    }

    public boolean isStaged() {
        return staged;
    }

    /** replaces the rows, keeping the selection of files still present */
    public void setFiles(List<FileChange> files) {
        List<String> selected = selectedFiles().stream().map(FileChange::path).toList();
        model.files = new ArrayList<>(files);
        model.fireTableDataChanged();
        for (int i = 0; i < files.size(); i++) {
            if (selected.contains(files.get(i).path())) addRowSelectionInterval(i, i);
        }
    }

    public List<FileChange> getFiles() {
        return model.files;
    }

    public List<FileChange> selectedFiles() {
        List<FileChange> list = new ArrayList<>();
        for (int r : getSelectedRows()) list.add(model.files.get(convertRowIndexToModel(r)));
        return list;
    }

    private void fireMove(List<FileChange> files) {
        if (listener != null && !files.isEmpty()) listener.move(this, files);
    }

    private void popup(MouseEvent e) {
        int r = rowAtPoint(e.getPoint());
        if (r >= 0 && !isRowSelected(r)) setRowSelectionInterval(r, r);
        List<FileChange> files = selectedFiles();
        if (files.isEmpty()) return;
        JPopupMenu menu = new JPopupMenu();
        if (checkable) {
            item(menu, staged ? "Unstage" : "Stage", () -> fireMove(files));
            if (!staged) item(menu, "Discard Changes…", () -> { if (listener != null) listener.discard(files); });
            menu.addSeparator();
        }
        item(menu, "Copy Path", () -> copy(files, false));
        item(menu, "Copy Full Path", () -> copy(files, true));
        if (workdir != null) {
            item(menu, "Show in Finder", () -> {
                try {
                    new ProcessBuilder("open", "-R", workdir.resolve(files.getFirst().path()).toString()).start();
                } catch (Exception ex) {
                    Toolkit.getDefaultToolkit().beep();
                }
            });
        }
        menu.show(this, e.getX(), e.getY());
    }

    private static void item(JPopupMenu menu, String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        menu.add(i);
    }

    private void copy(List<FileChange> files, boolean full) {
        if (files.isEmpty()) return;
        String s = files.stream()
                .map(f -> full && workdir != null ? workdir.resolve(f.path()).toString() : f.path())
                .collect(Collectors.joining("\n"));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private class Model extends AbstractTableModel {
        List<FileChange> files = new ArrayList<>();

        @Override public int getRowCount() { return files.size(); }
        @Override public int getColumnCount() { return checkable ? 3 : 2; }

        @Override public Class<?> getColumnClass(int c) {
            return checkable && c == 0 ? Boolean.class : Object.class;
        }

        @Override public boolean isCellEditable(int r, int c) {
            return checkable && c == 0;
        }

        @Override public Object getValueAt(int r, int c) {
            FileChange f = files.get(r);
            if (checkable) {
                if (c == 0) return staged;
                c--;
            }
            return c == 0 ? f.kind() : f;
        }

        @Override public void setValueAt(Object value, int r, int c) {
            if (checkable && c == 0 && !value.equals(staged)) {
                List<FileChange> files = isRowSelected(r) ? selectedFiles() : List.of(this.files.get(r));
                fireMove(files);
            }
        }
    }

    /** colored status letter */
    private static class StatusRenderer extends DefaultTableCellRenderer {
        StatusRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, sel, false, r, c);
            FileChange.Kind k = (FileChange.Kind) v;
            l.setText(String.valueOf(k.symbol));
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            if (!sel) {
                l.setForeground(switch (k) {
                    case ADDED, UNTRACKED -> new Color(0x2da44e);
                    case DELETED -> new Color(0xcf222e);
                    case CONFLICTED -> new Color(0xbf8700);
                    case RENAMED -> new Color(0x8250df);
                    default -> new Color(0x0969da);
                });
            }
            return l;
        }
    }

    /** file name, then the directory dimmed */
    private static class PathRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            FileChange f = (FileChange) v;
            super.getTableCellRendererComponent(t, f.path(), sel, false, r, c);
            String p = f.path();
            int i = p.lastIndexOf('/');
            String name = i < 0 ? p : p.substring(i + 1);
            String dir = i < 0 ? "" : p.substring(0, i);
            String rename = f.oldPath() != null && !f.oldPath().equals(f.path()) ? " ← " + f.oldPath() : "";
            setText("<html>" + esc(name) + " <font color='gray'>" + esc(dir) + esc(rename) + "</font></html>");
            setToolTipText(p + rename);
            return this;
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }

    /** drag rows from one table onto the other */
    private class Handler extends TransferHandler {
        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override protected Transferable createTransferable(JComponent c) {
            Moving m = new Moving(FileTable.this, selectedFiles());
            String text = m.files().stream().map(FileChange::path).collect(Collectors.joining("\n"));
            return new Transferable() {
                @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {FLAVOR, DataFlavor.stringFlavor}; }
                @Override public boolean isDataFlavorSupported(DataFlavor f) { return FLAVOR.equals(f) || DataFlavor.stringFlavor.equals(f); }
                @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                    if (FLAVOR.equals(f)) return m;
                    if (DataFlavor.stringFlavor.equals(f)) return text;
                    throw new UnsupportedFlavorException(f);
                }
            };
        }

        @Override public boolean canImport(TransferSupport s) {
            if (!s.isDataFlavorSupported(FLAVOR)) return false;
            try {
                return ((Moving) s.getTransferable().getTransferData(FLAVOR)).source() != FileTable.this;
            } catch (Exception e) {
                return false;
            }
        }

        @Override public boolean importData(TransferSupport s) {
            if (!canImport(s)) return false;
            try {
                Moving m = (Moving) s.getTransferable().getTransferData(FLAVOR);
                if (listener != null) listener.move(m.source(), m.files());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
