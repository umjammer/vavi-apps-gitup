/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.jna.Structs.GitOid;

import static vavi.apps.gitup.jna.LibGit2.GIT_SORT_TIME;
import static vavi.apps.gitup.jna.LibGit2.GIT_SORT_TOPOLOGICAL;
import static vavi.apps.gitup.model.GitException.check;


/**
 * paged history over all branches, remote branches and tags, newest first.
 * <p>
 * each {@link #next(int)} call reads only the requested number of commits and
 * extends the graph layout incrementally.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class CommitLog implements AutoCloseable {

    private static final LibGit2 git = LibGit2.INSTANCE;

    /**
     * a commit row.
     *
     * @param lane   graph lane of this commit
     * @param before lane contents (expected commit ids) above this row
     * @param after  lane contents below this row
     */
    public record CommitRow(String oid, List<String> parents, String summary, String message,
                            String author, String email, Instant time,
                            int lane, String[] before, String[] after) {
        public String shortOid() { return oid.substring(0, 7); }
    }

    private final GitRepo repo;
    private Pointer walk;
    private boolean done;
    private final GraphLayout layout = new GraphLayout();

    /**
     * SourceTree's log filters.
     *
     * @param allBranches false: only the history of HEAD ("Current Branch")
     * @param remotes false: remote branches are not shown (with all branches)
     * @param dateOrder true: "Date Order" (git log --date-order), false: "Ancestor Order" (git log --topo-order)
     */
    public record Options(boolean allBranches, boolean remotes, boolean dateOrder) {
        public static final Options DEFAULT = new Options(true, true, true);

        /** e.g. "all,remotes,date" */
        public String encode() {
            return (allBranches ? "all" : "current") + "," + (remotes ? "remotes" : "noremotes") + "," + (dateOrder ? "date" : "ancestor");
        }

        /** @return DEFAULT for null or garbage */
        public static Options decode(String s) {
            if (s == null) return DEFAULT;
            List<String> v = List.of(s.split(","));
            if (v.size() != 3) return DEFAULT;
            return new Options(!v.get(0).equals("current"), !v.get(1).equals("noremotes"), !v.get(2).equals("ancestor"));
        }
    }

    CommitLog(GitRepo repo, boolean workingCopy) {
        this(repo, workingCopy, Options.DEFAULT);
    }

    CommitLog(GitRepo repo, boolean workingCopy, Options options) {
        this.repo = repo;
        PointerByReference wp = new PointerByReference();
        check(git.git_revwalk_new(wp, repo.handle()), "revwalk");
        walk = wp.getValue();
        // topological alone keeps the commits of a branch together (--topo-order), with time: --date-order
        git.git_revwalk_sorting(walk, options.dateOrder() ? GIT_SORT_TOPOLOGICAL | GIT_SORT_TIME : GIT_SORT_TOPOLOGICAL);
        if (!repo.isHeadUnborn()) git.git_revwalk_push_head(walk);
        if (options.allBranches()) {
            git.git_revwalk_push_glob(walk, "refs/heads");
            if (options.remotes()) git.git_revwalk_push_glob(walk, "refs/remotes");
            git.git_revwalk_push_glob(walk, "refs/tags");
        }
        // lane 0 connects the "Uncommitted changes" row to HEAD
        if (workingCopy && !repo.isHeadUnborn()) layout.expect(repo.revparse("HEAD"));
    }

    public boolean isDone() {
        return done;
    }

    /** @return up to n more commits, empty when done */
    public List<CommitRow> next(int n) {
        List<CommitRow> rows = new ArrayList<>(n);
        if (done) return rows;
        GitOid oid = new GitOid();
        while (rows.size() < n) {
            int rc = git.git_revwalk_next(oid, walk);
            if (rc == LibGit2.GIT_ITEROVER) {
                done = true;
                break;
            }
            check(rc, "revwalk");
            oid.read();
            rows.add(read(oid.hex()));
        }
        return rows;
    }

    private CommitRow read(String hex) {
        CommitRow c = repo.commitRow(hex);
        GraphLayout.Step step = layout.add(hex, c.parents());
        return new CommitRow(hex, c.parents(), c.summary(), c.message(), c.author(), c.email(), c.time(),
                step.lane(), step.before(), step.after());
    }

    @Override
    public void close() {
        if (walk != null) {
            git.git_revwalk_free(walk);
            walk = null;
        }
    }

    /** lane assignment for the history graph */
    static final class GraphLayout {

        record Step(int lane, String[] before, String[] after) {}

        /** commit id expected next in each lane, null for a free lane */
        private final List<String> lanes = new ArrayList<>();

        Step add(String oid, List<String> parents) {
            String[] before = lanes.toArray(String[]::new);
            int lane = lanes.indexOf(oid);
            if (lane < 0) lane = allocate();
            // other lanes waiting for this commit merge into it
            for (int i = 0; i < lanes.size(); i++) {
                if (i != lane && oid.equals(lanes.get(i))) lanes.set(i, null);
            }
            lanes.set(lane, parents.isEmpty() ? null : parents.getFirst());
            for (int i = 1; i < parents.size(); i++) {
                String p = parents.get(i);
                if (!lanes.contains(p)) lanes.set(allocate(), p);
            }
            while (!lanes.isEmpty() && lanes.getLast() == null) lanes.removeLast();
            return new Step(lane, before, lanes.toArray(String[]::new));
        }

        /** reserves a lane expecting the commit */
        void expect(String oid) {
            lanes.set(allocate(), oid);
        }

        private int allocate() {
            int i = lanes.indexOf(null);
            if (i >= 0) return i;
            lanes.add(null);
            return lanes.size() - 1;
        }
    }
}
