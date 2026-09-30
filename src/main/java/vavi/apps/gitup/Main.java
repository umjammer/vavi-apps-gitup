/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.ui.FlatRoundBorder;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.model.Bookmarks;
import vavi.apps.gitup.model.MessageHistory;
import vavi.apps.gitup.ui.MainWindow;
import vavi.apps.gitup.ui.RepositoryBrowser;
import vavi.apps.gitup.ui.WindowState;
import vavi.apps.gitup.ui.icons.IconProvider;


/**
 * SourceTree-like git client using GitUp's GitUpKit.framework as the engine.
 * <p>
 * usage: {@code java vavi.apps.gitup.Main [repository...]}
 * <p>
 * without arguments the tabs of the last session are restored, or the repository browser is shown.
 * <p>
 * system properties
 * <ul>
 * <li>{@code gitup.framework} ... GitUp.app / GitUpKit.framework location</li>
 * <li>{@code gitup.theme} ... "light" (default) or "dark"</li>
 * <li>{@code gitup.bookmarks} ... bookmarks file (default ~/Library/Application Support/vavi-apps-gitup/bookmarks.txt)</li>
 * <li>{@code gitup.messageHistory} ... number of commit messages remembered (default 50)</li>
 * </ul>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class Main implements MainWindow.App {

    private static final Preferences prefs = Preferences.userNodeForPackage(Main.class);
    private static final String OPEN_TABS = "openTabs";
    private static final String SELECTED_TAB = "selectedTab";
    private static final String LAST_DIR = "lastRepository";
    private static final String BROWSER_VISIBLE = "browser.visible";

    private final Path bookmarksFile = System.getProperty("gitup.bookmarks") != null
            ? Path.of(System.getProperty("gitup.bookmarks")) : Bookmarks.defaultFile();
    private final Bookmarks bookmarks = Bookmarks.load(bookmarksFile);
    private final MessageHistory messageHistory = new MessageHistory(MessageHistory.defaultFile(), MessageHistory.configuredSize());
    private MainWindow window;
    private RepositoryBrowser browser;

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "GitUp Swing");
        if ("dark".equals(System.getProperty("gitup.theme"))) {
            FlatMacDarkLaf.setup();
        } else {
            FlatMacLightLaf.setup();
        }
        UIManager.put("Table.showHorizontalLines", false);
        hideFocusIndicators();

        if (GitUpKitLocator.find() == null && !chooseFramework()) {
            System.exit(1);
        }
        // loads GitUpKit on the main thread now, before any window
        LibGit2.INSTANCE.hashCode();
        // SourceTree's asset icons come through AppKit's main thread, which must not be waited for on the EDT
        IconProvider.get().preload();

        List<Path> paths = new ArrayList<>();
        for (String a : args) {
            if (!a.isBlank()) paths.add(Path.of(a).toAbsolutePath().normalize());
        }
        // the application menu's "Settings…"
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) {
            java.awt.Desktop.getDesktop().setPreferencesHandler(e -> vavi.apps.gitup.ui.SettingsWindow.open());
        }
        SwingUtilities.invokeLater(() -> new Main().start(paths));
    }

    /** restores the tabs of the last session, then opens the given repositories (selecting the last one) */
    private void start(List<Path> args) {
        List<Path> restored = new ArrayList<>();
        for (String s : prefs.get(OPEN_TABS, "").split("\n")) {
            if (!s.isBlank() && Files.isDirectory(Path.of(s))) restored.add(Path.of(s));
        }
        int selected = prefs.getInt(SELECTED_TAB, 0);
        if (restored.isEmpty() && args.isEmpty()) {
            showBrowser();
            return;
        }
        restored.forEach(this::open);
        if (args.isEmpty()) {
            window.select(selected);
        } else {
            args.forEach(this::open); // an already open one is selected
        }
        if (WindowState.getFlag(BROWSER_VISIBLE, false)) showBrowser();
    }

    /** opens the repository in a tab of the main window, bookmarks it */
    private void open(Path path) {
        if (window == null || !window.isDisplayable()) {
            window = new MainWindow(this);
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    SwingUtilities.invokeLater(Main.this::exitIfNoWindow);
                }
            });
        }
        window.open(path);
        window.setVisible(true);
        window.toFront();
        prefs.put(LAST_DIR, path.toString());
        if (bookmarks.addIfAbsent(path)) {
            bookmarks.save(bookmarksFile);
            if (browser != null) browser.reload();
        }
    }

    @Override
    public void chooseAndOpen() {
        JFileChooser chooser = new JFileChooser(prefs.get(LAST_DIR, System.getProperty("user.home")));
        chooser.setDialogTitle("Open Repository");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(window != null && window.isVisible() ? window : browser) != JFileChooser.APPROVE_OPTION) return;
        open(chooser.getSelectedFile().toPath());
    }

    @Override
    public void showBrowser() {
        if (browser == null) {
            browser = new RepositoryBrowser(bookmarks, bookmarksFile, this::open);
            browser.setCommandLogs(this::commandLog);
            browser.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    WindowState.setFlag(BROWSER_VISIBLE, false);
                    SwingUtilities.invokeLater(Main.this::exitIfNoWindow);
                }
            });
        }
        WindowState.setFlag(BROWSER_VISIBLE, true);
        browser.setVisible(true);
        browser.toFront();
    }

    @Override
    public void tabsChanged(List<Path> paths, int selected) {
        prefs.put(OPEN_TABS, String.join("\n", paths.stream().map(Path::toString).toList()));
        prefs.putInt(SELECTED_TAB, Math.max(selected, 0));
        try {
            prefs.flush(); // a quit (⌘Q) exits without closing the window
        } catch (java.util.prefs.BackingStoreException e) {
            System.getLogger(Main.class.getName()).log(System.Logger.Level.WARNING, e.getMessage(), e);
        }
    }

    /** the command history of the tab of the repository, null when not open */
    private vavi.apps.gitup.model.CommandLog commandLog(Path repo) {
        if (window == null || !window.isDisplayable()) return null;
        for (vavi.apps.gitup.ui.RepoPanel p : window.panels()) {
            if (sameFile(repo, p.getWorkdir()) || sameFile(repo, p.getPath())) return p.commandLog();
        }
        return null;
    }

    private static boolean sameFile(Path a, Path b) {
        if (b == null) return false;
        try {
            return Files.isSameFile(a, b);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Override
    public MessageHistory messageHistory() {
        return messageHistory;
    }

    /** quits when neither the main window nor the browser is shown */
    private void exitIfNoWindow() {
        for (Window w : Window.getWindows()) {
            if (w.isVisible() && (w instanceof MainWindow || w instanceof RepositoryBrowser)) return;
        }
        System.exit(0);
    }

    /**
     * no blue focus box around lists, trees, tables (their scroll panes) and cells.
     * the macOS themes draw a 2px focus ring and a focused border color.
     */
    static void hideFocusIndicators() {
        UIManager.put("Component.focusWidth", 0);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusedBorderColor", UIManager.getColor("Component.borderColor"));
        UIManager.put("Table.focusCellHighlightBorder", UIManager.getBorder("Table.cellNoFocusBorder"));
        UIManager.put("Table.focusSelectedCellHighlightBorder", UIManager.getBorder("Table.cellNoFocusBorder"));
        UIManager.put("List.focusCellHighlightBorder", UIManager.getBorder("List.cellNoFocusBorder"));
        UIManager.put("List.focusSelectedCellHighlightBorder", UIManager.getBorder("List.cellNoFocusBorder"));
        UIManager.put("Tree.showCellFocusIndicator", false);
        UIManager.put("SplitPaneDivider.focusable", false);
        Color control = UIManager.getColor("control");
        UIManager.put("Button.borderColor", control);
        UIManager.put("Button.default.borderColor", control);
        UIManager.put("ComboBox.background", control);
        UIManager.put("ComboBox.editableBackground", control);
        UIManager.put("ComboBox.buttonBackground", control);
        UIManager.put("ComboBox.buttonSeparatorColor", control);
        UIManager.put("ComboBox.buttonArrowColor", Color.darkGray);
        UIManager.put("ComboBox.buttonHoverArrowColor", Color.darkGray);
        UIManager.put("ComboBox.buttonPressedArrowColor", Color.darkGray);
        UIManager.put("ComboBoxUI", ComboBoxUI.class.getName());
        UIManager.put("ComboBox.border", new FlatRoundBorder() {
            @Override
            protected Paint getBorderColor(Component c) {
                if (c instanceof JComboBox<?> jcb && jcb.getUI() instanceof ComboBoxUI ui && (ui.isHover() || ui.isPressed())) {
                    return ui.getHoverOrPressedBackground();
                }
                return control;
            }
        });
    }

    /** ComboBoxUI with normal button hover and pressed effect */
    public static class ComboBoxUI extends FlatComboBoxUI {
        public static ComponentUI createUI(JComponent c) {
            return new ComboBoxUI();
        }

        private boolean hovered;
        private boolean pressedState;

        private final MouseListener hoverListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (comboBox != null) {
                    Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), comboBox);
                    if (!comboBox.contains(p)) {
                        setHovered(false);
                    }
                } else {
                    setHovered(false);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    setPressed(true);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    setPressed(false);
                }
            }
        };

        private void setHovered(boolean h) {
            if (comboBox != null && !comboBox.isEnabled()) {
                h = false;
            }
            if (this.hovered != h) {
                this.hovered = h;
                this.hover = h;
                if (comboBox != null) {
                    comboBox.repaint();
                }
            }
        }

        private void setPressed(boolean p) {
            if (comboBox != null && !comboBox.isEnabled()) {
                p = false;
            }
            if (this.pressedState != p) {
                this.pressedState = p;
                this.pressed = p;
                if (comboBox != null) {
                    comboBox.repaint();
                }
            }
        }

        public boolean isHover() {
            return hovered && comboBox != null && comboBox.isEnabled();
        }

        public boolean isPressed() {
            return pressedState && comboBox != null && comboBox.isEnabled();
        }

        public Color getHoverOrPressedBackground() {
            if (isPressed()) {
                Color pressedBg = UIManager.getColor("Button.toolbar.pressedBackground");
                if (pressedBg != null) return pressedBg;
                Color p = UIManager.getColor("Button.pressedBackground");
                if (p != null) return p;
                return getHoverBackground().darker();
            }
            return getHoverBackground();
        }

        public Color getHoverBackground() {
            Color hoverBg = UIManager.getColor("Button.toolbar.hoverBackground");
            if (hoverBg != null) return hoverBg;
            Color hover = UIManager.getColor("Button.hoverBackground");
            if (hover != null) return hover;
            return UIManager.getColor("control").darker();
        }

        @Override
        protected void installListeners() {
            super.installListeners();
            if (comboBox != null) {
                comboBox.removeMouseListener(hoverListener);
                comboBox.addMouseListener(hoverListener);
            }
        }

        @Override
        protected void uninstallListeners() {
            if (comboBox != null) {
                comboBox.removeMouseListener(hoverListener);
            }
            super.uninstallListeners();
        }

        @Override
        public void configureArrowButton() {
            super.configureArrowButton();
            if (arrowButton != null) {
                arrowButton.removeMouseListener(hoverListener);
                arrowButton.addMouseListener(hoverListener);
            }
        }

        @Override
        public void unconfigureArrowButton() {
            if (arrowButton != null) {
                arrowButton.removeMouseListener(hoverListener);
            }
            super.unconfigureArrowButton();
        }

        @Override
        public void configureEditor() {
            super.configureEditor();
            if (editor != null) {
                editor.removeMouseListener(hoverListener);
                editor.addMouseListener(hoverListener);
            }
        }

        @Override
        public void unconfigureEditor() {
            if (editor != null) {
                editor.removeMouseListener(hoverListener);
            }
            super.unconfigureEditor();
        }

        @Override
        protected Color getBackground(boolean enabled) {
            if (enabled && !comboBox.isEditable() && (isHover() || isPressed())) {
                return getHoverOrPressedBackground();
            }
            return super.getBackground(enabled);
        }

        @Override
        public void update(Graphics g, JComponent c) {
            Color oldBtnBg = buttonBackground;
            if (comboBox.isEnabled() && !comboBox.isEditable() && (isHover() || isPressed())) {
                buttonBackground = getHoverOrPressedBackground();
            }
            try {
                super.update(g, c);
            } finally {
                buttonBackground = oldBtnBg;
            }
        }
    }

    /** asks for GitUp.app when it is not at a default location */
    private static boolean chooseFramework() throws Exception {
        boolean[] ok = {false};
        SwingUtilities.invokeAndWait(() -> {
            JOptionPane.showMessageDialog(null, "GitUp.app was not found.\nPlease choose GitUp.app (https://gitup.co).", "GitUp", JOptionPane.INFORMATION_MESSAGE);
            JFileChooser chooser = new JFileChooser("/Applications");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            chooser.setSelectedFile(new File("/Applications/GitUp.app"));
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                Path p = chooser.getSelectedFile().toPath();
                GitUpKitLocator.save(p);
                ok[0] = GitUpKitLocator.find() != null;
            }
        });
        return ok[0];
    }
}
