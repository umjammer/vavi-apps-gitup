/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import java.util.BitSet;

import vavi.apps.gitup.model.LazyPatch.Hunk;
import vavi.apps.gitup.model.LazyPatch.Row;

import static vavi.apps.gitup.jna.LibGit2.GIT_DELTA_ADDED;
import static vavi.apps.gitup.jna.LibGit2.GIT_DELTA_DELETED;
import static vavi.apps.gitup.jna.LibGit2.GIT_DELTA_UNTRACKED;


/**
 * builds a unified diff containing only the selected lines of a {@link LazyPatch}.
 * <p>
 * forward (stage lines of an unstaged patch, applied to the index):
 * selected '+'/'-' are kept, an unselected '-' becomes context, an unselected '+' is dropped.
 * <p>
 * reverse (unstage lines of a staged patch applied to the index, or discard lines of an
 * unstaged patch applied to the working directory): the patch is inverted,
 * a selected '+' becomes '-', a selected '-' becomes '+', an unselected '+' becomes context,
 * an unselected '-' is dropped.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class PartialPatchBuilder {

    private PartialPatchBuilder() {}

    /**
     * @param selectedRows row indices (see {@link LazyPatch#row(int)}); a selected hunk header selects the whole hunk
     * @param reverse true for unstage / discard
     * @return patch text, or null when nothing is selected
     */
    public static String build(LazyPatch patch, BitSet selectedRows, boolean reverse) {
        BitSet sel = expandHeaders(patch, selectedRows);

        StringBuilder body = new StringBuilder();
        int offset = 0; // accumulated (new - old) of emitted hunks
        boolean all = true;
        boolean any = false;
        for (Hunk h : patch.hunks()) {
            int base = patch.hunkRow(h.index()) + 1;
            boolean hunkSelected = false;
            for (int i = 0; i < h.lineCount(); i++) {
                Row r = patch.row(base + i);
                if (r.isChange()) {
                    if (sel.get(base + i)) hunkSelected = true;
                    else all = false;
                }
            }
            if (!hunkSelected) continue;
            any = true;

            StringBuilder lines = new StringBuilder();
            int oldCount = 0, newCount = 0;
            boolean lastEmitted = false;
            for (int i = 0; i < h.lineCount(); i++) {
                Row r = patch.row(base + i);
                boolean s = sel.get(base + i);
                char o = r.origin();
                if (o == '=' || o == '>' || o == '<') { // "\ No newline at end of file"
                    if (lastEmitted) lines.append("\\ No newline at end of file\n");
                    continue;
                }
                char out;
                if (o == '+') {
                    out = reverse ? (s ? '-' : ' ') : (s ? '+' : 0);
                } else if (o == '-') {
                    out = reverse ? (s ? '+' : 0) : (s ? '-' : ' ');
                } else {
                    out = ' ';
                }
                lastEmitted = out != 0;
                if (out == 0) continue;
                if (out != '+') oldCount++;
                if (out != '-') newCount++;
                lines.append(out).append(stripEol(r.content())).append('\n');
            }
            // oldCount is 0 only when the source side of the original hunk is empty,
            // then its start is already "the line after which to insert"
            int oldStart = reverse ? h.newStart() : h.oldStart();
            int newStart = oldStart + offset;
            if (oldCount == 0) newStart++;
            if (newCount == 0) newStart = Math.max(newStart - 1, 0);
            body.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            body.append(lines);
            offset += newCount - oldCount;
        }
        if (!any) return null;

        int status = patch.deltaStatus();
        boolean added = status == GIT_DELTA_ADDED || status == GIT_DELTA_UNTRACKED;
        boolean deleted = status == GIT_DELTA_DELETED;
        boolean oldExists = reverse ? !deleted : !added;
        boolean newExists = reverse ? !(added && all) : !(deleted && all);

        String path = patch.file().path();
        String mode = Integer.toOctalString(patch.mode() != 0 ? patch.mode() : 0100644);
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        if (!oldExists) sb.append("new file mode ").append(mode).append('\n');
        if (!newExists) sb.append("deleted file mode ").append(mode).append('\n');
        sb.append("--- ").append(oldExists ? "a/" + path : "/dev/null").append('\n');
        sb.append("+++ ").append(newExists ? "b/" + path : "/dev/null").append('\n');
        sb.append(body);
        return sb.toString();
    }

    /** selects every row of a hunk whose header row is selected */
    static BitSet expandHeaders(LazyPatch patch, BitSet selectedRows) {
        BitSet sel = (BitSet) selectedRows.clone();
        for (Hunk h : patch.hunks()) {
            int hr = patch.hunkRow(h.index());
            if (selectedRows.get(hr)) sel.set(hr + 1, hr + 1 + h.lineCount());
        }
        return sel;
    }

    /** selects every row */
    public static BitSet all(LazyPatch patch) {
        BitSet b = new BitSet(patch.rowCount());
        b.set(0, patch.rowCount());
        return b;
    }

    private static String stripEol(String s) {
        int e = s.length();
        if (e > 0 && s.charAt(e - 1) == '\n') e--;
        return s.substring(0, e);
    }
}
