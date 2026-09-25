/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import vavi.apps.gitup.model.LazyPatch;
import vavi.apps.gitup.model.LazyPatch.Hunk;
import vavi.apps.gitup.model.LazyPatch.Row;
import vavi.apps.gitup.model.PartialPatchBuilder;
import vavi.apps.gitup.model.Settings;


/**
 * virtualized hunk diff view.
 * <p>
 * the preferred height is {@code rowCount * rowHeight}, painting asks the
 * {@link LazyPatch} only for the rows inside the clip, so a huge diff costs
 * the same as a small one. rows are selectable by hunk (click the header) and
 * by line (click, shift-click, cmd-click, drag). hunk headers have
 * SourceTree-like buttons (stage / unstage / discard, hunk or selected lines).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class DiffView extends JComponent implements Scrollable {

    /** what the shown patch is, decides the available actions */
    public enum Mode { UNSTAGED, STAGED, COMMIT }

    /** REVERSE: apply the inverse of a commit's hunk / lines to the working copy (SourceTree's "Reverse hunk") */
    public enum Action { STAGE, UNSTAGE, DISCARD, REVERSE }

    /** receives stage / unstage / discard requests, rows are patch rows (a header row means the whole hunk) */
    public interface Listener {
        void perform(Action action, LazyPatch patch, BitSet rows);
    }

    private LazyPatch patch;
    private Mode mode = Mode.COMMIT;
    private String message;
    private Listener listener;

    private final BitSet selection = new BitSet();
    private int anchor = -1;

    private int rowHeight;
    private int charWidth;
    private int ascent;
    private int maxChars = 80;

    private static final int GUTTER_DIGITS = 6;
    private static final int TAB = 4;

    /** buttons painted in hunk headers during the last paint, in component coordinates */
    private record HeaderButton(Rectangle bounds, Action action, int hunk, boolean lines) {}

    private final List<HeaderButton> buttons = new ArrayList<>();

    public DiffView() {
        applyFontSetting();
        setOpaque(true);
        setFocusable(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { pressed(e); }
            @Override public void mouseDragged(MouseEvent e) { dragged(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup(e); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        int menu = Keys.menu();
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), "copy");
        getActionMap().put("copy", action("copy", this::copySelection));
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menu), "selectAll");
        getActionMap().put("selectAll", action("selectAll", this::selectAll));
    }

    private static AbstractAction action(String name, Runnable r) {
        return new AbstractAction(name) {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }

    @Override
    public void setFont(Font font) {
        super.setFont(font);
        FontMetrics fm = getFontMetrics(font);
        rowHeight = fm.getHeight() + 2;
        charWidth = fm.charWidth('m');
        ascent = fm.getAscent() + 1;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public LazyPatch getPatch() {
        return patch;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * shows the patch. the previous patch is not closed here, the owner closes it.
     *
     * @param message shown when patch is null (e.g. "No changes")
     */
    public void setPatch(LazyPatch patch, Mode mode, String message) {
        this.patch = patch;
        this.mode = mode;
        this.message = patch == null ? message : patch.isBinary() ? "Binary file" : patch.rowCount() == 0 ? "No content changes" : null;
        selection.clear();
        anchor = -1;
        maxChars = 80;
        revalidate();
        repaint();
        if (getParent() != null) scrollRectToVisible(new Rectangle(0, 0, 1, 1));
    }

    /** keeps the patch but replaces it with a re-read one, preserving the scroll position */
    public void replacePatch(LazyPatch patch, String message) {
        Rectangle r = getVisibleRect();
        setPatch(patch, mode, message);
        SwingUtilities.invokeLater(() -> scrollRectToVisible(r));
    }

    /**
     * selects and scrolls to a changed line.
     *
     * @param origin '+' (line is on the new side) or '-' (on the old side)
     * @return false when not found
     */
    public boolean reveal(char origin, int line) {
        if (patch == null || message != null) return false;
        for (int r = 0; r < rows(); r++) {
            Row row = patch.row(r);
            if (row.origin() == origin && (origin == '+' ? row.newLineno() : row.oldLineno()) == line) {
                selection.clear();
                selection.set(r);
                anchor = r;
                int y = r * rowHeight;
                Rectangle v = getVisibleRect();
                scrollRectToVisible(new Rectangle(v.x, Math.max(0, y - v.height / 3), 1, v.height));
                repaint();
                return true;
            }
        }
        return false;
    }

    public BitSet getSelection() {
        return (BitSet) selection.clone();
    }

    // geometry

    private int gutterWidth() {
        return charWidth * GUTTER_DIGITS * 2 + 12;
    }

    private int rows() {
        return patch == null ? 0 : patch.rowCount();
    }

    private int rowAt(int y) {
        return Math.max(0, Math.min(rows() - 1, y / rowHeight));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(gutterWidth() + (maxChars + 2) * charWidth, Math.max(rows() * rowHeight, rowHeight));
    }

    // painting

    /** SourceTree's diff font: Menlo, Monaco before macOS 10.6 */
    private static final List<String> DEFAULT_FONTS = List.of("Menlo", "Monaco");
    public static final int DEFAULT_FONT_SIZE = 12;

    /** @return the first installed of SourceTree's fonts, or the logical monospaced font */
    public static String defaultFontName() {
        List<String> installed = List.of(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        return DEFAULT_FONTS.stream().filter(installed::contains).findFirst().orElse(Font.MONOSPACED);
    }

    /** the font from the settings, or the default */
    public static Font settingsFont() {
        Settings s = Settings.get();
        String name = s.diffFontName() != null ? s.diffFontName() : defaultFontName();
        int size = s.diffFontSize() > 0 ? s.diffFontSize() : DEFAULT_FONT_SIZE;
        return new Font(name, Font.PLAIN, size);
    }

    /** (re)applies the font of the settings */
    public void applyFontSetting() {
        Font f = settingsFont();
        if (f.equals(getFont())) return;
        setFont(f);
        revalidate();
        repaint();
    }

    /** the color from the settings, or the theme's default */
    private static Color color(Settings.DiffColor key) {
        Color c = Settings.get().diffColor(key);
        return c != null ? c : defaultColor(key);
    }

    /** the default for the current (light / dark) theme */
    public static Color defaultColor(Settings.DiffColor key) {
        Color bg = UIManager.getColor("Panel.background");
        boolean dark = bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
        return switch (key) {
            case ADDED -> dark ? new Color(0x1f3d2a) : new Color(0xe6ffed);
            case REMOVED -> dark ? new Color(0x4a2127) : new Color(0xffeef0);
            case ADDED_TEXT -> dark ? new Color(0x3fb950) : new Color(0x1a7f37);
            case REMOVED_TEXT -> dark ? new Color(0xf85149) : new Color(0xcf222e);
            case HUNK_HEADER -> dark ? new Color(0x2b3445) : new Color(0xf1f8ff);
            case SELECTION -> dark ? new Color(0x3d5a80) : new Color(0xb4d5fe);
        };
    }

    private boolean isDark() {
        Color bg = UIManager.getColor("Panel.background");
        return bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        boolean dark = isDark();
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");
        Color dim = UIManager.getColor("Label.disabledForeground");
        Color addBg = color(Settings.DiffColor.ADDED);
        Color delBg = color(Settings.DiffColor.REMOVED);
        Color addFg = color(Settings.DiffColor.ADDED_TEXT);
        Color delFg = color(Settings.DiffColor.REMOVED_TEXT);
        Color headBg = color(Settings.DiffColor.HUNK_HEADER);
        Color selBg = color(Settings.DiffColor.SELECTION);
        Color gutterBg = dark ? bg.brighter() : new Color(0xf6f8fa);

        Rectangle clip = g.getClipBounds();
        g.setColor(bg);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        buttons.clear();

        if (patch == null || message != null) {
            String m = message != null ? message : "";
            g.setColor(dim);
            Rectangle v = getVisibleRect();
            int w = g.getFontMetrics().stringWidth(m);
            g.drawString(m, v.x + (v.width - w) / 2, v.y + v.height / 2);
            return;
        }

        Rectangle visible = getVisibleRect();
        int gw = gutterWidth();
        int first = clip.y / rowHeight;
        int last = Math.min(rows() - 1, (clip.y + clip.height) / rowHeight);
        int widest = maxChars;
        for (int r = first; r <= last; r++) {
            Row row = patch.row(r);
            int y = r * rowHeight;
            if (row.isHeader()) {
                g.setColor(headBg);
                g.fillRect(clip.x, y, clip.width, rowHeight);
                g.setColor(dim);
                g.drawString(row.content(), gw + 4, y + ascent);
                paintHeaderButtons(g, row.hunk(), visible, y);
                continue;
            }
            Color lineBg = row.origin() == '+' ? addBg : row.origin() == '-' ? delBg : bg;
            if (selection.get(r)) lineBg = selBg;
            g.setColor(lineBg);
            g.fillRect(clip.x, y, clip.width, rowHeight);
            g.setColor(gutterBg);
            g.fillRect(0, y, gw, rowHeight);
            g.setColor(dim);
            if (row.oldLineno() > 0) drawRight(g, String.valueOf(row.oldLineno()), charWidth * GUTTER_DIGITS, y + ascent);
            if (row.newLineno() > 0) drawRight(g, String.valueOf(row.newLineno()), charWidth * (GUTTER_DIGITS * 2 - 1) + 4, y + ascent);
            char o = row.origin();
            boolean marker = o == '=' || o == '>' || o == '<';
            String text = marker ? "\\ No newline at end of file" : expandTabs(row.content());
            widest = Math.max(widest, text.length());
            g.setColor(marker ? dim : o == '+' ? addFg : o == '-' ? delFg : fg);
            if (!marker) g.drawString(String.valueOf(o == ' ' ? ' ' : o), gw - charWidth, y + ascent);
            g.drawString(text, gw + 4, y + ascent);
        }
        if (widest > maxChars) {
            maxChars = widest;
            SwingUtilities.invokeLater(this::revalidate);
        }
    }

    private void drawRight(Graphics2D g, String s, int right, int y) {
        g.drawString(s, right - g.getFontMetrics().stringWidth(s), y);
    }

    private static String expandTabs(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == '\n' || s.charAt(e - 1) == '\r')) e--;
        if (s.indexOf('\t') < 0) return s.substring(0, e);
        StringBuilder sb = new StringBuilder(e + 16);
        for (int i = 0; i < e; i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                do sb.append(' '); while (sb.length() % TAB != 0);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** true when some selected row belongs to the hunk */
    private boolean hunkHasSelection(int hunk) {
        int start = patch.hunkRow(hunk);
        int end = start + 1 + patch.hunks().get(hunk).lineCount();
        int n = selection.nextSetBit(start);
        return n >= 0 && n < end;
    }

    private void paintHeaderButtons(Graphics2D g, int hunk, Rectangle visible, int y) {
        boolean lines = hunkHasSelection(hunk);
        String what = lines ? "lines" : "hunk";
        List<Object[]> list = new ArrayList<>();
        if (mode == Mode.COMMIT) {
            list.add(new Object[] {"Reverse " + what, Action.REVERSE});
        } else if (mode == Mode.UNSTAGED) {
            list.add(new Object[] {"Discard " + what, Action.DISCARD});
            list.add(new Object[] {"Stage " + what, Action.STAGE});
        } else {
            list.add(new Object[] {"Unstage " + what, Action.UNSTAGE});
        }
        FontMetrics fm = g.getFontMetrics();
        int x = visible.x + visible.width - 6;
        Color border = UIManager.getColor("Component.borderColor");
        Color buttonBg = UIManager.getColor("Button.background");
        Color fg = UIManager.getColor("Button.foreground");
        for (int i = list.size() - 1; i >= 0; i--) {
            String label = (String) list.get(i)[0];
            int w = fm.stringWidth(label) + 14;
            x -= w;
            Rectangle b = new Rectangle(x, y + 1, w, rowHeight - 2);
            g.setColor(buttonBg != null ? buttonBg : Color.white);
            g.fillRoundRect(b.x, b.y, b.width, b.height, 6, 6);
            g.setColor(border != null ? border : Color.gray);
            g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 6, 6);
            g.setColor(fg != null ? fg : Color.black);
            g.drawString(label, b.x + 7, y + ascent);
            buttons.add(new HeaderButton(b, (Action) list.get(i)[1], hunk, lines));
            x -= 6;
        }
    }

    // interaction

    private void pressed(MouseEvent e) {
        requestFocusInWindow();
        if (patch == null || message != null) return;
        if (e.isPopupTrigger()) {
            popup(e);
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        for (HeaderButton b : buttons) {
            if (b.bounds().contains(e.getPoint())) {
                fire(b.action(), b.lines() ? selection : hunkRows(b.hunk()));
                return;
            }
        }
        int r = rowAt(e.getY());
        Row row = patch.row(r);
        boolean toggle = (e.getModifiersEx() & Keys.menu()) != 0;
        boolean extend = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        if (row.isHeader()) {
            if (!toggle) selection.clear();
            int lines = patch.hunks().get(row.hunk()).lineCount();
            if (lines > 0) selectRange(r + 1, r + lines);
            anchor = -1;
        } else if (extend && anchor >= 0) {
            selection.clear();
            selectRange(anchor, r);
        } else if (toggle) {
            if (row.isChange()) selection.flip(r);
            anchor = r;
        } else {
            selection.clear();
            if (row.isChange()) selection.set(r);
            anchor = r;
        }
        repaint();
    }

    private void dragged(MouseEvent e) {
        if (patch == null || message != null || anchor < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
        int r = rowAt(e.getY());
        selection.clear();
        selectRange(anchor, r);
        scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
        repaint();
    }

    /** selects change lines between a and b (inclusive) */
    private void selectRange(int a, int b) {
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
            if (patch.row(i).isChange()) selection.set(i);
        }
    }

    /** rows of the hunk as the builder expects: the header row selects the whole hunk */
    private BitSet hunkRows(int hunk) {
        BitSet b = new BitSet();
        Hunk h = patch.hunks().get(hunk);
        int start = patch.hunkRow(hunk);
        b.set(start, start + 1 + h.lineCount());
        return b;
    }

    private void fire(Action action, BitSet rows) {
        if (listener != null && !rows.isEmpty()) listener.perform(action, patch, (BitSet) rows.clone());
    }

    private void popup(MouseEvent e) {
        if (patch == null || message != null) return;
        int r = rowAt(e.getY());
        if (!selection.get(r) && patch.row(r).isChange()) {
            selection.clear();
            selection.set(r);
            anchor = r;
            repaint();
        }
        int hunk = patch.hunkOfRow(r);
        JPopupMenu menu = new JPopupMenu();
        boolean sel = !selection.isEmpty();
        if (mode == Mode.UNSTAGED) {
            add(menu, "Stage Lines", sel, () -> fire(Action.STAGE, selection));
            add(menu, "Stage Hunk", true, () -> fire(Action.STAGE, hunkRows(hunk)));
            add(menu, "Discard Lines", sel, () -> fire(Action.DISCARD, selection));
            add(menu, "Discard Hunk", true, () -> fire(Action.DISCARD, hunkRows(hunk)));
            menu.addSeparator();
        } else if (mode == Mode.STAGED) {
            add(menu, "Unstage Lines", sel, () -> fire(Action.UNSTAGE, selection));
            add(menu, "Unstage Hunk", true, () -> fire(Action.UNSTAGE, hunkRows(hunk)));
            menu.addSeparator();
        } else {
            add(menu, "Reverse Lines", sel, () -> fire(Action.REVERSE, selection));
            add(menu, "Reverse Hunk", true, () -> fire(Action.REVERSE, hunkRows(hunk)));
            menu.addSeparator();
        }
        add(menu, "Copy Lines", sel, this::copySelection);
        add(menu, "Copy Hunk", true, () -> copy(hunkText(hunk)));
        add(menu, "Copy All", true, () -> copy(allText()));
        add(menu, "Copy as Patch", true, () -> copy(PartialPatchBuilder.build(patch, sel ? selection : PartialPatchBuilder.all(patch), false)));
        add(menu, "Copy File Path", true, () -> copy(patch.file().path()));
        menu.show(this, e.getX(), e.getY());
    }

    private static void add(JPopupMenu menu, String label, boolean enabled, Runnable r) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(enabled);
        item.addActionListener(e -> r.run());
        menu.add(item);
    }

    // copy

    private void selectAll() {
        if (patch == null) return;
        selection.clear();
        for (int i = 0; i < rows(); i++) if (patch.row(i).isChange()) selection.set(i);
        repaint();
    }

    private void copySelection() {
        if (patch == null || selection.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = selection.nextSetBit(0); i >= 0; i = selection.nextSetBit(i + 1)) sb.append(patch.row(i).content());
        copy(sb.toString());
    }

    private String hunkText(int hunk) {
        StringBuilder sb = new StringBuilder();
        int start = patch.hunkRow(hunk);
        sb.append(patch.row(start).content()).append('\n');
        for (int i = 1; i <= patch.hunks().get(hunk).lineCount(); i++) {
            Row r = patch.row(start + i);
            sb.append(r.origin()).append(r.content());
        }
        return sb.toString();
    }

    private String allText() {
        StringBuilder sb = new StringBuilder();
        for (Hunk h : patch.hunks()) sb.append(hunkText(h.index()));
        return sb.toString();
    }

    private static void copy(String s) {
        if (s != null) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    // Scrollable

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? rowHeight : charWidth * 4;
    }

    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visible.height - rowHeight : visible.width / 2;
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getParent().getWidth() > getPreferredSize().width;
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}
