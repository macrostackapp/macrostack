# Stacking engine log

The lab notebook for MacroStack's stacking engine (`app/src/main/java/com/macrostack/app/fusion/`): where it
stands, what was tried, and what's next. Read it before working on the engine; add to it after every change.

## Where it stands (v2.0, 2026-10-07)

Two methods, both streaming one frame at a time (memory doesn't grow with the frame count):

- **Depth map** (default, like Zerene DMap / Helicon B): per-cell sharpness curves → depth with sub-frame
  precision → confidence-weighted median → blend frames around the depth (mostly the nearest one on
  detail, many on flat areas for noise). Sharpness counts only what noise and glow can't fake: own detail
  minus a per-brightness noise model where it's strong, detail shared with neighbouring frames where it's
  weak, a brightness-ramp test against glow, a coarse 4×4-block measure for things never in focus (which
  must not be brighter than the cell usually is — glow).
- **Pyramid** (like Zerene PMax / Helicon C): Laplacian pyramid, max local luma energy per level, guided by
  a depth-map pre-pass (per-cell frame weights), coarsest level a weighted average, garrote denoise on the
  two finest levels.

Alignment: neighbouring pairs on copies ≥ 2000 px (`StackFusion.alignSampleSize`), inverse-compositional
Lucas–Kanade, similarity on coarse levels, affine on the final fit (lightly blurred image, Tukey-robust,
brightness/contrast-invariant, parallel). Steps kept as measured; `cleanSteps` replaces only outliers.
Each frame's exposure is matched to the middle frame (chained brightness ratios).

### Benchmark baseline (v2.0)

Exact scores: `tools/bench/history/score_v2.0.txt`. Summary (soft = % of detailed areas below 0.7 of the
sharpest frame; made-up = % of the picture with detail in no frame):

Soft % (made-up % in brackets), ours vs focus-stack 1.5 and Shine Stacker 1.17 (default settings: align +
balance, then its Pyramid and Depth map):

| Set | Our Depth map | Our Pyramid | focus-stack | Shine Pyramid | Shine Depth map |
|---|---|---|---|---|---|
| pcbdm | 2.0 (0.3) | 1.7 (0.4) | 5.1 (17.3) | 46.9 (2.9)* | 49.1 (1.0)* |
| pcb | 3.5 (0.6) | 6.2 (0.5) | 3.3 (26.8) | 0.4 (2.0) | 1.3 (0.7) |
| plants | 0.7 (0.1) | 2.4 (0.4) | 1.4 (5.0) | 0.0 (2.4) | 1.4 (0.1) |
| fruits | 6.9 (0.0) | 0.0 (0.0) | 0.0 (2.3) | 1.1 (2.8) | 4.3 (0.5) |
| keyboard | 6.4 (0.0) | 1.0 (0.0) | 0.6 (1.6) | 2.3 (2.5) | 9.7 (0.6) |
| room | 10.7 (0.0) | 0.2 (0.1) | 0.0 (13.4) | 0.5 (14.1) | 2.5 (4.5) |
| pro | 26.1 (0.0) | 8.9 (0.2) | 24.4 (50.0) | 98.9 (0.0)* | 98.9 (0.0)* |

\* Shine Stacker's alignment (SIFT features, rigid) failed on frames with little in focus and skipped them:
4 of 10 frames in pcbdm, 48 of 50 in pro. Its `phase_corr_fallback` option might help; not tried (we run every
engine with its defaults). Where it aligns, its Pyramid keeps the most fine contrast of all (kept 1.03–1.16)
with a few % made-up detail, and its Depth map is sharper than ours on `room` (2.5 vs 10.7 % soft, but 4.5 %
made-up) — a pointer for flaw 2 below.

Alignment (leftover shift between neighbours, `align_residual.py`): keyboard 0.06 px (focus-stack 0.07),
room 0.70 px (focus-stack 0.56–0.62). Speed on the laptop: 20 × 12 MP in 11–12 s (focus-stack 27 s), 50 × 17 MP
in 33–39 s (focus-stack 185–192 s); MacroStack reads pre-decoded frames, so that's a little flattering. Synthetic tests (`FusionTest`): clean 30.1 / 30.3 dB, moving bug 31.4 / 29.8 dB,
flat noise 8.6 / 12.6 (single frame 22.6), occluder ring 24.5 / 26.6 dB (Depth map / Pyramid).

## Known flaws (most important first)

1. **Hot pixels → dotted trails in the Pyramid.** Each frame's stuck pixels land in a different place after
   alignment; the Pyramid keeps the strongest detail, so they all show (seen in `pro`; focus-stack does the
   same; the Depth map averages them away). Idea: hot pixels sit at the same *sensor* position in every
   frame and are sharp in all of them — find them in the first/middle/last frames and fill them in before
   warping.
2. **Depth map loses texture when focus steps are much bigger than the depth of field** (`pro`, every 20th
   frame): weak detail only counts as far as neighbouring frames share it, and there they don't. Idea: judge
   "shared" by the step size (how different neighbouring frames are), or fall back to own detail where the
   neighbours have none at all.
3. **Breathing overestimated on very blurry frames** (`pro`: total magnification change 32 % vs focus-stack's
   21 %; per-step ~0.1 % too much at the blurry end). Blur that grows from frame to frame looks like zoom.
   No visible harm found yet.
4. Faint grey speck left by the drawer handle in `room` (Depth map).
5. Pyramid keeps less of the finest contrast than focus-stack (kept ~0.95 vs ~1.2) — partly because
   focus-stack's extra "detail" is artifacts, but worth checking on real macro subjects.

## Backlog

- Hot-pixel removal (flaw 1).
- Depth-map texture with coarse focus steps (flaw 2).
- Compare against **enfuse** (Hugin) too — download needs the user's OK. Shine Stacker is in the benchmark
  since 2026-10-08 (`tools/shinestacker-env`).
- The user's studio stacks (shot list: hairy subject, dark edge on bright and bright on dark, shiny,
  backlit, tilted text page, same subject with fine and coarse steps, stack ending early, low light / hot
  pixels, LED flicker, tripod vs hand-held, slight motion, clip-on lens at max magnification, 3× lens).
