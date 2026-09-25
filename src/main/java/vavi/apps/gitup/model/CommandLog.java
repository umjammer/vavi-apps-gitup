/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;


/**
 * the git commands equivalent to what the application did (SourceTree's "Command History").
 * <p>
 * the application calls libgit2 and GitUpKit, not the git command, so each entry is
 * the command that does the same; GitUp only operations (history rewriting) have a note.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class CommandLog {

    /**
     * @param note how it was really done, e.g. "GitUpKit GCHistory rewrite", null for none
     */
    public record Entry(Instant time, String command, String note) {}

    private static final int LIMIT = 1000;

    private final List<Entry> entries = new ArrayList<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();

    public void add(String command) {
        add(command, null);
    }

    public void add(String command, String note) {
        Entry e = new Entry(Instant.now(), command, note);
        synchronized (entries) {
            entries.add(e);
            if (entries.size() > LIMIT) entries.removeFirst();
        }
        listeners.forEach(l -> l.accept(e));
    }

    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** called on the thread that did the operation */
    public void addListener(Consumer<Entry> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<Entry> l) {
        listeners.remove(l);
    }

    /** quotes an argument for a POSIX shell when needed */
    public static String quote(String s) {
        if (s == null) return "''";
        if (!s.isEmpty() && s.matches("[A-Za-z0-9_@%+=:,./^~{}-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** "-- a b c" for paths */
    public static String paths(java.util.Collection<String> paths) {
        StringBuilder sb = new StringBuilder("--");
        for (String p : paths) sb.append(' ').append(quote(p));
        return sb.toString();
    }

    /** the first line of a message, for -m */
    public static String message(String m) {
        return quote(m == null ? "" : m.strip());
    }
}
