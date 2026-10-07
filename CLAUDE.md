# MacroStack — working notes for Claude

MacroStack is an Android focus-bracketing camera with its own on-phone focus-stacking engine, built for
the user's **Samsung Galaxy S24 Ultra** (clip-on macro lens). The user is a macro photographer, not a
programmer: explain results in photographer terms (sharpness, halos, noise, ghosting), not code. They want
to pick the work up in any new session without re-explaining. Everything needed is in this file,
`docs/ENGINE_LOG.md` and `tools/bench/`.

## Layout

- `app/src/main/java/com/macrostack/app/` — the app. `fusion/` is the stacking engine (pure Kotlin, no
  Android code): `StackFusion.kt` pipeline, `Aligner.kt`, `Warp.kt`, `DepthMapFuser.kt`, `PyramidFuser.kt`.
  `README.md` describes the app and how the engine works, for the user.
- `app/src/test/.../fusion/` — `FusionTest` (synthetic stacks with a known answer; must keep passing),
  `RealStackTest` (opt-in, real frames; see its doc comment for the env vars).
- `docs/ENGINE_LOG.md` — engine status, benchmark baseline, known flaws, backlog, what didn't work, history.
  **Read it first** for any engine work; update it after every change.
- `tools/bench/` — benchmark against other stackers (README there); `sets.txt` lists the test stacks;
  `history/` keeps each version's scores; `work/` is disposable.
- `tools/focus-stack/` — focus-stack 1.5 for Windows (MIT), the comparison engine.
- `samples/` — test stacks (large, not code). Licences: `pro_macro` CC BY 4.0, credit Johannes Sood if
  shown anywhere; `pcb*` from focus-stack (MIT); `phone_dff` research data (Suwajanakorn et al. 2015).

## Build, test, deliver

- JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot`), Android SDK `C:\Users\abuba\Android`.
- `./gradlew testDebugUnitTest lintDebug assembleDebug` — tests must pass, lint must have 0 errors.
- Deliver: bump `versionCode`/`versionName` in `app/build.gradle.kts`, copy
  `app/build/outputs/apk/debug/app-debug.apk` to `MacroStack.apk` in the project folder, send it to the user.
- No phone or emulator on this laptop: UI and camera code can't be run here. The user installs the APK and
  reports back (screenshots, Settings → Camera info → Copy).

## The engine improvement loop

When the user says to continue improving the engine, or adds new stacks to `samples\` (also: `/improve-engine`):

1. **Catch up.** Read `docs/ENGINE_LOG.md` (flaws, backlog, what didn't work) and `tools/bench/sets.txt`.
   Look for new folders in `samples\` and register each in `sets.txt` (about one in three as `holdout`;
   never tune on holdout sets' worst blocks).
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
   changed, `README.md`; bump the version; build and deliver the APK; show the user a before/after crop
   sheet of the spots that changed.

## Gotchas

- Gradle doesn't see environment-variable changes as inputs: real-stack test runs need `--rerun`
  (`tools/bench/bench.sh` does it). Never run two Gradle builds at once, and don't edit a script while a
  background job is running it.
- Stopping a background benchmark can leave its child processes (`sh bench_all.sh`, Gradle, python)
  running, and a second run then writes the same files. Check with PowerShell
  `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'bench_all|RealStackTest' }` and
  end them with `taskkill /PID <id> /T /F`.
- Long Python heredocs in Bash get mangled (`\n`, `\t`, `\b` escapes): write scripts to a file and run them.
  Never write Android string resources through a heredoc.
- Don't leave the shell's working directory inside `app/build` (`gradlew clean` then fails).
- Android unit tests can't use AWT/ImageIO: `SyntheticStack` draws procedurally and has its own PNG writer.
- Downloading anything (tools, datasets) needs the user's OK first: say the file, source and size.
