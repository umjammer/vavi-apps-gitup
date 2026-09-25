/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
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
import javax.swing.Timer;

import vavi.apps.gitup.jna.RepoWatcher;
import vavi.apps.gitup.model.CommitLog;
import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.FileChange;
import vavi.apps.gitup.model.GitRepo;
import vavi.apps.gitup.model.GitRepo.IgnoreTarget;
import vavi.apps.gitup.model.GitRepo.PullResult;
import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Stash;
import vavi.apps.gitup.model.GitRepo.Status;
import vavi.apps.gitup.model.LazyPatch;
import vavi.apps.gitup.model.MessageHistory;
import vavi.apps.gitup.objc.HistoryOps;
import vavi.apps.gitup.objc.RemoteOps;

import static java.lang.System.getLogger;


/**
 * one repository (a tab), SourceTree style:
 * <pre>
 *  toolbar
 *  sidebar | log (graph, description, date, author, commit)
 *          |-----------------------------------------------
 *          | staged / unstaged files | hunk diff
 *          | commit message          |
 * </pre>
 * every git call goes through {@link GitExecutor}, the UI state lives on the EDT.
 * the working directory is watched with FSEvents and refreshed on change.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class RepoPanel extends JPanel {

    private static final System.Logger logger = getLogger(RepoPanel.class.getName());

    private static final int PAGE = 500;

    /** the window hosting the tab */
    public interface Host {
        /** the title (branch) changed */
        void titleChanged(RepoPanel panel);
        /** the repository could not be opened */
        void failed(RepoPanel panel);
        /** recent commit messages, shared by the tabs */
        MessageHistory messageHistory();
    }

    private final GitExecutor exec = new GitExecutor(this::showError);
    private final Host host;
    private final Path path;

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
    /** refs, HEAD and dirtiness of the loaded log, the log is rebuilt only when this changes */
    private String loadedKey;

    // EDT only
    private Path workdir;
    private String headBranch;
    private RepoWatcher watcher;
    /** the log whose rows are shown, pages of another (replaced) log are dropped */
    private CommitLog shownLog;
    /** true while the working copy (not a commit) is shown */
    private boolean showingWorking = true;
    private String selectedCommit;
    /** the working copy file whose diff is shown */
    private FileChange currentFile;
    private boolean adjusting;
    private boolean merging;

    // watcher batching (EDT)
    private final Set<String> pendingPaths = new LinkedHashSet<>();
    private final Timer watchTimer = new Timer(300, e -> watcherFired());

    public RepoPanel(Path path, Host host) {
        super(new BorderLayout());
        this.path = path;
        this.host = host;
        watchTimer.setRepeats(false);
        buildUi();

        exec.submit(() -> repo = new GitRepo(path), r -> {
            workdir = r.workdir();
            staging.stagedTable.setWorkdir(workdir);
            staging.unstagedTable.setWorkdir(workdir);
            staging.commitTable.setWorkdir(workdir);
            host.titleChanged(this);
            startWatcher(r.gitDir());
            refreshAll(true);
        }, e -> {
            showError(e);
            host.failed(this);
        });
    }

    /** the path given at creation */
    public Path getPath() {
        return path;
    }

    /** the working directory, null until opened */
    public Path getWorkdir() {
        return workdir;
    }

    public String getRepositoryName() {
        Path p = workdir != null ? workdir : path;
        return p.getFileName() != null ? p.getFileName().toString() : p.toString();
    }

    /** name (branch) */
    public String getTitle() {
        return getRepositoryName() + (headBranch != null ? " (" + headBranch + ")" : "");
    }

    /** stops watching and closes the repository */
    public void close() {
        watchTimer.stop();
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        LazyPatch shown = diff.getPatch();
        exec.run(() -> {
            if (shown != null) shown.close();
            if (remote != null) remote.close();
            if (log != null) log.close();
            if (repo != null) repo.close();
        }, null);
        exec.shutdown();
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

        add(buildToolBar(), BorderLayout.NORTH);
        add(main, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        add(statusBar, BorderLayout.SOUTH);

        logPanel.setListener(new LogPanel.Listener() {
            @Override public void selected(CommitRow commit) { commitSelected(commit); }
            @Override public void loadMore() { RepoPanel.this.loadMore(); }
            @Override public void createBranch(CommitRow commit) { newBranch(commit.oid()); }
            @Override public void editMessage(CommitRow commit) { RepoPanel.this.editMessage(commit); }
        });
        sidebar.setListener(new SidebarPanel.Listener() {
            @Override public void checkout(Ref ref) { RepoPanel.this.checkout(ref); }
            @Override public void reveal(Ref ref) { logPanel.select(ref.target()); }
            @Override public void stashApply(Stash stash, boolean drop) { RepoPanel.this.stashApply(stash, drop); }
            @Override public void stashDrop(Stash stash) { RepoPanel.this.stashDrop(stash); }
            @Override public void showStash(Stash stash) { RepoPanel.this.showStash(stash); }
        });
        FileTable.Listener files = new FileTable.Listener() {
            @Override public void move(FileTable source, List<FileChange> list) {
                exec.run(() -> {
                    if (source.isStaged()) repo.unstage(list);
                    else repo.stage(list);
                }, RepoPanel.this::refreshStatus);
            }
            @Override public void discard(List<FileChange> list) { discardFiles(list); }
            @Override public void stopTracking(List<FileChange> list) {
                List<FileChange> tracked = list.stream().filter(f -> f.kind() != FileChange.Kind.UNTRACKED).toList();
                exec.run(() -> repo.stopTracking(tracked), RepoPanel.this::refreshStatus);
            }
            @Override public void ignore(List<FileChange> list) { RepoPanel.this.ignore(list); }
            @Override public void trash(List<FileChange> list) { RepoPanel.this.trash(list); }
            @Override public void resolve(List<FileChange> list, boolean ours) {
                exec.run(() -> list.forEach(f -> repo.resolveConflict(f.path(), ours)), RepoPanel.this::refreshStatus);
            }
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
        staging.amendBox.addActionListener(e -> {
            if (staging.amendBox.isSelected() && staging.message.getText().isBlank()) {
                exec.submit(() -> repo.headMessage(), m -> {
                    if (m != null && staging.message.getText().isBlank()) staging.message.setText(m.strip());
                });
            }
        });
        staging.abortMergeButton.addActionListener(e -> abortMerge());
        staging.historyButton.addActionListener(e -> showMessageHistory());
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
    private final int shift = KeyEvent.SHIFT_DOWN_MASK;

    private final Action commitAction = action("Commit", "Focus the commit message", KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask), () -> {
        logPanel.select(null);
        staging.message.requestFocusInWindow();
    });
    private final Action pullAction = action("Pull", "Fetch, then fast-forward, or merge / rebase (pull.rebase) the upstream", KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask | shift), () -> pull(null));
    private final Action pullRebaseAction = action("Pull with Rebase", "Fetch, then rebase the current branch onto the upstream", null, () -> pull(true));
    private final Action pushAction = action("Push", "Push the current branch", KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask), this::push);
    private final Action fetchAction = action("Fetch", "Fetch all remotes", KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask | shift), this::fetch);
    private final Action branchAction = action("Branch", "Create a branch at HEAD", KeyStroke.getKeyStroke(KeyEvent.VK_B, menuMask | shift), () -> newBranch(null));
    private final Action stashAction = action("Stash", "Stash the working copy changes", KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask | shift), this::stash);
    private final Action discardAction = action("Discard", "Discard the selected unstaged files", null, () -> discardFiles(staging.unstagedTable.selectedFiles()));
    private final Action abortMergeAction = action("Abort Merge", "Throw away the merge in progress", null, this::abortMerge);
    private final Action refreshAction = action("Refresh", "Reload the repository", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), () -> refreshAll(true));

    /** actions for the window's "Repository" menu, null is a separator */
    public List<Action> repositoryActions() {
        return java.util.Arrays.asList(refreshAction, null, commitAction, branchAction, stashAction, discardAction, abortMergeAction,
                null, fetchAction, pullAction, pullRebaseAction, pushAction);
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        for (Action a : new Action[] {commitAction, null, pullAction, pushAction, fetchAction, null, branchAction, stashAction, discardAction, null, refreshAction}) {
            if (a == null) {
                bar.addSeparator();
                continue;
            }
            JButton b = bar.add(a);
            b.setHideActionText(false);
            b.setFocusable(false);
        }
        remoteActions.addAll(List.of(pullAction, pullRebaseAction, pushAction, fetchAction));
        abortMergeAction.setEnabled(false);
        return bar;
    }

    private void showError(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getMessage() == null) c = c.getCause();
        statusBar.setText("Error: " + c.getMessage());
        JOptionPane.showMessageDialog(this, c.getMessage() != null ? c.getMessage() : c.toString(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private boolean confirm(String message, String title) {
        return JOptionPane.showConfirmDialog(this, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // watching

    private void startWatcher(Path gitDir) {
        try {
            watcher = new RepoWatcher(workdir, gitDir, 0.2, paths -> SwingUtilities.invokeLater(() -> {
                pendingPaths.addAll(paths);
                watchTimer.restart();
            }));
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, "live refresh disabled: " + e.getMessage(), e);
        }
    }

    /** a batch of file system changes settled */
    private void watcherFired() {
        if (repo == null && workdir == null) return;
        List<String> paths = new ArrayList<>(pendingPaths);
        pendingPaths.clear();
        if (paths.remove(RepoWatcher.GIT_REFS)) {
            refreshAll(false);
            return;
        }
        boolean index = paths.remove(RepoWatcher.GIT_INDEX);
        exec.submit(() -> {
            if (index || paths.size() > 200) return true;
            for (String p : paths) {
                if (p.isEmpty() || !repo.isIgnored(p)) return true;
            }
            return false; // only ignored files (e.g. build output) changed
        }, changed -> {
            if (changed) refreshStatus();
        });
    }

    // refresh

    private record Snapshot(Status status, List<Ref> refs, Map<String, List<Ref>> byTarget, String head,
                            GitRepo.State state, List<Stash> stashes, CommitLog log, List<CommitRow> page, boolean more) {}

    /**
     * reloads refs, status and stashes; the log is rebuilt when refs, HEAD or
     * the dirtiness changed, or when forced.
     */
    public void refreshAll(boolean force) {
        exec.submit(() -> {
            Status status = repo.status();
            List<Ref> refs = repo.refs();
            String head = repo.headBranch();
            boolean dirty = !status.staged().isEmpty() || !status.unstaged().isEmpty();
            String key = refs + "|" + repo.headOid() + "|" + head + "|" + dirty;
            List<CommitRow> page = null;
            boolean more = false;
            if (force || !key.equals(loadedKey)) {
                loadedKey = key;
                if (log != null) log.close();
                log = repo.log(dirty);
                page = log.next(PAGE);
                more = !log.isDone();
            }
            Map<String, List<Ref>> byTarget = new HashMap<>();
            for (Ref r : refs) if (r.target() != null) byTarget.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
            return new Snapshot(status, refs, byTarget, head, repo.state(), repo.stashes(), log, page, more);
        }, s -> {
            headBranch = s.head();
            host.titleChanged(this);
            sidebar.setRefs(s.refs(), headBranch);
            sidebar.setStashes(s.stashes());
            applyStatus(s.status(), s.state());
            if (s.page() == null) return; // log unchanged
            shownLog = s.log();
            boolean dirty = !s.status().staged().isEmpty() || !s.status().unstaged().isEmpty();
            adjusting = true;
            logPanel.reset(dirty, s.byTarget(), headBranch);
            logPanel.append(s.page(), s.more());
            adjusting = false;
            if (showingWorking || selectedCommit == null || !logPanel.select(selectedCommit)) {
                showingWorking = true;
                logPanel.selectFirst(); // "Uncommitted changes" when dirty, HEAD otherwise
            }
        });
    }

    private record Page(CommitLog log, List<CommitRow> rows, boolean more) {}

    private void loadMore() {
        exec.submit(() -> log == null ? new Page(null, List.of(), false) : new Page(log, log.next(PAGE), !log.isDone()), p -> {
            if (p.log() != null && p.log() == shownLog) logPanel.append(p.rows(), p.more());
        });
    }

    /** reloads the working copy status and the shown diff */
    public void refreshStatus() {
        if (workdir == null) return;
        exec.submit(() -> Map.entry(repo.status(), repo.state()), s -> {
            boolean dirty = !s.getKey().staged().isEmpty() || !s.getKey().unstaged().isEmpty();
            if (dirty != logPanel.hasUncommitted()) {
                refreshAll(false); // the "Uncommitted changes" row and its lane come or go
                return;
            }
            applyStatus(s.getKey(), s.getValue());
            if (showingWorking) reopenCurrentFile();
        });
    }

    private void applyStatus(Status s, GitRepo.State state) {
        adjusting = true;
        try {
            staging.stagedTable.setFiles(s.staged());
            staging.unstagedTable.setFiles(s.unstaged());
            staging.setCounts(s.staged().size(), s.unstaged().size());
        } finally {
            adjusting = false;
        }
        boolean nowMerging = state == GitRepo.State.MERGE;
        if (nowMerging != merging) {
            merging = nowMerging;
            staging.setMerging(merging);
            abortMergeAction.setEnabled(merging);
            if (merging && staging.message.getText().isBlank()) {
                exec.submit(() -> repo.mergeMessage(), m -> {
                    if (m != null && staging.message.getText().isBlank()) {
                        staging.message.setText(m.lines().filter(l -> !l.startsWith("#")).collect(Collectors.joining("\n")).strip());
                    }
                });
            }
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
            String message = f.kind() == FileChange.Kind.CONFLICTED ? "Conflicted: edit the file, then stage it to mark it resolved" : "No changes";
            LazyPatch old = diff.getPatch();
            if (keepPosition && diff.getMode() == mode) diff.replacePatch(p, message);
            else diff.setPatch(p, mode, message);
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

    // working copy actions

    private void diffAction(DiffView.Action action, LazyPatch patch, BitSet rows) {
        if (action == DiffView.Action.DISCARD && !confirm(
                "Discard the selected changes in " + patch.file().path() + "?\nThis cannot be undone.", "Discard")) {
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

    private static String names(List<FileChange> files) {
        return files.size() == 1 ? files.getFirst().path() : files.size() + " files";
    }

    private void discardFiles(List<FileChange> files) {
        if (files.isEmpty()) return;
        if (!confirm("Discard all changes in " + names(files) + "?\nUntracked files are deleted. This cannot be undone.", "Discard")) {
            return;
        }
        exec.run(() -> repo.discard(files), this::refreshStatus);
    }

    /** moves working copy files to the trash */
    private void trash(List<FileChange> files) {
        List<Path> paths = files.stream().map(f -> workdir.resolve(f.path())).filter(Files::exists).toList();
        if (paths.isEmpty()) return;
        if (!confirm("Move " + names(files) + " to the Trash?", "Move to Trash")) return;
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            showError(new UnsupportedOperationException("moving to the trash is not supported"));
            return;
        }
        List<String> failed = new ArrayList<>();
        for (Path p : paths) {
            if (!Desktop.getDesktop().moveToTrash(p.toFile())) failed.add(workdir.relativize(p).toString());
        }
        if (!failed.isEmpty()) showError(new IllegalStateException("could not move to the trash: " + String.join(", ", failed)));
        refreshStatus();
    }

    private void ignore(List<FileChange> files) {
        exec.submit(() -> {
            Map<IgnoreTarget, Path> targets = new EnumMap<>(IgnoreTarget.class);
            for (IgnoreTarget t : IgnoreTarget.values()) targets.put(t, repo.ignoreFile(t));
            return targets;
        }, targets -> {
            IgnoreDialog.Result r = new IgnoreDialog(this, files, targets).showDialog();
            if (r == null) return;
            List<FileChange> tracked = files.stream()
                    .filter(f -> f.kind() != FileChange.Kind.UNTRACKED && f.kind() != FileChange.Kind.ADDED).toList();
            exec.submit(() -> {
                Path file = null;
                for (String p : r.patterns()) file = repo.ignore(p, r.target());
                if (r.stopTracking() && !tracked.isEmpty()) repo.stopTracking(tracked);
                return file;
            }, file -> {
                statusBar.setText("Added " + String.join(", ", r.patterns()) + " to " + file);
                refreshStatus();
            });
        });
    }

    // commit

    private void commit() {
        String message = staging.message.getText().strip();
        boolean amend = staging.amendBox.isSelected();
        if (message.isEmpty()) {
            staging.message.requestFocusInWindow();
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (staging.stagedTable.getFiles().isEmpty() && !amend && !merging) {
            JOptionPane.showMessageDialog(this, "Nothing is staged.", "Commit", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        exec.submit(() -> amend ? repo.amend(message + "\n") : repo.commit(message + "\n"), oid -> {
            try {
                host.messageHistory().add(message);
            } catch (RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "message history: " + e.getMessage(), e);
            }
            staging.message.setText("");
            staging.amendBox.setSelected(false);
            statusBar.setText((amend ? "Amended " : "Committed ") + oid.substring(0, 7));
            currentFile = null;
            refreshAll(true);
        });
    }

    private void abortMerge() {
        if (!merging || !confirm("Abort the merge?\nAll changes of the merge, including resolved conflicts, are lost.", "Abort Merge")) return;
        exec.run(() -> repo.abortMerge(), () -> {
            staging.message.setText("");
            refreshAll(true);
        });
    }

    /** a popup of recent commit messages under the History button */
    private void showMessageHistory() {
        List<String> messages = host.messageHistory().messages();
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (messages.isEmpty()) {
            javax.swing.JMenuItem none = new javax.swing.JMenuItem("No recent messages");
            none.setEnabled(false);
            menu.add(none);
        }
        for (String m : messages) {
            String first = m.lines().findFirst().orElse("");
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(first.length() > 72 ? first.substring(0, 71) + "…" : first);
            item.setToolTipText("<html><pre>" + m.replace("&", "&amp;").replace("<", "&lt;") + "</pre></html>");
            item.addActionListener(e -> {
                staging.message.setText(m);
                staging.message.requestFocusInWindow();
            });
            menu.add(item);
        }
        menu.show(staging.historyButton, 0, staging.historyButton.getHeight());
    }

    // history rewriting

    /** GitUp's "Edit Message": the commit and its descendants are rewritten with the same trees */
    private void editMessage(CommitRow c) {
        exec.submit(() -> Map.entry(repo.isPublished(c.oid()), repo.state()), info -> {
            if (info.getValue() != GitRepo.State.NONE) {
                showError(new IllegalStateException("finish or abort the merge in progress first"));
                return;
            }
            javax.swing.JTextArea text = new javax.swing.JTextArea(c.message().strip(), 8, 60);
            text.setFont(staging.message.getFont());
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.add(new JLabel("New commit message for " + c.shortOid() + ":"), BorderLayout.NORTH);
            p.add(new JScrollPane(text), BorderLayout.CENTER);
            if (info.getKey()) {
                JLabel warn = new JLabel("<html><font color='#bc4c00'>This commit is on a remote branch already.<br>"
                        + "Rewriting it changes published history, others will need to reconcile.</font></html>");
                p.add(warn, BorderLayout.SOUTH);
            }
            if (JOptionPane.showConfirmDialog(this, p, "Edit Message", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String message = text.getText().strip();
            if (message.isEmpty() || message.equals(c.message().strip())) return;
            exec.submit(() -> HistoryOps.editMessage(workdir, c.oid(), message + "\n"), newOid -> {
                statusBar.setText("Rewrote " + c.shortOid() + " as " + newOid.substring(0, 7));
                selectedCommit = newOid;
                showingWorking = false;
                refreshAll(true);
            });
        });
    }

    // branches

    private void checkout(Ref ref) {
        switch (ref.kind()) {
            case LOCAL -> {
                if (ref.shorthand().equals(headBranch)) return;
                exec.run(() -> repo.checkout(ref.shorthand()), () -> refreshAll(true));
            }
            case REMOTE -> exec.run(() -> repo.checkoutRemote(ref.shorthand()), () -> refreshAll(true));
            default -> {}
        }
    }

    /** @param oid null for HEAD */
    private void newBranch(String oid) {
        String name = JOptionPane.showInputDialog(this, "New branch name:", "New Branch", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        exec.run(() -> repo.createBranch(name.strip(), oid != null ? oid : repo.headOid(), true), () -> refreshAll(true));
    }

    // stash

    private void stash() {
        JTextField message = new JTextField(30);
        JCheckBox keepIndex = new JCheckBox("Keep staged changes");
        JCheckBox untracked = new JCheckBox("Include untracked files", true);
        JPanel p = new JPanel(new GridLayout(0, 1));
        p.add(new JLabel("Message:"));
        p.add(message);
        p.add(keepIndex);
        p.add(untracked);
        if (JOptionPane.showConfirmDialog(this, p, "Stash", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        exec.submit(() -> repo.stashSave(message.getText(), keepIndex.isSelected(), untracked.isSelected()), saved -> {
            statusBar.setText(saved ? "Stashed" : "No local changes to stash");
            refreshAll(false);
        });
    }

    private void stashApply(Stash stash, boolean drop) {
        exec.run(() -> {
            if (drop) repo.stashPop(stash.index());
            else repo.stashApply(stash.index());
        }, () -> {
            statusBar.setText((drop ? "Popped " : "Applied ") + stash.message());
            refreshAll(false);
        });
    }

    /** shows the stash like a commit: its message and its changes against the stashed HEAD */
    private void showStash(Stash stash) {
        exec.submit(() -> repo.commitRow(stash.oid()), row -> {
            logPanel.getTable().clearSelection();
            commitSelected(row);
        });
    }

    private void stashDrop(Stash stash) {
        if (!confirm("Delete the stash \"" + stash.message() + "\"?\nThis cannot be undone.", "Delete Stash")) return;
        exec.run(() -> repo.stashDrop(stash.index()), () -> refreshAll(false));
    }

    // remote

    /** runs a remote operation on the git thread with the GitUpKit transport, op returns a status message */
    private void remoteOp(String label, Function<RemoteOps, String> op) {
        remoteActions.forEach(a -> a.setEnabled(false));
        statusBar.setText(label + "…");
        exec.submit(() -> {
            if (remote == null) {
                remote = new RemoteOps(repo.workdir(), prompter, s -> SwingUtilities.invokeLater(() -> statusBar.setText(label + ": " + s)));
            }
            return op.apply(remote);
        }, message -> {
            remoteActions.forEach(a -> a.setEnabled(true));
            statusBar.setText(label + ": " + message);
            refreshAll(false);
        }, e -> {
            remoteActions.forEach(a -> a.setEnabled(true));
            showError(e);
            refreshAll(false);
        });
    }

    private void fetch() {
        remoteOp("Fetch", ops -> {
            ops.fetchAll();
            return "done";
        });
    }

    /** @param rebase null: as configured (pull.rebase / branch.&lt;name&gt;.rebase) */
    private void pull(Boolean rebase) {
        remoteOp(Boolean.TRUE.equals(rebase) ? "Pull (rebase)" : "Pull", ops -> {
            ops.fetchAll();
            PullResult r = repo.pullFromUpstream(rebase != null ? rebase : repo.isPullRebaseConfigured());
            if (r == PullResult.CONFLICTS) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "The merge has conflicts.\nResolve them (right click: Resolve Using Mine / Theirs, or edit and stage),\nthen commit, or abort the merge.",
                        "Pull", JOptionPane.WARNING_MESSAGE));
            }
            return switch (r) {
                case UP_TO_DATE -> "already up to date";
                case FAST_FORWARD -> "fast-forwarded";
                case MERGED -> "merged";
                case REBASED -> "rebased";
                case CONFLICTS -> "merge has conflicts";
            };
        });
    }

    private void push() {
        remoteOp("Push", ops -> {
            String branch = repo.headBranch();
            if (branch == null || "HEAD".equals(branch)) throw new IllegalStateException("not on a branch");
            ops.push(branch, repo.upstream(branch) != null);
            return "done";
        });
    }

    /** credential prompts, called on the git thread, shown on the EDT */
    private final RemoteOps.Prompter prompter = new RemoteOps.Prompter() {
        @Override public String[] userPassword(String url, String user) {
            String[][] result = new String[1][];
            invokeAndWait(() -> {
                JTextField u = new JTextField(user != null ? user : "", 20);
                JPasswordField p = new JPasswordField(20);
                JPanel panel = new JPanel(new GridLayout(0, 1));
                panel.add(new JLabel(url));
                panel.add(new JLabel("Username:"));
                panel.add(u);
                panel.add(new JLabel("Password / token:"));
                panel.add(p);
                if (JOptionPane.showConfirmDialog(RepoPanel.this, panel, "Authentication", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    result[0] = new String[] {u.getText(), new String(p.getPassword())};
                }
            });
            return result[0];
        }

        @Override public String passphrase(String url, String key) {
            String[] result = new String[1];
            invokeAndWait(() -> {
                JPasswordField p = new JPasswordField(20);
                JPanel panel = new JPanel(new GridLayout(0, 1));
                panel.add(new JLabel("Passphrase for " + key));
                panel.add(p);
                if (JOptionPane.showConfirmDialog(RepoPanel.this, panel, "SSH Key", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
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
