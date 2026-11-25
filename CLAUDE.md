# CLAUDE.md

Project instructions are shared with other agents and live in AGENTS.md:

@AGENTS.md

## Claude-specific notes

- On Windows, run Gradle from PowerShell as `.\gradlew.bat ...`.
- `gradlew run` opens a GUI window and blocks until it is closed; run it in the background or ask the
  user to check the result instead of waiting on it.
- Repository: https://github.com/jurriaanr/mesh-viewer (branch `main`).
- Line endings and the `gradlew` executable bit are pinned in `.gitattributes` / the index; a new shell
  script needs `git update-index --chmod=+x` on Windows.
