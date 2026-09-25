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
    }

    private static GitException error(String what, ObjCObjectByReference e) {
        NSError error = e.getValueAs(NSError.class);
        return new GitException(what + ": " + (error != null ? error.localizedDescription() : "unknown error"));
    }

    /**
     * replaces the message of a commit, descendants are rewritten and local branches (and HEAD) moved.
     *
     * @return the new commit id
     */
    public static String editMessage(Path workdir, String sha1, String message) {
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

            NSObject updated = repo.copyCommit_withUpdatedMessage_updatedParents_updatedTreeFromIndex_updateCommitter_error(
                    commit, message, null, null, true, e);
            if (updated == null) throw error("copy commit", e);
            NSObject transform = history.rewriteCommit_withUpdatedCommit_copyTrees_conflictHandler_error(commit, updated, true, null, e);
            if (transform == null) throw error("rewrite", e);
            if (!repo.applyReferenceTransform_error(transform, e)) throw error("update references", e);
            return Rococoa.cast(updated, GCObject.class).SHA1();
        } finally {
            pool.drain();
        }
    }

    /** GCObject (GCCommit) */
    public abstract static class GCObject extends NSObject {
        public abstract String SHA1();
    }
}
