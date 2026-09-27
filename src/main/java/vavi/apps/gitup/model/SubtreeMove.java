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
import java.util.List;
import java.util.function.BiConsumer;

import static java.lang.System.getLogger;


/**
 * moves a folder with its history from a repository to another one ("git move subtree inter projects").
 * <p>
 * libgit2 has no {@code git subtree}, so this runs the git command.
 * <pre>
 * cd source
 * git subtree split -P dir -b split
 * cd target
 * git checkout --orphan temp                    # a branch with no history
 * git pull source split                         # the commits of the subtree
 * git checkout branch                           # back to the original branch
 * git merge --allow-unrelated-histories temp    # merges the unrelated commits
 * git branch -d temp
 * </pre>
 * the files come at the top of the target. into a folder of the target
 * {@code git subtree add -P prefix source split} is used instead (the same fetch and merge, under the prefix).
 * <p>
 * afterwards the split branch is deleted and the folder is removed from the source when asked.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-27 nsano initial version <br>
 */
public final class SubtreeMove {

    private static final System.Logger logger = getLogger(SubtreeMove.class.getName());

    /** what is done to the moved folder in the source afterwards */
    public enum SourceCleanup {
        /** nothing, the folder stays */
        NONE,
        /** {@code git rm -r --cached}: no longer tracked (staged), the files stay on disk */
        UNTRACK,
        /** {@code git rm -r}: deleted (staged) */
        DELETE
    }

    /**
     * @param source the source working directory
     * @param dir the folder to move, relative to the source, "/" separated
     * @param target the target working directory
     * @param prefix the folder the files go to in the target, "" for the top (the merge of an unrelated history)
     * @param deleteSplit deletes the split branch of the source afterwards
     * @param cleanup what is done to the folder in the source afterwards
     */
    public record Plan(Path source, String dir, Path target, String prefix, boolean deleteSplit, SourceCleanup cleanup) {
        public Plan {
            dir = normalize(dir);
            prefix = normalize(prefix);
            if (dir.isEmpty()) throw new IllegalArgumentException("no folder to move");
        }
    }

    /** a git command failed */
    public static class GitException extends RuntimeException {
        public GitException(String message) {
            super(message);
        }
    }

    /** a git command run in a repository: "cd repo &amp;&amp; git ..." */
    public record Command(Path repo, String command) {}

    private final Plan plan;
    private final Git git;

    /** the branch made in the source by the split */
    private String split;
    /** the temporary branch in the target */
    private String temp;
    /** the branch of the target */
    private String branch;

    /** @param log receives each command (repository, command line) and then its output lines (null, line) */
    public SubtreeMove(Plan plan, BiConsumer<Path, String> log) {
        this.plan = plan;
        this.git = new Git(log);
    }

    public Plan plan() {
        return plan;
    }

    /** @return the split branch name, after {@link #run()} */
    public String splitBranch() {
        return split;
    }

