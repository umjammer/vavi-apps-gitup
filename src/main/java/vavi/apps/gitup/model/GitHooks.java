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
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * git hooks of a repository (local) and of the user (global, core.hooksPath in the global config).
 * a hook is enabled when its file is executable, git ignores it otherwise.
 * <p>
 * preset scripts are written as blocks between markers, so several presets share one hook file
 * and applying a preset again replaces its block.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-28 nsano initial version <br>
 */
public class GitHooks {

    /** where a hook lives */
    public enum Scope { GLOBAL, LOCAL }

    /** client side and server side hook names git knows */
    public static final List<String> CATEGORIES = List.of(
            "pre-commit", "prepare-commit-msg", "commit-msg", "post-commit",
            "pre-push", "pre-rebase", "post-rewrite", "post-checkout", "post-merge", "pre-merge-commit",
            "applypatch-msg", "pre-applypatch", "post-applypatch", "pre-auto-gc", "reference-transaction",
            "push-to-checkout", "sendemail-validate", "fsmonitor-watchman", "post-index-change",
            "pre-receive", "update", "proc-receive", "post-receive", "post-update");

    /** hooks git feeds on stdin */
    private static final Set<String> STDIN = Set.of("pre-push", "pre-receive", "post-receive", "post-rewrite", "reference-transaction", "proc-receive");

    /** a hook file (it may not exist yet) */
    public record Hook(Scope scope, String name, Path file) {
        public boolean exists() {
            return Files.isRegularFile(file);
        }
        public boolean enabled() {
            return Files.isExecutable(file);
        }
    }

    /** a ready made hook script block */
    public record Preset(String id, String category, String title, String description, String parameterLabel, String defaultParameter) {
        /** the block for the hook file, the parameter replaces {@code @PARAM@} */
        public String block(String parameter) {
            String body = switch (id) {
                case "main-branch-guard" -> MAIN_BRANCH_GUARD;
                case "bump-version-snapshot" -> BUMP_VERSION_SNAPSHOT;
                default -> throw new IllegalStateException(id);
            };
            return BEGIN + id + "\n" + body.replace("@PARAM@", parameter == null ? "" : parameter) + END + id + "\n";
        }
    }

    public static final List<Preset> PRESETS = List.of(
            new Preset("main-branch-guard", "pre-push", "Protected branch guard",
                    "rejects pushes to the protected branches", "Protected branches (space separated):", "main"),
            new Preset("bump-version-snapshot", "pre-push", "\"bump version\" SNAPSHOT check",
                    "rejects pushing a \"bump version\" commit whose pom.xml version is a -SNAPSHOT", null, null));

    /** the presets for the hook */
    public static List<Preset> presets(String category) {
        return PRESETS.stream().filter(p -> p.category().equals(category)).toList();
    }

    static final String BEGIN = "# >>> gitup preset: ";
    static final String END = "# <<< gitup preset: ";
    static final String MANAGED = "# managed by vavi-apps-gitup, blocks between the gitup preset markers are replaced by the app";
    static final String STDIN_LINE = "gitup_stdin=$(cat)";

    private static final String MAIN_BRANCH_GUARD = """
            gitup_protected="@PARAM@"
            printf '%s\\n' "$gitup_stdin" | while read -r local_ref local_sha remote_ref remote_sha; do
              [ -n "$remote_ref" ] || continue
              for b in $gitup_protected; do
                if [ "$remote_ref" = "refs/heads/$b" ]; then
                  echo "gitup: pushing to the protected branch '$b' is not allowed" >&2
                  exit 1
                fi
              done
            done || exit 1
            """;

    private static final String BUMP_VERSION_SNAPSHOT = """
            printf '%s\\n' "$gitup_stdin" | while read -r local_ref local_sha remote_ref remote_sha; do
              case "$local_sha" in *[!0]*) ;; *) continue ;; esac
              case "$remote_sha" in
                *[!0]*) git cat-file -e "$remote_sha" 2>/dev/null && range="$remote_sha..$local_sha" || range="$local_sha --not --remotes" ;;
                *) range="$local_sha --not --remotes" ;;
              esac
              for c in $(git rev-list $range); do
                if git log -1 --format=%s "$c" | grep -qi 'bump version'; then
                  v=$(git show "$c:pom.xml" 2>/dev/null | sed -e '/<parent>.*<\\/parent>/d' -e '/<parent>/,/<\\/parent>/d' | grep -m1 '<version>')
                  case "$v" in
                    *-SNAPSHOT*)
                      echo "gitup: $(git rev-parse --short "$c") is a \\"bump version\\" but pom.xml is $(echo $v)" >&2
                      exit 1 ;;
                  esac
                fi
              done
            done || exit 1
            """;

    private final Path workdir;
    private final Path gitDir;
    private final Path home;

    /**
     * @param workdir the working directory, git commands run there
     * @param gitDir the .git directory
     */
    public GitHooks(Path workdir, Path gitDir) {
        this(workdir, gitDir, Path.of(System.getProperty("user.home")));
    }

    GitHooks(Path workdir, Path gitDir, Path home) {
        this.workdir = workdir;
        this.gitDir = gitDir;
        this.home = home;
    }

    /** the local hooks directory: local core.hooksPath or .git/hooks */
    public Path localDir() {
        String v = config("--local", "core.hooksPath");
        return v != null ? resolve(v, workdir) : gitDir.resolve("hooks");
    }

    /** the global hooks directory (global core.hooksPath), null when not set */
    public Path globalDir() {
        String v = config("--global", "core.hooksPath");
        return v != null ? resolve(v, home) : null;
    }

