# vavi.apps.gitup

## Usage

* one window, a tab per repository (the tabs are restored at the next start, repositories given on the command line are added)
* window size / position, split panes and log columns, the repository browser (shown or not, size, position) are remembered
* repository browser (⌘B): bookmarks in nested groups, search, drag to regroup, drop folders from the Finder,
  import SourceTree's bookmarks (Repository Browser menu), expanded / collapsed groups are remembered,
  ahead ↑ / behind ↓ counts of the current branch against its upstream (as of the last fetch, recomputed when the window
  is activated or with ⌘R)
* search (⌘F) the whole history: commit messages, file names and changed lines, a result jumps to the commit, file and line
* SourceTree-like looks: light blue repository browser icons, ref labels with icons in the log (branch, current branch, tag),
  settings tabs with icons
* icons: SourceTree's, read at runtime from an installed SourceTree.app (not distributed), or the built-in ones
  (`-Dgitup.icons=builtin|sourcetree`, `-Dgitup.sourcetree=/path/to/Sourcetree.app`)
* 3 panes: log with graph / staged + unstaged files (checkbox, drag & drop) / hunk diff
* hunk diff is lazy: only hunk headers are read when a file is selected, line texts are fetched only for the visible rows
* stage / unstage / discard by file, hunk, or selected lines; on a commit: reverse a hunk or selected lines into the working copy
* a bar above the hunk diff shows the file (with its status icon); its "…" (ellipsis in a circle) menu switches show / ignore whitespace and lines of context (1 – 100) like SourceTree (hunk / line actions are off while whitespace is ignored)
* commit details like SourceTree's: the author's avatar, commit (full and short), parents (click to select), author, date,
  committer (with its avatar, and the commit date when not the author date), labels (branches, remote branches, tags as in the log), message.
  avatars come from GitHub (the commit on a github.com remote, a saved github.com account's token is used; noreply emails),
  then Gravatar, SourceTree's "mystery man" when none; cached in `~/Library/Caches/vavi-apps-gitup/avatars`
  (Settings › General › "Show avatars in commit details" to turn off)
* "Uncommitted changes" row in bold, "・" for its commit and author, the latest modification time of the changed files as its date
* file menu: open (default application), resolve using mine / theirs, stop tracking, ignore… (exact file, extension, everything beneath a folder, custom pattern;
  into .gitignore, .git/info/exclude or the global ignore file), move to trash
* commit, amend, commit message history (last 50, `-Dgitup.messageHistory=` to change)
* spell checking of the commit message with macOS's spell checker (as SourceTree's): red underlines,
  right click for guesses, "Ignore Spelling", "Learn Spelling"
* log columns: graph, description, commit, author (with email), date; select several commits to see the changes of the range
* SourceTree's dropdowns above the log (remembered per repository): all branches / current branch,
  show / hide remote branches, date order (`--date-order`) / ancestor order (`--topo-order`)
