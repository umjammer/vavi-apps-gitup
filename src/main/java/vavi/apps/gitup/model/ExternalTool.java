/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


/**
 * external diff / merge tools (SourceTree's "External Diff / Merge").
 * <p>
 * a command is run by {@code /bin/sh -c} with the files in the environment variables
 * {@code LOCAL}, {@code REMOTE}, {@code BASE} and {@code MERGED}, so the command refers to
 * them as {@code "$LOCAL"} and paths are never pasted into the command line.
 *
 * @param diff  the diff command, null when the tool cannot diff
 * @param merge the merge command, null when the tool cannot merge
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-26 nsano initial version <br>
 */
public record ExternalTool(String id, String name, String executable, String diff, String merge) {

    public static final String CUSTOM = "custom";

    public static final List<ExternalTool> PRESETS = List.of(
            new ExternalTool("filemerge", "FileMerge", "opendiff",
                    "opendiff \"$LOCAL\" \"$REMOTE\"",
                    "opendiff \"$LOCAL\" \"$REMOTE\" -ancestor \"$BASE\" -merge \"$MERGED\""),
            new ExternalTool("vscode", "Visual Studio Code", "code",
                    "code --wait --diff \"$LOCAL\" \"$REMOTE\"",
                    "code --wait --merge \"$LOCAL\" \"$REMOTE\" \"$BASE\" \"$MERGED\""),
            new ExternalTool("kaleidoscope", "Kaleidoscope", "ksdiff",
                    "ksdiff --partial-changeset -- \"$LOCAL\" \"$REMOTE\"",
                    "ksdiff --merge --output \"$MERGED\" --base \"$BASE\" -- \"$LOCAL\" \"$REMOTE\""),
            new ExternalTool("bcompare", "Beyond Compare", "bcomp",
                    "bcomp \"$LOCAL\" \"$REMOTE\"",
                    "bcomp \"$LOCAL\" \"$REMOTE\" \"$BASE\" \"$MERGED\""),
            new ExternalTool("meld", "Meld", "meld",
                    "meld \"$LOCAL\" \"$REMOTE\"",
                    "meld --auto-merge \"$LOCAL\" \"$BASE\" \"$REMOTE\" --output \"$MERGED\""),
            new ExternalTool("p4merge", "P4Merge", "p4merge",
                    "p4merge \"$LOCAL\" \"$REMOTE\"",
                    "p4merge \"$BASE\" \"$LOCAL\" \"$REMOTE\" \"$MERGED\""),
            new ExternalTool(CUSTOM, "Custom", null, null, null));

    public static ExternalTool preset(String id) {
        return PRESETS.stream().filter(t -> t.id().equals(id)).findFirst().orElse(PRESETS.getFirst());
    }

    /** the usual Homebrew / local bins, GUI launched apps have a minimal PATH */
    private static final String EXTRA_PATH = "/usr/local/bin:/opt/homebrew/bin";

    /** where application bundles are looked up, including one level of sub folders (e.g. {@code /Applications/Local}) */
    private static final List<Path> APP_DIRS = List.of(Path.of("/Applications"), Path.of(System.getProperty("user.home"), "Applications"));

    /** where executables live inside an application bundle */
    private static final List<String> BUNDLE_BINS = List.of("Contents/MacOS", "Contents/Resources/app/bin", "Contents/SharedSupport/bin");

    private static volatile List<Path> bundleBinDirs;

    /** @return the bin dirs in application bundles which have a preset's executable, lazily scanned once */
    static List<Path> bundleBinDirs() {
        if (bundleBinDirs == null) {
            List<Path> bundles = new ArrayList<>();
            for (Path dir : APP_DIRS) {
                for (Path p : list(dir)) {
                    if (p.getFileName().toString().endsWith(".app")) bundles.add(p);
                    else for (Path q : list(p)) if (q.getFileName().toString().endsWith(".app")) bundles.add(q);
                }
            }
            Set<Path> dirs = new LinkedHashSet<>();
            for (ExternalTool t : PRESETS) {
                if (t.executable == null) continue;
                for (Path bundle : bundles) {
                    for (String bin : BUNDLE_BINS) {
                        Path d = bundle.resolve(bin);
                        if (Files.isExecutable(d.resolve(t.executable))) dirs.add(d);
                    }
                }
            }
            bundleBinDirs = List.copyOf(dirs);
        }
        return bundleBinDirs;
    }

    /** @return the sub directories, empty when not readable */
    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** @return PATH plus the usual Homebrew / local bins and the bin dirs of installed application bundles */
    static String searchPath(String path) {
        StringBuilder sb = new StringBuilder(path == null ? "" : path);
        sb.append(File.pathSeparator).append(EXTRA_PATH);
        bundleBinDirs().forEach(d -> sb.append(File.pathSeparator).append(d));
        return sb.toString();
    }

    /** @return true when the executable is on the PATH, in the usual Homebrew / local bins or in an application bundle */
    public boolean installed() {
        if (executable == null) return true;
        for (String dir : searchPath(System.getenv("PATH")).split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, executable))) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return name + (installed() ? "" : " (not found)");
    }

    /**
     * starts a command.
     *
     * @param files LOCAL, REMOTE, BASE, MERGED
     */
    public static Process launch(String command, Map<String, String> files, Path workdir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.environment().putAll(files);
        // GUI launched apps have a minimal PATH
        pb.environment().put("PATH", searchPath(pb.environment().get("PATH")));
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }
}
