/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import vavi.apps.gitup.jna.GitUpKitLocator;
import vavi.apps.gitup.model.CommitLog.CommitRow;
import vavi.apps.gitup.model.GitRepo.Ref;
import vavi.apps.gitup.model.GitRepo.IgnorePatterns;
import vavi.apps.gitup.model.GitRepo.IgnoreTarget;
import vavi.apps.gitup.model.GitRepo.PullResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * merge on pull, amend, stash, stop tracking, ignore, graph lane to HEAD.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
@EnabledIf("frameworkExists")
class GitRepoFeaturesTest {

    static boolean frameworkExists() {
        return GitUpKitLocator.find() != null;
    }

    @TempDir
    Path dir;

    String sh(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "core.excludesFile=" + dir.resolve("global-ignore")));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    /** a clone "b" whose main tracks origin/main of a bare "remote.git", both with one commit */
    Path setupClone() throws Exception {
        Path bare = dir.resolve("remote.git");
        Path a = dir.resolve("a");
        Files.createDirectories(bare);
        sh(bare, "init", "-q", "--bare", "-b", "main");
        sh(dir, "clone", "-q", bare.toString(), "a");
        sh(a, "checkout", "-q", "-b", "main");
        Files.writeString(a.resolve("f.txt"), "1\n2\n3\n4\n5\n");
        sh(a, "add", "f.txt");
        sh(a, "commit", "-q", "-m", "one");
        sh(a, "push", "-q", "-u", "origin", "main");
        sh(dir, "clone", "-q", bare.toString(), "b");
        Path b = dir.resolve("b");
        sh(b, "config", "user.name", "t");
        sh(b, "config", "user.email", "t@example.com");
        return b;
    }

    /** someone else pushes a change of line n */
    void pushFromA(int line, String text) throws Exception {
        Path a = dir.resolve("a");
        List<String> lines = new ArrayList<>(Files.readAllLines(a.resolve("f.txt")));
        lines.set(line - 1, text);
        Files.writeString(a.resolve("f.txt"), String.join("\n", lines) + "\n");
        sh(a, "commit", "-q", "-am", "a: " + text);
        sh(a, "push", "-q");
    }

    void commitInB(Path b, int line, String text) throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(b.resolve("f.txt")));
        lines.set(line - 1, text);
        Files.writeString(b.resolve("f.txt"), String.join("\n", lines) + "\n");
        sh(b, "commit", "-q", "-am", "b: " + text);
    }

    @Test
    void pullMerge() throws Exception {
        Path b = setupClone();
        pushFromA(1, "one");
        commitInB(b, 5, "five");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.MERGED, repo.pullFromUpstream());
            assertEquals(GitRepo.State.NONE, repo.state());
        }
        assertEquals("one\n2\n3\n4\nfive\n", Files.readString(b.resolve("f.txt")));
        assertEquals(3, sh(b, "log", "-1", "--format=%P %H").trim().split(" ").length, "merge commit has 2 parents");
        assertEquals("", sh(b, "status", "--porcelain"));
    }

    @Test
    void pullConflictThenCommit() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.CONFLICTS, repo.pullFromUpstream());
            assertEquals(GitRepo.State.MERGE, repo.state());
            assertTrue(repo.status().unstaged().stream().anyMatch(f -> f.kind() == FileChange.Kind.CONFLICTED));
            assertTrue(repo.mergeMessage().startsWith("Merge"), repo.mergeMessage());

            Files.writeString(b.resolve("f.txt"), "1\n2\nTHREE\n4\n5\n");
            repo.stage(List.of(new FileChange("f.txt", "f.txt", FileChange.Kind.MODIFIED, false)));
            repo.commit("merged\n");
            assertEquals(GitRepo.State.NONE, repo.state());
        }
        assertEquals(3, sh(b, "log", "-1", "--format=%P %H").trim().split(" ").length);
    }

    @Test
    void abortMerge() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.CONFLICTS, repo.pullFromUpstream());
            repo.abortMerge();
            assertEquals(GitRepo.State.NONE, repo.state());
        }
        assertEquals("", sh(b, "status", "--porcelain"));
        assertEquals("1\n2\nTHREE-B\n4\n5\n", Files.readString(b.resolve("f.txt")));
    }

    @Test
    void resolveTheirs() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.CONFLICTS, repo.pullFromUpstream());
            repo.resolveConflict("f.txt", false);
            assertTrue(repo.status().unstaged().stream().noneMatch(f -> f.kind() == FileChange.Kind.CONFLICTED));
        }
        assertEquals("1\n2\nTHREE-A\n4\n5\n", Files.readString(b.resolve("f.txt")));
        assertEquals("M  f.txt\n", sh(b, "status", "--porcelain"));
    }

    @Test
    void resolveOurs() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.CONFLICTS, repo.pullFromUpstream());
            repo.resolveConflict("f.txt", true);
            repo.commit("merged, ours\n");
        }
        assertEquals("1\n2\nTHREE-B\n4\n5\n", Files.readString(b.resolve("f.txt")));
        assertEquals("", sh(b, "status", "--porcelain"));
    }

    @Test
    void pullRebase() throws Exception {
        Path b = setupClone();
        pushFromA(1, "one");
        commitInB(b, 5, "five");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertFalse(repo.isPullRebaseConfigured());
            sh(b, "config", "pull.rebase", "true");
            assertTrue(repo.isPullRebaseConfigured());
            assertEquals(PullResult.REBASED, repo.pullFromUpstream(true));
        }
        assertEquals("b: five\na: one\none", sh(b, "log", "--format=%s").strip(), "linear, no merge commit");
        assertEquals("one\n2\n3\n4\nfive\n", Files.readString(b.resolve("f.txt")));
        assertEquals("", sh(b, "status", "--porcelain"));
        assertEquals("main", sh(b, "rev-parse", "--abbrev-ref", "HEAD").strip());
    }

    @Test
    void pullRebaseConflictContinue() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        commitInB(b, 5, "five");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.REBASE_CONFLICTS, repo.pullFromUpstream(true));
            assertEquals(GitRepo.State.REBASE, repo.state());
            assertEquals(List.of("f.txt"), repo.conflictedPaths());
            assertThrows(GitException.class, repo::continueRebase, "conflicts not resolved yet");

            repo.resolveConflict("f.txt", true); // ours: the upstream side while rebasing
            Files.writeString(b.resolve("f.txt"), "1\n2\nTHREE-AB\n4\n5\n");
            repo.stage(List.of(new FileChange("f.txt", "f.txt", FileChange.Kind.MODIFIED, false)));
            assertTrue(repo.continueRebase());
            assertEquals(GitRepo.State.NONE, repo.state());
        }
        assertEquals("b: five\nb: THREE-B\na: THREE-A\none", sh(b, "log", "--format=%s").strip());
        assertEquals("1\n2\nTHREE-AB\n4\nfive\n", Files.readString(b.resolve("f.txt")));
        assertEquals("", sh(b, "status", "--porcelain"));
        assertEquals("main", sh(b, "rev-parse", "--abbrev-ref", "HEAD").strip());
    }

    @Test
    void pullRebaseConflictAbort() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        sh(b, "fetch", "-q");
        String before = sh(b, "rev-parse", "HEAD");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.REBASE_CONFLICTS, repo.pullFromUpstream(true));
            repo.abortRebase();
            assertEquals(GitRepo.State.NONE, repo.state());
        }
        assertEquals(before, sh(b, "rev-parse", "HEAD"));
        assertEquals("", sh(b, "status", "--porcelain"));
        assertEquals("main", sh(b, "rev-parse", "--abbrev-ref", "HEAD").strip());
    }

    @Test
    void pullRebaseRefusesDirty() throws Exception {
        Path b = setupClone();
        pushFromA(1, "one");
        commitInB(b, 5, "five");
        sh(b, "fetch", "-q");
        Files.writeString(b.resolve("f.txt"), "dirty\n");
        try (GitRepo repo = new GitRepo(b)) {
            GitException e = assertThrows(GitException.class, () -> repo.pullFromUpstream(true));
            assertTrue(e.getMessage().contains("stash"), e.getMessage());
        }
        assertEquals("dirty\n", Files.readString(b.resolve("f.txt")));
    }

    @Test
    void publishedAndCommitRow() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        try (GitRepo repo = new GitRepo(b)) {
            String head = repo.headOid();
            String first = repo.revparse("HEAD~1");
            assertFalse(repo.isPublished(head));
            assertTrue(repo.isPublished(first));
            CommitRow r = repo.commitRow(head);
            assertEquals("b: two", r.summary());
            assertEquals(List.of(first), r.parents());
        }
    }

    /** a commit of a file in b at the given time (committer and author dates), on the current branch */
    void commitAt(Path b, String name, long epoch) throws Exception {
        Files.writeString(b.resolve(name + ".txt"), name + "\n");
        sh(b, "add", ".");
        ProcessBuilder pb = new ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", name).directory(b.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_COMMITTER_DATE", epoch + " +0000");
        pb.environment().put("GIT_AUTHOR_DATE", epoch + " +0000");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
    }

    static List<String> summaries(GitRepo repo, CommitLog.Options options) {
        try (CommitLog log = repo.log(false, options)) {
            return log.next(100).stream().map(CommitRow::summary).toList();
        }
    }

    /** SourceTree's dropdowns above the log */
    @Test
    void logOptions() throws Exception {
        Path b = setupClone(); // "one" on main and origin/main
        long t = 1_700_000_000L;
        sh(b, "checkout", "-q", "-b", "topic");
        commitAt(b, "topic 1", t + 100);
        sh(b, "checkout", "-q", "main");
        commitAt(b, "main 1", t + 200);
        sh(b, "checkout", "-q", "topic");
        commitAt(b, "topic 2", t + 300);
        sh(b, "checkout", "-q", "main");
        commitAt(b, "main 2", t + 400);
        pushFromA(3, "three"); // only on origin/main after the fetch
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            List<String> all = summaries(repo, CommitLog.Options.DEFAULT);
            assertTrue(all.contains("a: three"), "remote branches shown");
            assertTrue(all.containsAll(List.of("topic 1", "topic 2", "main 1", "main 2")));

            List<String> noRemotes = summaries(repo, new CommitLog.Options(true, false, true));
            assertFalse(noRemotes.contains("a: three"), "remote branches hidden");
            assertTrue(noRemotes.containsAll(List.of("topic 1", "topic 2")), "other local branches kept");

            assertEquals(List.of("main 2", "main 1", "one"), summaries(repo, new CommitLog.Options(false, true, true)), "current branch only");

            // date order interleaves the branches by time, ancestor order keeps each branch together
            List<String> local = List.of("main 2", "main 1", "topic 2", "topic 1");
            assertEquals(List.of("main 2", "topic 2", "main 1", "topic 1"),
                    summaries(repo, new CommitLog.Options(true, false, true)).stream().filter(local::contains).toList());
            List<String> ancestor = summaries(repo, new CommitLog.Options(true, false, false)).stream().filter(local::contains).toList();
            assertEquals(Math.abs(ancestor.indexOf("main 2") - ancestor.indexOf("main 1")), 1, "main together: " + ancestor);
            assertEquals(Math.abs(ancestor.indexOf("topic 2") - ancestor.indexOf("topic 1")), 1, "topic together: " + ancestor);
        }
        assertEquals(CommitLog.Options.DEFAULT, CommitLog.Options.decode(null));
        assertEquals(CommitLog.Options.DEFAULT, CommitLog.Options.decode("garbage"));
        CommitLog.Options o = new CommitLog.Options(false, false, false);
        assertEquals(o, CommitLog.Options.decode(o.encode()));
    }

    /** SourceTree's "origin/HEAD" label: in the log labels, not among the branches */
    @Test
    void remoteHead() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        try (GitRepo repo = new GitRepo(b)) {
            String originMain = repo.revparse("origin/main");
            List<Ref> heads = repo.remoteHeads();
            assertEquals(List.of("origin/HEAD"), heads.stream().map(Ref::shorthand).toList());
            assertEquals(Ref.Kind.REMOTE, heads.getFirst().kind());
            assertEquals(originMain, heads.getFirst().target(), "resolved to what it points to");
            assertFalse(repo.refs().stream().anyMatch(r -> r.shorthand().equals("origin/HEAD")), "not a branch");
            assertEquals(List.of("origin/main", "origin/HEAD"),
                    repo.refsByTarget().get(originMain).stream().map(Ref::shorthand).filter(n -> n.startsWith("origin/")).toList());
        }
    }

    /** the repository browser's counts */
    @Test
    void aheadBehind() throws Exception {
        Path b = setupClone();
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(new GitRepo.AheadBehind("main", "origin/main", 0, 0), repo.aheadBehind());
        }
        commitInB(b, 2, "two");
        commitInB(b, 3, "three");
        pushFromA(5, "five");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(new GitRepo.AheadBehind("main", "origin/main", 2, 1), repo.aheadBehind());
        }
        sh(b, "checkout", "-q", "-b", "local");
        try (GitRepo repo = new GitRepo(b)) {
            assertNull(repo.aheadBehind(), "no upstream");
        }
        sh(b, "checkout", "-q", "--detach");
        try (GitRepo repo = new GitRepo(b)) {
            assertNull(repo.aheadBehind(), "detached");
        }
    }

    /** protect pushed commits: where a commit is pushed, which branch moves drop pushed commits */
    @Test
    void pushedCommits() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        try (GitRepo repo = new GitRepo(b)) {
            String head = repo.headOid();
            String first = repo.revparse("HEAD~1");
            assertEquals(List.of(), repo.publishedIn(head));
            assertEquals(List.of("origin/main"), repo.publishedIn(first), "origin/HEAD is not listed");
            assertTrue(repo.contains(head, first));
            assertFalse(repo.contains(first, head));

            // resetting main to origin/main drops only the unpushed commit
            assertEquals(Map.of(), repo.droppedPublished(Map.of("refs/heads/main", first)));
        }
        sh(b, "push", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            String head = repo.headOid();
            String first = repo.revparse("HEAD~1");
            assertEquals(Map.of("main", List.of("origin/main")), repo.droppedPublished(Map.of("refs/heads/main", first)));
            assertEquals(Map.of(), repo.droppedPublished(Map.of("refs/heads/main", head)), "not moved");
            assertEquals(Map.of(), repo.droppedPublished(Map.of("refs/heads/other", first)), "no such branch");
        }
        commitInB(b, 3, "three");
        try (GitRepo repo = new GitRepo(b)) {
            // forward (the new tip contains the pushed tip) drops nothing
            assertEquals(Map.of(), repo.droppedPublished(Map.of("refs/heads/main", repo.headOid())));
        }
    }

    /** a multiple selection shows the changes of the whole range */
    @Test
    void rangeFiles() throws Exception {
        Path b = setupClone();
        commitInB(b, 1, "ONE");
        Files.writeString(b.resolve("g.txt"), "g\n");
        sh(b, "add", "g.txt");
        sh(b, "commit", "-q", "-m", "add g");
        commitInB(b, 5, "FIVE");
        try (GitRepo repo = new GitRepo(b)) {
            String newest = repo.revparse("HEAD");
            String oldest = repo.revparse("HEAD~2");
            List<FileChange> files = repo.rangeFiles(oldest, newest);
            assertEquals(List.of("f.txt", "g.txt"), files.stream().map(FileChange::path).sorted().toList());
            FileChange f = files.stream().filter(x -> x.path().equals("f.txt")).findFirst().orElseThrow();
            try (LazyPatch p = repo.openPatch(oldest, newest, f)) {
                long changes = java.util.stream.IntStream.range(0, p.rowCount()).mapToObj(p::row).filter(LazyPatch.Row::isChange).count();
                assertEquals(4, changes, "line 1 and line 5 replaced, from before the oldest to the newest");
            }
            assertEquals(List.of("f.txt"), repo.rangeFiles(newest, newest).stream().map(FileChange::path).toList());
        }
    }

    @Test
    void undoCommitIsSoft() throws Exception {
        Path b = setupClone();
        String before = sh(b, "rev-parse", "HEAD").strip();
        Files.writeString(b.resolve("g.txt"), "g\n");
        try (GitRepo repo = new GitRepo(b)) {
            repo.stage(repo.status().unstaged());
            GitRepo.RefSnapshot s = repo.snapshotRefs("Commit", true);
            repo.commit("g\n");
            assertFalse(before.equals(repo.headOid()));
            repo.restore(s);
            assertEquals(before, repo.headOid());
        }
        assertEquals("A  g.txt\n", sh(b, "status", "--porcelain"), "the committed changes are staged again");
        assertEquals("main", sh(b, "rev-parse", "--abbrev-ref", "HEAD").strip());
    }

    @Test
    void undoRewriteIsHard() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        commitInB(b, 4, "four");
        String before = sh(b, "rev-parse", "HEAD").strip();
        try (GitRepo repo = new GitRepo(b)) {
            GitRepo.RefSnapshot s = repo.snapshotRefs("Delete Commit", false);
            vavi.apps.gitup.objc.HistoryOps.delete(b, repo.revparse("HEAD~1"));
            repo.resetHardToHead();
            assertEquals("1\n2\n3\nfour\n5\n", Files.readString(b.resolve("f.txt")));

            Files.writeString(b.resolve("f.txt"), "dirty\n");
            assertThrows(GitException.class, () -> repo.restore(s), "needs a clean working copy");
            repo.discard(repo.status().unstaged());

            repo.restore(s);
            assertEquals(before, repo.headOid());
        }
        assertEquals("1\ntwo\n3\nfour\n5\n", Files.readString(b.resolve("f.txt")));
        assertEquals("", sh(b, "status", "--porcelain"));
    }

    @Test
    void remotes() throws Exception {
        Path b = setupClone();
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(List.of("origin"), repo.remotes().stream().map(GitRepo.Remote::name).toList());
            repo.createRemote("upstream", "https://example.com/u.git");
            assertThrows(GitException.class, () -> repo.createRemote("bad name", "x"));
            repo.editRemote("upstream", "mirror", "https://example.com/m.git", "git@example.com:m.git");
            GitRepo.Remote m = repo.remotes().stream().filter(r -> r.name().equals("mirror")).findFirst().orElseThrow();
            assertEquals("https://example.com/m.git", m.url());
            assertEquals("git@example.com:m.git", m.pushUrl());
            repo.editRemote("mirror", "mirror", "https://example.com/m2.git", "");
            assertNull(repo.remotes().stream().filter(r -> r.name().equals("mirror")).findFirst().orElseThrow().pushUrl());

            // renaming origin moves its remote branches and main's upstream
            repo.editRemote("origin", "home", repo.remotes().getFirst().url(), null);
            assertEquals("home/main", repo.upstream("main"));
            repo.removeRemote("mirror");
            assertEquals(List.of("home"), repo.remotes().stream().map(GitRepo.Remote::name).toList());
        }
        assertEquals("home\n", sh(b, "remote"));
    }

    @Test
    void renameAndDeleteBranch() throws Exception {
        Path b = setupClone();
        sh(b, "branch", "topic");
        sh(b, "checkout", "-q", "-b", "wip");
        commitInB(b, 2, "unmerged");
        sh(b, "checkout", "-q", "main");
        try (GitRepo repo = new GitRepo(b)) {
            repo.renameBranch("topic", "feature/x");
            assertThrows(GitException.class, () -> repo.renameBranch("feature/x", "bad..name"));
            assertTrue(repo.isMergedIntoHead("feature/x"));
            repo.deleteBranch("feature/x", false);

            assertFalse(repo.isMergedIntoHead("wip"));
            GitException e = assertThrows(GitException.class, () -> repo.deleteBranch("wip", false));
            assertTrue(e.getMessage().contains("not merged"), e.getMessage());
            repo.deleteBranch("wip", true);
            assertThrows(GitException.class, () -> repo.deleteBranch("main", true), "the checked out branch");
        }
        assertEquals("* main", sh(b, "branch").strip());
    }

    @Test
    void resetModes() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        String first = sh(b, "rev-parse", "HEAD~1").strip();
        try (GitRepo repo = new GitRepo(b)) {
            repo.reset(first, vavi.apps.gitup.jna.LibGit2.GIT_RESET_SOFT);
            assertEquals(first, repo.headOid());
            assertEquals("M  f.txt\n", sh(b, "status", "--porcelain"), "soft: the change is staged");
            sh(b, "commit", "-q", "-m", "again");

            repo.reset(first, vavi.apps.gitup.jna.LibGit2.GIT_RESET_MIXED);
            assertEquals(" M f.txt\n", sh(b, "status", "--porcelain"), "mixed: the change is unstaged");
            sh(b, "commit", "-q", "-am", "again");

            repo.reset(first, vavi.apps.gitup.jna.LibGit2.GIT_RESET_HARD);
            assertEquals("", sh(b, "status", "--porcelain"), "hard: the change is gone");
            assertEquals("1\n2\n3\n4\n5\n", Files.readString(b.resolve("f.txt")));
        }
    }

    /** undo of a mixed reset restores the index too, redo goes forward again */
    @Test
    void undoRedoMixedReset() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        String second = sh(b, "rev-parse", "HEAD").strip();
        String first = sh(b, "rev-parse", "HEAD~1").strip();
        try (GitRepo repo = new GitRepo(b)) {
            GitRepo.RefSnapshot before = repo.snapshotRefs("Reset", GitRepo.Restore.INDEX);
            repo.reset(first, vavi.apps.gitup.jna.LibGit2.GIT_RESET_MIXED);
            assertEquals(" M f.txt\n", sh(b, "status", "--porcelain"));

            GitRepo.RefSnapshot forward = repo.snapshotRefs("Reset", GitRepo.Restore.INDEX); // what the UI keeps for redo
            repo.restore(before);
            assertEquals(second, repo.headOid());
            assertEquals("", sh(b, "status", "--porcelain"), "index back to the restored HEAD");

            repo.restore(forward);
            assertEquals(first, repo.headOid());
            assertEquals(" M f.txt\n", sh(b, "status", "--porcelain"));
        }
    }

    /** undo of a branch deletion brings the branch back */
    @Test
    void undoDeleteBranch() throws Exception {
        Path b = setupClone();
        sh(b, "branch", "topic");
        try (GitRepo repo = new GitRepo(b)) {
            GitRepo.RefSnapshot s = repo.snapshotRefs("Delete Branch", GitRepo.Restore.REFS);
            repo.deleteBranch("topic", false);
            repo.restore(s);
            assertTrue(repo.refs().stream().anyMatch(r -> r.shorthand().equals("topic")));
        }
    }

    @Test
    void checkoutCommitAndForce() throws Exception {
        Path b = setupClone();
        commitInB(b, 2, "two");
        String first = sh(b, "rev-parse", "HEAD~1").strip();
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(List.of("main"), repo.branchesAt(repo.headOid()));
            repo.checkoutDetached(first, false);
            assertTrue(repo.isHeadDetached());
            assertEquals(first, repo.headOid());
            assertEquals("1\n2\n3\n4\n5\n", Files.readString(b.resolve("f.txt")));

            Files.writeString(b.resolve("f.txt"), "local change\n");
            assertThrows(GitException.class, () -> repo.checkout("main", false), "a conflicting local change stops a safe checkout");
            repo.checkout("main", true);
            assertEquals("main", repo.headBranch());
        }
        assertEquals("", sh(b, "status", "--porcelain"), "force discarded the change");
    }

    @Test
    void mergeOptions() throws Exception {
        Path b = setupClone();
        sh(b, "checkout", "-q", "-b", "topic");
        commitInB(b, 1, "ONE");
        String topic = sh(b, "rev-parse", "HEAD").strip();
        sh(b, "checkout", "-q", "main");
        try (GitRepo repo = new GitRepo(b)) {
            // fast-forward possible, but a merge commit is asked for
            assertEquals(GitRepo.MergeResult.MERGED, repo.merge(topic, "Merge topic\n", true, true));
            assertEquals(3, sh(b, "log", "-1", "--format=%P %H").strip().split(" ").length);
            assertEquals("Merge topic", sh(b, "log", "-1", "--format=%s").strip());
            assertEquals(GitRepo.MergeResult.UP_TO_DATE, repo.merge(topic, "x\n", false, true));
        }
        // diverged, not committed: left merging
        sh(b, "checkout", "-q", "topic");
        commitInB(b, 2, "TWO");
        String topic2 = sh(b, "rev-parse", "HEAD").strip();
        sh(b, "checkout", "-q", "main");
        commitInB(b, 5, "FIVE");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(GitRepo.MergeResult.NOT_COMMITTED, repo.merge(topic2, "x\n", false, false));
            assertEquals(GitRepo.State.MERGE, repo.state());
            repo.commit("merged later\n");
            assertEquals(GitRepo.State.NONE, repo.state());
        }
        assertEquals("ONE\nTWO\n3\n4\nFIVE\n", Files.readString(b.resolve("f.txt")));
    }

    @Test
    void cherryPick() throws Exception {
        Path b = setupClone();
        sh(b, "checkout", "-q", "-b", "topic");
        Files.writeString(b.resolve("g.txt"), "g\n");
        sh(b, "add", "g.txt");
        sh(b, "-c", "user.name=someone", "-c", "user.email=someone@example.com", "commit", "-q", "-m", "add g");
        String g = sh(b, "rev-parse", "HEAD").strip();
        commitInB(b, 3, "CONFLICT-A");
        String conflicting = sh(b, "rev-parse", "HEAD").strip();
        sh(b, "checkout", "-q", "main");
        commitInB(b, 3, "CONFLICT-B");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(GitRepo.CherryPickResult.COMMITTED, repo.cherryPick(g, true));
            assertEquals("add g", sh(b, "log", "-1", "--format=%s").strip());
            assertEquals("someone", sh(b, "log", "-1", "--format=%an").strip(), "the original author is kept");
            assertEquals(GitRepo.State.NONE, repo.state());

            assertEquals(GitRepo.CherryPickResult.CONFLICTS, repo.cherryPick(conflicting, true));
            assertEquals(GitRepo.State.CHERRY_PICK, repo.state());
            repo.abortMerge();
            assertEquals(GitRepo.State.NONE, repo.state());
            assertEquals("", sh(b, "status", "--porcelain"));
        }
    }

    /** SourceTree's "Reverse hunk" / reverse lines of a commit in the log */
    @Test
    void reverseCommitHunkAndLines() throws Exception {
        Path b = setupClone();
        List<String> lines = new ArrayList<>(Files.readAllLines(b.resolve("f.txt")));
        lines.set(0, "ONE");
        lines.set(4, "FIVE");
        lines.add("six");
        Files.writeString(b.resolve("f.txt"), String.join("\n", lines) + "\n");
        sh(b, "commit", "-q", "-am", "change");
        Files.writeString(b.resolve("g.txt"), "g\n"); // a later commit elsewhere, the reverse still applies
        sh(b, "add", "g.txt");
        sh(b, "commit", "-q", "-m", "later");
        try (GitRepo repo = new GitRepo(b)) {
            String change = repo.revparse("HEAD~1");
            FileChange f = repo.commitFiles(change).getFirst();
            try (LazyPatch p = repo.openPatch(change, f)) {
                // the whole patch: lines 1 and 5 changed, six added (one or two hunks)
                BitSet all = PartialPatchBuilder.all(p);
                // reverse only the "+six" line first
                BitSet six = new BitSet();
                for (int r = 0; r < p.rowCount(); r++) if (p.row(r).content().equals("six\n")) six.set(r);
                repo.reverseLines(p, six);
                assertEquals("ONE\n2\n3\n4\nFIVE\n", Files.readString(b.resolve("f.txt")));
            }
            // back to the commit's state, then reverse the whole patch (every hunk)
            sh(b, "checkout", "--", "f.txt");
            try (LazyPatch p = repo.openPatch(change, f)) {
                repo.reverseLines(p, PartialPatchBuilder.all(p));
            }
            assertEquals("1\n2\n3\n4\n5\n", Files.readString(b.resolve("f.txt")));
            // the reversed state no longer matches the commit: a second reverse does not apply
            try (LazyPatch p = repo.openPatch(change, f)) {
                GitException e = assertThrows(GitException.class, () -> repo.reverseLines(p, PartialPatchBuilder.all(p)));
                assertTrue(e.getMessage().startsWith("cannot reverse in f.txt"), e.getMessage());
            }
        }
        assertEquals(" M f.txt\n", sh(b, "status", "--porcelain"), "the working copy only, the index is untouched");
    }

    /** a merge commit against its first parent brings what the merged branch changed (git cherry-pick -m 1) */
    @Test
    void cherryPickMergeCommit() throws Exception {
        Path b = setupClone();
        sh(b, "checkout", "-q", "-b", "feature");
        Files.writeString(b.resolve("g.txt"), "g\n");
        sh(b, "add", "g.txt");
        sh(b, "commit", "-q", "-m", "feature work");
        sh(b, "checkout", "-q", "main");
        sh(b, "checkout", "-q", "-b", "integration");
        sh(b, "merge", "-q", "--no-ff", "-m", "merge feature", "feature");
        String merge = sh(b, "rev-parse", "HEAD").strip();
        sh(b, "checkout", "-q", "main");
        try (GitRepo repo = new GitRepo(b)) {
            assertThrows(GitException.class, () -> repo.cherryPick(merge, true), "a merge needs the parent");
            assertEquals(GitRepo.CherryPickResult.COMMITTED, repo.cherryPick(merge, true, 1));
        }
        assertEquals("g\n", Files.readString(b.resolve("g.txt")));
        assertEquals("merge feature", sh(b, "log", "-1", "--format=%s").strip());
        assertEquals(1, sh(b, "log", "-1", "--format=%P").strip().split(" ").length, "a normal commit, not a merge");
        assertEquals("", sh(b, "status", "--porcelain"));
    }

    /** contents for external diff / merge tools */
    @Test
    void contents() throws Exception {
        Path b = setupClone();
        pushFromA(3, "THREE-A");
        commitInB(b, 3, "THREE-B");
        sh(b, "fetch", "-q");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals("1\n2\nTHREE-B\n4\n5\n", new String(repo.contentAt("HEAD", "f.txt")));
            assertEquals("1\n2\n3\n4\n5\n", new String(repo.contentAt("HEAD~1", "f.txt")));
            assertNull(repo.contentAt("HEAD", "none.txt"));
            assertEquals("1\n2\nTHREE-B\n4\n5\n", new String(repo.indexContent("f.txt", 0)));
            assertEquals(PullResult.CONFLICTS, repo.pullFromUpstream());
            assertEquals("1\n2\n3\n4\n5\n", new String(repo.indexContent("f.txt", 1)), "base");
            assertEquals("1\n2\nTHREE-B\n4\n5\n", new String(repo.indexContent("f.txt", 2)), "ours");
            assertEquals("1\n2\nTHREE-A\n4\n5\n", new String(repo.indexContent("f.txt", 3)), "theirs");
        }
    }

    @Test
    void pullUpToDate() throws Exception {
        Path b = setupClone();
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals(PullResult.UP_TO_DATE, repo.pullFromUpstream());
        }
    }

    @Test
    void amend() throws Exception {
        Path b = setupClone();
        String before = sh(b, "rev-parse", "HEAD").trim();
        Files.writeString(b.resolve("g.txt"), "g\n");
        try (GitRepo repo = new GitRepo(b)) {
            assertEquals("one\n", repo.headMessage());
            repo.stage(repo.status().unstaged());
            repo.amend("one amended\n");
        }
        assertEquals("one amended", sh(b, "log", "-1", "--format=%s").trim());
        assertEquals("1", sh(b, "rev-list", "--count", "HEAD").trim(), "replaced, not added");
        assertFalse(before.equals(sh(b, "rev-parse", "HEAD").trim()));
        assertTrue(sh(b, "show", "--name-only", "--format=").contains("g.txt"));
    }

    @Test
    void stash() throws Exception {
        Path b = setupClone();
        Files.writeString(b.resolve("f.txt"), "changed\n");
        Files.writeString(b.resolve("u.txt"), "untracked\n");
        try (GitRepo repo = new GitRepo(b)) {
            assertTrue(repo.stashSave("wip", false, true));
            assertEquals("", sh(b, "status", "--porcelain"));
            List<GitRepo.Stash> list = repo.stashes();
            assertEquals(1, list.size());
            assertTrue(list.getFirst().message().contains("wip"), list.getFirst().message());
            assertFalse(repo.stashSave("nothing", false, false), "nothing to stash");
            repo.stashApply(0);
            assertEquals("changed\n", Files.readString(b.resolve("f.txt")));
            assertEquals(1, repo.stashes().size());
            repo.discard(repo.status().unstaged());
            repo.stashPop(0);
            assertEquals(0, repo.stashes().size());
            assertEquals("untracked\n", Files.readString(b.resolve("u.txt")));
        }
    }

    @Test
    void stopTracking() throws Exception {
        Path b = setupClone();
        try (GitRepo repo = new GitRepo(b)) {
            repo.stopTracking(List.of(new FileChange("f.txt", "f.txt", FileChange.Kind.MODIFIED, false)));
        }
        assertTrue(Files.exists(b.resolve("f.txt")));
        assertEquals("D  f.txt\n?? f.txt\n", sh(b, "status", "--porcelain"));
    }

    @Test
    void ignorePatterns() {
        assertEquals("/src/a.log", IgnorePatterns.exact("src/a.log"));
        assertEquals("*.log", IgnorePatterns.extension("src/a.log"));
        assertNull(IgnorePatterns.extension("src/Makefile"));
        assertNull(IgnorePatterns.extension(".env"));
        assertEquals("/src/build/", IgnorePatterns.beneath("src/build"));
        assertEquals(List.of("a/b", "a"), IgnorePatterns.parents("a/b/c.txt"));
        assertEquals("/\\#x\\[1]\\*", IgnorePatterns.exact("#x[1]*"));
        assertEquals("/a\\ ", IgnorePatterns.exact("a "));
    }

    @Test
    void ignore() throws Exception {
        Path b = setupClone();
        Files.createDirectories(b.resolve("build"));
        Files.writeString(b.resolve("build/x.o"), "x");
        Files.writeString(b.resolve("a.log"), "x");
        Files.writeString(b.resolve("keep.txt"), "x");
        try (GitRepo repo = new GitRepo(b)) {
            assertFalse(repo.isIgnored("a.log"));
            repo.ignore(IgnorePatterns.extension("a.log"), IgnoreTarget.REPOSITORY);
            repo.ignore(IgnorePatterns.beneath("build"), IgnoreTarget.LOCAL);
            repo.ignore(IgnorePatterns.beneath("build"), IgnoreTarget.LOCAL); // no duplicate
            assertTrue(repo.isIgnored("a.log"));
            assertTrue(repo.isIgnored("build/x.o"));
            assertEquals(b.resolve(".gitignore").toRealPath(), repo.ignoreFile(IgnoreTarget.REPOSITORY).toRealPath());
        }
        assertEquals("*.log\n", Files.readString(b.resolve(".gitignore")));
        assertTrue(Files.readString(b.resolve(".git/info/exclude")).endsWith("/build/\n"));
        assertEquals(1, Files.readString(b.resolve(".git/info/exclude")).lines().filter("/build/"::equals).count());
        String status = sh(b, "status", "--porcelain");
        assertFalse(status.contains("a.log"), status);
        assertFalse(status.contains("build"), status);
        assertTrue(status.contains("keep.txt"), status);
    }

    /** with a working copy row, HEAD's row starts with an edge from the top in lane 0 */
    @Test
    void graphLaneToHead() throws Exception {
        Path b = setupClone();
        sh(b, "checkout", "-q", "-b", "topic");
        commitInB(b, 2, "two");
        sh(b, "checkout", "-q", "main");
        try (GitRepo repo = new GitRepo(b); CommitLog log = repo.log(true)) {
            List<CommitRow> rows = log.next(10);
            String head = repo.headOid();
            // topic's commit comes first and passes lane 0 through, then HEAD in lane 0
            assertEquals(head, rows.getFirst().before()[0]);
            CommitRow headRow = rows.stream().filter(r -> r.oid().equals(head)).findFirst().orElseThrow();
            assertEquals(0, headRow.lane());
            assertEquals(head, headRow.before()[0]);
        }
    }
}
