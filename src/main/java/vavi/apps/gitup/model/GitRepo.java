/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.jna.Structs.GitDiffDelta;
import vavi.apps.gitup.jna.Structs.GitDiffOptions;
import vavi.apps.gitup.jna.Structs.GitOid;
import vavi.apps.gitup.jna.Structs.GitStatusEntry;
import vavi.apps.gitup.jna.Structs.GitStatusOptions;
import vavi.apps.gitup.jna.Structs.GitStrarray;

import static vavi.apps.gitup.jna.LibGit2.*;
import static vavi.apps.gitup.model.GitException.check;


/**
 * a git repository backed by libgit2 inside GitUpKit.
 * <p>
 * not thread safe, confine every call to one thread (see {@code GitExecutor}).
 * {@link LazyPatch} returned from here may be read from other threads.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class GitRepo implements AutoCloseable {

    private static final LibGit2 git = LibGit2.INSTANCE;

    private Pointer repo;
    private final Path workdir;

    /** a reference */
    public record Ref(String name, String shorthand, Kind kind, String target) {
        public enum Kind { LOCAL, REMOTE, TAG, OTHER }
    }

    /** index and working directory changes */
    public record Status(List<FileChange> staged, List<FileChange> unstaged) {}

    public GitRepo(Path path) {
        PointerByReference pp = new PointerByReference();
        check(git.git_repository_open(pp, path.toAbsolutePath().toString()), "open " + path);
        repo = pp.getValue();
        String wd = git.git_repository_workdir(repo);
        if (wd == null) {
            git.git_repository_free(repo);
            throw new GitException("bare repositories are not supported: " + path);
        }
        workdir = Path.of(wd);
    }

    Pointer handle() {
        if (repo == null) throw new IllegalStateException("closed");
        return repo;
    }

    public Path workdir() {
        return workdir;
    }

    /** the .git directory */
    public Path gitDir() {
        return Path.of(git.git_repository_path(handle()));
    }

    @Override
    public void close() {
        if (repo != null) {
            git.git_repository_free(repo);
            repo = null;
        }
    }

    // head / refs

    public boolean isHeadUnborn() {
        return git.git_repository_head_unborn(handle()) == 1;
    }

    public boolean isHeadDetached() {
        return git.git_repository_head_detached(handle()) == 1;
    }

    /** @return the branch short name, "HEAD" when detached, null when unborn */
    public String headBranch() {
        PointerByReference pp = new PointerByReference();
        int rc = git.git_repository_head(pp, handle());
        if (rc == GIT_EUNBORNBRANCH || rc == GIT_ENOTFOUND) return null;
        check(rc, "head");
        try {
            return git.git_reference_is_branch(pp.getValue()) == 1 ? git.git_reference_shorthand(pp.getValue()) : "HEAD";
        } finally {
            git.git_reference_free(pp.getValue());
        }
    }

    /** @return the commit id HEAD points to, null when unborn */
    public String headOid() {
        return isHeadUnborn() ? null : revparse("HEAD");
    }

    /** @return commit id hex of the spec */
    public String revparse(String spec) {
        PointerByReference pp = new PointerByReference();
        check(git.git_revparse_single(pp, handle(), spec), "revparse " + spec);
        try {
            return git.git_oid_tostr_s(git.git_object_id(pp.getValue()));
        } finally {
            git.git_object_free(pp.getValue());
        }
    }

    /** @return all refs (branches, remote branches, tags), targets are peeled to commits */
    public List<Ref> refs() {
        List<Ref> list = new ArrayList<>();
        PointerByReference ip = new PointerByReference();
        check(git.git_reference_iterator_new(ip, handle()), "refs");
        Pointer iter = ip.getValue();
        try {
            PointerByReference rp = new PointerByReference();
            while (git.git_reference_next(rp, iter) == 0) {
                Pointer ref = rp.getValue();
                try {
                    String name = git.git_reference_name(ref);
                    Ref.Kind kind = git.git_reference_is_branch(ref) == 1 ? Ref.Kind.LOCAL
                            : git.git_reference_is_remote(ref) == 1 ? Ref.Kind.REMOTE
                            : git.git_reference_is_tag(ref) == 1 ? Ref.Kind.TAG : Ref.Kind.OTHER;
                    if (kind == Ref.Kind.REMOTE && name.endsWith("/HEAD")) continue;
                    String target = null;
                    PointerByReference op = new PointerByReference();
                    if (git.git_reference_peel(op, ref, GIT_OBJECT_COMMIT) == 0) {
                        target = git.git_oid_tostr_s(git.git_object_id(op.getValue()));
                        git.git_object_free(op.getValue());
                    }
                    list.add(new Ref(name, git.git_reference_shorthand(ref), kind, target));
                } finally {
                    git.git_reference_free(ref);
                }
            }
        } finally {
            git.git_reference_iterator_free(iter);
        }
        return list;
    }

    /** @return commit id to ref short names */
    public Map<String, List<Ref>> refsByTarget() {
        Map<String, List<Ref>> map = new HashMap<>();
        for (Ref r : refs()) {
            if (r.target() != null) map.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    // status

    public Status status() {
        GitStatusOptions opts = new GitStatusOptions();
        git.git_status_options_init(opts, 1);
        opts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR;
        opts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED | GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS
                | GIT_STATUS_OPT_RENAMES_HEAD_TO_INDEX | GIT_STATUS_OPT_SORT_CASE_SENSITIVELY;
        PointerByReference lp = new PointerByReference();
        check(git.git_status_list_new(lp, handle(), opts), "status");
        Pointer list = lp.getValue();
        List<FileChange> staged = new ArrayList<>();
        List<FileChange> unstaged = new ArrayList<>();
        try {
            long n = git.git_status_list_entrycount(list).longValue();
            for (long i = 0; i < n; i++) {
                GitStatusEntry e = new GitStatusEntry(git.git_status_byindex(list, new NativeLong(i)));
                int s = e.status;
                if ((s & GIT_STATUS_CONFLICTED) != 0) {
                    GitDiffDelta d = new GitDiffDelta(e.index_to_workdir != null ? e.index_to_workdir : e.head_to_index);
                    unstaged.add(new FileChange(d.new_file.path, d.old_file.path, FileChange.Kind.CONFLICTED, false));
                    continue;
                }
                if (e.head_to_index != null) {
                    GitDiffDelta d = new GitDiffDelta(e.head_to_index);
                    staged.add(new FileChange(d.new_file.path, d.old_file.path, FileChange.Kind.ofDelta(d.status), true));
                }
                if (e.index_to_workdir != null) {
                    GitDiffDelta d = new GitDiffDelta(e.index_to_workdir);
                    unstaged.add(new FileChange(d.new_file.path, d.old_file.path, FileChange.Kind.ofDelta(d.status), false));
                }
            }
        } finally {
            git.git_status_list_free(list);
        }
        return new Status(staged, unstaged);
    }

    // diff

    private GitDiffOptions diffOptions(String path, boolean untracked) {
        GitDiffOptions o = new GitDiffOptions();
        git.git_diff_options_init(o, 1);
        o.flags = GIT_DIFF_DISABLE_PATHSPEC_MATCH;
        if (untracked) o.flags |= GIT_DIFF_INCLUDE_UNTRACKED | GIT_DIFF_RECURSE_UNTRACKED_DIRS | GIT_DIFF_SHOW_UNTRACKED_CONTENT;
        o.pathspec = new GitStrarray();
        if (path != null) o.pathspec.set(path);
        return o;
    }

    private Pointer index() {
        PointerByReference ip = new PointerByReference();
        check(git.git_repository_index(ip, handle()), "index");
        return ip.getValue();
    }

    /** @return HEAD tree, null when unborn */
    private Pointer headTree0() {
        if (isHeadUnborn()) return null;
        return commitTree(revparse("HEAD"));
    }

    private Pointer commitTree(String oid) {
        Pointer commit = lookupCommit(oid);
        try {
            PointerByReference tp = new PointerByReference();
            check(git.git_commit_tree(tp, commit), "tree");
            return tp.getValue();
        } finally {
            git.git_commit_free(commit);
        }
    }

    Pointer lookupCommit(String oid) {
        GitOid id = new GitOid();
        check(git.git_oid_fromstr(id, oid), "oid");
        PointerByReference cp = new PointerByReference();
        check(git.git_commit_lookup(cp, handle(), id), "commit " + oid);
        return cp.getValue();
    }

    /**
     * opens a patch of one working copy file. only that file is diffed.
     *
     * @return null when there is no difference any more
     */
    public LazyPatch openPatch(FileChange file) {
        List<String> paths = file.oldPath() != null && !file.oldPath().equals(file.path())
                ? List.of(file.oldPath(), file.path()) : List.of(file.path());
        GitDiffOptions o = diffOptions(null, !file.staged());
        o.pathspec.set(paths.toArray(String[]::new));
        PointerByReference dp = new PointerByReference();
        Pointer index = index();
        try {
            if (file.staged()) {
                Pointer tree = headTree0();
                try {
                    check(git.git_diff_tree_to_index(dp, handle(), tree, index, o), "diff");
                } finally {
                    if (tree != null) git.git_tree_free(tree);
                }
            } else {
                check(git.git_diff_index_to_workdir(dp, handle(), index, o), "diff");
            }
        } finally {
            git.git_index_free(index);
        }
        return toPatch(dp.getValue(), file);
    }

    /** opens a patch of one file in a commit (against its first parent) */
    public LazyPatch openPatch(String commitOid, FileChange file) {
        return openPatch(commitOid, commitOid, file);
    }

    /** opens a patch of one file changed from the first parent of oldest to newest */
    public LazyPatch openPatch(String oldestOid, String newestOid, FileChange file) {
        Pointer[] trees = rangeTrees(oldestOid, newestOid);
        try {
            GitDiffOptions o = diffOptions(null, false);
            o.pathspec.set(file.oldPath() != null && !file.oldPath().equals(file.path())
                    ? new String[] {file.oldPath(), file.path()} : new String[] {file.path()});
            PointerByReference dp = new PointerByReference();
            check(git.git_diff_tree_to_tree(dp, handle(), trees[0], trees[1], o), "diff");
            return toPatch(dp.getValue(), file);
        } finally {
            freeTrees(trees);
        }
    }

    private static LazyPatch toPatch(Pointer diff, FileChange file) {
        if (git.git_diff_num_deltas(diff).longValue() == 0) {
            git.git_diff_free(diff);
            return null;
        }
        return new LazyPatch(diff, 0, file);
    }

    /** @return [first parent tree of oldest (nullable), tree of newest] */
    private Pointer[] rangeTrees(String oldestOid, String newestOid) {
        if (oldestOid.equals(newestOid)) return commitTrees(newestOid);
        Pointer[] base = commitTrees(oldestOid);
        if (base[1] != null) git.git_tree_free(base[1]);
        return new Pointer[] {base[0], commitTree(newestOid)};
    }

    /** @return [parent tree (nullable), commit tree] */
    Pointer[] commitTrees(String commitOid) {
        Pointer commit = lookupCommit(commitOid);
        try {
            PointerByReference tp = new PointerByReference();
            check(git.git_commit_tree(tp, commit), "tree");
            Pointer parentTree = null;
            if (git.git_commit_parentcount(commit) > 0) {
                parentTree = commitTree(git.git_oid_tostr_s(git.git_commit_parent_id(commit, 0)));
            }
            return new Pointer[] {parentTree, tp.getValue()};
        } finally {
            git.git_commit_free(commit);
        }
    }

    static void freeTrees(Pointer[] trees) {
        for (Pointer t : trees) if (t != null) git.git_tree_free(t);
    }

    /** @return files changed in the commit (against its first parent) */
    public List<FileChange> commitFiles(String commitOid) {
        return rangeFiles(commitOid, commitOid);
    }

    /** @return files changed from the first parent of oldest to newest (a range of commits, like SourceTree's multi selection) */
    public List<FileChange> rangeFiles(String oldestOid, String newestOid) {
        Pointer[] trees = rangeTrees(oldestOid, newestOid);
        PointerByReference dp = new PointerByReference();
        try {
            check(git.git_diff_tree_to_tree(dp, handle(), trees[0], trees[1], diffOptions(null, false)), "diff");
        } finally {
            freeTrees(trees);
        }
        Pointer diff = dp.getValue();
        try {
            List<FileChange> list = new ArrayList<>();
            long n = git.git_diff_num_deltas(diff).longValue();
            for (long i = 0; i < n; i++) {
                GitDiffDelta d = new GitDiffDelta(git.git_diff_get_delta(diff, new NativeLong(i)));
                list.add(new FileChange(d.new_file.path, d.old_file.path, FileChange.Kind.ofDelta(d.status), true));
            }
            return list;
        } finally {
            git.git_diff_free(diff);
        }
    }

    // staging

    /** stages whole files */
    public void stage(Collection<FileChange> files) {
        Pointer index = index();
        try {
            for (FileChange f : files) {
                if (f.kind() == FileChange.Kind.DELETED) {
                    check(git.git_index_remove_bypath(index, f.path()), "remove " + f.path());
                } else {
                    check(git.git_index_add_bypath(index, f.path()), "add " + f.path());
                    if (f.oldPath() != null && !f.oldPath().equals(f.path()) && Files.notExists(workdir.resolve(f.oldPath()))) {
                        check(git.git_index_remove_bypath(index, f.oldPath()), "remove " + f.oldPath());
                    }
                }
            }
            check(git.git_index_write(index), "write index");
        } finally {
            git.git_index_free(index);
        }
    }

    /** unstages whole files (resets the index entries to HEAD) */
    public void unstage(Collection<FileChange> files) {
        List<String> paths = new ArrayList<>();
        for (FileChange f : files) {
            paths.add(f.path());
            if (f.oldPath() != null && !f.oldPath().equals(f.path())) paths.add(f.oldPath());
        }
        if (isHeadUnborn()) {
            Pointer index = index();
            try {
                for (String p : paths) check(git.git_index_remove_bypath(index, p), "remove " + p);
                check(git.git_index_write(index), "write index");
            } finally {
                git.git_index_free(index);
            }
            return;
        }
        PointerByReference op = new PointerByReference();
        check(git.git_revparse_single(op, handle(), "HEAD"), "HEAD");
        try {
            GitStrarray sa = new GitStrarray();
            sa.set(paths.toArray(String[]::new));
            check(git.git_reset_default(handle(), op.getValue(), sa), "reset");
        } finally {
            git.git_object_free(op.getValue());
        }
    }

    /** applies a unified diff to the index or the working directory */
    public void apply(String patchText, int location) {
        byte[] b = patchText.getBytes(StandardCharsets.UTF_8);
        PointerByReference dp = new PointerByReference();
        check(git.git_diff_from_buffer(dp, b, new NativeLong(b.length)), "parse patch");
        try {
            check(git.git_apply(handle(), dp.getValue(), location, null), "apply");
        } finally {
            git.git_diff_free(dp.getValue());
        }
    }

    /** stages the selected rows of an unstaged patch */
    public void stageLines(LazyPatch patch, BitSet rows) {
        String p = PartialPatchBuilder.build(patch, rows, false);
        if (p != null) apply(p, GIT_APPLY_LOCATION_INDEX);
    }

    /** unstages the selected rows of a staged patch */
    public void unstageLines(LazyPatch patch, BitSet rows) {
        String p = PartialPatchBuilder.build(patch, rows, true);
        if (p != null) apply(p, GIT_APPLY_LOCATION_INDEX);
    }

    /** discards the selected rows of an unstaged patch from the working directory */
    public void discardLines(LazyPatch patch, BitSet rows) {
        String p = PartialPatchBuilder.build(patch, rows, true);
        if (p != null) apply(p, GIT_APPLY_LOCATION_WORKDIR);
    }

    /** discards whole working directory changes, untracked files are deleted */
    public void discard(Collection<FileChange> files) {
        for (FileChange f : files) {
            if (f.kind() == FileChange.Kind.UNTRACKED) {
                try {
                    Files.deleteIfExists(workdir.resolve(f.path()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                continue;
            }
            try (LazyPatch patch = openPatch(new FileChange(f.path(), f.oldPath(), f.kind(), false))) {
                if (patch == null) continue;
                if (patch.isBinary()) throw new GitException("cannot discard a binary file: " + f.path());
                discardLines(patch, PartialPatchBuilder.all(patch));
            }
        }
    }

    // commit

    /** repository state */
    public enum State { NONE, MERGE, REBASE, OTHER }

    public State state() {
        return switch (git.git_repository_state(handle())) {
            case GIT_REPOSITORY_STATE_NONE -> State.NONE;
            case GIT_REPOSITORY_STATE_MERGE -> State.MERGE;
            case GIT_REPOSITORY_STATE_REBASE, GIT_REPOSITORY_STATE_REBASE_INTERACTIVE, GIT_REPOSITORY_STATE_REBASE_MERGE -> State.REBASE;
            default -> State.OTHER;
        };
    }

    /** @return .git/MERGE_MSG, null when none */
    public String mergeMessage() {
        Path p = Path.of(git.git_repository_path(handle())).resolve("MERGE_MSG");
        try {
            return Files.exists(p) ? Files.readString(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** @return the message of HEAD, null when unborn */
    public String headMessage() {
        if (isHeadUnborn()) return null;
        Pointer c = lookupCommit(revparse("HEAD"));
        try {
            return git.git_commit_message(c);
        } finally {
            git.git_commit_free(c);
        }
    }

    private Pointer writeIndexTree() {
        Pointer index = index();
        GitOid treeId = new GitOid();
        try {
            if (git.git_index_has_conflicts(index) == 1) throw new GitException("unresolved conflicts, stage the resolved files first");
            check(git.git_index_write_tree(treeId, index), "write tree");
        } finally {
            git.git_index_free(index);
        }
        PointerByReference tp = new PointerByReference();
        check(git.git_tree_lookup(tp, handle(), treeId), "tree");
        return tp.getValue();
    }

    private Pointer signature() {
        PointerByReference sp = new PointerByReference();
        check(git.git_signature_default(sp, handle()), "signature (set user.name and user.email)");
        return sp.getValue();
    }

    /**
     * commits the index on HEAD. while merging, MERGE_HEAD becomes the second parent
     * and the merge state is cleaned up.
     *
     * @return new commit id
     */
    public String commit(String message) {
        boolean merging = state() == State.MERGE;
        Pointer tree = writeIndexTree();
        Pointer sig = null;
        List<Pointer> parents = new ArrayList<>();
        try {
            sig = signature();
            if (!isHeadUnborn()) parents.add(lookupCommit(revparse("HEAD")));
            if (merging) parents.add(lookupCommit(revparse("MERGE_HEAD")));
            GitOid id = new GitOid();
            check(git.git_commit_create(id, handle(), "HEAD", sig, sig, null, message, tree,
                    new NativeLong(parents.size()), parents.isEmpty() ? null : parents.toArray(Pointer[]::new)), "commit");
            if (merging) git.git_repository_state_cleanup(handle());
            return id.hex();
        } finally {
            parents.forEach(git::git_commit_free);
            if (sig != null) git.git_signature_free(sig);
            git.git_tree_free(tree);
        }
    }

    /** replaces HEAD with a commit of the index and the message (author is kept), @return new commit id */
    public String amend(String message) {
        if (isHeadUnborn()) throw new GitException("nothing to amend");
        Pointer tree = writeIndexTree();
        Pointer sig = null;
        Pointer head = lookupCommit(revparse("HEAD"));
        try {
            sig = signature();
            GitOid id = new GitOid();
            check(git.git_commit_amend(id, head, "HEAD", null, sig, null, message, tree), "amend");
            return id.hex();
        } finally {
            git.git_commit_free(head);
            if (sig != null) git.git_signature_free(sig);
            git.git_tree_free(tree);
        }
    }

    /** index and working directory to HEAD (git reset --hard HEAD), untracked files are kept */
    public void resetHardToHead() {
        PointerByReference op = new PointerByReference();
        check(git.git_revparse_single(op, handle(), "HEAD"), "HEAD");
        try {
            check(git.git_reset(handle(), op.getValue(), GIT_RESET_HARD, null), "reset");
        } finally {
            git.git_object_free(op.getValue());
        }
    }

    /** @return the tree id of HEAD, null when unborn */
    public String headTree() {
        if (isHeadUnborn()) return null;
        Pointer t = headTree0();
        try {
            return git.git_oid_tostr_s(git.git_object_id(t));
        } finally {
            git.git_tree_free(t);
        }
    }

    /** aborts a merge in progress: hard reset to HEAD and cleans up the merge state */
    public void abortMerge() {
        PointerByReference op = new PointerByReference();
        check(git.git_revparse_single(op, handle(), "HEAD"), "HEAD");
        try {
            check(git.git_reset(handle(), op.getValue(), GIT_RESET_HARD, null), "reset");
        } finally {
            git.git_object_free(op.getValue());
        }
        git.git_repository_state_cleanup(handle());
    }

    // branches

    /** checks out a local branch (safe checkout, fails on conflicting local changes) */
    public void checkout(String localBranch) {
        String refname = "refs/heads/" + localBranch;
        PointerByReference op = new PointerByReference();
        check(git.git_revparse_single(op, handle(), refname), "branch " + localBranch);
        try {
            check(git.git_checkout_tree(handle(), op.getValue(), LibGit2.safeCheckoutOptions()), "checkout");
        } finally {
            git.git_object_free(op.getValue());
        }
        check(git.git_repository_set_head(handle(), refname), "set HEAD");
    }

    /** a remote */
    public record Remote(String name, String url, String pushUrl) {}

    public List<Remote> remotes() {
        GitStrarray names = new GitStrarray();
        check(git.git_remote_list(names, handle()), "remotes");
        names.read();
        List<Remote> list = new ArrayList<>();
        try {
            int n = names.count.intValue();
            String[] a = n == 0 ? new String[0] : names.strings.getStringArray(0, n, "UTF-8");
            for (String name : a) {
                PointerByReference rp = new PointerByReference();
                if (git.git_remote_lookup(rp, handle(), name) != 0) continue;
                try {
                    list.add(new Remote(name, git.git_remote_url(rp.getValue()), git.git_remote_pushurl(rp.getValue())));
                } finally {
                    git.git_remote_free(rp.getValue());
                }
            }
        } finally {
            git.git_strarray_dispose(names);
        }
        return list;
    }

    public void createRemote(String name, String url) {
        IntByReference valid = new IntByReference();
        check(git.git_remote_name_is_valid(valid, name), "remote name");
        if (valid.getValue() == 0) throw new GitException("invalid remote name: " + name);
        PointerByReference rp = new PointerByReference();
        check(git.git_remote_create(rp, handle(), name, url), "create remote " + name);
        git.git_remote_free(rp.getValue());
    }

    /**
     * renames and / or changes the URLs of a remote. renaming moves its remote branches and
     * the upstream settings of local branches.
     *
     * @param pushUrl empty or null for none (the fetch URL is used)
     */
    public void editRemote(String name, String newName, String url, String pushUrl) {
        String current = name;
        if (!name.equals(newName)) {
            IntByReference valid = new IntByReference();
            check(git.git_remote_name_is_valid(valid, newName), "remote name");
            if (valid.getValue() == 0) throw new GitException("invalid remote name: " + newName);
            GitStrarray problems = new GitStrarray();
            check(git.git_remote_rename(problems, handle(), name, newName), "rename remote " + name);
            git.git_strarray_dispose(problems);
            current = newName;
        }
        check(git.git_remote_set_url(handle(), current, url), "set url");
        boolean clear = pushUrl == null || pushUrl.isBlank();
        String now = current;
        boolean hasPushUrl = remotes().stream().anyMatch(r -> r.name().equals(now) && r.pushUrl() != null);
        if (!clear || hasPushUrl) { // clearing an unset push url is an error in libgit2
            check(git.git_remote_set_pushurl(handle(), current, clear ? null : pushUrl), "set push url");
        }
    }

    /** removes a remote with its remote branches (the repository on the server is not touched) */
    public void removeRemote(String name) {
        check(git.git_remote_delete(handle(), name), "remove remote " + name);
    }

    /** renames a local branch, its upstream setting follows */
    public void renameBranch(String name, String newName) {
        IntByReference valid = new IntByReference();
        check(git.git_branch_name_is_valid(valid, newName), "branch name");
        if (valid.getValue() == 0) throw new GitException("invalid branch name: " + newName);
        PointerByReference bp = new PointerByReference();
        check(git.git_branch_lookup(bp, handle(), name, GIT_BRANCH_LOCAL), "branch " + name);
        try {
            PointerByReference np = new PointerByReference();
            check(git.git_branch_move(np, bp.getValue(), newName, 0), "rename " + name);
            git.git_reference_free(np.getValue());
        } finally {
            git.git_reference_free(bp.getValue());
        }
    }

    /** @return true when HEAD contains the tip of the local branch */
    public boolean isMergedIntoHead(String branch) {
        if (isHeadUnborn()) return false;
        String tip = revparse("refs/heads/" + branch);
        String head = headOid();
        if (tip.equals(head)) return true;
        GitOid h = new GitOid(), t = new GitOid();
        git.git_oid_fromstr(h, head);
        git.git_oid_fromstr(t, tip);
        return git.git_graph_descendant_of(handle(), h, t) == 1;
    }

    /**
     * deletes a local branch.
     *
     * @param force delete even when HEAD does not contain it (its commits may be lost)
     */
    public void deleteBranch(String name, boolean force) {
        PointerByReference bp = new PointerByReference();
        check(git.git_branch_lookup(bp, handle(), name, GIT_BRANCH_LOCAL), "branch " + name);
        try {
            if (git.git_branch_is_head(bp.getValue()) == 1) throw new GitException("cannot delete the checked out branch " + name);
            if (!force && !isMergedIntoHead(name)) {
                throw new GitException(name + " is not merged into HEAD, use force to delete it anyway");
            }
            check(git.git_branch_delete(bp.getValue()), "delete " + name);
        } finally {
            git.git_reference_free(bp.getValue());
        }
    }

    /**
     * moves the current branch (or detached HEAD) to the commit.
     *
     * @param type {@link LibGit2#GIT_RESET_SOFT} keeps index and working copy,
     *             {@link LibGit2#GIT_RESET_MIXED} resets the index, keeps the working copy,
     *             {@link LibGit2#GIT_RESET_HARD} resets both (local changes are lost)
     */
    public void reset(String commitOid, int type) {
        PointerByReference op = new PointerByReference();
        check(git.git_revparse_single(op, handle(), commitOid), "commit " + commitOid);
        try {
            check(git.git_reset(handle(), op.getValue(), type, null), "reset");
        } finally {
            git.git_object_free(op.getValue());
        }
    }

    /** creates a local branch at the commit, optionally checks it out */
    public void createBranch(String name, String commitOid, boolean checkout) {
        Pointer commit = lookupCommit(commitOid);
        try {
            PointerByReference rp = new PointerByReference();
            check(git.git_branch_create(rp, handle(), name, commit, 0), "create branch " + name);
            git.git_reference_free(rp.getValue());
        } finally {
            git.git_commit_free(commit);
        }
        if (checkout) checkout(name);
    }

    /** creates a local branch tracking the remote branch (e.g. "origin/foo") and checks it out */
    public String checkoutRemote(String remoteBranch) {
        String local = remoteBranch.substring(remoteBranch.indexOf('/') + 1);
        PointerByReference rp = new PointerByReference();
        if (git.git_branch_lookup(rp, handle(), local, GIT_BRANCH_LOCAL) == 0) {
            git.git_reference_free(rp.getValue());
        } else {
            Pointer commit = lookupCommit(revparse("refs/remotes/" + remoteBranch));
            try {
                check(git.git_branch_create(rp, handle(), local, commit, 0), "create branch " + local);
                try {
                    check(git.git_branch_set_upstream(rp.getValue(), remoteBranch), "set upstream");
                } finally {
                    git.git_reference_free(rp.getValue());
                }
            } finally {
                git.git_commit_free(commit);
            }
        }
        checkout(local);
        return local;
    }

    /** @return "remote/branch" of the upstream, null when not set */
    public String upstream(String localBranch) {
        PointerByReference bp = new PointerByReference();
        if (git.git_branch_lookup(bp, handle(), localBranch, GIT_BRANCH_LOCAL) != 0) return null;
        try {
            PointerByReference up = new PointerByReference();
            if (git.git_branch_upstream(up, bp.getValue()) != 0) return null;
            try {
                return git.git_reference_shorthand(up.getValue());
            } finally {
                git.git_reference_free(up.getValue());
            }
        } finally {
            git.git_reference_free(bp.getValue());
        }
    }

    /** result of {@link #pullFromUpstream()} */
    public enum PullResult { UP_TO_DATE, FAST_FORWARD, MERGED, CONFLICTS, REBASED, REBASE_CONFLICTS }

    /** @return true when branch.&lt;name&gt;.rebase or pull.rebase is set */
    public boolean isPullRebaseConfigured() {
        String branch = headBranch();
        PointerByReference cp = new PointerByReference();
        check(git.git_repository_config_snapshot(cp, handle()), "config");
        try {
            IntByReference v = new IntByReference();
            if (branch != null && git.git_config_get_bool(v, cp.getValue(), "branch." + branch + ".rebase") == 0) return v.getValue() != 0;
            return git.git_config_get_bool(v, cp.getValue(), "pull.rebase") == 0 && v.getValue() != 0;
        } finally {
            git.git_config_free(cp.getValue());
        }
    }

    /** {@link #pullFromUpstream(boolean)} with a merge */
    public PullResult pullFromUpstream() {
        return pullFromUpstream(false);
    }

    /**
     * integrates the fetched upstream into the current branch: fast-forward when possible,
     * otherwise a merge commit or a rebase. on conflicts the repository is left merging
     * ({@link #commit}, {@link #abortMerge}) or rebasing ({@link #continueRebase}, {@link #abortRebase}).
     */
    public PullResult pullFromUpstream(boolean rebase) {
        String branch = headBranch();
        if (branch == null || "HEAD".equals(branch)) throw new GitException("not on a branch");
        String upstream = upstream(branch);
        if (upstream == null) throw new GitException("no upstream for " + branch);
        if (state() != State.NONE) throw new GitException("finish or abort the merge in progress first");
        String target = revparse("refs/remotes/" + upstream);
        GitOid t = new GitOid();
        git.git_oid_fromstr(t, target);
        PointerByReference ap = new PointerByReference();
        check(git.git_annotated_commit_lookup(ap, handle(), t), "upstream");
        Pointer[] heads = {ap.getValue()};
        try {
            IntByReference analysis = new IntByReference(), preference = new IntByReference();
            check(git.git_merge_analysis(analysis, preference, handle(), heads, new NativeLong(1)), "merge analysis");
            int a = analysis.getValue();
            if ((a & GIT_MERGE_ANALYSIS_UP_TO_DATE) != 0) return PullResult.UP_TO_DATE;
            if ((a & GIT_MERGE_ANALYSIS_FASTFORWARD) != 0) {
                fastForward(branch, target, t);
                return PullResult.FAST_FORWARD;
            }
            if (rebase) {
                return rebase(heads[0]) ? PullResult.REBASED : PullResult.REBASE_CONFLICTS;
            }
            Pointer opts = LibGit2.safeCheckoutOptions();
            opts.setInt(4, GIT_CHECKOUT_SAFE | GIT_CHECKOUT_ALLOW_CONFLICTS);
            check(git.git_merge(handle(), heads, new NativeLong(1), null, opts), "merge");
        } finally {
            git.git_annotated_commit_free(ap.getValue());
        }
        Pointer index = index();
        try {
            check(git.git_index_read(index, 1), "read index");
            if (git.git_index_has_conflicts(index) == 1) return PullResult.CONFLICTS;
        } finally {
            git.git_index_free(index);
        }
        commit("Merge remote-tracking branch '" + upstream + "' into " + branch + "\n");
        return PullResult.MERGED;
    }

    /**
     * rebases HEAD onto the annotated upstream.
     *
     * @return false when stopped by conflicts (the repository is left rebasing, see {@link #continueRebase()})
     */
    private boolean rebase(Pointer upstream) {
        Status st = status();
        if (!st.staged().isEmpty() || st.unstaged().stream().anyMatch(f -> f.kind() != FileChange.Kind.UNTRACKED)) {
            throw new GitException("commit or stash the local changes before rebasing");
        }
        PointerByReference rp = new PointerByReference();
        check(git.git_rebase_init(rp, handle(), null, upstream, null, null), "rebase");
        return runRebase(rp.getValue(), false);
    }

    /**
     * applies the remaining operations of the rebase, frees it.
     *
     * @param commitCurrent true to commit the current (resolved) operation first
     * @return true when finished, false when stopped by conflicts
     */
    private boolean runRebase(Pointer rebase, boolean commitCurrent) {
        Pointer sig = null;
        try {
            sig = signature();
            if (commitCurrent) commitRebaseOperation(rebase, sig);
            PointerByReference op = new PointerByReference();
            int rc;
            while ((rc = git.git_rebase_next(op, rebase)) == 0) {
                if (!conflictedPaths().isEmpty()) return false; // left for the user
                commitRebaseOperation(rebase, sig);
            }
            if (rc != GIT_ITEROVER) check(rc, "rebase");
            check(git.git_rebase_finish(rebase, sig), "finish rebase");
            return true;
        } finally {
            if (sig != null) git.git_signature_free(sig);
            git.git_rebase_free(rebase);
        }
    }

    private void commitRebaseOperation(Pointer rebase, Pointer sig) {
        int c = git.git_rebase_commit(new GitOid(), rebase, null, sig, null, null);
        if (c != GIT_EAPPLIED) check(c, "rebase commit"); // EAPPLIED: already upstream, skipped
    }

    /** @return paths with unresolved conflicts */
    public List<String> conflictedPaths() {
        return status().unstaged().stream().filter(f -> f.kind() == FileChange.Kind.CONFLICTED).map(FileChange::path).toList();
    }

    /**
     * commits the resolved operation of a stopped rebase and goes on.
     *
     * @return true when the rebase finished, false when stopped by conflicts again
     */
    public boolean continueRebase() {
        if (state() != State.REBASE) throw new GitException("no rebase in progress");
        List<String> conflicts = conflictedPaths();
        if (!conflicts.isEmpty()) throw new GitException("resolve and stage first: " + String.join(", ", conflicts));
        PointerByReference rp = new PointerByReference();
        check(git.git_rebase_open(rp, handle(), null), "open rebase");
        return runRebase(rp.getValue(), true);
    }

    /** throws the rebase in progress away, the branch is back where it was */
    public void abortRebase() {
        PointerByReference rp = new PointerByReference();
        check(git.git_rebase_open(rp, handle(), null), "open rebase");
        try {
            check(git.git_rebase_abort(rp.getValue()), "abort rebase");
        } finally {
            git.git_rebase_free(rp.getValue());
        }
    }

    private void fastForward(String branch, String target, GitOid t) {
        PointerByReference op = new PointerByReference();
        check(git.git_revparse_single(op, handle(), target), "target");
        try {
            check(git.git_checkout_tree(handle(), op.getValue(), LibGit2.safeCheckoutOptions()), "checkout");
        } finally {
            git.git_object_free(op.getValue());
        }
        PointerByReference rp = new PointerByReference();
        check(git.git_reference_lookup(rp, handle(), "refs/heads/" + branch), "branch");
        try {
            PointerByReference np = new PointerByReference();
            check(git.git_reference_set_target(np, rp.getValue(), t, "pull: fast-forward"), "fast-forward");
            git.git_reference_free(np.getValue());
        } finally {
            git.git_reference_free(rp.getValue());
        }
    }

    // undo

    /** what a restore resets besides the references */
    public enum Restore {
        /** references only (undo commit: the changes come back staged) */
        REFS,
        /** references and the index (the changes come back unstaged) */
        INDEX,
        /** references, index and working directory (needs a clean working copy) */
        ALL
    }

    /**
     * where HEAD and the local branches pointed before an operation.
     *
     * @param head "refs/heads/..." or a commit id when detached, null when unborn
     */
    public record RefSnapshot(String label, String head, Map<String, String> branches, Restore restore) {
        public boolean soft() { return restore != Restore.ALL; }
    }

    /** @param soft true for {@link Restore#REFS}, false for {@link Restore#ALL} */
    public RefSnapshot snapshotRefs(String label, boolean soft) {
        return snapshotRefs(label, soft ? Restore.REFS : Restore.ALL);
    }

    public RefSnapshot snapshotRefs(String label, Restore restore) {
        Map<String, String> branches = new java.util.LinkedHashMap<>();
        for (Ref r : refs()) {
            if (r.kind() == Ref.Kind.LOCAL && r.target() != null) branches.put(r.name(), r.target());
        }
        String head = null;
        if (!isHeadUnborn()) {
            String b = headBranch();
            head = b != null && !"HEAD".equals(b) ? "refs/heads/" + b : revparse("HEAD");
        }
        return new RefSnapshot(label, head, branches, restore);
    }

    /**
     * puts HEAD and the local branches back (branches created later are kept).
     * a hard restore needs a clean working copy.
     */
    public void restore(RefSnapshot s) {
        if (state() != State.NONE) throw new GitException("finish or abort the merge / rebase in progress first");
        if (!s.soft()) {
            Status st = status();
            if (!st.staged().isEmpty() || st.unstaged().stream().anyMatch(f -> f.kind() != FileChange.Kind.UNTRACKED)) {
                throw new GitException("commit or stash the local changes before undoing");
            }
        }
        for (Map.Entry<String, String> e : s.branches().entrySet()) {
            GitOid id = new GitOid();
            git.git_oid_fromstr(id, e.getValue());
            PointerByReference rp = new PointerByReference();
            check(git.git_reference_create(rp, handle(), e.getKey(), id, 1, "undo: " + s.label()), "restore " + e.getKey());
            git.git_reference_free(rp.getValue());
        }
        if (s.head() != null) {
            if (s.head().startsWith("refs/")) {
                check(git.git_repository_set_head(handle(), s.head()), "set HEAD");
            } else {
                GitOid id = new GitOid();
                git.git_oid_fromstr(id, s.head());
                check(git.git_repository_set_head_detached(handle(), id), "set HEAD");
            }
        }
        switch (s.restore()) {
            case ALL -> resetHardToHead();
            case INDEX -> {
                if (!isHeadUnborn()) reset(revparse("HEAD"), GIT_RESET_MIXED);
            }
            case REFS -> {}
        }
    }

    // conflicts

    /** offset of the git_oid in git_index_entry (ctime 8, mtime 8, dev ino mode uid gid file_size 24) */
    private static final int INDEX_ENTRY_ID = 40;

    /** resolves a conflicted file with our (HEAD) or their version, a missing side deletes the file */
    public void resolveConflict(String path, boolean ours) {
        Pointer index = index();
        try {
            PointerByReference a = new PointerByReference(), o = new PointerByReference(), t = new PointerByReference();
            check(git.git_index_conflict_get(a, o, t, index, path), "conflict " + path);
            Pointer entry = ours ? o.getValue() : t.getValue();
            Path file = workdir.resolve(path);
            if (entry == null) {
                Files.deleteIfExists(file);
                check(git.git_index_remove_bypath(index, path), "remove " + path);
            } else {
                GitOid id = new GitOid(entry.share(INDEX_ENTRY_ID));
                PointerByReference bp = new PointerByReference();
                check(git.git_blob_lookup(bp, handle(), id), "blob");
                try {
                    long size = git.git_blob_rawsize(bp.getValue());
                    byte[] content = size == 0 ? new byte[0] : git.git_blob_rawcontent(bp.getValue()).getByteArray(0, (int) size);
                    Files.createDirectories(file.getParent());
                    Files.write(file, content);
                } finally {
                    git.git_blob_free(bp.getValue());
                }
                check(git.git_index_add_bypath(index, path), "add " + path);
            }
            check(git.git_index_write(index), "write index");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            git.git_index_free(index);
        }
    }

    // commits

    /** @return true when a remote branch contains the commit (rewriting it rewrites published history) */
    public boolean isPublished(String oid) {
        GitOid c = new GitOid();
        git.git_oid_fromstr(c, oid);
        for (Ref r : refs()) {
            if (r.kind() != Ref.Kind.REMOTE || r.target() == null) continue;
            if (r.target().equals(oid)) return true;
            GitOid t = new GitOid();
            git.git_oid_fromstr(t, r.target());
            if (git.git_graph_descendant_of(handle(), t, c) == 1) return true;
        }
        return false;
    }

    /** reads one commit (without graph lanes) */
    public CommitLog.CommitRow commitRow(String oid) {
        Pointer c = lookupCommit(oid);
        try {
            int pc = git.git_commit_parentcount(c);
            List<String> parents = new ArrayList<>(pc);
            for (int i = 0; i < pc; i++) parents.add(git.git_oid_tostr_s(git.git_commit_parent_id(c, i)));
            vavi.apps.gitup.jna.Structs.GitSignature sig = new vavi.apps.gitup.jna.Structs.GitSignature(git.git_commit_author(c));
            String summary = git.git_commit_summary(c);
            String message = git.git_commit_message(c);
            return new CommitLog.CommitRow(oid, parents, summary != null ? summary : "", message != null ? message : "",
                    sig.name, sig.email, java.time.Instant.ofEpochSecond(sig.when.time), 0, new String[0], new String[0]);
        } finally {
            git.git_commit_free(c);
        }
    }

    // stash

    /** a stash entry, index 0 is the newest */
    public record Stash(int index, String message, String oid) {}

    public List<Stash> stashes() {
        List<Stash> list = new ArrayList<>();
        LibGit2.StashCallback cb = (i, message, id, payload) -> {
            list.add(new Stash(i.intValue(), message, git.git_oid_tostr_s(id)));
            return 0;
        };
        check(git.git_stash_foreach(handle(), cb, null), "stash list");
        return list;
    }

    /** stashes the working copy changes, @return false when there was nothing to stash */
    public boolean stashSave(String message, boolean keepIndex, boolean includeUntracked) {
        Pointer sig = signature();
        try {
            int flags = (keepIndex ? GIT_STASH_KEEP_INDEX : 0) | (includeUntracked ? GIT_STASH_INCLUDE_UNTRACKED : 0);
            int rc = git.git_stash_save(new GitOid(), handle(), sig, message == null || message.isBlank() ? null : message, flags);
            if (rc == GIT_ENOTFOUND) return false;
            check(rc, "stash");
            return true;
        } finally {
            git.git_signature_free(sig);
        }
    }

    public void stashApply(int index) {
        check(git.git_stash_apply(handle(), new NativeLong(index), null), "stash apply");
    }

    public void stashPop(int index) {
        check(git.git_stash_pop(handle(), new NativeLong(index), null), "stash pop");
    }

    public void stashDrop(int index) {
        check(git.git_stash_drop(handle(), new NativeLong(index)), "stash drop");
    }

    // ignore / tracking

    /** where an ignore pattern is written */
    public enum IgnoreTarget {
        /** .gitignore at the top of the working directory */
        REPOSITORY,
        /** .git/info/exclude, not shared */
        LOCAL,
        /** core.excludesFile (default ~/.config/git/ignore) */
        GLOBAL
    }

    /** removes files from the index but keeps them in the working directory (git rm --cached) */
    public void stopTracking(Collection<FileChange> files) {
        Pointer index = index();
        try {
            for (FileChange f : files) check(git.git_index_remove_bypath(index, f.path()), "remove " + f.path());
            check(git.git_index_write(index), "write index");
        } finally {
            git.git_index_free(index);
        }
    }

    public boolean isIgnored(String path) {
        IntByReference r = new IntByReference();
        check(git.git_ignore_path_is_ignored(r, handle(), path), "ignored " + path);
        return r.getValue() == 1;
    }

    /** @return the file the target stands for */
    public Path ignoreFile(IgnoreTarget target) {
        return switch (target) {
            case REPOSITORY -> workdir.resolve(".gitignore");
            case LOCAL -> Path.of(git.git_repository_path(handle())).resolve("info/exclude");
            case GLOBAL -> globalExcludesFile();
        };
    }

    private Path globalExcludesFile() {
        PointerByReference cp = new PointerByReference();
        check(git.git_repository_config_snapshot(cp, handle()), "config");
        try {
            PointerByReference vp = new PointerByReference();
            if (git.git_config_get_string(vp, cp.getValue(), "core.excludesfile") == 0) {
                String v = vp.getValue().getString(0, "UTF-8");
                if (v.startsWith("~/")) v = System.getProperty("user.home") + v.substring(1);
                return Path.of(v);
            }
        } finally {
            git.git_config_free(cp.getValue());
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("git/ignore");
    }

    /** appends the pattern to the ignore file of the target (creating it), @return the file */
    public Path ignore(String pattern, IgnoreTarget target) {
        Path file = ignoreFile(target);
        try {
            Files.createDirectories(file.getParent());
            String current = Files.exists(file) ? Files.readString(file) : "";
            if (current.lines().anyMatch(pattern::equals)) return file;
            String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
            Files.writeString(file, current + prefix + pattern + "\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SourceTree's ways to ignore a file, as patterns */
    public static final class IgnorePatterns {
        private IgnorePatterns() {}

        /** the exact file, anchored at the top */
        public static String exact(String path) {
            return "/" + escape(path);
        }

        /** every file with the same extension, null when the file has none */
        public static String extension(String path) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            return dot <= 0 || dot == name.length() - 1 ? null : "*" + escape(name.substring(dot));
        }

        /** everything beneath the directory ("a/b" -> "/a/b/") */
        public static String beneath(String dir) {
            return "/" + escape(dir) + "/";
        }

        /** parent directories of the path, nearest first ("a/b/c.txt" -> ["a/b", "a"]) */
        public static List<String> parents(String path) {
            List<String> list = new ArrayList<>();
            for (int i = path.lastIndexOf('/'); i > 0; i = path.lastIndexOf('/', i - 1)) list.add(path.substring(0, i));
            return list;
        }

        /** escapes glob characters, a leading '#' or '!' and trailing spaces */
        static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '*' || c == '?' || c == '[' || c == '\\') sb.append('\\');
                else if (i == 0 && (c == '#' || c == '!')) sb.append('\\');
                sb.append(c);
            }
            int e = sb.length();
            while (e > 0 && sb.charAt(e - 1) == ' ') e--;
            if (e < sb.length()) {
                String tail = sb.substring(e).replace(" ", "\\ ");
                sb.setLength(e);
                sb.append(tail);
            }
            return sb.toString();
        }
    }

    // log

    /** @return a new history walker over all refs */
    public CommitLog log() {
        return new CommitLog(this, false);
    }

    /**
     * @param workingCopy true when an "Uncommitted changes" row is shown above,
     *                    the graph then starts with a lane leading to HEAD
     */
    public CommitLog log(boolean workingCopy) {
        return new CommitLog(this, workingCopy);
    }
}
