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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import vavi.apps.gitup.model.GitRepo.RefSnapshot;
import vavi.apps.gitup.model.GitRepo.Restore;


/**
 * keeps the undo / redo history of a repository over restarts.
 * <p>
 * one file per working directory under {@code ~/Library/Application Support/vavi-apps-gitup/undo/}:
 * <pre>
 * [undo]            (or [redo])
 * label	REFS	refs/heads/main
 * 	refs/heads/main	0123abcd...
 * </pre>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class UndoStore {

    /** the stacks, oldest first */
    public record History(List<RefSnapshot> undo, List<RefSnapshot> redo) {}

    private final Path file;

    public UndoStore(Path dir, Path workdir) {
        this.file = dir.resolve(hash(workdir.toAbsolutePath().normalize().toString()) + ".txt");
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "vavi-apps-gitup", "undo");
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public History load() {
        List<RefSnapshot> undo = new ArrayList<>(), redo = new ArrayList<>();
        if (!Files.exists(file)) return new History(undo, redo);
        try {
            List<RefSnapshot> current = undo;
            String label = null, head = null;
            Restore restore = null;
            Map<String, String> branches = null;
            for (String line : Files.readAllLines(file)) {
                if (line.equals("[undo]") || line.equals("[redo]")) {
                    if (label != null) current.add(new RefSnapshot(label, head, branches, restore));
                    label = null;
                    current = line.equals("[undo]") ? undo : redo;
                } else if (line.startsWith("\t")) {
                    String[] f = line.substring(1).split("\t");
                    if (branches != null && f.length == 2) branches.put(f[0], f[1]);
                } else if (!line.isBlank()) {
                    if (label != null) current.add(new RefSnapshot(label, head, branches, restore));
                    String[] f = line.split("\t", -1);
                    label = f[0];
                    restore = Restore.valueOf(f[1]);
                    head = f.length > 2 && !f[2].isEmpty() ? f[2] : null;
                    branches = new LinkedHashMap<>();
                }
            }
            if (label != null) current.add(new RefSnapshot(label, head, branches, restore));
        } catch (IOException | RuntimeException e) {
            // a broken file is dropped
            return new History(new ArrayList<>(), new ArrayList<>());
        }
        return new History(undo, redo);
    }

    public void save(List<RefSnapshot> undo, List<RefSnapshot> redo) {
        StringBuilder sb = new StringBuilder();
        write(sb, "[undo]", undo);
        write(sb, "[redo]", redo);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(StringBuilder sb, String section, List<RefSnapshot> list) {
        sb.append(section).append('\n');
        for (RefSnapshot s : list) {
            sb.append(s.label().replace('\t', ' ').replace('\n', ' ')).append('\t').append(s.restore()).append('\t')
                    .append(s.head() != null ? s.head() : "").append('\n');
            s.branches().forEach((k, v) -> sb.append('\t').append(k).append('\t').append(v).append('\n'));
        }
    }
}
