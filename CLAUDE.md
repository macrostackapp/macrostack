# MacroStack — working notes for Claude

MacroStack is an open-source (GPL-3.0) Android focus-bracketing camera with its own on-phone
focus-stacking engine, developed and tested on a **Samsung Galaxy S24 Ultra** with a clip-on macro lens.
Everything needed to continue the work is in this file, `docs/ENGINE_LOG.md` and `tools/bench/`.

Machine- and maintainer-specific notes (paths, accounts, the signing key, what needs the maintainer's OK)
are in `CLAUDE.local.md`, which is not in git. Read it first if it exists.

## Layout

- `app/src/main/java/com/macrostack/app/` — the app. `fusion/` is the stacking engine (pure Kotlin, no
  Android code): `StackFusion.kt` pipeline, `Aligner.kt`, `Warp.kt`, `DepthMapFuser.kt`, `PyramidFuser.kt`.
  `README.md` describes the app and how the engine works, for users.
- `app/src/test/.../fusion/` — `FusionTest` (synthetic stacks with a known answer; must keep passing),
  `RealStackTest` (opt-in, real frames; see its doc comment for the env vars).
- `docs/ENGINE_LOG.md` — engine status, benchmark baseline, known flaws, backlog, what didn't work, history.
  **Read it first** for any engine work; update it after every change.
- `docs/CONTRIBUTORS.md` — everyone who sent test stacks, and the datasets used, with their licences.
- `tools/bench/` — benchmark against other stackers (README there); `sets.txt` lists the test stacks;
  `history/` keeps each version's scores; `work/` is disposable.
- `tools/focus-stack/` — focus-stack 1.5 for Windows (MIT), and `tools/shinestacker-env/` — Shine Stacker 1.17
  (Python, its own environment): the comparison engines. Both run automatically in the benchmark.
- `samples/` — test stacks (large, not code). Licences: `pro_macro` CC BY 4.0, credit Johannes Sood if
  shown anywhere; `pcb*` from focus-stack (MIT); `phone_dff` research data (Suwajanakorn et al. 2015), not
  to be republished.
- `site/` — the project website (static HTML, no build step), deployed to GitHub Pages.
- `.github/` — the Pages workflow and the issue forms (sharing a stack, reporting a problem).

## Versions and releases

- Each finished version is one commit on `main`, tagged `vX.Y` (or `vX.Y.Z`), and published as a GitHub
  release with the signed APK attached as `MacroStack.apk` (always that name: the website's download button
  links to `releases/latest/download/MacroStack.apk`).
- Not tracked (see `.gitignore`): `samples/` (test stacks, 2 GB), `tools/focus-stack/` (focus-stack 1.5 for
  Windows from github.com/PetteriAimonen/focus-stack/releases), `tools/shinestacker-env/` (`python -m venv`
  + `pip install shinestacker`), `tools/bench/work/`, `MacroStack.apk`, the signing key (`keystore/`,
  `keystore.properties`) and `CLAUDE.local.md`. Re-getting a missing tool is a download: ask first.
- `.gitattributes` keeps LF line endings (shell scripts break with CRLF).

## Build, test, release

- JDK 17 and the Android SDK (`local.properties`).
- `./gradlew testDebugUnitTest lintDebug assembleRelease` — tests must pass, lint must have 0 errors.
  The release APK (`app/build/outputs/apk/release/app-release.apk`) is signed with the key named in
  `keystore.properties`; without that file it comes out unsigned. Android only installs an update signed
  with the same key as the installed app, so that key must never change.
- A release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, build, commit, tag, then
  `gh release create vX.Y MacroStack.apk --title "MacroStack X.Y" --notes ...`. Update the version line on the
  website (`site/index.html`), and its test results (from `tools/bench/history/`) when the engine changed.
- No phone or emulator in the development setup: UI and camera code can't be run here. Testers install the
  APK and report back (screenshots, Settings → Camera info → Copy).
- Website images must be the maintainer's own photos, or credited as their licence requires
  (`docs/CONTRIBUTORS.md`); contributors' stacks only with their permission.

## The engine improvement loop

When asked to continue improving the engine, or when new stacks appear in `samples\` (also: `/improve-engine`):

1. **Catch up.** Read `docs/ENGINE_LOG.md` (flaws, backlog, what didn't work) and `tools/bench/sets.txt`.
   Look for new folders in `samples\` and register each in `sets.txt` (about one in three as `holdout`;
   never tune on holdout sets' worst blocks). Stacks from other photographers: record who sent them in
   `docs/CONTRIBUTORS.md` (everyone who helps is credited; ask the maintainer for anything missing).
2. **Baseline.** `sh tools/bench/bench_all.sh <label>` (all sets; new ones get focus-stack run once) and
   `python tools/bench/compare_scores.py tools/bench/history/<latest>.txt tools/bench/work/bench/score_<label>.txt`.
3. **Find flaws** on tune sets: worst-block sheets (`bench_score.py SET auto 0 tools/bench/work/ppm_SET 32 dm|py 6`),
   then *look at the images* (the score is fooled by hot pixels and noise). Explain a flaw by probing the
   depth map (`MACROSTACK_PROBE="x,y"` with `RealStackTest` prints what each frame measured there) or with
   crops of the source frames (`frames_sheet.py`).
4. **Fix** in `fusion/`. Add a `FusionTest` case for the flaw where a synthetic stack can show it.
5. **Check** with `./gradlew testDebugUnitTest`, then rerun the full benchmark and `compare_scores.py`
   against the latest history file: no `!!` regressions, holdout sets included. If a fix only helps the set
   it was tuned on, rethink it.
6. **Record**: copy the score file to `tools/bench/history/score_v<version>.txt`; update `docs/ENGINE_LOG.md`
   (baseline table, flaws, backlog, tried-and-failed, history line) and, if the user-facing behaviour
   changed, `README.md` and the website; bump the version; build the APK; show a before/after crop sheet
   of the spots that changed.

## Gotchas

- Gradle doesn't see environment-variable changes as inputs: real-stack test runs need `--rerun`
  (`tools/bench/bench.sh` does it). Never run two Gradle builds at once, and don't edit a script while a
  background job is running it.
- Stopping a background benchmark can leave its child processes (`sh bench_all.sh`, Gradle, python)
  running, and a second run then writes the same files. On Windows check with PowerShell
  `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'bench_all|RealStackTest' }` and
  end them with `taskkill /PID <id> /T /F`.
- Long Python heredocs in Bash get mangled (`\n`, `\t`, `\b` escapes): write scripts to a file and run them.
  Never write Android string resources through a heredoc.
- Don't leave the shell's working directory inside `app/build` (`gradlew clean` then fails).
- Android unit tests can't use AWT/ImageIO: `SyntheticStack` draws procedurally and has its own PNG writer.
- Downloading anything (tools, datasets) needs the maintainer's OK first: say the file, source and size.
