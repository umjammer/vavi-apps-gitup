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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * git hooks of a repository (local) and of the user (global, core.hooksPath in the global config).
 * a hook is enabled when its file is executable, git ignores it otherwise.
 * <p>
 * preset scripts are written as blocks between markers, so several presets share one hook file
 * and applying a preset again replaces its block. the presets are of {@link HookPresets}.
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

    /** a parameter of a preset, written as {@code @PARAM:label:default@} in its body */
    public record Parameter(String label, String defaultValue) {}

    /** {@code @PARAM:label:default@}, the label is the key, the same label means the same parameter */
    static final Pattern PARAM = Pattern.compile("@PARAM:([^:@\\n]+):([^@\\n]*)@");

    /**
     * a ready made hook script block.
     *
     * @param id unique among presets, used in the block markers
     * @param category the hook name the preset is for
     * @param body the script, parameters are written as {@code @PARAM:label:default@}
     * @param source where the preset came from (a resource, a file, a url), for tool tips
     */
    public record Preset(String id, String category, String title, String description, String body, String source) {

        public Preset {
            if (!id.matches("[\\w.-]+(/[\\w.-]+)*")) throw new IllegalArgumentException("bad preset id: " + id);
            if (!body.isEmpty() && !body.endsWith("\n")) body += "\n";
        }

        /** the distinct parameters in the body order */
        public List<Parameter> parameters() {
            Map<String, Parameter> map = new LinkedHashMap<>();
            Matcher m = PARAM.matcher(body);
            while (m.find()) map.putIfAbsent(m.group(1), new Parameter(m.group(1), m.group(2)));
            return new ArrayList<>(map.values());
        }

        /**
         * the block for the hook file.
         * @param values the values of {@link #parameters()} in order, a missing or null value is the default
         */
        public String block(List<String> values) {
            List<Parameter> params = parameters();
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < params.size(); i++) {
                String v = values != null && i < values.size() ? values.get(i) : null;
                map.put(params.get(i).label(), v != null ? v : params.get(i).defaultValue());
            }
            String b = PARAM.matcher(body).replaceAll(r -> Matcher.quoteReplacement(map.get(r.group(1))));
            return BEGIN + id + "\n" + b + END + id + "\n";
        }
    }

    static final String BEGIN = "# >>> gitup preset: ";
    static final String END = "# <<< gitup preset: ";
    static final String MANAGED = "# managed by vavi-apps-gitup, blocks between the gitup preset markers are replaced by the app";
    static final String STDIN_LINE = "gitup_stdin=$(cat)";

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

    /**
     * the parameter values a preset block was written with.
     * @return values in the order of {@link Preset#parameters()}, null when the block is absent or was edited
     */
    public static List<String> parametersOf(String script, Preset preset) {
        Matcher b = blockPattern(preset.id()).matcher(script);
        if (!b.find()) return null;
        String inner = b.group().substring((BEGIN + preset.id() + "\n").length());
        inner = inner.substring(0, inner.lastIndexOf(END + preset.id()));
        // the body as a regex, a parameter is a group, the same label again is a back reference
        StringBuilder re = new StringBuilder();
        Map<String, Integer> groups = new HashMap<>();
        Matcher m = GitHooks.PARAM.matcher(preset.body());
        int last = 0;
        while (m.find()) {
            re.append(Pattern.quote(preset.body().substring(last, m.start())));
            Integer g = groups.get(m.group(1));
            if (g != null) {
                re.append("\\").append(g);
            } else {
                groups.put(m.group(1), groups.size() + 1);
                re.append("([^\\n]*?)");
            }
            last = m.end();
        }
        re.append(Pattern.quote(preset.body().substring(last)));
        Matcher v = Pattern.compile(re.toString(), Pattern.DOTALL).matcher(inner);
        if (!v.matches()) return null;
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= groups.size(); i++) values.add(v.group(i));
        return values;
    }

    /**
     * @param script the current script, empty or managed ones only (others are replaced)
     * @param values the parameter values, see {@link Preset#block(List)}
     * @return the script with the preset block added, or its block replaced
     */
    public static String applyPreset(String script, Preset preset, List<String> values) {
        String block = preset.block(values);
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

    /**
     * a whole hook script as a preset body.
     * a hook file has one interpreter, so the script (python, perl, bash…) runs by the interpreter of its shebang
     * as a here document, it gets the hook arguments and the stdin, and its failure stops the hook.
     * its {@code exit 0} does not end the following blocks.
     *
     * @param category the hook name, for the stdin
     */
    public static String wrap(String script, String category) {
        String interpreter = "/bin/sh";
        if (script.startsWith("#!")) {
            int eol = script.indexOf('\n');
            interpreter = (eol < 0 ? script.substring(2) : script.substring(2, eol)).strip();
        }
        String eof = "GITUP_EOF";
        for (int i = 1; Pattern.compile("^" + eof + "$", Pattern.MULTILINE).matcher(script).find(); i++) eof = "GITUP_EOF" + i;
        return (STDIN.contains(category) ? "printf '%s\\n' \"$gitup_stdin\" | " : "")
                + interpreter + " /dev/fd/3 \"$@\" 3<<'" + eof + "' || exit $?\n"
                + script + (script.endsWith("\n") ? "" : "\n")
                + eof + "\n";
    }

    /**
     * true when the lines the range [start, end) touches are not free to be a new preset:
     * they are in a preset block (markers included) or lines the app writes.
     */
    public static boolean touchesPreset(String script, int start, int end) {
        for (String l : lines(script, start, end).split("\n")) {
            if (l.startsWith(BEGIN) || l.startsWith(END) || l.equals(MANAGED) || l.equals(STDIN_LINE)) return true;
        }
        Matcher m = Pattern.compile("^" + Pattern.quote(BEGIN) + "(\\S+)$", Pattern.MULTILINE).matcher(script);
        while (m.find()) {
            Matcher b = blockPattern(m.group(1)).matcher(script);
            if (b.find(m.start()) && b.start() == m.start() && start < b.end() && b.start() < Math.max(end, start + 1)) return true;
        }
        return false;
    }

    /** the whole lines the range [start, end) touches */
    public static String lines(String script, int start, int end) {
        int from = start == 0 ? 0 : script.lastIndexOf('\n', start - 1) + 1;
        int to = script.indexOf('\n', Math.max(end - 1, from));
        return script.substring(from, to < 0 ? script.length() : to + 1);
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
