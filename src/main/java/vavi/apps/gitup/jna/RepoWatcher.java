/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;


/**
 * watches a working directory with FSEvents (not GitUpKit's GCLiveRepository, which
 * loads the whole history on creation).
 * <p>
 * changes are reported as repository relative paths on an FSEvents dispatch queue thread.
 * inside .git only what changes status or refs is reported, as {@link #GIT_INDEX} or {@link #GIT_REFS}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class RepoWatcher implements AutoCloseable {

    /** reported for a change of the index or the exclude file (status changes) */
    public static final String GIT_INDEX = ".git/index";
    /** reported for a change of HEAD, refs or the merge state (history changes) */
    public static final String GIT_REFS = ".git/refs";

    interface CoreServices extends Library {
        CoreServices INSTANCE = Native.load("CoreServices", CoreServices.class);

        int kFSEventStreamCreateFlagNoDefer = 0x02;
        int kFSEventStreamCreateFlagWatchRoot = 0x04;
        int kFSEventStreamCreateFlagFileEvents = 0x10;
        long kFSEventStreamEventIdSinceNow = 0xFFFFFFFFFFFFFFFFL;

        interface FSEventStreamCallback extends Callback {
            void invoke(Pointer stream, Pointer info, NativeLong numEvents, Pointer eventPaths, Pointer eventFlags, Pointer eventIds);
        }

        Pointer FSEventStreamCreate(Pointer allocator, FSEventStreamCallback callback, Pointer context, Pointer pathsToWatch,
                                    long sinceWhen, double latency, int flags);
        void FSEventStreamSetDispatchQueue(Pointer stream, Pointer queue);
        byte FSEventStreamStart(Pointer stream);
        void FSEventStreamStop(Pointer stream);
        void FSEventStreamInvalidate(Pointer stream);
        void FSEventStreamRelease(Pointer stream);
    }

    interface CoreFoundation extends Library {
        CoreFoundation INSTANCE = Native.load("CoreFoundation", CoreFoundation.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        int kCFStringEncodingUTF8 = 0x08000100;

        Pointer CFStringCreateWithCString(Pointer allocator, String s, int encoding);
        Pointer CFArrayCreate(Pointer allocator, Pointer[] values, NativeLong count, Pointer callbacks);
        void CFRelease(Pointer cf);
    }

    interface LibDispatch extends Library {
        LibDispatch INSTANCE = Native.load("System", LibDispatch.class);

        Pointer dispatch_queue_create(String label, Pointer attr);
        void dispatch_release(Pointer object);
    }

    private final Path root;
    private final Path gitDir;
    private final Consumer<List<String>> listener;
    /** strongly referenced while the stream lives */
    private final CoreServices.FSEventStreamCallback callback;
    private Pointer stream;
    private Pointer queue;

    /**
     * @param listener called with relative paths (or {@link #GIT_INDEX}, {@link #GIT_REFS}) of a batch of changes
     */
    public RepoWatcher(Path workdir, Path gitDir, double latency, Consumer<List<String>> listener) {
        try {
            this.root = workdir.toRealPath();
            this.gitDir = gitDir.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.listener = listener;
        this.callback = (s, info, n, paths, flags, ids) -> {
            try {
                handle(paths.getStringArray(0, n.intValue(), StandardCharsets.UTF_8.name()));
            } catch (Throwable t) {
                System.getLogger(RepoWatcher.class.getName()).log(System.Logger.Level.WARNING, t.getMessage(), t);
            }
        };

        CoreFoundation cf = CoreFoundation.INSTANCE;
        Pointer path = cf.CFStringCreateWithCString(null, root.toString(), CoreFoundation.kCFStringEncodingUTF8);
        Pointer callbacks = NativeLibrary.getInstance("CoreFoundation").getGlobalVariableAddress("kCFTypeArrayCallBacks");
        Pointer paths = cf.CFArrayCreate(null, new Pointer[] {path}, new NativeLong(1), callbacks);
        try {
            CoreServices cs = CoreServices.INSTANCE;
            stream = cs.FSEventStreamCreate(null, callback, null, paths, CoreServices.kFSEventStreamEventIdSinceNow, latency,
                    CoreServices.kFSEventStreamCreateFlagFileEvents | CoreServices.kFSEventStreamCreateFlagNoDefer | CoreServices.kFSEventStreamCreateFlagWatchRoot);
            if (stream == null) throw new IllegalStateException("FSEventStreamCreate failed: " + root);
            queue = LibDispatch.INSTANCE.dispatch_queue_create("vavi.apps.gitup.watcher", null);
            cs.FSEventStreamSetDispatchQueue(stream, queue);
            if (cs.FSEventStreamStart(stream) == 0) throw new IllegalStateException("FSEventStreamStart failed: " + root);
        } finally {
            cf.CFRelease(paths);
            cf.CFRelease(path);
        }
    }

    private void handle(String[] paths) {
        List<String> changes = new ArrayList<>();
        String gitPrefix = gitDir + "/";
        String rootPrefix = root + "/";
        for (String p : paths) {
            if (p.startsWith(gitPrefix) || p.equals(gitDir.toString())) {
                String kind = classifyGitPath(p.substring(Math.min(p.length(), gitPrefix.length())));
                if (kind != null && !changes.contains(kind)) changes.add(kind);
            } else if (p.startsWith(rootPrefix)) {
                changes.add(p.substring(rootPrefix.length()));
            } else if (p.equals(root.toString())) {
                changes.add("");
            }
        }
        if (!changes.isEmpty()) listener.accept(changes);
    }

    /** @return GIT_INDEX, GIT_REFS or null for objects, logs, lock files etc. */
    static String classifyGitPath(String rel) {
        if (rel.endsWith(".lock")) return null;
        if (rel.equals("index") || rel.equals("info/exclude")) return GIT_INDEX;
        if (rel.equals("HEAD") || rel.equals("packed-refs") || rel.equals("MERGE_HEAD") || rel.startsWith("refs/")) return GIT_REFS;
        return null;
    }

    @Override
    public synchronized void close() {
        if (stream != null) {
            CoreServices cs = CoreServices.INSTANCE;
            cs.FSEventStreamStop(stream);
            cs.FSEventStreamInvalidate(stream);
            cs.FSEventStreamRelease(stream);
            stream = null;
        }
        if (queue != null) {
            LibDispatch.INSTANCE.dispatch_release(queue);
            queue = null;
        }
    }
}
