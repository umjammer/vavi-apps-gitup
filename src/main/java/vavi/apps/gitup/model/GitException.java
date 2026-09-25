/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import com.sun.jna.Pointer;

import vavi.apps.gitup.jna.LibGit2;
import vavi.apps.gitup.jna.Structs.GitError;


/**
 * libgit2 / GitUpKit error.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public class GitException extends RuntimeException {

    private final int code;

    public GitException(String message) {
        this(message, -1);
    }

    public GitException(String message, int code) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** throws when {@code rc < 0}, message is taken from {@code git_error_last()} */
    static int check(int rc, String what) {
        if (rc < 0) {
            Pointer p = LibGit2.INSTANCE.git_error_last();
            String m = p == null ? null : new GitError(p).message;
            throw new GitException(what + ": " + (m != null ? m : "error " + rc), rc);
        }
        return rc;
    }
}
