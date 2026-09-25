/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.nio.file.Path;

import org.rococoa.ID;
import org.rococoa.ObjCObjectByReference;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSArray;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;
import org.rococoa.cocoa.foundation.NSError;
import org.rococoa.cocoa.foundation.NSObject;

import vavi.apps.gitup.model.GitException;


/**
 * GitUp's history rewriting ("Edit Message" of any commit), not an ordinary git operation.
 * <p>
 * same steps as GitUp.app's {@code -[GIMapViewController editCommitMessage:]}:
 * copy the commit with the new message, replay the descendants with their trees copied
 * (so no conflict can happen), then move the references.
 * <p>
 * {@code GCHistory} holds the whole commit graph, it is loaded only for the operation and released after.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class HistoryOps {

    private HistoryOps() {}

    /** GCRepository with the history categories */
    public abstract static class GCHistoryRepository extends GCRepository {
        /** @param sorting GCHistorySorting, 0: none */
        public abstract GCHistory loadHistoryUsingSorting_error(long sorting, ObjCObjectByReference error);

        public abstract NSObject copyCommit_withUpdatedMessage_updatedParents_updatedTreeFromIndex_updateCommitter_error(
                NSObject commit, String message, NSArray parents, NSObject index, boolean updateCommitter, ObjCObjectByReference error);

        public abstract boolean applyReferenceTransform_error(NSObject transform, ObjCObjectByReference error);
    }

    /** GCHistory */
    public abstract static class GCHistory extends NSObject {
        public abstract NSObject historyCommitWithSHA1(String sha1);

        public abstract NSObject rewriteCommit_withUpdatedCommit_copyTrees_conflictHandler_error(
                NSObject commit, NSObject updatedCommit, boolean copyTrees, ID conflictHandler, ObjCObjectByReference error);

        public abstract NSObject squashCommit_withMessage_newCommit_error(NSObject commit, String message, ObjCObjectByReference newCommit, ObjCObjectByReference error);

        public abstract NSObject fixupCommit_newCommit_error(NSObject commit, ObjCObjectByReference newCommit, ObjCObjectByReference error);

        public abstract NSObject deleteCommit_withConflictHandler_error(NSObject commit, ID conflictHandler, ObjCObjectByReference error);

        public abstract NSObject swapCommitWithItsParent_conflictHandler_newChildCommit_newParentCommit_error(
                NSObject commit, ID conflictHandler, ObjCObjectByReference newChildCommit, ObjCObjectByReference newParentCommit, ObjCObjectByReference error);
    }

    /** GCHistoryCommit */
    public abstract static class GCHistoryCommit extends NSObject {
        public abstract NSArray parents();
        public abstract NSArray children();
        public abstract String SHA1();
    }

    private static GitException error(String what, ObjCObjectByReference e) {
        NSError error = e.getValueAs(NSError.class);
        return new GitException(what + ": " + (error != null ? error.localizedDescription() : "unknown error"));
    }

    /** a rewrite on the loaded history, returns a GCReferenceTransform or null (error set) */
    private interface Rewrite {
        /** @param created a rewrite that makes the new commit itself puts its SHA1 at [0] */
        NSObject apply(GCHistoryRepository repo, GCHistory history, NSObject commit, ObjCObjectByReference newCommit, String[] created, ObjCObjectByReference error);
    }

    /**
     * loads the history, runs the rewrite on the commit, applies the reference transform.
     * without a conflict handler GitUpKit fails (nothing is changed) when a replay conflicts.
     *
     * @return the SHA1 of the new commit set by the rewrite, null when it sets none
     */
    private static String rewrite(Path workdir, String sha1, String what, Rewrite rewrite) {
        NSAutoreleasePool pool = NSAutoreleasePool.new_();
        try {
            ObjCObjectByReference e = new ObjCObjectByReference();
            GCRepository r = GCRepository.CLASS.alloc().initWithExistingLocalRepository_error(workdir.toString(), e);
            if (r == null) throw error("open", e);
            GCHistoryRepository repo = Rococoa.cast(r, GCHistoryRepository.class);

            GCHistory history = repo.loadHistoryUsingSorting_error(0, e);
            if (history == null) throw error("load history", e);
            NSObject commit = history.historyCommitWithSHA1(sha1);
            if (commit == null) throw new GitException("commit not found: " + sha1);

            ObjCObjectByReference newCommit = new ObjCObjectByReference();
            String[] created = new String[1];
            NSObject transform = rewrite.apply(repo, history, commit, newCommit, created, e);
            if (transform == null) throw error(what, e);
            if (!repo.applyReferenceTransform_error(transform, e)) throw error("update references", e);
            if (created[0] != null) return created[0];
            GCObject c = newCommit.getValueAs(GCObject.class);
            return c != null ? c.SHA1() : null;
        } finally {
            pool.drain();
        }
    }

    /**
     * replaces the message of a commit, descendants are rewritten and local branches (and HEAD) moved.
     *
     * @return the new commit id
     */
    public static String editMessage(Path workdir, String sha1, String message) {
        return rewrite(workdir, sha1, "rewrite", (repo, history, commit, newCommit, created, e) -> {
            NSObject updated = repo.copyCommit_withUpdatedMessage_updatedParents_updatedTreeFromIndex_updateCommitter_error(
                    commit, message, null, null, true, e);
            if (updated == null) return null;
            created[0] = Rococoa.cast(updated, GCObject.class).SHA1();
            return history.rewriteCommit_withUpdatedCommit_copyTrees_conflictHandler_error(commit, updated, true, null, e);
        });
    }

    /** melds the commit into its parent with the message, @return the new commit id */
    public static String squashWithParent(Path workdir, String sha1, String message) {
        return rewrite(workdir, sha1, "squash", (repo, history, commit, newCommit, created, e) ->
                history.squashCommit_withMessage_newCommit_error(commit, message, newCommit, e));
    }

    /** melds the commit into its parent keeping the parent's message, @return the new commit id */
    public static String fixupWithParent(Path workdir, String sha1) {
        return rewrite(workdir, sha1, "fixup", (repo, history, commit, newCommit, created, e) ->
                history.fixupCommit_newCommit_error(commit, newCommit, e));
    }

    /** removes the commit, descendants are replayed on its parent */
    public static void delete(Path workdir, String sha1) {
        rewrite(workdir, sha1, "delete (conflicts?)", (repo, history, commit, newCommit, created, e) ->
                history.deleteCommit_withConflictHandler_error(commit, null, e));
    }

    /** swaps the commit with its parent (moves it down), @return the new id of the commit */
    public static String swapWithParent(Path workdir, String sha1) {
        return rewrite(workdir, sha1, "swap (conflicts?)", (repo, history, commit, newCommit, created, e) ->
                history.swapCommitWithItsParent_conflictHandler_newChildCommit_newParentCommit_error(commit, null, null, newCommit, e));
    }

    /** swaps the commit with its (only) child (moves it up), @return the new id of the commit */
    public static String swapWithChild(Path workdir, String sha1) {
        return rewrite(workdir, sha1, "swap (conflicts?)", (repo, history, commit, newCommit, created, e) -> {
            NSArray children = Rococoa.cast(commit, GCHistoryCommit.class).children();
            if (children == null || children.count() != 1) throw new GitException("the commit needs exactly one child to move up");
            NSObject child = children.objectAtIndex(0);
            return history.swapCommitWithItsParent_conflictHandler_newChildCommit_newParentCommit_error(child, null, newCommit, null, e);
        });
    }

    /** GCObject (GCCommit) */
    public abstract static class GCObject extends NSObject {
        public abstract String SHA1();
    }
}
