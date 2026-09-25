/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.model;

import static vavi.apps.gitup.jna.LibGit2.*;


/**
 * a changed file in the index, the working directory or a commit.
 *
 * @param path    repository relative path (new side)
 * @param oldPath repository relative path (old side, differs on rename)
 * @param kind    change kind
 * @param staged  true for head to index, false for index to workdir (ignored for commits)
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public record FileChange(String path, String oldPath, Kind kind, boolean staged) {

    public enum Kind {
        ADDED('A'), MODIFIED('M'), DELETED('D'), RENAMED('R'), TYPECHANGE('T'), UNTRACKED('?'), CONFLICTED('C');

        public final char symbol;

        Kind(char symbol) {
            this.symbol = symbol;
        }

        /** from git_delta_t */
        static Kind ofDelta(int status) {
            return switch (status) {
                case GIT_DELTA_ADDED -> ADDED;
                case GIT_DELTA_DELETED -> DELETED;
                case GIT_DELTA_RENAMED, GIT_DELTA_COPIED -> RENAMED;
                case GIT_DELTA_TYPECHANGE -> TYPECHANGE;
                case GIT_DELTA_UNTRACKED -> UNTRACKED;
                case GIT_DELTA_CONFLICTED -> CONFLICTED;
                default -> MODIFIED;
            };
        }
    }

    @Override
    public String toString() {
        return path;
    }
}
