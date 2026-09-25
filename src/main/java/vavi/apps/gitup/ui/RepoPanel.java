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
import vavi.apps.gitup.model.HistorySearch;
import vavi.apps.gitup.model.HistorySearch.Hit;
import vavi.apps.gitup.model.GitRepo.PullResult;
import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.Stash;
import vavi.apps.gitup.model.GitRepo.Status;
import vavi.apps.gitup.model.LazyPatch;
import vavi.apps.gitup.model.MessageHistory;
import vavi.apps.gitup.objc.HistoryOps;
import vavi.apps.gitup.objc.RemoteOps;
import vavi.apps.gitup.ui.icons.IconProvider;

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
    private final JTextField searchField = new JTextField(20);
    private final SearchPanel searchPanel = new SearchPanel();
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
    /** remotes of the last refresh */
    private List<GitRepo.Remote> remotes = List.of();
    /** the oldest commit of a multiple selection shown in the lower panes, equals selectedCommit for one */
    private String rangeOldest;
    /** the working copy file whose diff is shown */
    private FileChange currentFile;
    private boolean adjusting;
    private boolean merging;
    /** the repository state last shown */
    private GitRepo.State repoState = GitRepo.State.NONE;

    // watcher batching (EDT)
    private final Set<String> pendingPaths = new LinkedHashSet<>();
    private final Timer watchTimer = new Timer(300, e -> watcherFired());

    public RepoPanel(Path path, Host host) {
        super(new BorderLayout());
        this.path = path;
        this.host = host;
        watchTimer.setRepeats(false);
        buildUi();

        exec.submit(() -> {
            repo = new GitRepo(path);
            repo.setCommandLog(commandLog);
            repo.setContextLines(vavi.apps.gitup.model.Settings.get().contextLines());
            return repo;
        }, r -> {
            workdir = r.workdir();
            staging.stagedTable.setWorkdir(workdir);
            staging.unstagedTable.setWorkdir(workdir);
            staging.commitTable.setWorkdir(workdir);
            host.titleChanged(this);
            loadUndo();
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

    /** diff colors repaint, the context lines reopen the shown diff */
    private final Runnable settingsListener = () -> SwingUtilities.invokeLater(() -> {
        diff.repaint();
        int n = vavi.apps.gitup.model.Settings.get().contextLines();
        if (repo == null && workdir == null) return;
        exec.run(() -> repo.setContextLines(n), () -> {
            if (showingWorking) reopenCurrentFile();
            else {
                List<FileChange> sel = staging.commitTable.selectedFiles();
                if (sel.size() == 1 && selectedCommit != null) showCommitFile(rangeOldest, selectedCommit, sel.getFirst());
            }
        });
    });

    {
        vavi.apps.gitup.model.Settings.get().addListener(settingsListener);
    }

    /** stops watching and closes the repository */
    public void close() {
        vavi.apps.gitup.model.Settings.get().removeListener(settingsListener);
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
        JPanel logArea = new JPanel(new BorderLayout());
        logArea.add(logPanel, BorderLayout.CENTER);
        searchPanel.setVisible(false);
        logArea.add(searchPanel, BorderLayout.SOUTH);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, logArea, bottom);
        center.setResizeWeight(0.4);
        center.setDividerLocation(330);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, center);
        main.setDividerLocation(200);
        WindowState.remember(main, "split.sidebar");
        WindowState.remember(center, "split.log");
        WindowState.remember(bottom, "split.staging");
        WindowState.remember(logPanel.getTable(), "log");

        add(buildToolBar(), BorderLayout.NORTH);
        add(main, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        add(statusBar, BorderLayout.SOUTH);

        logPanel.setListener(new LogPanel.Listener() {
            @Override public void selected(CommitRow commit) { commitSelected(commit); }
            @Override public void loadMore() { RepoPanel.this.loadMore(); }
            @Override public void createBranch(CommitRow commit) { newBranch(commit.oid()); }
            @Override public void selectedMany(List<CommitRow> commits) { commitsSelected(commits); }
            @Override public void editMessage(CommitRow commit) { RepoPanel.this.editMessage(commit); }
            @Override public void rewrite(CommitRow commit, LogPanel.Rewrite rewrite) { RepoPanel.this.rewrite(commit, rewrite); }
            @Override public void resetTo(CommitRow commit) { RepoPanel.this.resetTo(commit); }
            @Override public void checkoutCommit(CommitRow commit) { RepoPanel.this.checkoutCommit(commit); }
            @Override public void mergeCommit(CommitRow commit) { RepoPanel.this.mergeCommit(commit); }
            @Override public void cherryPick(CommitRow commit) { RepoPanel.this.cherryPick(commit); }
        });
        sidebar.setListener(new SidebarPanel.Listener() {
            @Override public void checkout(Ref ref) { RepoPanel.this.checkout(ref); }
            @Override public void reveal(Ref ref) { logPanel.select(ref.target()); }
            @Override public void stashApply(Stash stash, boolean drop) { RepoPanel.this.stashApply(stash, drop); }
            @Override public void stashDrop(Stash stash) { RepoPanel.this.stashDrop(stash); }
            @Override public void showStash(Stash stash) { RepoPanel.this.showStash(stash); }
            @Override public void newBranch() { RepoPanel.this.newBranch(null); }
            @Override public void renameBranch(Ref branch) { RepoPanel.this.renameBranch(branch); }
            @Override public void deleteBranch(Ref branch) { RepoPanel.this.deleteBranches(branch); }
            @Override public void deleteRemoteBranch(Ref branch) { RepoPanel.this.deleteRemoteBranch(branch); }
            @Override public void newRemote() { editRemote(null); }
            @Override public void editRemote(GitRepo.Remote remote) { RepoPanel.this.editRemote(remote); }
            @Override public void removeRemote(GitRepo.Remote remote) { RepoPanel.this.removeRemote(remote); }
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
            @Override public void externalDiff(List<FileChange> list) { list.forEach(f -> RepoPanel.this.externalDiff(f, null, null)); }
            @Override public void externalMerge(FileChange f) { RepoPanel.this.externalMerge(f); }
        };
        staging.stagedTable.setListener(files);
        staging.unstagedTable.setListener(files);
        // a commit's files: only the external diff (between the parent of the oldest and the newest selected)
        staging.commitTable.setListener(new FileTable.Listener() {
            @Override public void move(FileTable source, List<FileChange> list) {}
            @Override public void discard(List<FileChange> list) {}
            @Override public void stopTracking(List<FileChange> list) {}
            @Override public void ignore(List<FileChange> list) {}
            @Override public void trash(List<FileChange> list) {}
            @Override public void resolve(List<FileChange> list, boolean ours) {}
            @Override public void externalDiff(List<FileChange> list) {
                String oldest = rangeOldest, newest = selectedCommit;
                if (oldest != null && newest != null) list.forEach(f -> RepoPanel.this.externalDiff(f, oldest, newest));
            }
            @Override public void externalMerge(FileChange f) {}
        });
        staging.stagedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fileSelected(staging.stagedTable, staging.unstagedTable);
        });
        staging.unstagedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) fileSelected(staging.unstagedTable, staging.stagedTable);
        });
        staging.commitTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || adjusting) return;
            List<FileChange> sel = staging.commitTable.selectedFiles();
            if (sel.size() == 1 && selectedCommit != null) showCommitFile(rangeOldest, selectedCommit, sel.getFirst());
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
        staging.continueRebaseButton.addActionListener(e -> continueRebase());
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
    private final Action abortMergeAction = action("Abort Merge", "Throw away the merge or rebase in progress", null, this::abortMerge);
    private final Action undoAction = action("Undo", "Put the branches back where they were before the last operation",
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask), this::undo);
    private final Action redoAction = action("Redo", "Redo the last undone operation",
            KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | shift), this::redo);
    private final Action historyAction = action("Command History", "The git commands equivalent to what was done",
            KeyStroke.getKeyStroke(KeyEvent.VK_H, menuMask | shift), this::showCommandHistory);
    private final Action findAction = action("Find…", "Search the history", KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), () -> {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    });
    private final Action refreshAction = action("Refresh", "Reload the repository", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuMask), () -> refreshAll(true));

    /** actions for the window's "Repository" menu, null is a separator */
    public List<Action> repositoryActions() {
        return java.util.Arrays.asList(undoAction, redoAction, null, refreshAction, findAction, historyAction, null, commitAction, branchAction, stashAction, discardAction, abortMergeAction,
                null, fetchAction, pullAction, pullRebaseAction, pushAction);
    }

    private final Map<Action, IconProvider.Key> toolbarIcons = Map.of(
            commitAction, IconProvider.Key.COMMIT, pullAction, IconProvider.Key.PULL, pushAction, IconProvider.Key.PUSH,
            fetchAction, IconProvider.Key.FETCH, branchAction, IconProvider.Key.BRANCH, stashAction, IconProvider.Key.STASH,
            discardAction, IconProvider.Key.DISCARD, refreshAction, IconProvider.Key.REFRESH);

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
            IconProvider.Key key = toolbarIcons.get(a);
            javax.swing.Icon icon = key != null ? IconProvider.get().icon(key, 24) : null;
            if (icon != null) {
                b.setIcon(icon);
                b.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
                b.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
            }
        }
        remoteActions.addAll(List.of(pullAction, pullRebaseAction, pushAction, fetchAction));
        bar.add(javax.swing.Box.createHorizontalGlue());
        searchField.putClientProperty("JTextField.placeholderText", "Search messages, files, contents");
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.setMaximumSize(new java.awt.Dimension(320, searchField.getPreferredSize().height));
        searchField.setToolTipText("return: search the whole history (⌘F), esc: close");
        searchField.addActionListener(e -> startSearch());
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeSearch");
        searchField.getActionMap().put("closeSearch", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { closeSearch(); }
        });
        bar.add(searchField);
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
                            GitRepo.State state, List<Stash> stashes, List<GitRepo.Remote> remotes,
                            CommitLog log, List<CommitRow> page, boolean more) {}

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
            return new Snapshot(status, refs, byTarget, head, repo.state(), repo.stashes(), repo.remotes(), log, page, more);
        }, s -> {
            headBranch = s.head();
            host.titleChanged(this);
            remotes = s.remotes();
            sidebar.setRefs(s.refs(), s.remotes(), headBranch);
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
        if (state != repoState) {
            repoState = state;
            merging = state == GitRepo.State.MERGE || state == GitRepo.State.CHERRY_PICK;
            staging.setState(state);
            abortMergeAction.setEnabled(state == GitRepo.State.MERGE || state == GitRepo.State.REBASE || state == GitRepo.State.CHERRY_PICK);
            abortMergeAction.putValue(Action.NAME, state == GitRepo.State.REBASE ? "Abort Rebase"
                    : state == GitRepo.State.CHERRY_PICK ? "Abort Cherry-pick" : "Abort Merge");
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
        rangeOldest = c.oid();
        staging.showCommit(c);
        String oid = c.oid();
        exec.submit(() -> repo.commitFiles(oid), files -> {
            if (!oid.equals(selectedCommit)) return;
            adjusting = true;
            staging.commitTable.setFiles(files);
            adjusting = false;
            int row = 0;
            if (pendingReveal != null && pendingReveal.oid().equals(oid) && pendingReveal.path() != null) {
                for (int i = 0; i < files.size(); i++) if (files.get(i).path().equals(pendingReveal.path())) row = i;
            }
            if (!files.isEmpty()) {
                staging.commitTable.setRowSelectionInterval(row, row);
                staging.commitTable.scrollRectToVisible(staging.commitTable.getCellRect(row, 0, true));
            } else {
                setDiff(null, DiffView.Mode.COMMIT, "No changes");
            }
        });
    }

    /** several commits: the files changed over the whole range, like SourceTree */
    private void commitsSelected(List<CommitRow> commits) {
        if (adjusting) return;
        String newest = commits.getFirst().oid();
        String oldest = commits.getLast().oid();
        showingWorking = false;
        selectedCommit = newest;
        rangeOldest = oldest;
        staging.showCommits(commits);
        exec.submit(() -> repo.rangeFiles(oldest, newest), files -> {
            if (!newest.equals(selectedCommit) || !oldest.equals(rangeOldest)) return;
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

    /** shows a file changed from the parent of oldest to newest (the same commit for one) */
    private void showCommitFile(String oldest, String oid, FileChange f) {
        currentFile = null;
        exec.submit(() -> repo.openPatch(oldest, oid, f), p -> {
            if (!oid.equals(selectedCommit) || !oldest.equals(rangeOldest)) {
                if (p != null) exec.run(p::close, null);
                return;
            }
            setDiff(p, DiffView.Mode.COMMIT, "No changes");
            Hit h = pendingReveal;
            if (h != null && h.oid().equals(oid) && f.path().equals(h.path())) {
                pendingReveal = null;
                if (h.kind() == HistorySearch.Kind.CONTENT) diff.reveal(h.origin(), h.line());
            }
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
        if (action == DiffView.Action.REVERSE && !confirm(
                "Reverse the selected changes of this commit in " + patch.file().path() + "?\n"
                        + "The inverse is applied to the working copy, review and commit it.", "Reverse")) {
            return;
        }
        exec.run(() -> {
            switch (action) {
                case STAGE -> repo.stageLines(patch, rows);
                case UNSTAGE -> repo.unstageLines(patch, rows);
                case DISCARD -> repo.discardLines(patch, rows);
                case REVERSE -> repo.reverseLines(patch, rows);
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
        exec.submit(() -> {
            GitRepo.RefSnapshot snapshot = repo.snapshotRefs(amend ? "Amend" : "Commit", true);
            String oid = amend ? repo.amend(message + "\n") : repo.commit(message + "\n");
            pushUndo(snapshot); // only after it succeeded
            return oid;
        }, oid -> {
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

    /** aborts the merge or the rebase in progress */
    private void abortMerge() {
        if (repoState == GitRepo.State.REBASE) {
            if (!confirm("Abort the rebase?\nThe branch goes back to where it was before the pull.", "Abort Rebase")) return;
            exec.run(() -> repo.abortRebase(), () -> refreshAll(true));
            return;
        }
        String what = repoState == GitRepo.State.CHERRY_PICK ? "cherry-pick" : "merge";
        if (!merging || !confirm("Abort the " + what + "?\nAll its changes, including resolved conflicts, are lost.", "Abort")) return;
        exec.run(() -> repo.abortMerge(), () -> {
            staging.message.setText("");
            refreshAll(true);
        });
    }

    private void continueRebase() {
        exec.submit(() -> repo.continueRebase(), finished -> {
            statusBar.setText(finished ? "Rebase finished" : "Rebase stopped by conflicts again");
            if (!finished) {
                JOptionPane.showMessageDialog(this, "The next commit conflicts too.\nResolve, stage, then continue again.", "Rebase", JOptionPane.WARNING_MESSAGE);
            }
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

    // undo

    /** snapshots before history changing operations, newest last (EDT) */
    private final java.util.Deque<GitRepo.RefSnapshot> undoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_LIMIT = 20;

    /** snapshots taken right before an undo, to redo it (EDT) */
    private final java.util.Deque<GitRepo.RefSnapshot> redoStack = new java.util.ArrayDeque<>();

    {
        undoAction.setEnabled(false);
        redoAction.setEnabled(false);
    }

    /** called on the git thread after an operation succeeded, a new operation forgets the redo history */
    private void pushUndo(GitRepo.RefSnapshot s) {
        SwingUtilities.invokeLater(() -> {
            redoStack.clear();
            addUndo(s);
        });
    }

    private void addUndo(GitRepo.RefSnapshot s) {
        undoStack.addLast(s);
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeFirst();
        updateUndo();
    }

    /** the undo / redo history kept over restarts */
    private vavi.apps.gitup.model.UndoStore undoStore;

    private void loadUndo() {
        try {
            undoStore = new vavi.apps.gitup.model.UndoStore(vavi.apps.gitup.model.UndoStore.defaultDir(), workdir);
            vavi.apps.gitup.model.UndoStore.History h = undoStore.load();
            undoStack.clear();
            undoStack.addAll(h.undo());
            redoStack.clear();
            redoStack.addAll(h.redo());
            updateUndo();
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, "undo history: " + e.getMessage(), e);
        }
    }

    private void updateUndo() {
        if (undoStore != null) {
            try {
                undoStore.save(new ArrayList<>(undoStack), new ArrayList<>(redoStack));
            } catch (RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "undo history: " + e.getMessage(), e);
            }
        }
        GitRepo.RefSnapshot s = undoStack.peekLast();
        undoAction.setEnabled(s != null);
        undoAction.putValue(Action.NAME, s != null ? "Undo " + s.label() : "Undo");
        GitRepo.RefSnapshot r = redoStack.peekLast();
        redoAction.setEnabled(r != null);
        redoAction.putValue(Action.NAME, r != null ? "Redo " + r.label() : "Redo");
    }

    private void redo() {
        GitRepo.RefSnapshot r = redoStack.peekLast();
        if (r == null) return;
        exec.submit(() -> {
            GitRepo.RefSnapshot back = repo.snapshotRefs(r.label(), r.restore());
            repo.restore(r);
            return back;
        }, back -> {
            redoStack.remove(r);
            addUndo(back);
            statusBar.setText("Redid " + r.label());
            refreshAll(true);
        });
    }

    private void undo() {
        GitRepo.RefSnapshot s = undoStack.peekLast();
        if (s == null) return;
        String what = s.soft() ? "\nThe committed changes come back as staged changes."
                : "\nThe index and the working copy are reset to the restored HEAD.";
        if (!confirm("Undo " + s.label() + "?\nHEAD and the local branches go back to where they were." + what, "Undo")) return;
        exec.submit(() -> {
            GitRepo.RefSnapshot forward = repo.snapshotRefs(s.label(), s.restore());
            repo.restore(s);
            return forward;
        }, forward -> {
            undoStack.remove(s);
            redoStack.addLast(forward);
            updateUndo();
            statusBar.setText("Undid " + s.label());
            refreshAll(true);
        });
    }

    // external tools

    /** a temp copy of a version of a file, kept until the application ends (the tool may still read it) */
    private static Path tempCopy(Path dir, String path, String label, byte[] content) throws java.io.IOException {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        String file = dot > 0 ? name.substring(0, dot) + "." + label + name.substring(dot) : name + "." + label;
        Path p = dir.resolve(file);
        java.nio.file.Files.write(p, content != null ? content : new byte[0]);
        p.toFile().deleteOnExit();
        return p;
    }

    /**
     * opens the external diff tool on a file.
     *
     * @param oldest null for the working copy file (staged: HEAD vs index, unstaged: index vs the working file)
     */
    private void externalDiff(FileChange f, String oldest, String newest) {
        String command = vavi.apps.gitup.model.Settings.get().diffCommand();
        if (command == null) {
            showError(new IllegalStateException("choose an external diff tool in Settings (⌘,)"));
            return;
        }
        String oldPath = f.oldPath() != null ? f.oldPath() : f.path();
        exec.submit(() -> {
            Path dir = java.nio.file.Files.createTempDirectory("gitup-diff-");
            dir.toFile().deleteOnExit();
            Path local, remote;
            if (oldest != null) {
                String base = repo.commitRow(oldest).parents().isEmpty() ? null : oldest + "^";
                local = tempCopy(dir, oldPath, "LOCAL", base != null ? repo.contentAt(base, oldPath) : null);
                remote = tempCopy(dir, f.path(), "REMOTE", repo.contentAt(newest, f.path()));
            } else if (f.staged()) {
                local = tempCopy(dir, oldPath, "HEAD", repo.isHeadUnborn() ? null : repo.contentAt("HEAD", oldPath));
                remote = tempCopy(dir, f.path(), "INDEX", repo.indexContent(f.path(), 0));
            } else {
                byte[] index = repo.indexContent(oldPath, 0);
                local = tempCopy(dir, oldPath, "INDEX", index);
                Path file = workdir.resolve(f.path());
                remote = java.nio.file.Files.exists(file) ? file : tempCopy(dir, f.path(), "DELETED", null);
            }
            return Map.of("LOCAL", local.toString(), "REMOTE", remote.toString());
        }, env -> runTool("External Diff", command, env, null));
    }

    /** opens the external merge tool on a conflicted file, then offers to mark it resolved */
    private void externalMerge(FileChange f) {
        String command = vavi.apps.gitup.model.Settings.get().mergeCommand();
        if (command == null) {
            showError(new IllegalStateException("choose an external merge tool in Settings (⌘,)"));
            return;
        }
        exec.submit(() -> {
            Path dir = java.nio.file.Files.createTempDirectory("gitup-merge-");
            dir.toFile().deleteOnExit();
            return Map.of(
                    "BASE", tempCopy(dir, f.path(), "BASE", repo.indexContent(f.path(), 1)).toString(),
                    "LOCAL", tempCopy(dir, f.path(), "LOCAL", repo.indexContent(f.path(), 2)).toString(),
                    "REMOTE", tempCopy(dir, f.path(), "REMOTE", repo.indexContent(f.path(), 3)).toString(),
                    "MERGED", workdir.resolve(f.path()).toString());
        }, env -> runTool("External Merge", command, env, () -> {
            if (confirm("Did the merge of " + f.path() + " finish?\nStage it to mark the conflict resolved.", "External Merge")) {
                exec.run(() -> repo.stage(List.of(new FileChange(f.path(), f.path(), FileChange.Kind.MODIFIED, false))), this::refreshStatus);
            } else {
                refreshStatus();
            }
        }));
    }

    /** runs a tool on its own thread, reports a failure, then runs after (on the EDT) */
    private void runTool(String label, String command, Map<String, String> env, Runnable after) {
        commandLog.add(command.replace("$LOCAL", env.getOrDefault("LOCAL", "")).replace("$REMOTE", env.getOrDefault("REMOTE", ""))
                .replace("$BASE", env.getOrDefault("BASE", "")).replace("$MERGED", env.getOrDefault("MERGED", "")), label);
        statusBar.setText(label + ": " + command);
        Thread t = new Thread(() -> {
            try {
                Process p = vavi.apps.gitup.model.ExternalTool.launch(command, env, workdir);
                String out = new String(p.getInputStream().readAllBytes());
                int rc = p.waitFor();
                SwingUtilities.invokeLater(() -> {
                    if (rc != 0) showError(new IllegalStateException(label + " exited with " + rc + (out.isBlank() ? "" : ":\n" + out.strip())));
                    if (after != null) after.run();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> showError(e));
            }
        }, "external tool");
        t.setDaemon(true);
        t.start();
    }

    // command history

    /** the equivalent git commands of what was done in this tab */
    private final vavi.apps.gitup.model.CommandLog commandLog = new vavi.apps.gitup.model.CommandLog();
    private CommandHistory commandHistory;

    private void showCommandHistory() {
        if (commandHistory == null || !commandHistory.isDisplayable()) {
            commandHistory = new CommandHistory(this, getRepositoryName(), commandLog);
        }
        commandHistory.setVisible(true);
        commandHistory.toFront();
    }

    // search

    private static final int SEARCH_LIMIT = 1000;
    /** the search in progress, cancelled by setting its flag */
    private java.util.concurrent.atomic.AtomicBoolean searchCancel;
    /** a search result being revealed: the file (and line) is selected once the commit is shown */
    private Hit pendingReveal;

    {
        searchPanel.setListener(new SearchPanel.Listener() {
            @Override public void reveal(Hit hit) { RepoPanel.this.reveal(hit); }
            @Override public void stop() { if (searchCancel != null) searchCancel.set(true); }
            @Override public void research() { startSearch(); }
            @Override public void close() { closeSearch(); }
        });
    }

    /**
     * searches the whole history on its own thread with its own repository handle,
     * so a long content search does not block the git thread.
     */
    private void startSearch() {
        String q = searchField.getText().strip();
        if (q.isEmpty() || workdir == null) return;
        if (searchCancel != null) searchCancel.set(true);
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        searchCancel = cancel;
        searchPanel.start(q);
        searchPanel.setVisible(true);
        revalidate();
        java.util.Set<HistorySearch.Kind> kinds = searchPanel.kinds();
        Path wd = workdir;
        Thread t = new Thread(() -> {
            try (GitRepo r = new GitRepo(wd)) {
                HistorySearch.search(r, q, kinds, SEARCH_LIMIT,
                        hit -> SwingUtilities.invokeLater(() -> { if (!cancel.get()) searchPanel.add(hit); }),
                        n -> SwingUtilities.invokeLater(() -> { if (searchCancel == cancel) searchPanel.progress(n); }),
                        cancel::get);
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> showError(e));
            } finally {
                SwingUtilities.invokeLater(() -> { if (searchCancel == cancel) searchPanel.done(cancel.get(), SEARCH_LIMIT); });
            }
        }, "search");
        t.setDaemon(true);
        t.start();
    }

    private void closeSearch() {
        if (searchCancel != null) searchCancel.set(true);
        searchPanel.setVisible(false);
        revalidate();
        logPanel.getTable().requestFocusInWindow();
    }

    /** selects the commit of the hit (loading log pages until it appears), then its file and line */
    private void reveal(Hit hit) {
        pendingReveal = hit;
        logPanel.getTable().clearSelection(); // re-selecting the same commit reloads its files
        if (logPanel.select(hit.oid())) return;
        exec.submit(() -> {
            if (log == null) return null;
            List<CommitRow> more = new ArrayList<>();
            boolean found = false;
            while (!found && !log.isDone()) {
                List<CommitRow> page = log.next(PAGE);
                more.addAll(page);
                found = page.stream().anyMatch(r -> r.oid().equals(hit.oid()));
            }
            return new Page(log, more, !log.isDone());
        }, p -> {
            if (p != null && p.log() == shownLog) logPanel.append(p.rows(), p.more());
            if (!logPanel.select(hit.oid())) statusBar.setText("not in the log: " + hit.oid().substring(0, 7));
        });
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
            exec.submit(() -> {
                GitRepo.RefSnapshot snapshot = repo.snapshotRefs("Edit Message", false);
                commandLog.add("git rebase -i " + c.oid() + "^  # reword " + c.shortOid() + " -m " + vavi.apps.gitup.model.CommandLog.message(message),
                        "GitUpKit GCHistory rewrite: the descendants are replayed with their trees");
                String oid = HistoryOps.editMessage(workdir, c.oid(), message + "\n");
                pushUndo(snapshot);
                return oid;
            }, newOid -> {
                statusBar.setText("Rewrote " + c.shortOid() + " as " + newOid.substring(0, 7));
                selectedCommit = newOid;
                showingWorking = false;
                refreshAll(true);
            });
        });
    }

    /**
     * GitUp's squash / fixup / swap / delete. operations that may change HEAD's tree need a clean
     * working copy, the index and working directory are then reset to the new HEAD.
     */
    private void rewrite(CommitRow c, LogPanel.Rewrite r) {
        exec.submit(() -> {
            Status st = repo.status();
            boolean clean = st.staged().isEmpty() && st.unstaged().stream().allMatch(f -> f.kind() == FileChange.Kind.UNTRACKED);
            return new Object[] {repo.isPublished(c.oid()), repo.state(), clean, c.parents().isEmpty() ? null : repo.commitRow(c.parents().getFirst())};
        }, info -> {
            boolean published = (Boolean) info[0];
            if (info[1] != GitRepo.State.NONE) {
                showError(new IllegalStateException("finish or abort the merge / rebase in progress first"));
                return;
            }
            if (!(Boolean) info[2]) {
                showError(new IllegalStateException("commit or stash the local changes before rewriting history"));
                return;
            }
            CommitRow parent = (CommitRow) info[3];
            String warning = published ? "\n\nThis commit is on a remote branch already, this rewrites published history." : "";
            String message;
            switch (r) {
                case SQUASH -> {
                    if (parent == null) return;
                    javax.swing.JTextArea text = new javax.swing.JTextArea(parent.message().strip() + "\n\n" + c.message().strip(), 10, 60);
                    text.setFont(staging.message.getFont());
                    JPanel p = new JPanel(new BorderLayout(0, 6));
                    p.add(new JLabel("Squashed commit message:" + (published ? " (published history)" : "")), BorderLayout.NORTH);
                    p.add(new JScrollPane(text), BorderLayout.CENTER);
                    if (JOptionPane.showConfirmDialog(this, p, "Squash Into Parent", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
                    message = text.getText().strip();
                    if (message.isEmpty()) return;
                }
                case DELETE -> {
                    if (!confirm("Delete the commit " + c.shortOid() + " \"" + c.summary() + "\"?\nLater commits are replayed without it." + warning, "Delete Commit")) return;
                    message = null;
                }
                default -> {
                    if (published && !confirm("Rewrite " + c.shortOid() + "?" + warning, "Rewrite")) return;
                    message = null;
                }
            }
            String label = switch (r) {
                case SQUASH -> "Squashed";
                case FIXUP -> "Fixed up";
                case MOVE_UP -> "Moved up";
                case MOVE_DOWN -> "Moved down";
                case DELETE -> "Deleted";
            };
            exec.submit(() -> {
                String before = repo.headTree();
                GitRepo.RefSnapshot snapshot = repo.snapshotRefs(switch (r) {
                    case SQUASH -> "Squash";
                    case FIXUP -> "Fixup";
                    case MOVE_UP -> "Move Up";
                    case MOVE_DOWN -> "Move Down";
                    case DELETE -> "Delete Commit";
                }, false);
                String base = r == LogPanel.Rewrite.MOVE_DOWN || r == LogPanel.Rewrite.SQUASH || r == LogPanel.Rewrite.FIXUP ? c.oid() + "^^" : c.oid() + "^";
                commandLog.add("git rebase -i " + base + "  # " + switch (r) {
                    case SQUASH -> "squash " + c.shortOid() + " into its parent";
                    case FIXUP -> "fixup " + c.shortOid() + " into its parent";
                    case MOVE_UP -> "move " + c.shortOid() + " after its child";
                    case MOVE_DOWN -> "move " + c.shortOid() + " before its parent";
                    case DELETE -> "drop " + c.shortOid();
                }, "GitUpKit GCHistory rewrite");
                String result = switch (r) {
                    case SQUASH -> HistoryOps.squashWithParent(workdir, c.oid(), message + "\n");
                    case FIXUP -> HistoryOps.fixupWithParent(workdir, c.oid());
                    case MOVE_UP -> HistoryOps.swapWithChild(workdir, c.oid());
                    case MOVE_DOWN -> HistoryOps.swapWithParent(workdir, c.oid());
                    case DELETE -> {
                        HistoryOps.delete(workdir, c.oid());
                        yield null;
                    }
                };
                pushUndo(snapshot);
                if (!java.util.Objects.equals(before, repo.headTree())) repo.resetHardToHead();
                return java.util.Optional.ofNullable(result);
            }, result -> {
                statusBar.setText(label + " " + c.shortOid() + result.map(x -> " → " + x.substring(0, 7)).orElse(""));
                result.ifPresent(x -> {
                    selectedCommit = x;
                    showingWorking = false;
                });
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

    private void renameBranch(Ref branch) {
        String name = (String) JOptionPane.showInputDialog(this, "New name of " + branch.shorthand() + ":", "Rename Branch",
                JOptionPane.PLAIN_MESSAGE, null, null, branch.shorthand());
        if (name == null || name.isBlank() || name.strip().equals(branch.shorthand())) return;
        exec.run(() -> repo.renameBranch(branch.shorthand(), name.strip()), () -> {
            statusBar.setText("Renamed " + branch.shorthand() + " to " + name.strip());
            refreshAll(true);
        });
    }

    /**
     * SourceTree-like "Delete Branches": the local branches with checkboxes (the clicked one checked),
     * force regardless of merge status, and the remote branches too.
     */
    private void deleteBranches(Ref clicked) {
        exec.submit(() -> {
            java.util.Map<String, String> upstreams = new java.util.LinkedHashMap<>();
            for (Ref r : repo.refs()) {
                if (r.kind() == Ref.Kind.LOCAL && !r.shorthand().equals(headBranch)) upstreams.put(r.shorthand(), repo.upstream(r.shorthand()));
            }
            return upstreams;
        }, upstreams -> {
            JPanel list = new JPanel(new java.awt.GridLayout(0, 1));
            java.util.Map<String, JCheckBox> boxes = new java.util.LinkedHashMap<>();
            for (var e : upstreams.entrySet()) {
                JCheckBox b = new JCheckBox(e.getKey() + (e.getValue() != null ? "  → " + e.getValue() : ""), e.getKey().equals(clicked.shorthand()));
                boxes.put(e.getKey(), b);
                list.add(b);
            }
            JCheckBox force = new JCheckBox("Force delete regardless of merge status");
            JCheckBox remote = new JCheckBox("Also delete the remote branches (→) on the server");
            JScrollPane scroll = new JScrollPane(list);
            scroll.setPreferredSize(new java.awt.Dimension(420, Math.min(240, 28 * boxes.size() + 8)));
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.add(new JLabel("Delete local branches:"), BorderLayout.NORTH);
            p.add(scroll, BorderLayout.CENTER);
            JPanel options = new JPanel(new java.awt.GridLayout(0, 1));
            options.add(force);
            options.add(remote);
            p.add(options, BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, p, "Delete Branches", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            List<String> names = boxes.entrySet().stream().filter(e -> e.getValue().isSelected()).map(java.util.Map.Entry::getKey).toList();
            if (names.isEmpty()) return;
            boolean f = force.isSelected();
            List<String> remoteBranches = remote.isSelected() ? names.stream().map(upstreams::get).filter(java.util.Objects::nonNull).toList() : List.of();
            exec.submit(() -> {
                GitRepo.RefSnapshot snapshot = repo.snapshotRefs("Delete Branch", GitRepo.Restore.REFS);
                List<String> failed = new ArrayList<>();
                for (String n : names) {
                    try {
                        repo.deleteBranch(n, f);
                    } catch (RuntimeException ex) {
                        failed.add(ex.getMessage());
                    }
                }
                if (failed.size() < names.size()) pushUndo(snapshot);
                return failed;
            }, failed -> {
                if (!failed.isEmpty()) showError(new IllegalStateException(String.join("\n", failed)));
                statusBar.setText("Deleted " + (names.size() - failed.size()) + " branch(es)");
                if (!remoteBranches.isEmpty()) {
                    remoteOp("Delete remote branches", ops -> {
                        remoteBranches.forEach(ops::deleteRemoteBranch);
                        return "deleted " + String.join(", ", remoteBranches);
                    });
                } else {
                    refreshAll(true);
                }
            });
        });
    }

    private void deleteRemoteBranch(Ref branch) {
        if (!confirm("Delete the branch " + branch.shorthand() + " on the server?\nThis cannot be undone from here.", "Delete Remote Branch")) return;
        remoteOp("Delete " + branch.shorthand(), ops -> {
            ops.deleteRemoteBranch(branch.shorthand());
            return "deleted";
        });
    }

    /** @param remote null for a new remote */
    private void editRemote(GitRepo.Remote remote) {
        JTextField name = new JTextField(remote != null ? remote.name() : (hasOrigin() ? "" : "origin"), 30);
        JTextField url = new JTextField(remote != null ? remote.url() : "", 30);
        JTextField pushUrl = new JTextField(remote != null && remote.pushUrl() != null ? remote.pushUrl() : "", 30);
        pushUrl.putClientProperty("JTextField.placeholderText", "same as the URL");
        JPanel p = new JPanel(new GridLayout(0, 1));
        p.add(new JLabel("Remote name:"));
        p.add(name);
        p.add(new JLabel("URL / path:"));
        p.add(url);
        p.add(new JLabel("Push URL (optional):"));
        p.add(pushUrl);
        String title = remote != null ? "Edit Remote" : "New Remote";
        if (JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String n = name.getText().strip(), u = url.getText().strip(), pu = pushUrl.getText().strip();
        if (n.isEmpty() || u.isEmpty()) {
            showError(new IllegalArgumentException("a name and a URL are needed"));
            return;
        }
        exec.run(() -> {
            if (remote == null) {
                repo.createRemote(n, u);
                if (!pu.isEmpty()) repo.editRemote(n, n, u, pu);
            } else {
                repo.editRemote(remote.name(), n, u, pu);
            }
        }, () -> {
            statusBar.setText((remote == null ? "Added remote " : "Updated remote ") + n);
            refreshAll(true);
        });
    }

    private boolean hasOrigin() {
        return remotes.stream().anyMatch(r -> r.name().equals("origin"));
    }

    private void removeRemote(GitRepo.Remote remote) {
        if (!confirm("Remove the remote " + remote.name() + " (" + remote.url() + ")?\n"
                + "Its remote branches are removed from this repository, the repository on the server is not touched.", "Remove Remote")) return;
        exec.run(() -> repo.removeRemote(remote.name()), () -> {
            statusBar.setText("Removed remote " + remote.name());
            refreshAll(true);
        });
    }

    /** SourceTree's "Checkout…" of a commit: a branch pointing at it, or the commit itself (detached HEAD) */
    private void checkoutCommit(CommitRow c) {
        exec.submit(() -> repo.branchesAt(c.oid()), branches -> {
            javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
            JPanel p = new JPanel(new GridLayout(0, 1));
            p.add(new JLabel("Checkout " + c.shortOid() + " \"" + c.summary() + "\""));
            List<javax.swing.JRadioButton> branchButtons = new ArrayList<>();
            for (String b : branches) {
                javax.swing.JRadioButton r = new javax.swing.JRadioButton("the branch " + b, branchButtons.isEmpty());
                r.setActionCommand(b);
                g.add(r);
                p.add(r);
                branchButtons.add(r);
            }
            javax.swing.JRadioButton detached = new javax.swing.JRadioButton("the commit (detached HEAD)", branchButtons.isEmpty());
            g.add(detached);
            p.add(detached);
            JLabel note = new JLabel("<html><font color='gray'>on a detached HEAD new commits belong to no branch, create one to keep them</font></html>");
            p.add(note);
            JCheckBox clean = new JCheckBox("Clean (discard all local changes)");
            p.add(clean);
            if (JOptionPane.showConfirmDialog(this, p, "Checkout", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            if (clean.isSelected() && logPanel.hasUncommitted()
                    && !confirm("Discard all uncommitted changes of the working copy?\nThey cannot be recovered.", "Checkout")) return;
            String branch = branchButtons.stream().filter(javax.swing.AbstractButton::isSelected).map(javax.swing.AbstractButton::getActionCommand).findFirst().orElse(null);
            boolean force = clean.isSelected();
            exec.run(() -> {
                if (branch != null) repo.checkout(branch, force);
                else repo.checkoutDetached(c.oid(), force);
            }, () -> {
                statusBar.setText("Checked out " + (branch != null ? branch : c.shortOid() + " (detached HEAD)"));
                refreshAll(true);
            });
        });
    }

    /** SourceTree's "Merge…": the commit (or the branch at it) into the current branch */
    private void mergeCommit(CommitRow c) {
        if (headBranch == null || "HEAD".equals(headBranch)) {
            showError(new IllegalStateException("check out a branch to merge into first"));
            return;
        }
        String into = headBranch;
        exec.submit(() -> repo.refs().stream().filter(r -> c.oid().equals(r.target()) && r.kind() != Ref.Kind.TAG)
                .map(Ref::shorthand).filter(n -> !n.equals(into)).findFirst().orElse(null), name -> {
            String what = name != null ? (name.contains("/") ? "remote-tracking branch '" + name + "'" : "branch '" + name + "'") : "commit '" + c.shortOid() + "'";
            JTextField message = new JTextField("Merge " + what + " into " + into, 40);
            JCheckBox commit = new JCheckBox("Commit merged changes immediately", true);
            JCheckBox noFF = new JCheckBox("Create a new commit even if fast-forward is possible");
            JPanel p = new JPanel(new GridLayout(0, 1));
            p.add(new JLabel("Merge " + (name != null ? name : c.shortOid() + " \"" + c.summary() + "\"") + " into " + into));
            p.add(new JLabel("Message:"));
            p.add(message);
            p.add(commit);
            p.add(noFF);
            if (JOptionPane.showConfirmDialog(this, p, "Merge", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String msg = message.getText().strip();
            boolean now = commit.isSelected(), ff = noFF.isSelected();
            exec.submit(() -> {
                GitRepo.RefSnapshot snapshot = repo.snapshotRefs("Merge", GitRepo.Restore.ALL);
                GitRepo.MergeResult r = repo.merge(c.oid(), msg + "\n", ff, now);
                if (r == GitRepo.MergeResult.MERGED || r == GitRepo.MergeResult.FAST_FORWARD) pushUndo(snapshot);
                return r;
            }, r -> {
                switch (r) {
                    case UP_TO_DATE -> statusBar.setText("Already up to date");
                    case FAST_FORWARD -> statusBar.setText("Fast-forwarded " + into);
                    case MERGED -> statusBar.setText("Merged into " + into);
                    case NOT_COMMITTED -> statusBar.setText("Merged, not committed: review and commit");
                    case CONFLICTS -> JOptionPane.showMessageDialog(this, "The merge has conflicts.\nResolve them, stage the files and commit, or abort the merge.",
                            "Merge", JOptionPane.WARNING_MESSAGE);
                }
                if (r == GitRepo.MergeResult.NOT_COMMITTED) staging.message.setText(msg);
                refreshAll(true);
            });
        });
    }

    /** SourceTree's "Cherry Pick": the changes of the commit on HEAD */
    private void cherryPick(CommitRow c) {
        exec.submit(() -> c.parents().stream().map(repo::commitRow).toList(), parents -> cherryPick(c, parents));
    }

    /** @param parents for a merge commit, the parent to pick against is asked (git cherry-pick -m) */
    private void cherryPick(CommitRow c, List<CommitRow> parents) {
        JCheckBox commit = new JCheckBox("Commit immediately", true);
        JPanel p = new JPanel(new GridLayout(0, 1));
        p.add(new JLabel("Cherry-pick " + c.shortOid() + " \"" + c.summary() + "\" onto " + (headBranch != null ? headBranch : "HEAD") + "?"));
        javax.swing.JComboBox<String> mainline = new javax.swing.JComboBox<>();
        if (parents.size() > 1) {
            p.add(new JLabel("A merge commit: apply its changes against the parent"));
            for (int i = 0; i < parents.size(); i++) {
                mainline.addItem((i + 1) + ": " + parents.get(i).shortOid() + " " + parents.get(i).summary());
            }
            p.add(mainline);
        }
        p.add(commit);
        if (JOptionPane.showConfirmDialog(this, p, "Cherry-pick", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        boolean now = commit.isSelected();
        int parent = parents.size() > 1 ? mainline.getSelectedIndex() + 1 : 0;
        exec.submit(() -> {
            GitRepo.RefSnapshot snapshot = repo.snapshotRefs("Cherry-pick", GitRepo.Restore.ALL);
            GitRepo.CherryPickResult r = repo.cherryPick(c.oid(), now, parent);
            if (r == GitRepo.CherryPickResult.COMMITTED) pushUndo(snapshot);
            return r;
        }, r -> {
            switch (r) {
                case COMMITTED -> statusBar.setText("Cherry-picked " + c.shortOid());
                case NOT_COMMITTED -> statusBar.setText("Cherry-picked " + c.shortOid() + ", not committed: review and commit");
                case CONFLICTS -> JOptionPane.showMessageDialog(this, "The cherry-pick has conflicts.\nResolve them, stage the files and commit, or abort the cherry-pick.",
                        "Cherry-pick", JOptionPane.WARNING_MESSAGE);
            }
            refreshAll(true);
        });
    }

    /** SourceTree's "Reset current branch to this commit": soft, mixed or hard */
    private void resetTo(CommitRow c) {
        String branch = headBranch != null ? headBranch : "HEAD";
        javax.swing.JRadioButton soft = new javax.swing.JRadioButton("Soft - keep all local changes");
        javax.swing.JRadioButton mixed = new javax.swing.JRadioButton("Mixed - keep working copy but reset index", true);
        javax.swing.JRadioButton hard = new javax.swing.JRadioButton("Hard - discard all working copy changes");
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        JPanel p = new JPanel(new GridLayout(0, 1));
        p.add(new JLabel("Reset " + branch + " to " + c.shortOid() + " \"" + c.summary() + "\""));
        p.add(new JLabel("Using mode:"));
        for (javax.swing.JRadioButton b : new javax.swing.JRadioButton[] {soft, mixed, hard}) {
            g.add(b);
            p.add(b);
        }
        if (JOptionPane.showConfirmDialog(this, p, "Reset to Commit", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        int type = soft.isSelected() ? vavi.apps.gitup.jna.LibGit2.GIT_RESET_SOFT
                : mixed.isSelected() ? vavi.apps.gitup.jna.LibGit2.GIT_RESET_MIXED : vavi.apps.gitup.jna.LibGit2.GIT_RESET_HARD;
        GitRepo.Restore undoMode = soft.isSelected() ? GitRepo.Restore.REFS : mixed.isSelected() ? GitRepo.Restore.INDEX : GitRepo.Restore.ALL;
        if (hard.isSelected() && (logPanel.hasUncommitted())
                && !confirm("A hard reset discards all uncommitted changes of the working copy.\nThey cannot be recovered, even by undo.", "Reset (Hard)")) {
            return;
        }
        exec.submit(() -> {
            if (repo.state() != GitRepo.State.NONE) throw new IllegalStateException("finish or abort the merge / rebase in progress first");
            GitRepo.RefSnapshot snapshot = repo.snapshotRefs("Reset", undoMode);
            repo.reset(c.oid(), type);
            pushUndo(snapshot);
            return null;
        }, x -> {
            statusBar.setText("Reset " + branch + " to " + c.shortOid());
            refreshAll(true);
        });
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

    /** urls a saved account was already tried for in the current remote operation */
    private final java.util.Set<String> triedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** the saved account used in the current remote operation, for the error message */
    private volatile vavi.apps.gitup.model.Accounts.Account triedAccount;

    /** runs a remote operation on the git thread with the GitUpKit transport, op returns a status message */
    private void remoteOp(String label, Function<RemoteOps, String> op) {
        triedAccounts.clear();
        triedAccount = null;
        remoteActions.forEach(a -> a.setEnabled(false));
        statusBar.setText(label + "…");
        exec.submit(() -> {
            if (remote == null) {
                remote = new RemoteOps(repo.workdir(), prompter, s -> SwingUtilities.invokeLater(() -> statusBar.setText(label + ": " + s)));
                remote.setCommandLog(commandLog);
            }
            return op.apply(remote);
        }, message -> {
            remoteActions.forEach(a -> a.setEnabled(true));
            statusBar.setText(label + ": " + message);
            refreshAll(false);
        }, e -> {
            remoteActions.forEach(a -> a.setEnabled(true));
            vavi.apps.gitup.model.Accounts.Account a = triedAccount;
            if (a != null && e.getMessage() != null && e.getMessage().contains("authentication failed")) {
                showError(new IllegalStateException(e.getMessage() + "\n\nThe saved account " + a + " (its Keychain password / token) was tried first."
                        + "\nUpdate it in Settings ▸ Accounts (⌘,)."));
            } else {
                showError(e);
            }
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
            GitRepo.RefSnapshot snapshot = repo.snapshotRefs("Pull", false);
            PullResult r = repo.pullFromUpstream(rebase != null ? rebase : repo.isPullRebaseConfigured());
            if (r == PullResult.FAST_FORWARD || r == PullResult.MERGED || r == PullResult.REBASED) pushUndo(snapshot);
            if (r == PullResult.CONFLICTS || r == PullResult.REBASE_CONFLICTS) {
                boolean rb = r == PullResult.REBASE_CONFLICTS;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "The " + (rb ? "rebase" : "merge") + " has conflicts.\nResolve them (right click: Resolve Using Mine / Theirs, or edit and stage),\nthen "
                                + (rb ? "continue the rebase" : "commit") + ", or abort the " + (rb ? "rebase." : "merge."),
                        "Pull", JOptionPane.WARNING_MESSAGE));
            }
            return switch (r) {
                case UP_TO_DATE -> "already up to date";
                case FAST_FORWARD -> "fast-forwarded";
                case MERGED -> "merged";
                case REBASED -> "rebased";
                case CONFLICTS -> "merge has conflicts";
                case REBASE_CONFLICTS -> "rebase stopped by conflicts";
            };
        });
    }

    private record PushInfo(List<GitRepo.Remote> remotes, List<String> branches, Map<String, String> upstreams, String current) {}

    /** SourceTree-like: asks the remote, the branches (remote names, tracking), tags and force, then pushes */
    private void push() {
        exec.submit(() -> {
            List<String> branches = repo.refs().stream().filter(r -> r.kind() == Ref.Kind.LOCAL).map(Ref::shorthand).toList();
            Map<String, String> upstreams = new HashMap<>();
            for (String b : branches) {
                String u = repo.upstream(b);
                if (u != null) upstreams.put(b, u);
            }
            return new PushInfo(repo.remotes(), branches, upstreams, repo.headBranch());
        }, info -> {
            if (info.remotes().isEmpty()) {
                showError(new IllegalStateException("no remote: add one with the sidebar's REMOTES ▸ New Remote…"));
                return;
            }
            if (info.branches().isEmpty()) {
                showError(new IllegalStateException("no branch to push"));
                return;
            }
            PushDialog.Result r = new PushDialog(this, info.remotes(), info.branches(), info.upstreams(), info.current()).showDialog();
            if (r == null) return;
            if (r.force() && !confirm("Force push to " + r.remote() + "?\nCommits on the remote branches that are not here are lost.", "Force Push")) return;
            List<vavi.apps.gitup.objc.RemoteOps.BranchPush> pushes = r.branches().stream()
                    .map(b -> new vavi.apps.gitup.objc.RemoteOps.BranchPush(b.local(), b.remote())).toList();
            remoteOp("Push", ops -> {
                ops.pushBranches(r.remote(), pushes, r.tags(), r.force());
                for (PushDialog.Branch b : r.branches()) {
                    String upstream = r.remote() + "/" + b.remote();
                    if (b.track() && !upstream.equals(info.upstreams().get(b.local()))) repo.setUpstream(b.local(), upstream);
                }
                return "pushed " + r.branches().size() + " branch(es)" + (r.tags() ? " and the tags" : "") + " to " + r.remote();
            });
        });
    }

    /** credential prompts, called on the git thread, shown on the EDT */
    private final RemoteOps.Prompter prompter = new RemoteOps.Prompter() {
        @Override public String[] userPassword(String url, String user) {
            // a saved account first, once per operation (a wrong one would be asked for again and again)
            if (triedAccounts.add(url)) {
                try {
                    vavi.apps.gitup.model.Accounts.Account a = vavi.apps.gitup.model.Accounts.get().find(url, user);
                    String secret = a != null ? vavi.apps.gitup.model.Accounts.get().secret(a) : null;
                    if (secret != null) {
                        triedAccount = a;
                        return new String[] {a.username(), secret};
                    }
                } catch (RuntimeException e) {
                    logger.log(System.Logger.Level.WARNING, "account: " + e.getMessage(), e);
                }
            }
            if (org.rococoa.Foundation.isMainThread()) {
                // GitUpKit asks on the main thread (dispatch_sync): a Swing dialog would dead lock, use AppKit
                vavi.apps.gitup.model.Accounts.Account rejected = triedAccount;
                String note = rejected == null ? null
                        : "The saved password / token of " + rejected + " was rejected by the server (expired or revoked?). "
                        + "Enter a valid one, \"Remember\" replaces the saved one."
                        + (rejected.host().contains("github.com") ? " GitHub needs a personal access token with the repo scope." : "");
                vavi.apps.gitup.objc.NativePrompt.Answer a = vavi.apps.gitup.objc.NativePrompt.userPassword(url,
                        rejected != null ? rejected.username() : user, true, note);
                if (a == null) return null;
                if (a.remember()) rememberAccount(url, a.username(), a.secret());
                return new String[] {a.username(), a.secret()};
            }
            String[][] result = new String[1][];
            invokeAndWait(() -> {
                JTextField u = new JTextField(user != null ? user : "", 20);
                JPasswordField p = new JPasswordField(20);
                JCheckBox remember = new JCheckBox("Remember as an account (Keychain)", true);
                JPanel panel = new JPanel(new GridLayout(0, 1));
                panel.add(new JLabel(url));
                panel.add(new JLabel("Username:"));
                panel.add(u);
                panel.add(new JLabel("Password / token:"));
                panel.add(p);
                panel.add(remember);
                if (JOptionPane.showConfirmDialog(RepoPanel.this, panel, "Authentication", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    result[0] = new String[] {u.getText(), new String(p.getPassword())};
                    if (remember.isSelected()) rememberAccount(url, u.getText(), result[0][1]);
                }
            });
            return result[0];
        }

        @Override public String passphrase(String url, String key) {
            if (org.rococoa.Foundation.isMainThread()) return vavi.apps.gitup.objc.NativePrompt.passphrase(url, key);
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

        private void rememberAccount(String url, String user, String secret) {
            String host = vavi.apps.gitup.model.Accounts.host(url);
            if (host == null || user == null || user.isBlank()) return;
            try {
                vavi.apps.gitup.model.Accounts.get().put(new vavi.apps.gitup.model.Accounts.Account(
                        vavi.apps.gitup.model.Accounts.Service.guess(host), host, user.strip(),
                        vavi.apps.gitup.model.Accounts.Protocol.HTTPS), secret);
            } catch (RuntimeException e) {
                logger.log(System.Logger.Level.WARNING, "account: " + e.getMessage(), e);
            }
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