    /** @throws GitException when the move cannot be done, the reason as the message */
    public void check() {
        Path s = plan.source(), t = plan.target();
        if (!Files.isDirectory(s)) throw new GitException("not found: " + s);
        if (!Files.isDirectory(t)) throw new GitException("not found: " + t);
        Path st = Path.of(git.quiet(s, "rev-parse", "--show-toplevel").strip());
        Path tt = Path.of(git.quiet(t, "rev-parse", "--show-toplevel").strip());
        if (same(st, tt)) throw new GitException("the source and the target are the same repository");
        if (git.quiet(s, "ls-tree", "-d", "HEAD", "--", plan.dir()).isBlank())
            throw new GitException("\"" + plan.dir() + "\" is not a committed folder of " + s.getFileName());
        branch = git.quietOrNull(t, "symbolic-ref", "--short", "-q", "HEAD");
        if (branch == null || branch.isBlank()) throw new GitException(t.getFileName() + " is not on a branch (detached HEAD)");
        branch = branch.strip();
        if (git.quietOrNull(t, "rev-parse", "--verify", "-q", "HEAD") == null)
            throw new GitException(t.getFileName() + " has no commit yet");
        if (!git.quiet(t, "status", "--porcelain", "--untracked-files=no").isBlank())
            throw new GitException(t.getFileName() + " has uncommitted changes, commit or stash them first");
        if (!plan.prefix().isEmpty()) {
            if (!git.quiet(t, "ls-tree", "HEAD", "--", plan.prefix()).isBlank() || Files.exists(t.resolve(plan.prefix())))
                throw new GitException("\"" + plan.prefix() + "\" already exists in " + t.getFileName());
        } else {
            // at the top the files are merged with the target's, the same paths would conflict
            List<String> moving = List.of(git.quiet(s, "ls-tree", "-r", "-z", "--name-only", "HEAD:" + plan.dir()).split("\0"));
            java.util.Set<String> existing = new java.util.HashSet<>(List.of(git.quiet(t, "ls-tree", "-r", "-z", "--name-only", "HEAD").split("\0")));
            List<String> both = moving.stream().filter(x -> !x.isEmpty() && existing.contains(x)).limit(5).toList();
            if (!both.isEmpty())
                throw new GitException(t.getFileName() + " already has " + String.join(", ", both) + (both.size() == 5 ? ", …" : ""));
        }
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    /**
     * does the move.
     *
     * @throws GitException when a command fails, the target is back on its branch then
     */
    public void run() {
        check();
        Path s = plan.source(), t = plan.target();
        String name = plan.dir().replaceAll("[^A-Za-z0-9._-]+", "-");
        split = unique(s, "split-" + name);

        // source
        git.run(s, "subtree", "split", "-P", plan.dir(), "-b", split);

        // target
        try {
            if (plan.prefix().isEmpty()) {
                temp = unique(t, "temp-" + name);
                git.run(t, "checkout", "--orphan", temp);
                git.run(t, "pull", "--no-rebase", "--no-edit", s.toString(), split);
                git.run(t, "checkout", branch);
                git.run(t, "merge", "--allow-unrelated-histories", "-m", message(), temp);
                git.run(t, "branch", "-d", temp);
            } else {
                git.run(t, "subtree", "add", "-P", plan.prefix(), "-m", message(), s.toString(), split);
            }
        } catch (GitException e) {
            rollbackTarget();
            git.tryRun(s, "branch", "-D", split);
            throw e;
        }

        // cleanup
        if (plan.deleteSplit()) git.run(s, "branch", "-D", split);
        switch (plan.cleanup()) {
            case UNTRACK -> git.run(s, "rm", "-r", "-q", "--cached", "--", plan.dir());
            case DELETE -> git.run(s, "rm", "-r", "-q", "--", plan.dir());
            case NONE -> {}
        }
    }

    /** the merge commit message */
    String message() {
        String to = plan.prefix().isEmpty() ? "" : " into '" + plan.prefix() + "'";
        return "Move '" + plan.dir() + "' from " + plan.source().getFileName() + to;
    }

    /** puts the target back on its branch, without the temporary branch */
    private void rollbackTarget() {
        Path t = plan.target();
        if (git.quietOrNull(t, "rev-parse", "-q", "--verify", "MERGE_HEAD") != null) git.tryRun(t, "merge", "--abort");
        String current = git.quietOrNull(t, "symbolic-ref", "--short", "-q", "HEAD");
        if (current == null || !current.strip().equals(branch)) git.tryRun(t, "checkout", "-f", branch);
        if (temp != null && git.quietOrNull(t, "rev-parse", "-q", "--verify", "refs/heads/" + temp) != null)
            git.tryRun(t, "branch", "-D", temp);
    }

    /** a branch name not used in the repository */
    private String unique(Path repo, String base) {
        String n = base;
        for (int i = 2; git.quietOrNull(repo, "rev-parse", "-q", "--verify", "refs/heads/" + n) != null; i++) n = base + "-" + i;
        return n;
    }

    /**
     * the committed folders of a repository (what can be moved), sorted.
     *
     * @return "/" separated paths relative to the working directory
     * @throws GitException when not a repository or no commit
     */
    public static List<String> folders(Path repo) {
        String out = new Git(null).quiet(repo, "ls-tree", "-d", "-r", "-z", "--name-only", "HEAD");
        return java.util.Arrays.stream(out.split("\0")).filter(x -> !x.isEmpty()).sorted().toList();
    }

    /** "a\b/c/" → "a/b/c" */
    static String normalize(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/').replaceAll("/+", "/");
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.equals(".") ? "" : p;
    }

    /** the equivalent commands of the plan, for a confirmation */
    public List<Command> commands() {
        Path s = plan.source(), t = plan.target();
        String name = plan.dir().replaceAll("[^A-Za-z0-9._-]+", "-");
        String sp = split != null ? split : "split-" + name;
        String tp = temp != null ? temp : "temp-" + name;
        String br = branch != null ? branch : "<branch>";
        List<Command> list = new ArrayList<>();
        list.add(new Command(s, "git subtree split -P " + CommandLog.quote(plan.dir()) + " -b " + sp));
        if (plan.prefix().isEmpty()) {
            list.add(new Command(t, "git checkout --orphan " + tp));
            list.add(new Command(t, "git pull " + CommandLog.quote(s.toString()) + " " + sp));
            list.add(new Command(t, "git checkout " + br));
            list.add(new Command(t, "git merge --allow-unrelated-histories -m " + CommandLog.quote(message()) + " " + tp));
            list.add(new Command(t, "git branch -d " + tp));
        } else {
            list.add(new Command(t, "git subtree add -P " + CommandLog.quote(plan.prefix()) + " -m " + CommandLog.quote(message())
                    + " " + CommandLog.quote(s.toString()) + " " + sp));
        }
        if (plan.deleteSplit()) list.add(new Command(s, "git branch -D " + sp));
        switch (plan.cleanup()) {
            case UNTRACK -> list.add(new Command(s, "git rm -r -q --cached -- " + CommandLog.quote(plan.dir())));
            case DELETE -> list.add(new Command(s, "git rm -r -q -- " + CommandLog.quote(plan.dir())));
            case NONE -> {}
        }
        return list;
    }

    /** runs the git command */
    static final class Git {

        /** the git command: {@code gitup.git}, homebrew's, then the one on the PATH */
        static final String EXECUTABLE = locate();

        private static String locate() {
            String p = System.getProperty("gitup.git");
            if (p != null && !p.isBlank()) return p;
            for (String c : List.of("/opt/homebrew/bin/git", "/usr/local/bin/git", "/usr/bin/git")) {
                if (Files.isExecutable(Path.of(c))) return c;
            }
            return "git";
        }

        private final BiConsumer<Path, String> log;

        Git(BiConsumer<Path, String> log) {
            this.log = log != null ? log : (r, l) -> {};
        }

        private record Result(int exit, String out, String err) {}

        private static Result exec(Path repo, String... args) {
            List<String> cmd = new ArrayList<>();
            cmd.add(EXECUTABLE);
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_EDITOR", "true");
            pb.environment().put("GIT_MERGE_AUTOEDIT", "no");
            pb.environment().put("LC_ALL", "C");
            try {
                Process p = pb.start();
                p.getOutputStream().close();
                // stderr in another thread, a full pipe would block the process
                StringBuilder err = new StringBuilder();
                Thread t = Thread.ofVirtual().start(() -> {
                    try {
                        err.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        logger.log(System.Logger.Level.DEBUG, e.getMessage(), e);
                    }
                });
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = p.waitFor();
                t.join();
                return new Result(exit, out, err.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitException("interrupted");
            }
        }

        /** runs a query, not logged. @throws GitException when failed */
        String quiet(Path repo, String... args) {
            Result r = exec(repo, args);
            if (r.exit() != 0) throw new GitException(error(args, r));
            return r.out();
        }

        /** runs a query, not logged. @return null when failed */
        String quietOrNull(Path repo, String... args) {
            Result r = exec(repo, args);
            return r.exit() == 0 ? r.out() : null;
        }

        /** runs a command, logged. @throws GitException when failed */
        void run(Path repo, String... args) {
            if (!tryRun(repo, args)) throw new GitException(lastError);
        }

        private String lastError;

        /** runs a command, logged. @return false when failed */
        boolean tryRun(Path repo, String... args) {
            StringBuilder sb = new StringBuilder("git");
            for (String a : args) sb.append(' ').append(CommandLog.quote(a));
            log.accept(repo, sb.toString());
            Result r = exec(repo, args);
            // "git subtree split" draws its progress with \r
            (r.out() + r.err()).lines().map(l -> l.substring(l.lastIndexOf('\r') + 1)).filter(l -> !l.isBlank())
                    .forEach(l -> log.accept(null, l));
            if (r.exit() != 0) lastError = error(args, r);
            return r.exit() == 0;
        }

        private static String error(String[] args, Result r) {
            String msg = (r.err() + r.out()).strip();
            return "git " + String.join(" ", args) + " failed (" + r.exit() + ")" + (msg.isEmpty() ? "" : ":\n" + msg);
        }
    }
}
