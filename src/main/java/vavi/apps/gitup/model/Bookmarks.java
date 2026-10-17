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


/**
 * the repository browser's bookmarks: repositories in (nested) groups.
 * <p>
 * stored as text, one entry per line, nesting by leading tabs:
 * <pre>
 * G	work
 * 	R	app	/Users/me/src/app
 * 	G	old	collapsed
 * R	dotfiles	/Users/me/dotfiles
 * </pre>
 * a group shown collapsed in the repository browser has "collapsed" as the third field.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class Bookmarks {

    /** a group or a repository */
    public sealed interface Entry permits Group, Repo {
        String name();
    }

    /** a group, children are mutable */
    public static final class Group implements Entry {
        private String name;
        private boolean collapsed;
        private final List<Entry> children = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        public void setName(String name) { this.name = name; }
        /** shown collapsed in the repository browser */
        public boolean isCollapsed() { return collapsed; }
        public void setCollapsed(boolean collapsed) { this.collapsed = collapsed; }
        public List<Entry> children() { return children; }
        @Override public String toString() { return name; }
    }

    /** a bookmarked repository, name is the display name (the folder name by default) */
    public record Repo(String name, Path path) implements Entry {
        @Override public String toString() { return name; }

        /** the default display name */
        public static String defaultName(Path path) {
            return path.getFileName() != null ? path.getFileName().toString() : path.toString();
        }
    }

    private final Group root = new Group("");

    public Group root() {
        return root;
    }

    /** the default location, ~/Library/Application Support/vavi-apps-gitup/bookmarks.txt */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "vavi-apps-gitup", "bookmarks.txt");
    }

    public static Bookmarks load(Path file) {
        Bookmarks b = new Bookmarks();
        if (!Files.exists(file)) return b;
        try {
            List<Group> stack = new ArrayList<>(List.of(b.root));
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                int depth = 0;
                while (depth < line.length() && line.charAt(depth) == '\t') depth++;
                String[] f = line.substring(depth).split("\t", -1);
                while (stack.size() > depth + 1) stack.removeLast();
                Group parent = stack.getLast();
                if (f[0].equals("G") && f.length >= 2) {
                    Group g = new Group(f[1]);
                    g.setCollapsed(f.length >= 3 && f[2].equals("collapsed"));
                    parent.children().add(g);
                    stack.add(g);
                } else if (f[0].equals("R") && f.length >= 3) {
                    parent.children().add(new Repo(f[1], Path.of(f[2])));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return b;
    }

    public void save(Path file) {
        StringBuilder sb = new StringBuilder();
        write(sb, root, 0);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(StringBuilder sb, Group g, int depth) {
        for (Entry e : g.children()) {
            sb.append("\t".repeat(depth));
            switch (e) {
                case Group c -> {
                    sb.append("G\t").append(clean(c.name())).append(c.isCollapsed() ? "\tcollapsed" : "").append('\n');
                    write(sb, c, depth + 1);
                }
                case Repo r -> sb.append("R\t").append(clean(r.name())).append('\t').append(r.path()).append('\n');
            }
        }
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    /** @return the repository with the path anywhere in the tree, or null */
    public Repo find(Path path) {
        return find(root, path.toAbsolutePath().normalize());
    }

    private static Repo find(Group g, Path path) {
        for (Entry e : g.children()) {
            switch (e) {
                case Group c -> {
                    Repo r = find(c, path);
                    if (r != null) return r;
                }
                case Repo r -> {
                    if (r.path().toAbsolutePath().normalize().equals(path)) return r;
                }
            }
        }
        return null;
    }

    /** adds the repository at the top level unless bookmarked already, @return true when added */
    public boolean addIfAbsent(Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (find(p) != null) return false;
        root.children().add(new Repo(Repo.defaultName(p), p));
        return true;
    }

    /**
     * changes the display name of the entry anywhere in the tree. a blank name resets a repository
     * to its folder name and leaves a group as it is.
     * @return the renamed entry (a repository is replaced by a new one), or null when not found
     */
    public Entry rename(Entry entry, String name) {
        String n = name == null ? "" : clean(name).strip();
        switch (entry) {
            case Group g -> {
                if (!n.isEmpty()) g.setName(n);
                return g;
            }
            case Repo r -> {
                Repo renamed = new Repo(n.isEmpty() ? Repo.defaultName(r.path()) : n, r.path());
                return replace(root, r, renamed) ? renamed : null;
            }
        }
    }

    private static boolean replace(Group g, Entry old, Entry neu) {
        int i = g.children().indexOf(old);
        if (i >= 0) {
            g.children().set(i, neu);
            return true;
        }
        for (Entry e : g.children()) {
            if (e instanceof Group c && replace(c, old, neu)) return true;
        }
        return false;
    }

    /** removes the entry anywhere in the tree */
    public boolean remove(Entry entry) {
        return remove(root, entry);
    }

    private static boolean remove(Group g, Entry entry) {
        if (g.children().remove(entry)) return true;
        for (Entry e : g.children()) {
            if (e instanceof Group c && remove(c, entry)) return true;
        }
        return false;
    }
}
