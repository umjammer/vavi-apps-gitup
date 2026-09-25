/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.objc;

import org.rococoa.ID;
import org.rococoa.ObjCClass;
import org.rococoa.ObjCObjectByReference;
import org.rococoa.Rococoa;
import org.rococoa.cocoa.foundation.NSArray;
import org.rococoa.cocoa.foundation.NSObject;

import vavi.apps.gitup.jna.LibGit2;


/**
 * GitUpKit GCRepository (and categories) used for remote operations.
 * <p>
 * selectors are mapped by rococoa: {@code ':'} becomes {@code '_'}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public abstract class GCRepository extends NSObject {

    static {
        // GitUpKit must be loaded (on the main thread) before the class is looked up
        LibGit2.INSTANCE.hashCode();
    }

    public static final _Class CLASS = Rococoa.createClass("GCRepository", _Class.class);

    public interface _Class extends ObjCClass {
        GCRepository alloc();
    }

    public abstract GCRepository initWithExistingLocalRepository_error(String path, ObjCObjectByReference error);

    public abstract String workingDirectoryPath();

    public abstract void setDelegate(ID delegate);

    // branches

    public abstract GCBranch findLocalBranchWithName_error(String name, ObjCObjectByReference error);

    public abstract GCBranch lookupUpstreamForLocalBranch_error(GCBranch branch, ObjCObjectByReference error);

    /** @param name "remote/branch" */
    public abstract GCBranch findRemoteBranchWithName_error(String name, ObjCObjectByReference error);

    /** deletes the branch on the remote (push :branch) and the remote branch reference */
    public abstract boolean deleteRemoteBranchFromRemote_error(GCBranch remoteBranch, ObjCObjectByReference error);

    public abstract NSArray listRemotes(ObjCObjectByReference error);

    public abstract GCRemote lookupRemoteWithName_error(String name, ObjCObjectByReference error);

    // remote

    /** @param tagMode GCFetchTagMode (0: auto, 1: none, 2: all) */
    public abstract boolean fetchRemoteBranch_tagMode_updatedTips_error(GCBranch remoteBranch, long tagMode, ID updatedTips, ObjCObjectByReference error);

    public abstract boolean fetchDefaultRemoteBranchesFromRemote_tagMode_prune_updatedTips_error(GCRemote remote, long tagMode, boolean prune, ID updatedTips, ObjCObjectByReference error);

    public abstract boolean pushLocalBranchToUpstream_force_usedRemote_error(GCBranch branch, boolean force, ID usedRemote, ObjCObjectByReference error);

    public abstract boolean pushLocalBranch_toRemote_force_setUpstream_error(GCBranch branch, GCRemote remote, boolean force, boolean setUpstream, ObjCObjectByReference error);

    public abstract boolean pushAllTagsToRemote_force_error(GCRemote remote, boolean force, ObjCObjectByReference error);

    /** GCBranch / GCLocalBranch / GCRemoteBranch */
    public abstract static class GCBranch extends NSObject {
        public abstract String name();
        public abstract String fullName();
    }

    /** GCRemote */
    public abstract static class GCRemote extends NSObject {
        public abstract String name();
        /** NSURL */
        public abstract NSObject URL();
    }
}
