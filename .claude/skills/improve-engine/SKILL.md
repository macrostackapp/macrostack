---
name: improve-engine
description: Continue improving MacroStack's focus-stacking engine - benchmark it against focus-stack (and other stackers) on the test stacks in samples\, find flaws, fix them without regressions, and record the results. Use when the user says to continue or keep improving the engine, shares new test stacks, or asks how the engine compares now.
---

# Improve the stacking engine

Follow "The engine improvement loop" in `CLAUDE.md`, step by step. In short:

1. Read `docs/ENGINE_LOG.md` and `tools/bench/sets.txt`. Register any new `samples\` folders in `sets.txt`
   (about one in three as `holdout`) and tell the user which new stacks you found.
2. Run `sh tools/bench/bench_all.sh <label>` (long: run it in the background) and compare with the latest
   `tools/bench/history/` file using `tools/bench/compare_scores.py`.
3. Pick the most important flaw — from the new results or the top of the log's "Known flaws" — confirm it by
   looking at the images, find its cause, fix it, add a test.
4. Tests pass, full benchmark shows no `!!` regressions (holdout sets included).
5. Record: history score file, `docs/ENGINE_LOG.md`, version bump, APK, a before/after crop sheet for the user.

Report to the user in photographer terms: what looked wrong, why, what changed, the before/after numbers,
and what's next. If the user only asked a question, answer it from the log and the latest scores.
