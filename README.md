[![Release](https://jitpack.io/v/umjammer/vavi-apps-gitup.svg)](https://jitpack.io/#umjammer/vavi-apps-gitup)
[![Java CI](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-apps-gitup

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-apps-gitup)

SourceTree-like git GUI (Swing) using [GitUp](https://gitup.co)'s `GitUpKit.framework` as the engine through JNA.

 * one window, a tab per repository (tabs are restored at the next start)
 * repository browser (⌘B): bookmarks in nested groups, search, drag to regroup, drop folders from the Finder
 * 3 panes: log with graph / staged + unstaged files (checkbox, drag & drop) / hunk diff
 * hunk diff is lazy: only hunk headers are read when a file is selected, line texts are fetched only for the visible rows
 * stage / unstage / discard by file, hunk, or selected lines
 * file menu: open (default application), resolve using mine / theirs, stop tracking, ignore… (exact file, extension, everything beneath a folder, custom pattern;
   into .gitignore, .git/info/exclude or the global ignore file), move to trash
 * commit, amend, commit message history (last 50, `-Dgitup.messageHistory=` to change)
 * GitUp's "Edit Message" of any commit in the log (descendants are rewritten with the same trees)
 * branches / remotes / tags / stashes sidebar, checkout, new branch, stash / apply / pop / delete, show a stash's changes
 * fetch / push (GitUpKit transport, ssh keys, credential prompts),
   pull (fast-forward, merge with conflicts / abort, or rebase: "Pull with Rebase" or `pull.rebase`)
 * live refresh with FSEvents (changes to ignored files only are skipped)
 * copy (⌘C) almost everywhere, context menus for SHA, message, paths, lines, hunks, patch

## Usage

requires macOS and GitUp.app (tested with 1.4.0, embedding libgit2 1.4.4).

```shell
$ mvn package
$ mvn exec:exec -Drepo=/path/to/repository
```

without a repository the last session's tabs are restored, or the repository browser is shown.

 * GitUp.app is looked up at `~/Applications/GitUp.app`, then `/Applications/GitUp.app`,
   or set `-Dgitup.framework=/path/to/GitUp.app` (also asked and remembered at startup when not found)
 * `-Dgitup.theme=dark` for the dark theme
 * bookmarks are stored in `~/Library/Application Support/vavi-apps-gitup/bookmarks.txt` (`-Dgitup.bookmarks=` to change)

### Notes

 * GitUpKit asserts that it is loaded on the process main thread, the app dispatches `dlopen` to thread 0
 * libgit2 structures are pinned to the GitUp build (e.g. `git_diff_file` is 64 bytes there)
 * `git_checkout_tree` with `NULL` options is a dry run, the app always passes `GIT_CHECKOUT_SAFE`
 * live refresh uses FSEvents directly, not `GCLiveRepository`, which loads the whole history when created
 * "Edit Message" uses GitUp's `GCHistory` rewriting, the history is loaded only for that operation
 * a rebase that hits conflicts is aborted (resolve by pulling with merge)

## References

 * https://github.com/git-up/GitUp
 * https://libgit2.org/libgit2/#v1.4.4

## TODO

 * continue a conflicted rebase (now aborted)
 * more of GitUp's rewriting: squash, fixup, delete commit, swap with parent
