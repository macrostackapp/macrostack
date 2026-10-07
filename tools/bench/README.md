# Engine benchmark

Runs MacroStack's two merge methods and focus-stack on the same focus stacks, and scores them against the
source frames. How it fits into the improvement work: see "The engine improvement loop" in `CLAUDE.md`.

```bash
sh tools/bench/bench_all.sh LABEL [SET ...]          # all sets in sets.txt if none given
python tools/bench/compare_scores.py tools/bench/history/score_v2.0.txt tools/bench/work/bench/score_LABEL.txt
```

- `sets.txt` lists the test stacks: name, role (`tune` or `holdout`), frames, what each tests. A folder in
  `samples\` that isn't listed can still be run by its folder name.
- focus-stack runs once per set; its result is cached in `work/bench/SET_focusstack.png`.
- Scores go to `work/bench/score_LABEL.txt`. Per method: **kept** = the result's detail ÷ the sharpest
  frame's, over blocks with real detail (median); **soft** = % of those blocks below 0.7; **made-up** = % of
  all blocks with more than 1.5× the detail of every frame (halos, noise, artifacts). Frames are compared
  as the engine used them (exposure-matched).
- `compare_scores.py` flags regressions of our methods (`!!`) and exits 1 if there are any.
- `history/` keeps each released version's score file. `work/` (frame copies, results, logs) can be
  deleted at any time.

Other tools:

- `python tools/bench/bench_score.py SET auto 0 tools/bench/work/ppm_SET 32 dm|py N`: a sheet
  (`work/bench/SET_worst_dm.png`) of the N blocks where that method kept least, next to the sharpest frame,
  focus-stack and the other method. Look at it before trusting a number: hot pixels and noise in blurry
  frames can pose as "detail".
- `python tools/bench/align_residual.py ours SET tools/bench/work/ppm_SET`: leftover misalignment between
  neighbouring frames (zoom, rotation and shift errors). `... files 'GLOB'` measures already-aligned frames,
  e.g. focus-stack's `--align-only --save-steps` output.
- `frames_sheet.py` (one spot across source frames), `offset_sheet.py` (one spot across results).
- `to_ppm.py`: frames → PPMs for `RealStackTest` (done automatically by `bench_all.sh`).
