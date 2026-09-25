/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.ui.MainFrame;


/**
 * SourceTree-like git client using GitUp's GitUpKit.framework as the engine.
 * <p>
 * usage: {@code java vavi.apps.gitup.Main [repository]}
 * <p>
 * system properties
 * <ul>
 * <li>{@code gitup.framework} ... GitUp.app / GitUpKit.framework location</li>
 * <li>{@code gitup.theme} ... "light" (default) or "dark"</li>
 * </ul>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class Main {

    private static final Preferences prefs = Preferences.userNodeForPackage(Main.class);
    private static final String LAST_REPO = "lastRepository";

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

        Path repo = args.length > 0 ? Path.of(args[0]).toAbsolutePath().normalize() : null;
        if (repo == null) {
            String last = prefs.get(LAST_REPO, null);
            if (last != null && Files.isDirectory(Path.of(last))) repo = Path.of(last);
        }
        Path start = repo;
        SwingUtilities.invokeLater(() -> open(start));
    }

    /** opens a repository window, asks for one when path is null */
    static void open(Path path) {
        if (path == null) {
            JFileChooser chooser = new JFileChooser(prefs.get(LAST_REPO, System.getProperty("user.home")));
            chooser.setDialogTitle("Open Repository");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                if (java.awt.Window.getWindows().length == 0 || noVisibleWindow()) System.exit(0);
                return;
            }
            path = chooser.getSelectedFile().toPath();
        }
        prefs.put(LAST_REPO, path.toString());
        MainFrame frame = new MainFrame(path, Main::open);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                if (noVisibleWindow()) System.exit(0);
            }
        });
        frame.setVisible(true);
    }

    private static boolean noVisibleWindow() {
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isVisible() && w instanceof MainFrame) return false;
        }
        return true;
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
