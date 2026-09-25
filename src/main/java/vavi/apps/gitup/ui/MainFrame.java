/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import vavi.apps.gitup.model.CommitLog;
import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.FileChange;
import vavi.apps.gitup.model.GitRepo;
import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Status;
import vavi.apps.gitup.model.LazyPatch;
import vavi.apps.gitup.objc.RemoteOps;


/**
 * a repository window, SourceTree style:
 * <pre>
 *  toolbar
 *  sidebar | log (graph, description, date, author, commit)
 *          |-----------------------------------------------
 *          | staged / unstaged files | hunk diff
 *          | commit message          |
 * </pre>
 * every git call goes through {@link GitExecutor}, the UI state lives on the EDT.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class MainFrame extends JFrame {

    private static final int PAGE = 500;

    private final GitExecutor exec = new GitExecutor(this::showError);

    private final SidebarPanel sidebar = new SidebarPanel();
    private final LogPanel logPanel = new LogPanel();
    private final StagingPanel staging = new StagingPanel();
    private final DiffView diff = new DiffView();
    private final JLabel statusBar = new JLabel(" ");
    private final List<Action> remoteActions = new ArrayList<>();

    // git thread only
    private GitRepo repo;
    private CommitLog log;
    private RemoteOps remote;

    // EDT only
    private Path workdir;
    private String headBranch;
    /** increments on every log reload, stale pages are dropped */
    private int logGeneration;
    /** true while the working copy (not a commit) is shown */
    private boolean showingWorking = true;
    private String selectedCommit;
    /** the working copy file whose diff is shown */
    private FileChange currentFile;
    private boolean adjusting;

    private final Consumer<Path> opener;

    /** @param opener opens another repository window */
    public MainFrame(Path path, Consumer<Path> opener) {
        super(path.getFileName().toString());
        this.opener = opener;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        buildUi();
        setSize(new Dimension(1400, 900));
        setLocationByPlatform(true);

        exec.submit(() -> repo = new GitRepo(path), r -> {
            workdir = r.workdir();
            staging.stagedTable.setWorkdir(workdir);
            staging.unstagedTable.setWorkdir(workdir);
            staging.commitTable.setWorkdir(workdir);
            setTitle(workdir.getFileName().toString());
            refreshAll();
        }, e -> {
            showError(e);
            dispose();
        });

        addWindowListener(new WindowAdapter() {
            @Override public void windowActivated(WindowEvent e) {
                if (workdir != null) refreshStatus();
            }
            @Override public void windowClosed(WindowEvent e) {
                exec.run(() -> {
                    if (remote != null) remote.close();
                    if (log != null) log.close();
                    if (repo != null) repo.close();
                }, null);
                exec.shutdown();
            }
        });
    }

    // ui

    private void buildUi() {
        JScrollPane diffScroll = new JScrollPane(diff);
        diffScroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE); // header buttons follow the viewport
        diffScroll.getViewport().setBackground(diff.getBackground());

        JSplitPane bottom = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, staging, diffScroll);
        bottom.setResizeWeight(0.3);
        bottom.setDividerLocation(420);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, logPanel, bottom);
        center.setResizeWeight(0.4);
        center.setDividerLocation(330);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, center);
        main.setDividerLocation(200);

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildToolBar(), BorderLayout.NORTH);
        content.add(main, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        content.add(statusBar, BorderLayout.SOUTH);
        setContentPane(content);
        setJMenuBar(buildMenuBar());

        // listeners
        logPanel.setListener(new LogPanel.Listener() {
            @Override public void selected(CommitRow commit) { commitSelected(commit); }
            @Override public void loadMore() { MainFrame.this.loadMore(); }
            @Override public void createBranch(CommitRow commit) { newBranch(commit.oid()); }
        });
        sidebar.setListener(new SidebarPanel.Listener() {
            @Override public void checkout(Ref ref) { MainFrame.this.checkout(ref); }
            @Override public void reveal(Ref ref) { logPanel.select(ref.target()); }
        });
        FileTable.Listener files = new FileTable.Listener() {
            @Override public void move(FileTable source, List<FileChange> list) {
                exec.run(() -> {
                    if (source.isStaged()) repo.unstage(list);
                    else repo.stage(list);
                }, MainFrame.this::refreshStatus);
            }
            @Override public void discard(List<FileChange> list) { discardFiles(list); }
        };
        staging.stagedTable.setListener(files);
        staging.unstagedTable.setListener(files);
        staging.stagedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fileSelected(staging.stagedTable, staging.unstagedTable);
        });
        staging.unstagedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fileSelected(staging.unstagedTable, staging.stagedTable);
        });
        staging.commitTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || adjusting) return;
            List<FileChange> sel = staging.commitTable.selectedFiles();
            if (sel.size() == 1 && selectedCommit != null) showCommitFile(selectedCommit, sel.getFirst());
        });
        staging.commitButton.addActionListener(e -> commit());
        staging.stageAllButton.addActionListener(e -> {
            List<FileChange> all = staging.unstagedTable.getFiles();
            exec.run(() -> repo.stage(all), this::refreshStatus);
        });
        staging.unstageAllButton.addActionListener(e -> {
            List<FileChange> all = staging.stagedTable.getFiles();
            exec.run(() -> repo.unstage(all), this::refreshStatus);
        });
        diff.setListener(this::diffAction);
    }

    private Action action(String name, String tooltip, KeyStroke key, Runnable r) {
        AbstractAction a = new AbstractAction(name) {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
        a.putValue(Action.SHORT_DESCRIPTION, tooltip);
        if (key != null) a.putValue(Action.ACCELERATOR_KEY, key);
        return a;
    }

    private final int menuMask = Keys.menu();

    private final Action commitAction = action("Commit", "Focus the commit message", KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask), () -> {
        logPanel.select(null);
        staging.message.requestFocusInWindow();
    });
    private final Action pullAction = action("Pull", "Fetch and fast-forward the current branch", KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask | KeyEvent.SHIFT_DOWN_MASK), this::pull);
    private final Action pushAction = action("Push", "Push the current branch", KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask), this::push);
    private final Action fetchAction = action("Fetch", "Fetch all remotes", KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask | KeyEvent.SHIFT_DOWN_MASK), this::fetch);
    private final Action branchAction = action("Branch", "Create a branch at HEAD", KeyStroke.getKeyStroke(KeyEvent.VK_B, menuMask | KeyEvent.SHIFT_DOWN_MASK), () -> newBranch(null));
    private final Action discardAction = action("Discard", "Discard the selected unstaged files", null, () -> discardFiles(staging.unstagedTable.selectedFiles()));
    private final Action refreshAction = action("Refresh", "Reload the repository", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), this::refreshAll);
    private final Action openAction = action("Open Repository…", "Open another repository", KeyStroke.getKeyStroke(KeyEvent.VK_O, menuMask), this::openAnother);
    private final Action closeAction = action("Close Window", null, KeyStroke.getKeyStroke(KeyEvent.VK_W, menuMask), this::dispose);

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        for (Action a : new Action[] {commitAction, null, pullAction, pushAction, fetchAction, null, branchAction, discardAction, null, refreshAction}) {
            if (a == null) {
                bar.addSeparator();
                continue;
            }
            JButton b = bar.add(a);
            b.setHideActionText(false);
            b.setFocusable(false);
        }
        remoteActions.addAll(List.of(pullAction, pushAction, fetchAction));
        return bar;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(openAction);
        file.add(closeAction);
        bar.add(file);
        JMenu repository = new JMenu("Repository");
        for (Action a : new Action[] {refreshAction, null, commitAction, branchAction, discardAction, null, fetchAction, pullAction, pushAction}) {
            if (a == null) repository.addSeparator();
            else repository.add(a);
        }
        bar.add(repository);
        // accelerators also work without the menu
        for (Action a : new Action[] {commitAction, pullAction, pushAction, fetchAction, branchAction, refreshAction}) {
            KeyStroke k = (KeyStroke) a.getValue(Action.ACCELERATOR_KEY);
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, a.getValue(Action.NAME));
            getRootPane().getActionMap().put(a.getValue(Action.NAME), a);
        }
        return bar;
    }

    private void showError(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getMessage() == null) c = c.getCause();
        statusBar.setText("Error: " + c.getMessage());
        JOptionPane.showMessageDialog(this, c.getMessage() != null ? c.getMessage() : c.toString(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void openAnother() {
        opener.accept(null);
    }

    // refresh

    private record Snapshot(Status status, List<Ref> refs, Map<String, List<Ref>> byTarget, String head,
                            List<CommitRow> page, boolean more) {}

    /** reloads refs, status and the log */
    private void refreshAll() {
        int gen = ++logGeneration;
        exec.submit(() -> {
            if (log != null) log.close();
            log = repo.log();
            List<CommitRow> page = log.next(PAGE);
            List<Ref> refs = repo.refs();
            Map<String, List<Ref>> byTarget = new java.util.HashMap<>();
            for (Ref r : refs) if (r.target() != null) byTarget.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
            return new Snapshot(repo.status(), refs, byTarget, repo.headBranch(), page, !log.isDone());
        }, s -> {
            if (gen != logGeneration) return;
            headBranch = s.head();
            setTitle(workdir.getFileName() + (headBranch != null ? " (" + headBranch + ")" : ""));
            sidebar.setRefs(s.refs(), headBranch);
            boolean dirty = !s.status().staged().isEmpty() || !s.status().unstaged().isEmpty();
            adjusting = true;
            logPanel.reset(dirty, s.byTarget(), headBranch);
            logPanel.append(s.page(), s.more());
            adjusting = false;
            applyStatus(s.status());
            if (showingWorking || selectedCommit == null || !logPanel.select(selectedCommit)) {
                showingWorking = true;
                logPanel.selectFirst(); // "Uncommitted changes" when dirty, HEAD otherwise
            }
            statusBar.setText(" ");
        });
    }

    private void loadMore() {
        int gen = logGeneration;
        exec.submit(() -> {
            if (log == null) return Map.entry(List.<CommitRow>of(), false);
            List<CommitRow> page = log.next(PAGE);
            return Map.entry(page, !log.isDone());
        }, p -> {
            if (gen == logGeneration) logPanel.append(p.getKey(), p.getValue());
        });
    }

    /** reloads the working copy status and the shown diff */
    private void refreshStatus() {
        exec.submit(repo::status, s -> {
            applyStatus(s);
            logPanel.setUncommitted(!s.staged().isEmpty() || !s.unstaged().isEmpty());
            if (showingWorking) reopenCurrentFile();
        });
    }

    private void applyStatus(Status s) {
        adjusting = true;
        try {
            staging.stagedTable.setFiles(s.staged());
            staging.unstagedTable.setFiles(s.unstaged());
            staging.setCounts(s.staged().size(), s.unstaged().size());
        } finally {
            adjusting = false;
        }
    }

    // selection

    private void commitSelected(CommitRow c) {
        if (adjusting) return;
        if (c == null) {
            showingWorking = true;
            selectedCommit = null;
            staging.showWorking();
            reopenCurrentFile();
            return;
        }
        showingWorking = false;
        selectedCommit = c.oid();
        staging.showCommit(c);
        String oid = c.oid();
        exec.submit(() -> repo.commitFiles(oid), files -> {
            if (!oid.equals(selectedCommit)) return;
            adjusting = true;
            staging.commitTable.setFiles(files);
            adjusting = false;
            if (!files.isEmpty()) staging.commitTable.setRowSelectionInterval(0, 0);
            else setDiff(null, DiffView.Mode.COMMIT, "No changes");
        });
    }

    private void fileSelected(FileTable table, FileTable other) {
        if (adjusting) return;
        List<FileChange> sel = table.selectedFiles();
        if (sel.isEmpty()) return;
        adjusting = true;
        other.clearSelection();
        adjusting = false;
        if (sel.size() == 1) showFile(sel.getFirst(), false);
    }

    /** shows the working copy file, keeping the scroll position when it is the same file */
    private void showFile(FileChange f, boolean keepPosition) {
        currentFile = f;
        exec.submit(() -> repo.openPatch(f), p -> {
            if (currentFile != f) {
                if (p != null) exec.run(p::close, null);
                return;
            }
            DiffView.Mode mode = f.staged() ? DiffView.Mode.STAGED : DiffView.Mode.UNSTAGED;
            LazyPatch old = diff.getPatch();
            if (keepPosition && diff.getMode() == mode) diff.replacePatch(p, "No changes");
            else diff.setPatch(p, mode, "No changes");
            if (old != null) exec.run(old::close, null);
        });
    }

    private void showCommitFile(String oid, FileChange f) {
        currentFile = null;
        exec.submit(() -> repo.openPatch(oid, f), p -> {
            if (!oid.equals(selectedCommit)) {
                if (p != null) exec.run(p::close, null);
                return;
            }
            setDiff(p, DiffView.Mode.COMMIT, "No changes");
        });
    }

    private void setDiff(LazyPatch p, DiffView.Mode mode, String message) {
        LazyPatch old = diff.getPatch();
        diff.setPatch(p, mode, message);
        if (old != null) exec.run(old::close, null);
    }

    /** after a change: re-select the current file on the same side, or clear the diff */
    private void reopenCurrentFile() {
        FileChange f = currentFile;
        if (f == null) {
            setDiff(null, DiffView.Mode.UNSTAGED, "Select a file");
            return;
        }
        FileTable table = f.staged() ? staging.stagedTable : staging.unstagedTable;
        FileChange now = table.getFiles().stream().filter(x -> x.path().equals(f.path())).findFirst().orElse(null);
        if (now == null) {
            currentFile = null;
            setDiff(null, DiffView.Mode.UNSTAGED, "Select a file");
            return;
        }
        adjusting = true;
        int i = table.getFiles().indexOf(now);
        table.setRowSelectionInterval(i, i);
        adjusting = false;
        showFile(now, true);
    }

    // actions

    private void diffAction(DiffView.Action action, LazyPatch patch, BitSet rows) {
        if (action == DiffView.Action.DISCARD && JOptionPane.showConfirmDialog(this,
                "Discard the selected changes in " + patch.file().path() + "?\nThis cannot be undone.",
                "Discard", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        exec.run(() -> {
            switch (action) {
                case STAGE -> repo.stageLines(patch, rows);
                case UNSTAGE -> repo.unstageLines(patch, rows);
                case DISCARD -> repo.discardLines(patch, rows);
            }
        }, this::refreshStatus);
    }

    private void discardFiles(List<FileChange> files) {
        if (files.isEmpty()) return;
        String names = files.size() == 1 ? files.getFirst().path() : files.size() + " files";
        if (JOptionPane.showConfirmDialog(this, "Discard all changes in " + names + "?\nUntracked files are deleted. This cannot be undone.",
                "Discard", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        exec.run(() -> repo.discard(files), this::refreshStatus);
    }

    private void commit() {
        String message = staging.message.getText().strip();
        if (message.isEmpty()) {
            staging.message.requestFocusInWindow();
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (staging.stagedTable.getFiles().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Nothing is staged.", "Commit", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        exec.submit(() -> repo.commit(message + "\n"), oid -> {
            staging.message.setText("");
            statusBar.setText("Committed " + oid.substring(0, 7));
            currentFile = null;
            refreshAll();
        });
    }

    private void checkout(Ref ref) {
        switch (ref.kind()) {
            case LOCAL -> {
                if (ref.shorthand().equals(headBranch)) return;
                exec.run(() -> repo.checkout(ref.shorthand()), this::refreshAll);
            }
            case REMOTE -> exec.run(() -> repo.checkoutRemote(ref.shorthand()), this::refreshAll);
            default -> {}
        }
    }

    /** @param oid null for HEAD */
    private void newBranch(String oid) {
        String name = JOptionPane.showInputDialog(this, "New branch name:", "New Branch", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        exec.run(() -> repo.createBranch(name.strip(), oid != null ? oid : repo.headOid(), true), this::refreshAll);
    }

    // remote

    /** runs a remote operation on the git thread with the GitUpKit transport */
    private void remoteOp(String label, Consumer<RemoteOps> op) {
        remoteActions.forEach(a -> a.setEnabled(false));
        statusBar.setText(label + "…");
        exec.submit(() -> {
            if (remote == null) {
                remote = new RemoteOps(repo.workdir(), prompter, s -> SwingUtilities.invokeLater(() -> statusBar.setText(label + ": " + s)));
            }
            op.accept(remote);
            return null;
        }, x -> {
            remoteActions.forEach(a -> a.setEnabled(true));
            statusBar.setText(label + " done");
            refreshAll();
        }, e -> {
            remoteActions.forEach(a -> a.setEnabled(true));
            showError(e);
            refreshAll();
        });
    }

    private void fetch() {
        remoteOp("Fetch", RemoteOps::fetchAll);
    }

    private void pull() {
        remoteOp("Pull", ops -> {
            ops.fetchAll();
            repo.fastForwardToUpstream();
        });
    }

    private void push() {
        remoteOp("Push", ops -> {
            String branch = repo.headBranch();
            if (branch == null || "HEAD".equals(branch)) throw new IllegalStateException("not on a branch");
            ops.push(branch, repo.upstream(branch) != null);
        });
    }

    /** credential prompts, called on the git thread, shown on the EDT */
    private final RemoteOps.Prompter prompter = new RemoteOps.Prompter() {
        @Override public String[] userPassword(String url, String user) {
            String[][] result = new String[1][];
            invokeAndWait(() -> {
                JTextField u = new JTextField(user != null ? user : "", 20);
                JPasswordField p = new JPasswordField(20);
                JPanel panel = new JPanel(new java.awt.GridLayout(0, 1));
                panel.add(new JLabel(url));
                panel.add(new JLabel("Username:"));
                panel.add(u);
                panel.add(new JLabel("Password / token:"));
                panel.add(p);
                if (JOptionPane.showConfirmDialog(MainFrame.this, panel, "Authentication", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    result[0] = new String[] {u.getText(), new String(p.getPassword())};
                }
            });
            return result[0];
        }

        @Override public String passphrase(String url, String key) {
            String[] result = new String[1];
            invokeAndWait(() -> {
                JPasswordField p = new JPasswordField(20);
                JPanel panel = new JPanel(new java.awt.GridLayout(0, 1));
                panel.add(new JLabel("Passphrase for " + key));
                panel.add(p);
                if (JOptionPane.showConfirmDialog(MainFrame.this, panel, "SSH Key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    result[0] = new String(p.getPassword());
                }
            });
            return result[0];
        }

        private void invokeAndWait(Runnable r) {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    };
}
