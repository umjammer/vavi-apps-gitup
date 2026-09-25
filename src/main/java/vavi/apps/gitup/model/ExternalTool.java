/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


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

    /** @return true when the executable is on the PATH (or in the usual Homebrew / local bins) */
    public boolean installed() {
        if (executable == null) return true;
        String path = System.getenv().getOrDefault("PATH", "") + File.pathSeparator + "/usr/local/bin:/opt/homebrew/bin";
        for (String dir : path.split(File.pathSeparator)) {
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
        pb.environment().merge("PATH", "/usr/local/bin:/opt/homebrew/bin", (a, b) -> a + File.pathSeparator + b);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }
}