* undo (⌘Z) / redo (⇧⌘Z) of commit, amend, pull, reset, branch deletion and history rewrites
  (branches and HEAD go back; a commit's changes come back staged), kept over restarts
* GitUp's history rewriting in the log: edit message, edit author, squash / fixup into parent, move up / down, delete commit
* protect pushed commits (Settings › History, on by default): amend, edit message / author, squash, fixup, move, delete,
  reset, undo / redo that would rewrite or drop commits already on a remote branch are stopped, overriding needs a checkbox
  and a button (amend offers "Commit as New Commit"); the amend checkbox shows "(pushed)" when the last commit is pushed
* conflicts while moving / deleting a commit are resolved like GitUp: the conflicted files are checked out
  (HEAD detached), use mine / theirs, external merge tool or edit and mark resolved, then continue (the author is kept) or abort (nothing changes)
* branches / remotes / tags / stashes sidebar (a branch icon has the color of its line in the graph), checkout, new branch, stash / apply / pop / delete, show a stash's changes
* branches: rename, delete (SourceTree-like dialog: several at once, force regardless of merge status, the remote branches too),
  delete a branch on the server
* remotes (each with its branches in the sidebar): new, edit (name, URL, push URL), remove
* reset the current branch to a commit: soft / mixed / hard
* in the log: checkout (a branch at the commit or detached HEAD, optionally discarding local changes),
  merge (commit immediately or not, no fast-forward), cherry-pick (commit immediately or not, keeps the author,
  a merge commit against a chosen parent)
* toolbar count badges like SourceTree: commits behind on Pull, ahead on Push (against the upstream, as of the last fetch)
* background fetch like SourceTree ("Check default remotes for updates every 10 minutes", also after checking out a branch):
  quiet, saved accounts only, so the badges show a branch merged on the server
* push dialog like SourceTree: remote, branches to push (remote branch name, track), push all tags, force push
* fetch / push (GitUpKit transport, ssh keys, credential prompts),
  pull (fast-forward, merge or rebase: "Pull with Rebase" or `pull.rebase`; on conflicts resolve, then commit / continue, or abort)
* live refresh with FSEvents (changes to ignored files only are skipped)
* settings (⌘,)
    * general: background fetch interval (or never), spell checking of commit messages
    * accounts: service, host, username, protocol; passwords / tokens in the macOS Keychain (shared with git's osxkeychain helper),
      used when a fetch / push asks for credentials; import from `~/.m2/settings.xml` servers (checkbox per server,
      service / host guessed from the id, token headers of GitLab style servers too, encrypted passwords are skipped)
    * diff: colors (added / removed lines background and text like SourceTree, hunk header / selection),
      font (SourceTree's Menlo 12 by default, family / size), lines of context,
      external diff and merge tools (FileMerge, VS Code, Kaleidoscope, Beyond Compare, Meld, P4Merge or a custom command)
* file menu: external diff, external merge tool for a conflicted file
* command history (⇧⌘H): the git commands equivalent to what was done (libgit2 / GitUpKit calls, GitUp's rewrites as
  `git rebase -i` with a note), ⌘C copies them ready for a terminal
* copy (⌘C) almost everywhere, context menus for SHA, message, paths, lines, hunks, patch

without a repository the last session's tabs are restored, or the repository browser is shown.

### Gitup

* GitUp.app is looked up at `~/Applications/GitUp.app`, then `/Applications/GitUp.app`,
  or set `-Dgitup.framework=/path/to/GitUp.app` (also asked and remembered at startup when not found)
* `-Dgitup.theme=dark` for the dark theme
* bookmarks are stored in `~/Library/Application Support/vavi-apps-gitup/bookmarks.txt` (`-Dgitup.bookmarks=` to change)

### Build VaviGitUp.app

* not named "GitUp.app", that is GitUp itself (`-Djavapackager.name=` to change)
* the JRE is not bundled: `JAVA_HOME` or the newest installed JDK 25+ (`/usr/libexec/java_home -v 25+`) is used
* `Contents/MacOS/VaviGitUp` is a native launcher (`src/main/native/launcher.c`, starts the JVM in process with `JLI_Launch`),
  its JVM options must be kept in sync with the `vmArgs` in `pom.xml`
* ad-hoc signed, arm64 only

### Notes

* GitUpKit asserts that it is loaded on the process main thread, the app dispatches `dlopen` to thread 0
* libgit2 structures are pinned to the GitUp build (e.g. `git_diff_file` is 64 bytes there)
* `git_checkout_tree` with `NULL` options is a dry run, the app always passes `GIT_CHECKOUT_SAFE`
* live refresh uses FSEvents directly, not `GCLiveRepository`, which loads the whole history when created
* "Edit Message" uses GitUp's `GCHistory` rewriting, the history is loaded only for that operation
* GitUp's conflict resolver is replaced by the app's dialog (a GitUpKit conflict handler block through rococoa),
  while it is shown the git thread keeps running the dialog's tasks. GitUp does not swap with a root commit
* "Edit Author" makes a copy of the commit with libgit2 (`git_commit_amend`, the author date is kept),
  then GitUp's `GCHistory` rewrite replaces the commit with it
* github credential needs "contents" and "workflow" both "rw" 
