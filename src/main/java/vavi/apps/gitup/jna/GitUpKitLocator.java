/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;


/**
 * Resolves the GitUpKit.framework binary inside GitUp.app.
 * <p>
 * lookup order
 * <ol>
 * <li>system property {@code gitup.framework} (framework dir or binary, or GitUp.app)</li>
 * <li>user preference {@code gitup.framework}</li>
 * <li>{@code ~/Applications/GitUp.app}</li>
 * <li>{@code /Applications/GitUp.app}</li>
 * </ol>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class GitUpKitLocator {

    public static final String KEY = "gitup.framework";

    private static final String BINARY = "Contents/Frameworks/GitUpKit.framework/Versions/A/GitUpKit";

    private GitUpKitLocator() {}

    /** @return candidate locations in lookup order */
    static List<String> candidates() {
        List<String> list = new ArrayList<>();
        String p = System.getProperty(KEY);
        if (p != null && !p.isBlank()) list.add(p);
        p = Preferences.userNodeForPackage(GitUpKitLocator.class).get(KEY, null);
        if (p != null && !p.isBlank()) list.add(p);
        list.add(System.getProperty("user.home") + "/Applications/GitUp.app");
        list.add("/Applications/GitUp.app");
        return list;
    }

    /** @return the GitUpKit binary path, or null when not found */
    public static Path find() {
        for (String c : candidates()) {
            Path b = toBinary(Path.of(c));
            if (b != null) return b;
        }
        return null;
    }

    /** @throws IllegalStateException when not found */
    public static Path locate() {
        Path p = find();
        if (p == null) {
            throw new IllegalStateException("GitUpKit.framework not found, set -D" + KEY + "=/path/to/GitUp.app, searched: " + candidates());
        }
        return p;
    }

    /** accepts GitUp.app, GitUpKit.framework or the binary itself */
    static Path toBinary(Path p) {
        if (Files.isRegularFile(p)) return p;
        if (p.toString().endsWith(".app")) p = p.resolve(BINARY);
        else if (p.toString().endsWith(".framework")) p = p.resolve("Versions/A/GitUpKit");
        return Files.isRegularFile(p) ? p : null;
    }

    /** stores the location as a user preference */
    public static void save(Path appOrFramework) {
        Preferences.userNodeForPackage(GitUpKitLocator.class).put(KEY, appOrFramework.toString());
    }
}