- RAW input (deferred by the user): 16-bit pipeline, DNG development, stacking in linear light, 16-bit TIFF
  out. Today a DNG-only stack is decoded to 8 bits by Android with default settings.
- Unused test data on disk: `samples/phone_dff` scenes balls, bottles, metal, telephone, window.
- Retouch brush (paint a frame's detail back in) — the feature people pay Zerene/Helicon for.
- Credits screen in the app (Settings → About) listing `docs/CONTRIBUTORS.md`: promised to everyone who
  sends stacks (Facebook macro group post, 2026-10-08).

## Tried and didn't work (don't repeat without a new idea)

- Edge-preserving depth smoothing (v1.8): worse than the weighted median.
- Pure shared-detail sharpness (v1.9): ruined moving subjects → hybrid by E/n.
- Glow "steadiness" on all evidence (v1.9): broke subjects at the stack's ends → weak evidence only.
- Coarse weight 0.8, or 5×5 coarse averaging (v1.9): broke the bowl or the blanket in `room`.
- Linear breathing fit across the stack (v1.4–1.9): misplaced frames in real stacks (`room` scale error
  8e-4 per pair vs 3.6e-4 measured raw).
- No pre-blur in the final alignment fit (v2.0): failed the moving-subject and heavy-blur synthetic tests.
  One blur pass + robust fit works.
- Averaging forward and backward alignment, denser sampling (step 1), stricter gradient thresholds: no gain,
  or unstable.
- Two-sided "usual brightness" rule for coarse evidence: removed dark subjects' texture (`pro` stem);
  one-sided (only brighter than usual = glow) keeps the fix.
- Plain soft-threshold denoise in the Pyramid: dulled all fine detail → garrote with a higher threshold.

## How the score can mislead

- Hot pixels and noise in blurry frames count as "real detail": a block where the "sharpest frame" is a
  smooth patch with one dot isn't a real loss. Always look at the worst-block sheets
  (`bench_score.py SET auto 0 PPM 32 dm|py N`) before trusting a number.
- `pro` has only ~1 % of blocks with real detail, so its percentages swing by several points.
- The Depth map trades some fine texture for less noise by design, so its "kept" sits below 1.

## History

- **v2.0** (2026-10-07): benchmarked against focus-stack on 7 stacks. Steeper depth-map blend on detail;
  garrote denoise; affine, robust, larger-copy alignment without the breathing fit; exposure matching;
  one-sided usual-brightness rule for coarse evidence (white blobs in dark gaps). Laptop speed about the
  same as v1.9 (alignment parallelised).
- **v1.9** (2026-10-05): tuned on the user's 20-frame room stack. Shared detail for weak evidence, glow
  steadiness, coarse never-sharp fallback; Pyramid guided by a depth-map pre-pass.
- **v1.8**: per-brightness noise model, noise subtraction, weighted-median depth, brightness-consistent plateau.
- **v1.7**: method names Depth map / Pyramid; foreground stacking service; EXIF copied to results.
- **v1.5**: Depth map method added (default); Pyramid soft-threshold denoise.
- **v1.4**: first on-phone engine: similarity alignment + streaming Laplacian pyramid.
