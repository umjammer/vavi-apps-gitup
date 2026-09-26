/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;

import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.jna.Structs.GitDiffDelta;
import vavi.apps.gitup.jna.Structs.GitDiffHunk;
import vavi.apps.gitup.jna.Structs.GitDiffLine;


/**
 * a single file patch whose line texts are fetched on demand.
 * <p>
 * only hunk headers and line counts are read when opened, that gives the total row count
 * (one header row per hunk + its lines) without holding any line text in the java heap.
 * {@link #row(int)} reads a line from the native patch and keeps it in a small LRU cache,
 * so a view fetches only the rows it paints.
 * <p>
 * the native patch is immutable after creation, so {@link #row(int)} may be called from
 * the EDT while the git thread works on the repository.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class LazyPatch implements AutoCloseable {

    private static final LibGit2 git = LibGit2.INSTANCE;

    private static final Cleaner cleaner = Cleaner.create();

    /** hunk header info */
    public record Hunk(int index, String header, int oldStart, int oldLines, int newStart, int newLines, int lineCount) {}

    /** a display row, either a hunk header or a line in a hunk */
    public record Row(int hunk, int line, char origin, int oldLineno, int newLineno, String content) {
        public boolean isHeader() { return line < 0; }
        /** '+' or '-' */
        public boolean isChange() { return origin == '+' || origin == '-'; }
    }

    /** native handles, freed by close() or by the cleaner */
    private static final class Handles implements Runnable {
        Pointer diff;
        Pointer patch;
        @Override public synchronized void run() {
            if (patch != null) { git.git_patch_free(patch); patch = null; }
            if (diff != null) { git.git_diff_free(diff); diff = null; }
        }
    }

    private final Handles handles = new Handles();
    private final Cleaner.Cleanable cleanable;

    private final FileChange file;
    private final int deltaStatus;
    private final boolean binary;
    private final int mode;
    private final boolean whitespaceIgnored;
    private final List<Hunk> hunks;
    /** row index of each hunk header */
    private final int[] hunkRowStart;
    private final int rowCount;

    private static final int CACHE_SIZE = 4096;
    private final Map<Integer, Row> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Row> e) { return size() > CACHE_SIZE; }
    });

    /** number of lines read from native, for diagnostics and tests */
    private volatile long fetchCount;

    /**
     * @param diff a diff owned by this object from now on
     * @param index delta index in the diff
     * @param whitespaceIgnored the diff was made ignoring whitespace
     */
    LazyPatch(Pointer diff, int index, FileChange file, boolean whitespaceIgnored) {
        this.file = file;
        this.whitespaceIgnored = whitespaceIgnored;
        handles.diff = diff;
        cleanable = cleaner.register(this, handles);

        PointerByReference pp = new PointerByReference();
        GitException.check(git.git_patch_from_diff(pp, diff, new NativeLong(index)), "patch");
        handles.patch = pp.getValue();

        GitDiffDelta delta = new GitDiffDelta(git.git_diff_get_delta(diff, new NativeLong(index)));
        deltaStatus = delta.status;
        binary = (delta.flags & LibGit2.GIT_DIFF_FLAG_BINARY) != 0;
        int m = delta.new_file.mode & 0xffff;
        mode = m != 0 ? m : delta.old_file.mode & 0xffff;

        Pointer patch = handles.patch;
        int n = handles.patch == null ? 0 : git.git_patch_num_hunks(patch).intValue();
        List<Hunk> list = new ArrayList<>(n);
        hunkRowStart = new int[n];
        int rows = 0;
        PointerByReference hp = new PointerByReference();
        NativeLongByReference lines = new NativeLongByReference();
        for (int i = 0; i < n; i++) {
            GitException.check(git.git_patch_get_hunk(hp, lines, patch, new NativeLong(i)), "hunk");
            GitDiffHunk h = new GitDiffHunk(hp.getValue());
            int lc = lines.getValue().intValue();
            list.add(new Hunk(i, h.headerString(), h.old_start, h.old_lines, h.new_start, h.new_lines, lc));
            hunkRowStart[i] = rows;
            rows += 1 + lc;
        }
        hunks = Collections.unmodifiableList(list);
        rowCount = rows;
    }

    public FileChange file() { return file; }

    /** git_delta_t */
    public int deltaStatus() { return deltaStatus; }

    public boolean isBinary() { return binary; }

    /** true when whitespace changes are left out, such a patch does not apply to the file as is */
    public boolean isWhitespaceIgnored() { return whitespaceIgnored; }

    /** file mode (e.g. 0100644) */
    public int mode() { return mode; }

    public List<Hunk> hunks() { return hunks; }

    /** total rows (hunk headers + lines) */
    public int rowCount() { return rowCount; }

    public long fetchCount() { return fetchCount; }

    /** @return the row index of the hunk header */
    public int hunkRow(int hunk) { return hunkRowStart[hunk]; }

    /** @return hunk index containing the row */
    public int hunkOfRow(int row) {
        int i = java.util.Arrays.binarySearch(hunkRowStart, row);
        return i >= 0 ? i : -i - 2;
    }

    /** @return the row, fetched from native on a cache miss */
    public Row row(int row) {
        Row r = cache.get(row);
        if (r != null) return r;
        int h = hunkOfRow(row);
        int line = row - hunkRowStart[h] - 1;
        if (line < 0) {
            Hunk hunk = hunks.get(h);
            r = new Row(h, -1, 'H', 0, 0, hunk.header());
        } else {
            r = line(h, line);
        }
        cache.put(row, r);
        return r;
    }

    /** reads a line directly (not cached) */
    public Row line(int hunk, int line) {
        synchronized (handles) {
            if (handles.patch == null) throw new IllegalStateException("closed");
            PointerByReference lp = new PointerByReference();
            GitException.check(git.git_patch_get_line_in_hunk(lp, handles.patch, new NativeLong(hunk), new NativeLong(line)), "line");
            GitDiffLine l = GitDiffLine.read(lp.getValue());
            fetchCount++;
            return new Row(hunk, line, l.origin, l.oldLineno, l.newLineno, l.content);
        }
    }

    @Override
    public void close() {
        cache.clear();
        cleanable.clean();
    }
}
