/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;


/**
 * the main window, one tab per repository.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class MainWindow extends JFrame {

    /** application level requests */
    public interface App {
        /** shows a chooser and opens the chosen repository */
        void chooseAndOpen();
        void showBrowser();
        /** the set of open tabs changed */
        void tabsChanged(List<Path> paths, int selected);
    }

    private final JTabbedPane tabs = new JTabbedPane();
    private final JMenu repositoryMenu = new JMenu("Repository");
    private final App app;

    public MainWindow(App app) {
        super("GitUp Swing");
        this.app = app;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (BiConsumer<JTabbedPane, Integer>) (t, i) -> closeTab(i));
        tabs.putClientProperty("JTabbedPane.tabsPopupPolicy", "asNeeded");
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeededSingle");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        JButton plus = new JButton("+");
        plus.setToolTipText("Repository Browser");
        plus.putClientProperty("JButton.buttonType", "toolBarButton");
        plus.addActionListener(e -> app.showBrowser());
        tabs.putClientProperty("JTabbedPane.trailingComponent", plus);
        tabs.addChangeListener(e -> selectionChanged());
        setContentPane(tabs);
        setJMenuBar(buildMenuBar());
        setSize(new Dimension(1400, 900));
        setLocationByPlatform(true);

        addWindowListener(new WindowAdapter() {
            @Override public void windowActivated(WindowEvent e) {
                RepoPanel p = selected();
                if (p != null) p.refreshStatus();
            }
            @Override public void windowClosing(WindowEvent e) {
                // remember the tabs, then close them
                notifyTabs();
                for (RepoPanel p : panels()) p.close();
            }
        });
    }

    private Action action(String name, KeyStroke key, Runnable r) {
        AbstractAction a = new AbstractAction(name) {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
        if (key != null) a.putValue(Action.ACCELERATOR_KEY, key);
        return a;
    }

    private JMenuBar buildMenuBar() {
        int menu = Keys.menu();
        int shift = KeyEvent.SHIFT_DOWN_MASK;
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(action("Open Repository…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), app::chooseAndOpen));
        file.add(action("Repository Browser", KeyStroke.getKeyStroke(KeyEvent.VK_B, menu), app::showBrowser));
        file.addSeparator();
        file.add(action("Close Tab", KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), () -> {
            if (tabs.getSelectedIndex() >= 0) closeTab(tabs.getSelectedIndex());
        }));
        bar.add(file);
        bar.add(repositoryMenu);
        JMenu window = new JMenu("Window");
        window.add(action("Select Next Tab", KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, menu | shift), () -> selectRelative(1)));
        window.add(action("Select Previous Tab", KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, menu | shift), () -> selectRelative(-1)));
        window.addSeparator();
        window.add(action("Repository Browser", null, app::showBrowser));
        bar.add(window);
        return bar;
    }

    private void selectRelative(int d) {
        int n = tabs.getTabCount();
        if (n > 0) tabs.setSelectedIndex((tabs.getSelectedIndex() + d + n) % n);
    }

    /** the Repository menu shows the selected tab's actions */
    private void selectionChanged() {
        repositoryMenu.removeAll();
        RepoPanel p = selected();
        if (p != null) {
            for (Action a : p.repositoryActions()) {
                if (a == null) repositoryMenu.addSeparator();
                else repositoryMenu.add(a);
            }
            setTitle(p.getTitle());
            p.refreshStatus();
        } else {
            setTitle("GitUp Swing");
        }
        repositoryMenu.setEnabled(p != null);
        notifyTabs();
    }

    public RepoPanel selected() {
        Component c = tabs.getSelectedComponent();
        return c instanceof RepoPanel p ? p : null;
    }

    public List<RepoPanel> panels() {
        List<RepoPanel> list = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) list.add((RepoPanel) tabs.getComponentAt(i));
        return list;
    }

    public int getTabCount() {
        return tabs.getTabCount();
    }

    /** opens the repository in a new tab, or selects its tab */
    public void open(Path path) {
        Path n = path.toAbsolutePath().normalize();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            RepoPanel p = (RepoPanel) tabs.getComponentAt(i);
            if (n.equals(p.getPath()) || n.equals(p.getWorkdir())) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
        RepoPanel panel = new RepoPanel(n, new RepoPanel.Host() {
            @Override public void titleChanged(RepoPanel p) {
                int i = tabs.indexOfComponent(p);
                if (i < 0) return;
                tabs.setTitleAt(i, p.getRepositoryName());
                tabs.setToolTipTextAt(i, p.getTitle() + " — " + (p.getWorkdir() != null ? p.getWorkdir() : p.getPath()));
                if (p == selected()) setTitle(p.getTitle());
            }
            @Override public void failed(RepoPanel p) {
                int i = tabs.indexOfComponent(p);
                if (i >= 0) closeTab(i);
            }
        });
        tabs.addTab(panel.getRepositoryName(), panel);
        tabs.setSelectedComponent(panel);
    }

    public void select(int index) {
        if (index >= 0 && index < tabs.getTabCount()) tabs.setSelectedIndex(index);
    }

    private void closeTab(int index) {
        RepoPanel p = (RepoPanel) tabs.getComponentAt(index);
        tabs.removeTabAt(index);
        p.close();
        notifyTabs();
        if (tabs.getTabCount() == 0) {
            dispose();
            app.showBrowser();
        }
    }

    private void notifyTabs() {
        app.tabsChanged(panels().stream().map(RepoPanel::getPath).toList(), tabs.getSelectedIndex());
    }
}