    /** the directory git runs hooks of */
    public Path effectiveDir() {
        String v = git("rev-parse", "--git-path", "hooks");
        return v != null ? resolve(v, workdir) : localDir();
    }

    /** where a global hooks directory is made when there is none */
    public Path defaultGlobalDir() {
        return home.resolve(".config/git/hooks");
    }

    /** sets the global core.hooksPath */
    public void setGlobalDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String v = dir.startsWith(home) ? "~/" + home.relativize(dir) : dir.toString();
        if (run(List.of("git", "config", "--global", "core.hooksPath", v)) == null) throw new GitException("git config --global core.hooksPath failed");
    }

    /** the directory of the scope, null for global when not set */
    public Path dir(Scope scope) {
        return scope == Scope.GLOBAL ? globalDir() : localDir();
    }

    /** true when git runs hooks of the scope */
    public boolean active(Scope scope) {
        Path d = dir(scope);
        return d != null && same(d, effectiveDir());
    }

    /** the hooks there are (samples are not) */
    public List<Hook> list(Scope scope) {
        Path d = dir(scope);
        List<Hook> list = new ArrayList<>();
        if (d == null) return list;
        for (String c : CATEGORIES) {
            Path f = d.resolve(c);
            if (Files.isRegularFile(f)) list.add(new Hook(scope, c, f));
        }
        return list;
    }

    /** a hook object for the name, the file may not exist, null when the scope has no directory */
    public Hook hook(Scope scope, String name) {
        Path d = dir(scope);
        return d == null ? null : new Hook(scope, name, d.resolve(name));
    }

    public static String read(Hook hook) {
        try {
            return hook.exists() ? Files.readString(hook.file()) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** writes the script, a new file is made executable */
    public static void write(Hook hook, String script) {
        try {
            boolean created = !hook.exists();
            Files.createDirectories(hook.file().getParent());
            Files.writeString(hook.file(), script, StandardCharsets.UTF_8);
            if (created) setEnabled(hook, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void delete(Hook hook) {
        try {
            Files.deleteIfExists(hook.file());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** chmod +x / -x */
    public static void setEnabled(Hook hook, boolean enabled) {
        try {
            Set<PosixFilePermission> p = EnumSet.copyOf(Files.getPosixFilePermissions(hook.file()));
            Set<PosixFilePermission> x = EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE);
            if (enabled) p.addAll(x);
            else p.removeAll(x);
            Files.setPosixFilePermissions(hook.file(), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** a script for a new hook */
    public static String template(String name) {
        return "#!/bin/sh\n# " + name + " hook\n\nexit 0\n";
    }

    /** true when the script was made by {@link #applyPreset} (other blocks may be added) */
    public static boolean isManaged(String script) {
        return script.contains(MANAGED);
    }

    /** the ids of the presets in the script */
    public static List<String> presetsIn(String script) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("^" + Pattern.quote(BEGIN) + "(\\S+)$", Pattern.MULTILINE).matcher(script);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    /** the parameter a preset block was written with, null when absent */
    public static String parameterOf(String script, Preset preset) {
        if (preset.parameterLabel() == null) return null;
        Matcher m = Pattern.compile("^" + Pattern.quote(BEGIN + preset.id()) + "\\n\\w+=\"([^\"]*)\"", Pattern.MULTILINE).matcher(script);
        return m.find() ? m.group(1) : null;
    }

    /**
     * @param script the current script, empty or managed ones only (others are replaced)
     * @return the script with the preset block added, or its block replaced
     */
    public static String applyPreset(String script, Preset preset, String parameter) {
        String block = preset.block(parameter);
        if (!isManaged(script)) {
            return "#!/bin/sh\n" + MANAGED + "\n" + (STDIN.contains(preset.category()) ? STDIN_LINE + "\n" : "") + "\n" + block;
        }
        Matcher m = blockPattern(preset.id()).matcher(script);
        if (m.find()) return script.substring(0, m.start()) + block + script.substring(m.end());
        return script + (script.endsWith("\n") ? "" : "\n") + "\n" + block;
    }

    /** removes the preset block */
    public static String removePreset(String script, Preset preset) {
        return Pattern.compile("\\n?" + blockPattern(preset.id()).pattern(), Pattern.MULTILINE | Pattern.DOTALL).matcher(script).replaceFirst("");
    }

    private static Pattern blockPattern(String id) {
        return Pattern.compile("^" + Pattern.quote(BEGIN + id) + "$.*?^" + Pattern.quote(END + id) + "$\\n?", Pattern.MULTILINE | Pattern.DOTALL);
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private Path resolve(String v, Path base) {
        if (v.equals("~")) return home;
        if (v.startsWith("~/")) return home.resolve(v.substring(2));
        return base.resolve(v).normalize();
    }

    private String config(String scope, String key) {
        String v = git("config", scope, "--get", key);
        return v == null || v.isEmpty() ? null : v;
    }

    private String git(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return run(cmd);
    }

    /** @return trimmed stdout, null when git failed */
    private String run(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(workdir.toFile()).redirectErrorStream(false);
            if (!home.equals(Path.of(System.getProperty("user.home")))) { // tests
                pb.environment().put("HOME", home.toString());
                pb.environment().put("GIT_CONFIG_GLOBAL", home.resolve(".gitconfig").toString());
                pb.environment().remove("XDG_CONFIG_HOME");
            }
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.getErrorStream().readAllBytes();
            return p.waitFor() == 0 ? out : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
