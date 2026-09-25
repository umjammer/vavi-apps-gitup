/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.apps.gitup.jna;

import java.util.Map;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.rococoa.Foundation;

import vavi.apps.gitup.jna.Structs.GitDiffOptions;
import vavi.apps.gitup.jna.Structs.GitOid;
import vavi.apps.gitup.jna.Structs.GitStatusOptions;
import vavi.apps.gitup.jna.Structs.GitStrarray;


/**
 * libgit2 1.4.4 C API exported by GitUpKit.framework.
 * <p>
 * only the functions this app uses are declared. opaque handles are {@link Pointer}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-09-25 nsano initial version <br>
 */
public interface LibGit2 extends Library {

    LibGit2 INSTANCE = Loader.load();

    final class Loader {
        private Loader() {}
        /**
         * GitUpKit's {@code +[GCRepository load]} asserts {@code pthread_main_np() > 0},
         * so the framework must be dlopen'ed on the process main thread (thread 0).
         * the java launcher on macOS keeps thread 0 in a CFRunLoop, so we can dispatch there.
         */
        static LibGit2 load() {
            String path = GitUpKitLocator.locate().toString();
            LibGit2 lib = Foundation.callOnMainThread(() ->
                    Native.load(path, LibGit2.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8")));
            lib.git_libgit2_init();
            return lib;
        }
    }

    // constants

    int GIT_OK = 0;
    int GIT_ENOTFOUND = -3;
    int GIT_EUNBORNBRANCH = -9;
    int GIT_ITEROVER = -31;

    int GIT_OBJECT_COMMIT = 1;
    int GIT_OBJECT_TREE = 2;

    int GIT_SORT_TOPOLOGICAL = 1 << 0;
    int GIT_SORT_TIME = 1 << 1;

    int GIT_STATUS_CURRENT = 0;
    int GIT_STATUS_INDEX_NEW = 1 << 0;
    int GIT_STATUS_INDEX_MODIFIED = 1 << 1;
    int GIT_STATUS_INDEX_DELETED = 1 << 2;
    int GIT_STATUS_INDEX_RENAMED = 1 << 3;
    int GIT_STATUS_INDEX_TYPECHANGE = 1 << 4;
    int GIT_STATUS_WT_NEW = 1 << 7;
    int GIT_STATUS_WT_MODIFIED = 1 << 8;
    int GIT_STATUS_WT_DELETED = 1 << 9;
    int GIT_STATUS_WT_TYPECHANGE = 1 << 10;
    int GIT_STATUS_WT_RENAMED = 1 << 11;
    int GIT_STATUS_WT_UNREADABLE = 1 << 12;
    int GIT_STATUS_IGNORED = 1 << 14;
    int GIT_STATUS_CONFLICTED = 1 << 15;

    int GIT_STATUS_SHOW_INDEX_AND_WORKDIR = 0;

    int GIT_STATUS_OPT_INCLUDE_UNTRACKED = 1 << 0;
    int GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS = 1 << 4;
    int GIT_STATUS_OPT_RENAMES_HEAD_TO_INDEX = 1 << 7;
    int GIT_STATUS_OPT_SORT_CASE_SENSITIVELY = 1 << 9;

    int GIT_DIFF_NORMAL = 0;
    int GIT_DIFF_INCLUDE_UNTRACKED = 1 << 3;
    int GIT_DIFF_RECURSE_UNTRACKED_DIRS = 1 << 4;
    int GIT_DIFF_DISABLE_PATHSPEC_MATCH = 1 << 12;
    int GIT_DIFF_SHOW_UNTRACKED_CONTENT = 1 << 25;

    int GIT_DELTA_UNMODIFIED = 0;
    int GIT_DELTA_ADDED = 1;
    int GIT_DELTA_DELETED = 2;
    int GIT_DELTA_MODIFIED = 3;
    int GIT_DELTA_RENAMED = 4;
    int GIT_DELTA_COPIED = 5;
    int GIT_DELTA_IGNORED = 6;
    int GIT_DELTA_UNTRACKED = 7;
    int GIT_DELTA_TYPECHANGE = 8;
    int GIT_DELTA_UNREADABLE = 9;
    int GIT_DELTA_CONFLICTED = 10;

    int GIT_DIFF_FLAG_BINARY = 1 << 0;

    int GIT_APPLY_LOCATION_WORKDIR = 0;
    int GIT_APPLY_LOCATION_INDEX = 1;
    int GIT_APPLY_LOCATION_BOTH = 2;

    int GIT_RESET_SOFT = 1;
    int GIT_RESET_MIXED = 2;
    int GIT_RESET_HARD = 3;

    int GIT_BRANCH_LOCAL = 1;
    int GIT_BRANCH_REMOTE = 2;

    // library

    int git_libgit2_init();
    int git_libgit2_version(IntByReference major, IntByReference minor, IntByReference rev);
    Pointer git_error_last();

    // repository

    int git_repository_open(PointerByReference out, String path);
    void git_repository_free(Pointer repo);
    String git_repository_workdir(Pointer repo);
    String git_repository_path(Pointer repo);
    int git_repository_index(PointerByReference out, Pointer repo);
    int git_repository_head(PointerByReference out, Pointer repo);
    int git_repository_head_unborn(Pointer repo);
    int git_repository_head_detached(Pointer repo);
    int git_repository_set_head(Pointer repo, String refname);
    int git_repository_state(Pointer repo);

    // references

    int git_reference_lookup(PointerByReference out, Pointer repo, String name);
    int git_reference_iterator_new(PointerByReference out, Pointer repo);
    int git_reference_next(PointerByReference out, Pointer iter);
    void git_reference_iterator_free(Pointer iter);
    String git_reference_name(Pointer ref);
    String git_reference_shorthand(Pointer ref);
    Pointer git_reference_target(Pointer ref);
    int git_reference_is_branch(Pointer ref);
    int git_reference_is_remote(Pointer ref);
    int git_reference_is_tag(Pointer ref);
    int git_reference_peel(PointerByReference out, Pointer ref, int type);
    void git_reference_free(Pointer ref);

    int git_branch_lookup(PointerByReference out, Pointer repo, String name, int type);
    int git_branch_upstream(PointerByReference out, Pointer branch);
    int git_branch_create(PointerByReference out, Pointer repo, String name, Pointer target, int force);
    int git_branch_set_upstream(Pointer branch, String branchName);
    int git_branch_delete(Pointer branch);
    int git_branch_move(PointerByReference out, Pointer branch, String newName, int force);
    int git_branch_is_head(Pointer branch);
    int git_branch_name_is_valid(IntByReference valid, String name);

    // remotes

    int git_remote_list(GitStrarray out, Pointer repo);
    int git_remote_lookup(PointerByReference out, Pointer repo, String name);
    String git_remote_url(Pointer remote);
    String git_remote_pushurl(Pointer remote);
    void git_remote_free(Pointer remote);
    int git_remote_create(PointerByReference out, Pointer repo, String name, String url);
    int git_remote_set_url(Pointer repo, String remote, String url);
    int git_remote_set_pushurl(Pointer repo, String remote, String url);
    int git_remote_rename(GitStrarray problems, Pointer repo, String name, String newName);
    int git_remote_delete(Pointer repo, String name);
    int git_remote_name_is_valid(IntByReference valid, String name);
    void git_strarray_dispose(GitStrarray array);
    int git_reference_set_target(PointerByReference out, Pointer ref, GitOid id, String logMessage);
    int git_reference_create(PointerByReference out, Pointer repo, String name, GitOid id, int force, String logMessage);
    int git_repository_set_head_detached(Pointer repo, GitOid commitish);

    int git_graph_descendant_of(Pointer repo, GitOid commit, GitOid ancestor);

    // objects

    Pointer git_object_id(Pointer obj);
    void git_object_free(Pointer obj);
    int git_revparse_single(PointerByReference out, Pointer repo, String spec);

    // oid

    int git_oid_fromstr(GitOid out, String str);
    String git_oid_tostr_s(Pointer oid);

    // status

    int git_status_options_init(GitStatusOptions opts, int version);
    int git_status_list_new(PointerByReference out, Pointer repo, GitStatusOptions opts);
    NativeLong git_status_list_entrycount(Pointer list);
    Pointer git_status_byindex(Pointer list, NativeLong idx);
    void git_status_list_free(Pointer list);

    // index

    int git_index_read(Pointer index, int force);
    int git_index_write(Pointer index);
    int git_index_add_bypath(Pointer index, String path);
    int git_index_remove_bypath(Pointer index, String path);
    int git_index_write_tree(GitOid out, Pointer index);
    /** @return const git_index_entry*, null when not found */
    Pointer git_index_get_bypath(Pointer index, String path, int stage);
    int git_index_has_conflicts(Pointer index);
    void git_index_free(Pointer index);

    // diff

    int git_diff_options_init(GitDiffOptions opts, int version);
    int git_diff_index_to_workdir(PointerByReference out, Pointer repo, Pointer index, GitDiffOptions opts);
    int git_diff_tree_to_index(PointerByReference out, Pointer repo, Pointer oldTree, Pointer index, GitDiffOptions opts);
    int git_diff_tree_to_tree(PointerByReference out, Pointer repo, Pointer oldTree, Pointer newTree, GitDiffOptions opts);
    int git_diff_from_buffer(PointerByReference out, byte[] content, NativeLong len);
    NativeLong git_diff_num_deltas(Pointer diff);
    Pointer git_diff_get_delta(Pointer diff, NativeLong idx);
    void git_diff_free(Pointer diff);

    // patch

    int git_patch_from_diff(PointerByReference out, Pointer diff, NativeLong idx);
    Pointer git_patch_get_delta(Pointer patch);
    NativeLong git_patch_num_hunks(Pointer patch);
    int git_patch_get_hunk(PointerByReference out, NativeLongByReference linesInHunk, Pointer patch, NativeLong hunkIdx);
    int git_patch_num_lines_in_hunk(Pointer patch, NativeLong hunkIdx);
    int git_patch_get_line_in_hunk(PointerByReference out, Pointer patch, NativeLong hunkIdx, NativeLong lineOfHunk);
    void git_patch_free(Pointer patch);

    // apply

    int git_apply(Pointer repo, Pointer diff, int location, Pointer options);

    // reset / checkout

    int git_reset_default(Pointer repo, Pointer target, GitStrarray pathspecs);
    int git_checkout_options_init(Pointer opts, int version);
    /** NOTE: NULL opts is a dry run (GIT_CHECKOUT_NONE), use {@link #safeCheckoutOptions()} */
    int git_checkout_tree(Pointer repo, Pointer treeish, Pointer opts);

    int GIT_CHECKOUT_SAFE = 1 << 0;
    int GIT_CHECKOUT_FORCE = 1 << 1;

    /** git_checkout_options with GIT_CHECKOUT_FORCE (local changes are overwritten) */
    static Pointer forceCheckoutOptions() {
        Pointer m = safeCheckoutOptions();
        m.setInt(4, GIT_CHECKOUT_FORCE);
        return m;
    }

    /** git_checkout_options with GIT_CHECKOUT_SAFE, generously sized */
    static Pointer safeCheckoutOptions() {
        com.sun.jna.Memory m = new com.sun.jna.Memory(512);
        m.clear();
        INSTANCE.git_checkout_options_init(m, 1);
        m.setInt(4, GIT_CHECKOUT_SAFE); // checkout_strategy
        return m;
    }

    // merge

    int GIT_REPOSITORY_STATE_NONE = 0;
    int GIT_REPOSITORY_STATE_MERGE = 1;
    int GIT_REPOSITORY_STATE_CHERRYPICK = 4;
    int GIT_REPOSITORY_STATE_REBASE = 7;
    int GIT_REPOSITORY_STATE_REBASE_INTERACTIVE = 8;
    int GIT_REPOSITORY_STATE_REBASE_MERGE = 9;

    int GIT_MERGE_ANALYSIS_NORMAL = 1 << 0;
    int GIT_MERGE_ANALYSIS_UP_TO_DATE = 1 << 1;
    int GIT_MERGE_ANALYSIS_FASTFORWARD = 1 << 2;
    int GIT_MERGE_ANALYSIS_UNBORN = 1 << 3;

    int GIT_CHECKOUT_ALLOW_CONFLICTS = 1 << 4;

    int git_annotated_commit_lookup(PointerByReference out, Pointer repo, GitOid id);
    void git_annotated_commit_free(Pointer commit);
    int git_merge_analysis(IntByReference analysis, IntByReference preference, Pointer repo, Pointer[] theirHeads, NativeLong len);
    int git_merge(Pointer repo, Pointer[] theirHeads, NativeLong len, Pointer mergeOpts, Pointer checkoutOpts);
    int git_repository_state_cleanup(Pointer repo);
    /** opts may be NULL: GIT_CHECKOUT_SAFE | GIT_CHECKOUT_ALLOW_CONFLICTS */
    int git_cherrypick(Pointer repo, Pointer commit, Pointer opts);
    int git_cherrypick_options_init(Pointer opts, int version);

    /**
     * git_cherrypick_options choosing the mainline parent of a merge commit, with
     * GIT_CHECKOUT_SAFE | GIT_CHECKOUT_ALLOW_CONFLICTS.
     * <pre>
     * unsigned int version @0; unsigned int mainline @4;
     * git_merge_options merge_opts @8 (48 bytes); git_checkout_options checkout_opts @56 (strategy @60)
     * </pre>
     * the layout is checked with the values git_cherrypick_options_init writes.
     */
    static Pointer cherrypickOptions(int mainline) {
        com.sun.jna.Memory m = new com.sun.jna.Memory(1024);
        m.clear();
        INSTANCE.git_cherrypick_options_init(m, 1);
        if (m.getInt(0) != 1 || m.getInt(8) != 1 || m.getInt(56) != 1 || m.getInt(60) != GIT_CHECKOUT_SAFE) {
            throw new IllegalStateException("unexpected git_cherrypick_options layout in this libgit2");
        }
        m.setInt(4, mainline);
        m.setInt(60, GIT_CHECKOUT_SAFE | GIT_CHECKOUT_ALLOW_CONFLICTS);
        return m;
    }
    int git_reset(Pointer repo, Pointer target, int resetType, Pointer checkoutOpts);

    // rebase

    int GIT_EAPPLIED = -18;

    int git_rebase_init(PointerByReference out, Pointer repo, Pointer branch, Pointer upstream, Pointer onto, Pointer opts);
    int git_rebase_open(PointerByReference out, Pointer repo, Pointer opts);
    int git_rebase_next(PointerByReference operation, Pointer rebase);
    int git_rebase_commit(GitOid id, Pointer rebase, Pointer author, Pointer committer, String encoding, String message);
    int git_rebase_finish(Pointer rebase, Pointer signature);
    int git_rebase_abort(Pointer rebase);
    void git_rebase_free(Pointer rebase);

    // conflicts / blobs

    /** out parameters are const git_index_entry* */
    int git_index_conflict_get(PointerByReference ancestor, PointerByReference ours, PointerByReference theirs, Pointer index, String path);
    int git_blob_lookup(PointerByReference out, Pointer repo, GitOid id);
    Pointer git_blob_rawcontent(Pointer blob);
    long git_blob_rawsize(Pointer blob);
    void git_blob_free(Pointer blob);

    // stash

    int GIT_STASH_KEEP_INDEX = 1 << 0;
    int GIT_STASH_INCLUDE_UNTRACKED = 1 << 1;

    interface StashCallback extends com.sun.jna.Callback {
        int invoke(NativeLong index, String message, Pointer stashId, Pointer payload);
    }

    int git_stash_save(GitOid out, Pointer repo, Pointer stasher, String message, int flags);
    int git_stash_apply(Pointer repo, NativeLong index, Pointer options);
    int git_stash_pop(Pointer repo, NativeLong index, Pointer options);
    int git_stash_drop(Pointer repo, NativeLong index);
    int git_stash_foreach(Pointer repo, StashCallback callback, Pointer payload);

    // config / ignore

    int git_repository_config_snapshot(PointerByReference out, Pointer repo);
    int git_config_get_string(PointerByReference out, Pointer cfg, String name);
    int git_config_get_bool(IntByReference out, Pointer cfg, String name);
    void git_config_free(Pointer cfg);
    int git_ignore_path_is_ignored(IntByReference ignored, Pointer repo, String path);

    // revwalk

    int git_revwalk_new(PointerByReference out, Pointer repo);
    int git_revwalk_sorting(Pointer walk, int mode);
    int git_revwalk_push_head(Pointer walk);
    int git_revwalk_push_glob(Pointer walk, String glob);
    int git_revwalk_next(GitOid out, Pointer walk);
    void git_revwalk_free(Pointer walk);

    // commit

    int git_commit_lookup(PointerByReference out, Pointer repo, GitOid id);
    Pointer git_commit_id(Pointer commit);
    String git_commit_summary(Pointer commit);
    String git_commit_message(Pointer commit);
    Pointer git_commit_author(Pointer commit);
    Pointer git_commit_committer(Pointer commit);
    long git_commit_time(Pointer commit);
    int git_commit_parentcount(Pointer commit);
    Pointer git_commit_parent_id(Pointer commit, int n);
    int git_commit_tree(PointerByReference out, Pointer commit);
    void git_commit_free(Pointer commit);
    int git_commit_amend(GitOid id, Pointer commitToAmend, String updateRef, Pointer author, Pointer committer,
                         String encoding, String message, Pointer tree);
    int git_commit_create(GitOid id, Pointer repo, String updateRef, Pointer author, Pointer committer,
                          String encoding, String message, Pointer tree, NativeLong parentCount, Pointer[] parents);

    // tree

    int git_tree_lookup(PointerByReference out, Pointer repo, GitOid id);
    void git_tree_free(Pointer tree);

    // signature

    int git_signature_default(PointerByReference out, Pointer repo);
    /** @param offset timezone offset in minutes */
    int git_signature_new(PointerByReference out, String name, String email, long time, int offset);
    void git_signature_free(Pointer sig);
}
