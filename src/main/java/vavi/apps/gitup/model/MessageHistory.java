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
import java.util.Arrays;
import java.util.List;


/**
 * recent commit messages, newest first, shared by all repositories.
 * <p>
 * stored in a text file, messages are separated by a line with a single
 * record separator (U+001E) so multi-line messages survive.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class MessageHistory {

    /** default number of messages kept, override with {@code -Dgitup.messageHistory} */
    public static final int DEFAULT_SIZE = 50;

    private static final String SEPARATOR = "\u001e";

    private final Path file;
    private final int size;
    private final List<String> messages = new ArrayList<>();

    public MessageHistory(Path file, int size) {
        this.file = file;
        this.size = Math.max(1, size);
        if (Files.exists(file)) {
            try {
                String s = Files.readString(file);
                Arrays.stream(s.split("\n" + SEPARATOR + "\n", -1)).map(String::strip).filter(m -> !m.isEmpty())
                        .limit(this.size).forEach(messages::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** the default location, ~/Library/Application Support/vavi-apps-gitup/messages.txt */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "vavi-apps-gitup", "messages.txt");
    }

    /** the size from {@code -Dgitup.messageHistory}, {@link #DEFAULT_SIZE} otherwise */
    public static int configuredSize() {
        return Integer.getInteger("gitup.messageHistory", DEFAULT_SIZE);
    }

    /** @return newest first */
    public synchronized List<String> messages() {
        return List.copyOf(messages);
    }

    /** remembers a message (moved to the top when known), drops the oldest over the size, saves */
    public synchronized void add(String message) {
        String m = message.strip();
        if (m.isEmpty()) return;
        messages.remove(m);
        messages.addFirst(m);
        while (messages.size() > size) messages.removeLast();
        save();
    }

    public synchronized void clear() {
        messages.clear();
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n" + SEPARATOR + "\n", messages) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
