/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import java.lang.System.Logger.Level;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Pointer;
import org.rococoa.ID;
import org.rococoa.ObjCBlock;
import org.rococoa.ObjCBlocks;
import org.rococoa.ObjCBlocks.BlockLiteral;
import org.rococoa.ObjCObjectByReference;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSArray;
import org.rococoa.cocoa.foundation.NSAutoreleasePool;
import org.rococoa.cocoa.foundation.NSError;
import org.rococoa.cocoa.foundation.NSObject;

import vavi.apps.gitup.model.GitException;

import static java.lang.System.getLogger;


/**
 * GitUp's history rewriting ("Edit Message" of any commit), not an ordinary git operation.
 * <p>
 * same steps as GitUp.app's {@code -[GIMapViewController editCommitMessage:]}:
 * copy the commit with the new message, replay the descendants with their trees copied
 * (so no conflict can happen), then move the references.
 * <p>
 * {@code GCHistory} holds the whole commit graph, it is loaded only for the operation and released after.
 * <p>
 * a replay that conflicts fails (nothing is changed) unless a {@link ConflictResolver} is given: then, like GitUp.app,
 * HEAD is detached at the commit replayed onto, the conflicted index is checked out in the working directory
 * and the resolver lets the user resolve it. the resolved index is committed in place of the replayed commit
 * and HEAD is restored.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public final class HistoryOps {

    private static final System.Logger logger = getLogger(HistoryOps.class.getName());

    private HistoryOps() {}

    /** GCRepository with the history categories */
    public abstract static class GCHistoryRepository extends GCRepository {
        /** @param sorting GCHistorySorting, 0: none */
        public abstract GCHistory loadHistoryUsingSorting_error(long sorting, ObjCObjectByReference error);

        public abstract NSObject copyCommit_withUpdatedMessage_updatedParents_updatedTreeFromIndex_updateCommitter_error(
                NSObject commit, String message, NSArray parents, NSObject index, boolean updateCommitter, ObjCObjectByReference error);

        public abstract boolean applyReferenceTransform_error(NSObject transform, ObjCObjectByReference error);

        public abstract NSObject findCommitWithSHA1_error(String sha1, ObjCObjectByReference error);

        // conflict resolution

        public abstract boolean lookupHEADCurrentCommit_branch_error(ObjCObjectByReference commit, ObjCObjectByReference branch, ObjCObjectByReference error);

        /** @param options GCCheckoutOptions, 1: force */
        public abstract boolean checkoutCommit_options_error(NSObject commit, long options, ObjCObjectByReference error);

        public abstract boolean checkoutLocalBranch_options_error(NSObject branch, long options, ObjCObjectByReference error);

        public abstract boolean checkoutIndex_withOptions_error(NSObject index, long options, ObjCObjectByReference error);

        public abstract boolean writeRepositoryIndex_error(NSObject index, ObjCObjectByReference error);

        public abstract GCIndex readRepositoryIndex(ObjCObjectByReference error);

        /** @param mode GCResetMode, 2: hard */
        public abstract boolean resetToHEAD_error(long mode, ObjCObjectByReference error);
    }

    /** GCIndex */
    public abstract static class GCIndex extends NSObject {
        public abstract boolean hasConflicts();
    }

    /**
     * lets the user resolve the conflicts of a replayed commit, called on the rewriting thread.
     * the working directory has the conflicted files (HEAD detached at {@code ours}), resolving means
     * staging the resolution, nothing must be committed.
     */
    public interface ConflictResolver {
        /**
         * @param ours the commit replayed onto
         * @param theirs the commit replayed
         * @param message the message of the commit to create
         * @return true when resolved, false aborts the whole rewrite (nothing is changed)
         */
        boolean resolve(String ours, String theirs, String message);
    }

    /** GCConflictHandler: {@code GCCommit* (^)(GCIndex*, GCCommit* ours, GCCommit* theirs, NSArray* parents, NSString* message, NSError**)} */
    public interface ConflictHandler extends ObjCBlock {
        ID apply(BlockLiteral block, ID index, ID ourCommit, ID theirCommit, ID parentCommits, ID message, Pointer outError);
    }

    /** GCHistory */
    public abstract static class GCHistory extends NSObject {
        public abstract NSObject historyCommitWithSHA1(String sha1);

        public abstract NSObject rewriteCommit_withUpdatedCommit_copyTrees_conflictHandler_error(
                NSObject commit, NSObject updatedCommit, boolean copyTrees, BlockLiteral conflictHandler, ObjCObjectByReference error);

        public abstract NSObject squashCommit_withMessage_newCommit_error(NSObject commit, String message, ObjCObjectByReference newCommit, ObjCObjectByReference error);

        public abstract NSObject fixupCommit_newCommit_error(NSObject commit, ObjCObjectByReference newCommit, ObjCObjectByReference error);

        public abstract NSObject deleteCommit_withConflictHandler_error(NSObject commit, BlockLiteral conflictHandler, ObjCObjectByReference error);

        public abstract NSObject swapCommitWithItsParent_conflictHandler_newChildCommit_newParentCommit_error(
                NSObject commit, BlockLiteral conflictHandler, ObjCObjectByReference newChildCommit, ObjCObjectByReference newParentCommit, ObjCObjectByReference error);
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
        NSObject apply(GCHistoryRepository repo, GCHistory history, NSObject commit, BlockLiteral handler, ObjCObjectByReference newCommit, String[] created, ObjCObjectByReference error);
    }

    /** what the conflict handler did during a rewrite */
    private static final class Resolution {
        /** the commits created, kept until the rewrite is done */
        final List<NSObject> keep = new ArrayList<>();
        /** why the handler returned nil (GitUpKit sets no error then) */
        String failure;
    }

    /** the conflict handler block given to GitUpKit, null without resolver */
    private static BlockLiteral conflictHandler(GCHistoryRepository repo, ConflictResolver resolver, Resolution resolution) {
        if (resolver == null) return null;
        ConflictHandler handler = (block, index, ours, theirs, parents, message, outError) -> {
            NSObject our = Rococoa.wrap(ours, NSObject.class);
            NSObject their = Rococoa.wrap(theirs, NSObject.class);
            ObjCObjectByReference e = new ObjCObjectByReference();
            ObjCObjectByReference head = new ObjCObjectByReference(), branch = new ObjCObjectByReference();
            if (!repo.lookupHEADCurrentCommit_branch_error(head, branch, e)) return failed(resolution, "HEAD", e);
            NSObject headCommit = head.getValueAs(NSObject.class), headBranch = branch.getValueAs(NSObject.class);
            try {
                // detach HEAD at "ours", the conflicted index into the repository and the working directory (conflict markers)
                if (!repo.checkoutCommit_options_error(our, 0, e)) return failed(resolution, "checkout", e);
                NSObject conflicted = Rococoa.wrap(index, NSObject.class);
                if (!repo.checkoutIndex_withOptions_error(conflicted, 0, e)) return failed(resolution, "checkout index", e);

                String msg = Rococoa.wrap(message, org.rococoa.cocoa.foundation.NSString.class).toString();
                if (!resolver.resolve(sha1(our), sha1(their), msg)) {
                    resolution.failure = "aborted";
                    return null;
                }

                GCIndex resolved = repo.readRepositoryIndex(e);
                if (resolved == null) return failed(resolution, "read index", e);
                if (resolved.hasConflicts()) {
                    resolution.failure = "conflicts are not resolved";
                    return null;
                }
                // a copy of "theirs" keeps its author
                NSObject commit = repo.copyCommit_withUpdatedMessage_updatedParents_updatedTreeFromIndex_updateCommitter_error(
                        their, msg, Rococoa.wrap(parents, NSArray.class), resolved, true, e);
                if (commit == null) return failed(resolution, "commit", e);
                resolution.keep.add(commit);
                return commit.id();
            } catch (RuntimeException x) {
                logger.log(Level.WARNING, x.getMessage(), x);
                resolution.failure = String.valueOf(x.getMessage());
                return null;
            } finally {
                // back to the branch (references are moved by the transform later), index and working directory too
                ObjCObjectByReference ignored = new ObjCObjectByReference();
                if (headBranch != null) {
                    repo.checkoutLocalBranch_options_error(headBranch, 1, ignored);
                } else if (headCommit != null) {
                    repo.checkoutCommit_options_error(headCommit, 1, ignored);
                }
                repo.resetToHEAD_error(2, ignored);
            }
        };
        return ObjCBlocks.block(handler);
    }

    private static ID failed(Resolution resolution, String what, ObjCObjectByReference e) {
        resolution.failure = error(what, e).getMessage();
        return null;
    }

    private static String sha1(NSObject commit) {
        return Rococoa.cast(commit, GCObject.class).SHA1();
    }

    /**
     * loads the history, runs the rewrite on the commit, applies the reference transform.
     * without a conflict handler GitUpKit fails (nothing is changed) when a replay conflicts.
     *
     * @return the SHA1 of the new commit set by the rewrite, null when it sets none
     */
    private static String rewrite(Path workdir, String sha1, String what, ConflictResolver resolver, Rewrite rewrite) {
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
            Resolution resolution = new Resolution();
            BlockLiteral handler = conflictHandler(repo, resolver, resolution);
            NSObject transform = rewrite.apply(repo, history, commit, handler, newCommit, created, e);
            java.lang.ref.Reference.reachabilityFence(handler);
            if (transform == null) {
                if (resolution.failure != null) throw new GitException(what + ": " + resolution.failure);
                throw error(what, e);
            }
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
        return rewrite(workdir, sha1, "rewrite", null, (repo, history, commit, handler, newCommit, created, e) -> {
            NSObject updated = repo.copyCommit_withUpdatedMessage_updatedParents_updatedTreeFromIndex_updateCommitter_error(
                    commit, message, null, null, true, e);
            if (updated == null) return null;
            created[0] = Rococoa.cast(updated, GCObject.class).SHA1();
            return history.rewriteCommit_withUpdatedCommit_copyTrees_conflictHandler_error(commit, updated, true, null, e);
        });
    }

    /**
     * replaces a commit with another one already created (e.g. with another author), descendants are
     * rewritten with their trees copied and local branches (and HEAD) moved.
     *
     * @return the replacement id
     */
    public static String rewriteWith(Path workdir, String sha1, String replacementSha1) {
        return rewrite(workdir, sha1, "rewrite", null, (repo, history, commit, handler, newCommit, created, e) -> {
            NSObject updated = repo.findCommitWithSHA1_error(replacementSha1, e);
            if (updated == null) return null;
            created[0] = replacementSha1;
            return history.rewriteCommit_withUpdatedCommit_copyTrees_conflictHandler_error(commit, updated, true, null, e);
        });
    }

    /** melds the commit into its parent with the message, @return the new commit id */
    public static String squashWithParent(Path workdir, String sha1, String message) {
        return rewrite(workdir, sha1, "squash", null, (repo, history, commit, handler, newCommit, created, e) ->
                history.squashCommit_withMessage_newCommit_error(commit, message, newCommit, e));
    }

    /** melds the commit into its parent keeping the parent's message, @return the new commit id */
    public static String fixupWithParent(Path workdir, String sha1) {
        return rewrite(workdir, sha1, "fixup", null, (repo, history, commit, handler, newCommit, created, e) ->
                history.fixupCommit_newCommit_error(commit, newCommit, e));
    }

    /** removes the commit, descendants are replayed on its parent. fails on a conflict */
    public static void delete(Path workdir, String sha1) {
        delete(workdir, sha1, null);
    }

    /** removes the commit, descendants are replayed on its parent, conflicts go to the resolver (nullable) */
    public static void delete(Path workdir, String sha1, ConflictResolver resolver) {
        rewrite(workdir, sha1, "delete (conflicts?)", resolver, (repo, history, commit, handler, newCommit, created, e) ->
                history.deleteCommit_withConflictHandler_error(commit, handler, e));
    }

    /** swaps the commit with its parent (moves it down), fails on a conflict, @return the new id of the commit */
    public static String swapWithParent(Path workdir, String sha1) {
        return swapWithParent(workdir, sha1, null);
    }

    /** swaps the commit with its parent (moves it down), conflicts go to the resolver (nullable), @return the new id of the commit */
    public static String swapWithParent(Path workdir, String sha1, ConflictResolver resolver) {
        return rewrite(workdir, sha1, "swap (conflicts?)", resolver, (repo, history, commit, handler, newCommit, created, e) ->
                history.swapCommitWithItsParent_conflictHandler_newChildCommit_newParentCommit_error(commit, handler, null, newCommit, e));
    }

    /** swaps the commit with its (only) child (moves it up), fails on a conflict, @return the new id of the commit */
    public static String swapWithChild(Path workdir, String sha1) {
        return swapWithChild(workdir, sha1, null);
    }

    /** swaps the commit with its (only) child (moves it up), conflicts go to the resolver (nullable), @return the new id of the commit */
    public static String swapWithChild(Path workdir, String sha1, ConflictResolver resolver) {
        return rewrite(workdir, sha1, "swap (conflicts?)", resolver, (repo, history, commit, handler, newCommit, created, e) -> {
            NSArray children = Rococoa.cast(commit, GCHistoryCommit.class).children();
            if (children == null || children.count() != 1) throw new GitException("the commit needs exactly one child to move up");
            NSObject child = children.objectAtIndex(0);
            return history.swapCommitWithItsParent_conflictHandler_newChildCommit_newParentCommit_error(child, handler, newCommit, null, e);
        });
    }

    /** GCObject (GCCommit) */
    public abstract static class GCObject extends NSObject {
        public abstract String SHA1();
    }
}
