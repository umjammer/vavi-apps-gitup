/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import vavi.apps.gitup.model.GitHooks.Preset;

import static java.lang.System.getLogger;


/**
 * hook presets: the built-in ones (resources {@code /hooks/presets/}), then the user ones
 * ({@code *.sh} in the directories of the system property {@value #PROPERTY}, separated by the path separator,
 * then {@link #defaultDir()}). the first one of an id wins.
 * <p>
 * a preset file is a header of {@code # key: value} lines (category, title, description) and the body.
 * the file name without {@code .sh} is the id. parameters are written as {@code @PARAM:label:default@}.
 * <pre>
 * # category: pre-push
 * # title: Protected branch guard
 * # description: rejects pushes to the protected branches
 *
 * gitup_protected="@PARAM:Protected branches (space separated):main@"
 * ...
 * </pre>
 * presets from the web are of {@link HookPresetProvider}s, see {@link #remote()}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-01 nsano initial version <br>
 */
public class HookPresets {

    private static final System.Logger logger = getLogger(HookPresets.class.getName());

    /** the user preset directories, separated by {@link File#pathSeparator} */
    public static final String PROPERTY = "gitup.hooks.path";

    static final String RESOURCES = "/hooks/presets/";

    private static final Pattern HEADER = Pattern.compile("^#\\s*(category|title|description):\\s*(.*)$");

    private final List<Path> userDirs;

    /** the system property ones */
    public HookPresets() {
        this(propertyDirs());
    }

    /** @param userDirs the user preset directories, {@link #defaultDir()} is searched after them */
    public HookPresets(List<Path> userDirs) {
        this.userDirs = userDirs;
    }

    private static List<Path> propertyDirs() {
        String p = System.getProperty(PROPERTY);
        if (p == null || p.isBlank()) return List.of();
        return Stream.of(p.split(Pattern.quote(File.pathSeparator))).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    /** where the user presets are when the system property is not set */
    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "vavi-apps-gitup", "hooks", "presets");
    }

    /** the user directories in the search order */
    public List<Path> searchDirs() {
        List<Path> dirs = new ArrayList<>(userDirs);
        if (!dirs.contains(defaultDir())) dirs.add(defaultDir());
        return dirs;
    }

    /** where a new preset is saved: the first user directory, or {@link #defaultDir()} */
    public Path saveDir() {
        return userDirs.isEmpty() ? defaultDir() : userDirs.getFirst();
    }

    /** the built-in and the user presets, read every time */
    public List<Preset> all() {
        Map<String, Preset> map = new LinkedHashMap<>();
        for (Preset p : builtin()) map.putIfAbsent(p.id(), p);
        for (Path dir : searchDirs()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".sh")).sorted().toList()) {
                    try {
                        Preset p = parse(id(f.getFileName().toString()), Files.readString(f), f.toString());
                        if (map.putIfAbsent(p.id(), p) != null) logger.log(System.Logger.Level.DEBUG, "hook preset: shadowed: " + f);
                    } catch (IOException | RuntimeException e) {
                        logger.log(System.Logger.Level.WARNING, "hook preset: " + f + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING, "hook preset: " + dir + ": " + e.getMessage());
            }
        }
        return new ArrayList<>(map.values());
    }

    /** the presets for the hook */
    public List<Preset> presets(String category) {
        return all().stream().filter(p -> p.category().equals(category)).toList();
    }

    /** @return null when not found */
    public Preset get(String id) {
        return all().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    /** the presets in the resources, listed by {@code index} there */
    public static List<Preset> builtin() {
        List<Preset> list = new ArrayList<>();
        for (String name : resource("index").lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList()) {
            list.add(parse(id(name), resource(name), "built-in"));
        }
        return list;
    }

    private static String resource(String name) {
        try (InputStream is = HookPresets.class.getResourceAsStream(RESOURCES + name)) {
            if (is == null) throw new IllegalStateException("no resource: " + RESOURCES + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String id(String fileName) {
        return fileName.endsWith(".sh") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    /** a preset file to a preset */
    public static Preset parse(String id, String text, String source) {
        Map<String, String> header = new LinkedHashMap<>();
        List<String> lines = text.lines().toList();
        int i = 0;
        for (; i < lines.size(); i++) {
            Matcher m = HEADER.matcher(lines.get(i));
            if (!m.matches()) break;
            header.put(m.group(1), m.group(2).strip());
        }
        if (i < lines.size() && lines.get(i).isBlank()) i++;
        String category = header.get("category");
        if (category == null || !GitHooks.CATEGORIES.contains(category)) throw new IllegalArgumentException("bad category: " + category);
        String body = String.join("\n", lines.subList(i, lines.size())) + "\n";
        return new Preset(id, category, header.getOrDefault("title", id), header.getOrDefault("description", ""), body, source);
    }

    /** a preset to a preset file */
    public static String format(Preset p) {
        return "# category: " + p.category() + "\n"
                + "# title: " + p.title().replace("\n", " ") + "\n"
                + "# description: " + p.description().replace("\n", " ") + "\n"
                + "\n" + p.body();
    }

    /**
     * saves the preset as a user preset in {@link #saveDir()}.
     * @return the saved one
     */
    public Preset save(String id, String category, String title, String description, String body) {
        Path f = saveDir().resolve(id + ".sh");
        Preset p = new Preset(id, category, title, description, body, f.toString());
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, format(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    // web

    /** the presets a provider has, loaded once in the background */
    public record Remote(HookPresetProvider provider, CompletableFuture<List<Preset>> presets) {}

    private static List<Remote> remote;

    /** the {@link HookPresetProvider}s, their loading starts at the first call */
    public static synchronized List<Remote> remote() {
        if (remote == null) {
            List<Remote> list = new ArrayList<>();
            for (HookPresetProvider p : ServiceLoader.load(HookPresetProvider.class)) {
                list.add(new Remote(p, CompletableFuture.supplyAsync(() -> {
                    try {
                        return p.presets();
                    } catch (IOException e) {
                        logger.log(System.Logger.Level.WARNING, "hook preset: " + p.name() + ": " + e.getMessage());
                        throw new UncheckedIOException(e);
                    }
                })));
            }
            remote = list;
        }
        return remote;
    }
}
