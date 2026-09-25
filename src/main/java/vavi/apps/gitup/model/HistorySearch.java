/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;

import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.jna.Structs.GitDiffDelta;
import vavi.apps.gitup.jna.Structs.GitDiffLine;
import vavi.apps.gitup.jna.Structs.GitDiffOptions;
import vavi.apps.gitup.jna.Structs.GitOid;
import vavi.apps.gitup.jna.Structs.GitStrarray;

import static vavi.apps.gitup.jna.LibGit2.GIT_DIFF_FLAG_BINARY;
import static vavi.apps.gitup.jna.LibGit2.GIT_ITEROVER;
import static vavi.apps.gitup.jna.LibGit2.GIT_SORT_TIME;
import static vavi.apps.gitup.jna.LibGit2.GIT_SORT_TOPOLOGICAL;
import static vavi.apps.gitup.model.GitException.check;


/**
 * searches the whole history: commit messages, changed file names and changed lines (like {@code git log -S}).
 * <p>
 * streams hits while walking, newest first, and stops at a limit or when cancelled.
 * changes are taken against the first parent. case-insensitive.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class HistorySearch {

    private static final LibGit2 git = LibGit2.INSTANCE;

    private HistorySearch() {}

    public enum Kind { MESSAGE, FILE, CONTENT }

    /**
     * a hit.
     *
     * @param path   for FILE / CONTENT
     * @param line   for CONTENT: the line number (new side for '+', old side for '-')
     * @param origin for CONTENT: '+' or '-'
     */
    public record Hit(Kind kind, String oid, String summary, String path, int line, char origin, String text) {}

    /**
     * @param progress called with the number of commits scanned, every 100 commits
     * @return the number of hits
     */
    public static int search(GitRepo repo, String query, Set<Kind> kinds, int limit,
                             Consumer<Hit> sink, IntConsumer progress, BooleanSupplier cancelled) {
        String q = query.toLowerCase(Locale.ROOT);
        if (q.isEmpty() || kinds.isEmpty()) return 0;
        PointerByReference wp = new PointerByReference();
        check(git.git_revwalk_new(wp, repo.handle()), "revwalk");
        Pointer walk = wp.getValue();
        int hits = 0;
        int scanned = 0;
        try {
            git.git_revwalk_sorting(walk, GIT_SORT_TOPOLOGICAL | GIT_SORT_TIME);
            if (!repo.isHeadUnborn()) git.git_revwalk_push_head(walk);
            git.git_revwalk_push_glob(walk, "refs/heads");
            git.git_revwalk_push_glob(walk, "refs/remotes");
            git.git_revwalk_push_glob(walk, "refs/tags");
            GitOid oid = new GitOid();
            while (hits < limit && !cancelled.getAsBoolean() && git.git_revwalk_next(oid, walk) != GIT_ITEROVER) {
                oid.read();
                String hex = oid.hex();
                if (++scanned % 100 == 0) progress.accept(scanned);
                Pointer c = repo.lookupCommit(hex);
                String summary;
                String message;
                try {
                    summary = git.git_commit_summary(c);
                    message = git.git_commit_message(c);
                } finally {
                    git.git_commit_free(c);
                }
                if (summary == null) summary = "";
                if (kinds.contains(Kind.MESSAGE) && message != null && message.toLowerCase(Locale.ROOT).contains(q)) {
                    sink.accept(new Hit(Kind.MESSAGE, hex, summary, null, 0, ' ', summary));
                    hits++;
                }
                if (kinds.contains(Kind.FILE) || kinds.contains(Kind.CONTENT)) {
                    hits += searchChanges(repo, hex, summary, q, kinds, limit - hits, sink, cancelled);
                }
            }
            progress.accept(scanned);
        } finally {
            git.git_revwalk_free(walk);
        }
        return hits;
    }

    private static int searchChanges(GitRepo repo, String oid, String summary, String q, Set<Kind> kinds, int limit,
                                     Consumer<Hit> sink, BooleanSupplier cancelled) {
        Pointer[] trees = repo.commitTrees(oid);
        PointerByReference dp = new PointerByReference();
        try {
            GitDiffOptions o = new GitDiffOptions();
            git.git_diff_options_init(o, 1);
            o.context_lines = 0;
            o.interhunk_lines = 0;
            o.pathspec = new GitStrarray();
            check(git.git_diff_tree_to_tree(dp, repo.handle(), trees[0], trees[1], o), "diff");
        } finally {
            GitRepo.freeTrees(trees);
        }
        Pointer diff = dp.getValue();
        int hits = 0;
        try {
            long n = git.git_diff_num_deltas(diff).longValue();
            for (long i = 0; i < n && hits < limit && !cancelled.getAsBoolean(); i++) {
                GitDiffDelta d = new GitDiffDelta(git.git_diff_get_delta(diff, new NativeLong(i)));
                String path = d.new_file.path;
                if (kinds.contains(Kind.FILE) && (path.toLowerCase(Locale.ROOT).contains(q)
                        || d.old_file.path != null && d.old_file.path.toLowerCase(Locale.ROOT).contains(q))) {
                    sink.accept(new Hit(Kind.FILE, oid, summary, path, 0, ' ', path));
                    hits++;
                }
                if (kinds.contains(Kind.CONTENT) && (d.flags & GIT_DIFF_FLAG_BINARY) == 0 && hits < limit) {
                    Hit h = searchPatch(diff, i, oid, summary, path, q);
                    if (h != null) {
                        sink.accept(h);
                        hits++;
                    }
                }
            }
        } finally {
            git.git_diff_free(diff);
        }
        return hits;
    }

    /** @return the first changed line containing q in the delta, null when none */
    private static Hit searchPatch(Pointer diff, long index, String oid, String summary, String path, String q) {
        PointerByReference pp = new PointerByReference();
        if (git.git_patch_from_diff(pp, diff, new NativeLong(index)) != 0 || pp.getValue() == null) return null;
        Pointer patch = pp.getValue();
        try {
            if ((new GitDiffDelta(git.git_patch_get_delta(patch)).flags & GIT_DIFF_FLAG_BINARY) != 0) return null;
            int hunks = git.git_patch_num_hunks(patch).intValue();
            PointerByReference hp = new PointerByReference();
            NativeLongByReference lines = new NativeLongByReference();
            PointerByReference lp = new PointerByReference();
            for (int h = 0; h < hunks; h++) {
                if (git.git_patch_get_hunk(hp, lines, patch, new NativeLong(h)) != 0) continue;
                int n = lines.getValue().intValue();
                for (int l = 0; l < n; l++) {
                    if (git.git_patch_get_line_in_hunk(lp, patch, new NativeLong(h), new NativeLong(l)) != 0) continue;
                    GitDiffLine line = GitDiffLine.read(lp.getValue());
                    if ((line.origin == '+' || line.origin == '-') && line.content.toLowerCase(Locale.ROOT).contains(q)) {
                        int no = line.origin == '+' ? line.newLineno : line.oldLineno;
                        return new Hit(Kind.CONTENT, oid, summary, path, no, line.origin, line.content.strip());
                    }
                }
            }
            return null;
        } finally {
            git.git_patch_free(patch);
        }
    }

    /** all kinds */
    public static Set<Kind> all() {
        return EnumSet.allOf(Kind.class);
    }
}
