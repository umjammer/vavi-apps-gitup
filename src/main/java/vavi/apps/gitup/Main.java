/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.model.Bookmarks;
import vavi.apps.gitup.model.MessageHistory;
import vavi.apps.gitup.ui.MainWindow;
import vavi.apps.gitup.ui.RepositoryBrowser;


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

        if (GitUpKitLocator.find() == null && !chooseFramework()) {
            System.exit(1);
        }
        // loads GitUpKit on the main thread now, before any window
        LibGit2.INSTANCE.hashCode();

        List<Path> paths = new ArrayList<>();
        for (String a : args) paths.add(Path.of(a).toAbsolutePath().normalize());
        SwingUtilities.invokeLater(() -> new Main().start(paths));
    }

    private void start(List<Path> paths) {
        int selected = 0;
        if (paths.isEmpty()) {
            for (String s : prefs.get(OPEN_TABS, "").split("\n")) {
                if (!s.isBlank() && Files.isDirectory(Path.of(s))) paths.add(Path.of(s));
            }
            selected = prefs.getInt(SELECTED_TAB, 0);
        }
        if (paths.isEmpty()) {
            showBrowser();
            return;
        }
        paths.forEach(this::open);
        window.select(selected);
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
            browser.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    SwingUtilities.invokeLater(Main.this::exitIfNoWindow);
                }
            });
        }
        browser.setVisible(true);
        browser.toFront();
    }

    @Override
    public void tabsChanged(List<Path> paths, int selected) {
        prefs.put(OPEN_TABS, String.join("\n", paths.stream().map(Path::toString).toList()));
        prefs.putInt(SELECTED_TAB, Math.max(selected, 0));
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
