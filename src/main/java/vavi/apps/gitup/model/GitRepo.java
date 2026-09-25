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
    private Pointer headTree() {
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
                Pointer tree = headTree();
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
        Pointer[] trees = commitTrees(commitOid);
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

    /** @return [parent tree (nullable), commit tree] */
    private Pointer[] commitTrees(String commitOid) {
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

    private static void freeTrees(Pointer[] trees) {
        for (Pointer t : trees) if (t != null) git.git_tree_free(t);
    }

    /** @return files changed in the commit (against its first parent) */
    public List<FileChange> commitFiles(String commitOid) {
        Pointer[] trees = commitTrees(commitOid);
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

    /** commits the index on HEAD, @return new commit id */
    public String commit(String message) {
        Pointer index = index();
        GitOid treeId = new GitOid();
        try {
            if (git.git_index_has_conflicts(index) == 1) throw new GitException("unresolved conflicts in the index");
            check(git.git_index_write_tree(treeId, index), "write tree");
        } finally {
            git.git_index_free(index);
        }
        PointerByReference tp = new PointerByReference();
        check(git.git_tree_lookup(tp, handle(), treeId), "tree");
        Pointer tree = tp.getValue();
        PointerByReference sp = new PointerByReference();
        Pointer parent = null;
        try {
            check(git.git_signature_default(sp, handle()), "signature (set user.name and user.email)");
            Pointer[] parents = new Pointer[0];
            if (!isHeadUnborn()) {
                parent = lookupCommit(revparse("HEAD"));
                parents = new Pointer[] {parent};
            }
            GitOid id = new GitOid();
            check(git.git_commit_create(id, handle(), "HEAD", sp.getValue(), sp.getValue(), null, message, tree,
                    new NativeLong(parents.length), parents.length == 0 ? null : parents), "commit");
            return id.hex();
        } finally {
            if (parent != null) git.git_commit_free(parent);
            if (sp.getValue() != null) git.git_signature_free(sp.getValue());
            git.git_tree_free(tree);
        }
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

    /**
     * fast-forwards the current branch to its upstream.
     *
     * @return false when already up to date
     * @throws GitException when not fast-forwardable
     */
    public boolean fastForwardToUpstream() {
        String branch = headBranch();
        if (branch == null || "HEAD".equals(branch)) throw new GitException("not on a branch");
        String upstream = upstream(branch);
        if (upstream == null) throw new GitException("no upstream for " + branch);
        String head = revparse("HEAD");
        String target = revparse("refs/remotes/" + upstream);
        if (head.equals(target)) return false;
        GitOid t = new GitOid(), h = new GitOid();
        git.git_oid_fromstr(t, target);
        git.git_oid_fromstr(h, head);
        if (git.git_graph_descendant_of(handle(), h, t) == 1) return false; // ahead
        if (git.git_graph_descendant_of(handle(), t, h) != 1) {
            throw new GitException(branch + " and " + upstream + " have diverged, merge is not supported yet");
        }
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
        return true;
    }

    // log

    /** @return a new history walker over all refs */
    public CommitLog log() {
        return new CommitLog(this);
    }
}
