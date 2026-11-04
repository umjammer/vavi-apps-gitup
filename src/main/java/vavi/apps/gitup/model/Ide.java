/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;


/**
 * IDEs a repository can be opened in (the repository browser's "Open in IDE"), macOS application bundles
 * started by {@code open -a <bundle> <folder>}.
 *
 * @param apps the bundle names (without ".app") in order of preference, e.g. Ultimate before Community
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-04 nsano initial version <br>
 */
public record Ide(String id, String name, List<String> apps) {

    public static final String DEFAULT = "idea";

    public static final List<Ide> PRESETS = List.of(
            new Ide("idea", "IntelliJ IDEA", List.of("IntelliJ IDEA", "IntelliJ IDEA Ultimate", "IntelliJ IDEA CE", "IntelliJ IDEA Community Edition")),
            new Ide("eclipse", "Eclipse", List.of("Eclipse", "Eclipse IDE", "Eclipse Java")),
            new Ide("vscode", "Visual Studio Code", List.of("Visual Studio Code", "Visual Studio Code - Insiders")),
            new Ide("cursor", "Cursor", List.of("Cursor")),
            new Ide("windsurf", "Windsurf", List.of("Windsurf")),
            new Ide("zed", "Zed", List.of("Zed", "Zed Preview")),
            new Ide("fleet", "Fleet", List.of("Fleet")),
            new Ide("android-studio", "Android Studio", List.of("Android Studio", "Android Studio Preview")),
            new Ide("pycharm", "PyCharm", List.of("PyCharm", "PyCharm Professional Edition", "PyCharm CE", "PyCharm Community Edition")),
            new Ide("webstorm", "WebStorm", List.of("WebStorm")),
            new Ide("goland", "GoLand", List.of("GoLand")),
            new Ide("clion", "CLion", List.of("CLion")),
            new Ide("rider", "Rider", List.of("Rider")),
            new Ide("rustrover", "RustRover", List.of("RustRover")),
            new Ide("netbeans", "Apache NetBeans", List.of("Apache NetBeans", "NetBeans")),
            new Ide("xcode", "Xcode", List.of("Xcode")),
            new Ide("sublime", "Sublime Text", List.of("Sublime Text")),
            new Ide("nova", "Nova", List.of("Nova")),
            new Ide("bbedit", "BBEdit", List.of("BBEdit")));

    public static Ide preset(String id) {
        return PRESETS.stream().filter(t -> t.id().equals(id)).findFirst().orElse(PRESETS.getFirst());
    }

    /** where application bundles are looked up, including one level of sub folders (e.g. JetBrains Toolbox's) */
    private static final List<Path> APP_DIRS = List.of(Path.of("/Applications"), Path.of(System.getProperty("user.home"), "Applications"));

    private static volatile List<Path> bundles;

    /** @return the application bundles, scanned once (a few directory listings) */
    private static List<Path> bundles() {
        if (bundles == null) {
            List<Path> list = new ArrayList<>();
            for (Path dir : APP_DIRS) {
                for (Path p : list(dir)) {
                    if (p.getFileName().toString().endsWith(".app")) list.add(p);
                    else for (Path q : list(p)) if (q.getFileName().toString().endsWith(".app")) list.add(q);
                }
            }
            bundles = List.copyOf(list);
        }
        return bundles;
    }

    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** @return the installed bundle of this IDE, null when not found */
    public Path bundle() {
        for (String app : apps) {
            String file = app + ".app";
            for (Path b : bundles()) if (b.getFileName().toString().equals(file)) return b;
        }
        return null;
    }

    public boolean installed() {
        return bundle() != null;
    }

    /** @return the installed presets */
    public static List<Ide> installedPresets() {
        return PRESETS.stream().filter(Ide::installed).toList();
    }

    /** opens the folder in this IDE, by the found bundle or else by the name (Launch Services looks it up) */
    public void open(Path folder) throws IOException {
        Path b = bundle();
        new ProcessBuilder("open", "-a", b != null ? b.toString() : apps.getFirst(), folder.toString()).start();
    }

    @Override
    public String toString() {
        return name + (installed() ? "" : " (not found)");
    }
}
