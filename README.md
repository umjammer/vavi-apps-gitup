[![Release](https://jitpack.io/v/umjammer/vavi-apps-gitup.svg)](https://jitpack.io/#umjammer/vavi-apps-gitup)
[![Java CI](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-apps-gitup

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-apps-gitup)

SourceTree-like git GUI (Swing) using [GitUp](https://gitup.co)'s `GitUpKit.framework` as the engine through JNA.

 * 3 panes: log with graph / staged + unstaged files (checkbox, drag & drop) / hunk diff
 * hunk diff is lazy: only hunk headers are read when a file is selected, line texts are fetched only for the visible rows
 * stage / unstage / discard by file, hunk, or selected lines
 * commit, branches / remotes / tags sidebar, checkout, new branch
 * fetch / push (GitUpKit transport, ssh keys, credential prompts), pull (fast-forward only)
 * copy (⌘C) almost everywhere, context menus for SHA, message, paths, lines, hunks, patch

## Usage

requires macOS and GitUp.app (tested with 1.4.0, embedding libgit2 1.4.4).

```shell
$ mvn package
$ mvn exec:exec -Drepo=/path/to/repository
```

 * GitUp.app is looked up at `~/Applications/GitUp.app`, then `/Applications/GitUp.app`,
   or set `-Dgitup.framework=/path/to/GitUp.app` (also asked and remembered at startup when not found)
 * `-Dgitup.theme=dark` for the dark theme

### Notes

 * GitUpKit asserts that it is loaded on the process main thread, the app dispatches `dlopen` to thread 0
 * libgit2 structures are pinned to the GitUp build (e.g. `git_diff_file` is 64 bytes there)
 * `git_checkout_tree` with `NULL` options is a dry run, the app always passes `GIT_CHECKOUT_SAFE`

## References

 * https://github.com/git-up/GitUp
 * https://libgit2.org/libgit2/#v1.4.4

## TODO

 * merge / rebase on pull (only fast-forward now)
 * amend, stash
 * live refresh via `GCLiveRepository` (now refreshes on window activation and after each operation)
