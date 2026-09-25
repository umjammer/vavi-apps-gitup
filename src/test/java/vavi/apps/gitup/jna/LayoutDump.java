package vavi.apps.gitup.jna;

import java.nio.file.Path;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import vavi.apps.gitup.jna.Structs.GitStatusOptions;


/** debug: dumps raw git_diff_delta memory of the first status entry of the repo in args[0] */
public class LayoutDump {

    public static void main(String[] args) {
        LibGit2 git = LibGit2.INSTANCE;
        PointerByReference pp = new PointerByReference();
        System.err.println("open: " + git.git_repository_open(pp, Path.of(args[0]).toAbsolutePath().toString()));
        GitStatusOptions o = new GitStatusOptions();
        git.git_status_options_init(o, 1);
        o.flags = 1 | 16;
        PointerByReference lp = new PointerByReference();
        System.err.println("list: " + git.git_status_list_new(lp, pp.getValue(), o));
        System.err.println("count: " + git.git_status_list_entrycount(lp.getValue()));
        Pointer e = git.git_status_byindex(lp.getValue(), new NativeLong(0));
        System.err.printf("status=%x h2i=%s i2w=%s%n", e.getInt(0), e.getPointer(8), e.getPointer(16));
        Pointer d = e.getPointer(16) != null ? e.getPointer(16) : e.getPointer(8);
        for (int i = 0; i < 128; i += 8) {
            long v = d.getLong(i);
            String s = "";
            if (v > 0x100000000L && v < 0x7fffffffffffL) {
                try { s = d.getPointer(i).getString(0); } catch (Throwable t) { s = "?"; }
            }
            System.err.printf("%3d: %016x %s%n", i, v, s);
        }
    }
}
