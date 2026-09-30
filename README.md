[![Release](https://jitpack.io/v/umjammer/vavi-apps-gitup.svg)](https://jitpack.io/#umjammer/vavi-apps-gitup)
[![Java CI](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-apps-gitup/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-apps-gitup

<img alt="logo" src="src/test/resources/duke_git.png" width="160" />

SourceTree-like git GUI (Swing) using [GitUp](https://gitup.co)'s `GitUpKit.framework` as the engine through JNA.

requires macOS and GitUp.app (tested with 1.4.0, embedding libgit2 1.4.4).

### 🏆️ Key Features

- ⚡️ ultra speed diff view rendering
- 🔍️ full text searcher
- 🛡️ prevent your repository that already pushed from `commit --amend` etc.
- 🚛️ moving subtree inter projects by gui
- 🪝 git hook manager that has preset scripts

## Install

### maven

 * [maven](https://jitpack.io/#umjammer/vavi-apps-gitup)

### Build VaviGitUp.app

on macOS `mvn package` also makes `target/VaviGitUp/VaviGitUp.app` (the `release` profile, same as
[vavi-apps-hub](https://github.com/umjammer/vavi-apps-hub)).

```shell
$ mvn package -DskipTests
$ open target/VaviGitUp/VaviGitUp.app
$ open -a target/VaviGitUp/VaviGitUp.app --args /path/to/repository
```

## Usage

### Details

 - [Details](src/main/java/vavi/apps/gitup/readme.md)

### by maven

```shell
$ mvn package
$ mvn exec:exec -Drepo=/path/to/repository
```

## References

 * https://github.com/git-up/GitUp
 * https://libgit2.org/libgit2/#v1.4.4

## TODO

 - app icon
 - DnD merge, cherry-pick
 - ~~when git push, git hook -> plugin, built-in like SNAPSHOT checker~~
 - ~~log pane toolbar, back gray, button bg gray, fg dark gray like `x` button of tab~~
 - ~~external tool search dir recursively~~
 - at repository browser, incremental search, when long time no see it, 1st char input causes spinning cursor. in that case delay start searching
 - hook editor: popup menu: make selected to preset
 - outsource hook preset, add search path by a system property

---

<sub>image designed by @umjammer, drawn by nano banana</sub>
