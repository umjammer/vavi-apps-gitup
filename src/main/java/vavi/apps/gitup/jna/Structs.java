/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import java.nio.charset.StandardCharsets;

import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;


/**
 * libgit2 structures, layouts pinned to libgit2 1.4.4 (the version embedded in GitUp 1.4.0).
 * <p>
 * option structures we pass in have a trailing {@code reserved} area so that
 * a slightly newer libgit2 with appended fields does not read beyond our memory.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class Structs {

    private Structs() {}

    /** git_oid */
    @FieldOrder({"id"})
    public static class GitOid extends Structure {
        public byte[] id = new byte[20];
        public GitOid() {}
        public GitOid(Pointer p) { super(p); read(); }
        public static class ByValue extends GitOid implements Structure.ByValue {}
        public String hex() {
            StringBuilder sb = new StringBuilder(40);
            for (byte b : id) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        }
    }

    /** git_strarray */
    @FieldOrder({"strings", "count"})
    public static class GitStrarray extends Structure {
        public Pointer strings;
        public NativeLong count;
        public GitStrarray() { count = new NativeLong(0); }
        public GitStrarray(Pointer p) { super(p); read(); }

        /** keeps native memory reachable as long as this is */
        private Memory[] keep;

        public static class ByReference extends GitStrarray implements Structure.ByReference {}

        /** sets the strings (native memory is owned by this object) */
        public void set(String... values) {
            if (values == null || values.length == 0) {
                strings = null;
                count = new NativeLong(0);
                keep = null;
                return;
            }
            Memory array = new Memory((long) Native_SIZE * values.length);
            keep = new Memory[values.length + 1];
            keep[values.length] = array;
            for (int i = 0; i < values.length; i++) {
                byte[] b = values[i].getBytes(StandardCharsets.UTF_8);
                Memory m = new Memory(b.length + 1);
                m.write(0, b, 0, b.length);
                m.setByte(b.length, (byte) 0);
                keep[i] = m;
                array.setPointer((long) Native_SIZE * i, m);
            }
            strings = array;
            count = new NativeLong(values.length);
        }

        private static final int Native_SIZE = com.sun.jna.Native.POINTER_SIZE;
    }

    /**
     * git_diff_file.
     * <p>
     * GitUp's libgit2 build has 16 more bytes after {@code id_abbrev} (64 bytes total, measured),
     * {@code new_file} of git_diff_delta is at offset 80.
     */
    @FieldOrder({"id", "path", "size", "flags", "mode", "id_abbrev", "unknown"})
    public static class GitDiffFile extends Structure {
        public GitOid id;
        public String path;
        public long size;
        public int flags;
        public short mode;
        public short id_abbrev;
        public byte[] unknown = new byte[16];
    }

    /** git_diff_delta */
    @FieldOrder({"status", "flags", "similarity", "nfiles", "old_file", "new_file"})
    public static class GitDiffDelta extends Structure {
        public int status;
        public int flags;
        public short similarity;
        public short nfiles;
        public GitDiffFile old_file;
        public GitDiffFile new_file;
        public GitDiffDelta() {}
        public GitDiffDelta(Pointer p) { super(p); read(); }
    }

    /** git_diff_hunk */
    @FieldOrder({"old_start", "old_lines", "new_start", "new_lines", "header_len", "header"})
    public static class GitDiffHunk extends Structure {
        public int old_start;
        public int old_lines;
        public int new_start;
        public int new_lines;
        public NativeLong header_len;
        public byte[] header = new byte[128];
        public GitDiffHunk() {}
        public GitDiffHunk(Pointer p) { super(p); read(); }
        public String headerString() {
            int len = (int) Math.min(header_len.longValue(), header.length);
            return new String(header, 0, len, StandardCharsets.UTF_8).stripTrailing();
        }
    }

    /** git_diff_line, read by offsets because it is on the hot path */
    public static final class GitDiffLine {
        public final char origin;
        public final int oldLineno;
        public final int newLineno;
        public final String content;

        private GitDiffLine(char origin, int oldLineno, int newLineno, String content) {
            this.origin = origin;
            this.oldLineno = oldLineno;
            this.newLineno = newLineno;
            this.content = content;
        }

        /**
         * <pre>
         * char origin @0; int old_lineno @4; int new_lineno @8; int num_lines @12;
         * size_t content_len @16; git_off_t content_offset @24; const char *content @32
         * </pre>
         */
        public static GitDiffLine read(Pointer p) {
            char origin = (char) p.getByte(0);
            int oldNo = p.getInt(4);
            int newNo = p.getInt(8);
            long len = p.getLong(16);
            Pointer c = p.getPointer(32);
            String content = c == null || len == 0 ? "" : new String(c.getByteArray(0, (int) len), StandardCharsets.UTF_8);
            return new GitDiffLine(origin, oldNo, newNo, content);
        }
    }

    /** git_diff_options */
    @FieldOrder({"version", "flags", "ignore_submodules", "pathspec", "notify_cb", "progress_cb", "payload",
            "context_lines", "interhunk_lines", "id_abbrev", "max_size", "old_prefix", "new_prefix", "reserved"})
    public static class GitDiffOptions extends Structure {
        public int version;
        public int flags;
        public int ignore_submodules;
        public GitStrarray pathspec;
        public Pointer notify_cb;
        public Pointer progress_cb;
        public Pointer payload;
        public int context_lines;
        public int interhunk_lines;
        public short id_abbrev;
        public long max_size;
        public String old_prefix;
        public String new_prefix;
        public byte[] reserved = new byte[64];
    }

    /** git_status_options */
    @FieldOrder({"version", "show", "flags", "pathspec", "baseline", "reserved"})
    public static class GitStatusOptions extends Structure {
        public int version;
        public int show;
        public int flags;
        public GitStrarray pathspec;
        public Pointer baseline;
        public byte[] reserved = new byte[64];
    }

    /** git_status_entry */
    @FieldOrder({"status", "head_to_index", "index_to_workdir"})
    public static class GitStatusEntry extends Structure {
        public int status;
        public Pointer head_to_index;
        public Pointer index_to_workdir;
        public GitStatusEntry() {}
        public GitStatusEntry(Pointer p) { super(p); read(); }
    }

    /** git_error */
    @FieldOrder({"message", "klass"})
    public static class GitError extends Structure {
        public String message;
        public int klass;
        public GitError() {}
        public GitError(Pointer p) { super(p); read(); }
    }

    /** git_time */
    @FieldOrder({"time", "offset", "sign"})
    public static class GitTime extends Structure {
        public long time;
        public int offset;
        public byte sign;
    }

    /** git_signature */
    @FieldOrder({"name", "email", "when"})
    public static class GitSignature extends Structure {
        public String name;
        public String email;
        public GitTime when;
        public GitSignature() {}
        public GitSignature(Pointer p) { super(p); read(); }
    }
}
