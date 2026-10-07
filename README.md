# MacroStack

Focus bracketing for Android phones. It does what a camera's focus-shift / focus-bracketing mode does: you set a **start** and an **end** focus point and a number of frames, and the app sweeps the focus motor through them, shooting a full-resolution photo at each step. By default the whole stack is shot as one continuous burst. Each stack goes into its own folder, ready to copy to a laptop and stack.

It replaces the Pro-mode + autoclicker workflow.

## Install

`MacroStack.apk` is in this folder (Android 10 or newer). Installing over an older version keeps your settings.

- **Easiest:** copy `MacroStack.apk` to the phone (USB, Google Drive, etc.), open it, and allow "Install unknown apps" when Android asks.
- **With USB debugging on:** `adb install -r MacroStack.apk`

## The screen

```
┌──────────────────────────────────┐
│ [0.6×][1×][3×][5×]      (◎) (1×) │  lens buttons · peaking · magnifier
│                                  │
│            viewfinder            │  double-tap to magnify, drag to look around
│                                  │
│ ☀ ISO 320 · 1/50   [RAW] [FAST]  │  exposure · format · speed (tap to change)
├──────────────────────────────────┤
│ ━━━[▭▭]━━━━━━━━━━━━━━━━━━━━━━━━━ │  overview: tap/drag to jump
│  ╷ ╷ ╷ ╷ │ ╷ ╷ ▼ ╷ ╷ │ ╷ ╷ ╷ ╷ ╷  │  focus dial: drag or flick
│ NEAR           42.3          FAR │
│ ┌ ● START  31.0 ⌖┐┌ ● END  58.4 ⌖┐ │  tap card = mark · ⌖ = go there
│   (−)    40 frames     (+)       │
│       0.7% apart · about 4 s     │
│ (▣)          (◉)          (⚙)    │  last stack · shutter · settings
└──────────────────────────────────┘
```

## Shooting a stack

1. Clip on the macro lens and put the phone on a stand or tripod. Tap the **lens button** it's clipped onto (0.6× · 1× · 3× · 5×…).
2. **Turn the focus dial.** Drag the ruler under the yellow needle, or flick it. It ticks gently every 5 and clicks when it passes a marker. **Tap the strip above the ruler to jump** anywhere in the range. Peaking marks sharp edges; double-tap the preview (or the 1× button) to magnify.
3. Focus on the **closest** part of the subject and tap the **START** card.
4. Focus on the **farthest** part and tap the **END** card.
5. Set the **frames** with − / +. Every photo shows as a yellow dot under the dial, and the line below gives the spacing and the expected time. If the stacked result shows soft bands, use more frames.
6. Press the **shutter**, a volume key, or a Bluetooth remote. The ring around the shutter fills as it shoots; press it again to stop. Don't touch the phone until it beeps.

The ⌖ target on each card moves the lens back to that point so you can check it. A card is outlined when the lens is sitting exactly on its point. Start/end points are remembered per lens.

When it finishes, a message shows how many photos it took and how fast. Tap **View**, or the thumbnail next to the shutter, to open the stack in your gallery. It also warns you if any frame was taken while the lens was still moving.

Photos are saved to `Pictures/MacroStack/Stack_YYYYMMDD_HHMMSS/Stack_..._001.jpg, _002.jpg …`. Plug the phone into the laptop, copy that one folder, and drop it into Helicon Focus, Zerene Stacker, Photoshop (Auto-Blend), etc.

**Quick changes from the viewfinder:**
- the **exposure** chip opens Auto/Manual exposure (brightness, or ISO and shutter);
- the **format** chip switches JPEG → RAW → RAW+JPEG;
- the **speed** chip switches Fast ↔ Precise.

Everything else is in **⚙ Settings**.

## Stacking on the phone

Right after a stack is shot, the phone merges it into one photo that's sharp from front to back and saves it to `Pictures/MacroStack/Stacked/Stack_…_stacked.jpg`. The original frames stay in their own folder, so laptop stacking still works exactly as before. Turn it off in **⚙ → Stacking on the phone**.

- It runs in the background. **You can keep shooting.** It pauses while the camera is busy, and further stacks queue up.
- **You can also leave the app or turn the screen off.** A notification shows the progress, and Android won't stop the merge.
- The result keeps the camera details of the middle frame (date, ISO, shutter, focal length), so Gallery and editors show them.
- The ring around the thumbnail next to the shutter fills as it works, and the thumbnail shows the merged image building up. Tap it (or **Watch** in the message) to see it large, stop it, or open the result in Gallery.
- Expect roughly **0.6–1 s per photo** at 12 MP on a recent phone (the pyramid is the slower one), so a 30-frame stack takes 20–30 s.

There are two merge methods (**⚙ → Stacking on the phone → Merge method**). The stacking sheet's *Redo as …* button re-merges the same stack with the other one:

| | **Depth map** (default) | **Pyramid** |
| --- | --- | --- |
| Like | Zerene *DMap*, Helicon *Method B* | Zerene *PMax*, Helicon *Method C* |
| Noise | **Lower than a single frame**: flat, out-of-focus areas average many frames | Somewhat lower than a single frame |
| Subject moved during the stack | One clean outline | Faint ghosted outlines |
| Hairs/bristles crossing each other | Good | Best |
| Edge of a bright object in front of a far background | Clean | Clean, sometimes a faint dark rim |
| Things nearer or farther than the stack reaches | A little soft | Their least blurred frame |

How it works, the same way desktop stackers do:

1. **Align.** Neighbouring frames are compared on copies about 2000 px wide (half size for a 12 MP photo) to measure scale (focus breathing), rotation and shift (OIS, vibration), and on the finest level also the slight stretch and shear of a hand-held tilt. Lucas–Kanade registration is coarse-to-fine, on blurred copies for a robust search and lightly blurred ones for the final, precise fit, so fine texture that's in focus in only one frame doesn't mislead it. The fit ignores brightness and contrast differences, and after a first pass gives no weight to whatever still doesn't match (something that moved). Everything is referred to the middle frame, and the result is cropped to the area every frame covers. Each frame's **exposure** is matched to the middle frame's too: lights that flicker make some frames a little darker, which would otherwise show as patches.
2. **Merge (Depth map)** with a depth map. A first sweep measures how sharp, and how bright, each small cell of the image is in every frame. A cell's sharpness curve across the stack peaks at its depth, with sub-frame precision; curves are averaged with neighbouring cells to steady them. The hard part is telling real detail from things that only look like it, and three rules handle that:
   - **Noise** looks like fine detail, more so in bright areas. Each frame's noise level is measured per brightness (most of a frame is out of focus, so its least detailed cells are pure noise). Detail well above it counts as it is, minus the noise. Detail only a little above it counts only as far as it's shared with the neighbouring frames, because noise is different in every frame while real detail isn't.
   - **Glow** from a blurred object (an out-of-focus knee or lace doily spilling over a dark carpet or bowl) carries a real but blurred pattern. It gives itself away by changing the brightness frame after frame, while real detail's brightness holds steady around its sharpest frame, so weak detail on such a ramp doesn't count.
   - **Never sharp**: something nearer or farther than the stack reaches has no fine detail in any frame. Coarse detail (of 4×4-pixel blocks, where noise averages out) then picks its least blurred frame.

   The depth map is smoothed with a confidence-weighted median, which keeps depth steps at object edges crisp. A second sweep blends, for every pixel, the frames around its depth: mostly the single nearest one where there's detail (two blend only around the midpoint between them, as two frames each a little out of focus average to something softer than either), so a subject that moved appears once, and, where it's flat, every frame about as sharp *and the same brightness* there, which averages the noise away without mixing in the glow of a blurred object nearby.
3. **Merge (Pyramid)** with a Laplacian pyramid, like Zerene's *PMax* or Helicon's *Method C*. Each frame is split into detail layers from fine to coarse. At every layer and position, the frame with the most local detail (measured on brightness over a small window, so noise doesn't win) supplies all three colours; the coarsest layer is averaged. Rebuilding gives the sharp image. The pyramid is **guided by a depth map** (a quick first sweep): in each region, frames whose "detail" there is only noise or glow count for little, while frames with real detail of their own keep full say, so two hairs crossing at different depths both stay sharp. That removes most of a plain pyramid's halos, noise and ghosting. Fine detail at the measured noise level is removed (strong detail keeps nearly all its contrast), because picking the strongest detail would otherwise also pick the strongest noise. Frames are processed one at a time into a single merged pyramid, so memory stays around 300 MB however long the stack is. If it doesn't fit, the merge runs at half size.

For the very best results on difficult subjects (e.g. retouching between methods), the frames are still there for Helicon Focus or Zerene Stacker.

### How it compares

Tested on the laptop against [focus-stack](https://github.com/PetteriAimonen/focus-stack) 1.5, the best-known free stacker, on seven stacks: two circuit-board macro stacks from focus-stack's own examples, three hand-held phone sweeps from the *Depth from Focus with Your Mobile Phone* dataset (Suwajanakorn et al., 2015), 50 frames of a 999-frame Canon R5 II macro stack ([Johannes Sood, CC BY 4.0](https://huggingface.co/datasets/jjjsood/focus-stack-sample)), and a 20-frame phone stack. Each result was scored block by block against the sharpest frame there:

- **focus-stack** keeps the most fine texture (its wavelet merge boosts contrast past any single frame) but also shows detail that's in none of the frames — blotchy colours, contour bands — in 2–50 % of the picture.
- **MacroStack** invents almost none (under 1 %). The *Pyramid* is softer than the sharpest frame in 0–9 % of the detailed areas (focus-stack: 0–24 %), the *Depth map*, which trades some fine texture for less noise, in 1–26 %.
- MacroStack's alignment is as precise as focus-stack's on hand-held frames (0.06 vs 0.07 px between neighbours) and on the phone stack (0.7 vs 0.6 px).
- On the same laptop it's faster: 20 frames of 12 MP in 11–12 s vs 27 s, 50 frames of 17 MP in 33–39 s vs about 190 s (a little flattering, as MacroStack read already-decoded frames).

## Lenses (3×, 5×, …)

Some phones, Samsung in particular, hide their telephoto lenses from other apps. MacroStack looks for every rear lens: the listed cameras, the individual lenses inside multi-lens cameras, and unlisted camera IDs. It then tries up to three ways to reach each one:

1. **Open the lens directly.**
2. **Stream from that lens inside the multi-lens camera** (Android's physical-camera API).
3. **Zoom the main camera to 3.0× / 5.0×**, so the phone switches to its own telephoto, as the stock camera does.

It remembers which way worked. If something about a lens needs attention, a small amber warning appears under the lens buttons: no manual focus, no RAW, or *"Phone is using its 23 mm lens"* when the phone picked a different lens than the button is for. Tap the warning for an explanation. **⚙ → Camera info** shows exactly how each lens was reached.

## Image quality and RAW

The stock Samsung camera's look comes from merging several frames and AI processing for every photo (Expert RAW does the same thing to its DNGs). Samsung keeps that pipeline to its own app; no third-party app gets it, especially not for manual-focus bursts. Here is what MacroStack *can* do:

- **Photo format → RAW (DNG)** saves the untouched sensor data, the same kind of single-frame DNG that Pro mode saves with RAW on. For focus stacking this is the best input: no sharpening halos or noise-reduction smearing for the stacker to trip over, and full latitude for white balance and exposure.
  - **Helicon Focus** opens DNGs directly.
  - **Zerene Stacker** needs TIFFs. Batch-convert the DNGs to 16-bit TIFF in Lightroom / Camera Raw / darktable first, with the same settings for every frame.
  - **Photoshop:** open through Camera Raw, then Auto-Blend.
  - A 12 MP DNG is about 24 MB, so a 40-frame stack is about 1 GB. RAW bursts can be slower than JPEG because of the file sizes.
- **RAW + JPEG** saves both: a JPEG to check quickly, and the DNG to stack.
- **JPEG processing → High quality** (the default) uses the camera's best noise reduction and sharpening. *Fast* is lighter and can let bursts run quicker.
- **Photo size → Maximum** uses the full sensor (e.g. 50 MP on the 5× lens) for JPEGs, at a big speed cost.

Notes:
- RAW is the whole sensor of whichever lens took the photo. It is never cropped by zoom. If a 3×/5× lens is reached through the *zoom* method and the phone decides to stay on the main lens, the info line says *"RAW shows the full 23 mm frame"*. Some phones also refuse to switch lenses while RAW is on.
- If a lens can't do RAW, the app falls back to JPEG there and tells you.

## Fast vs. Precise

| | Fast (default) | Precise |
| --- | --- | --- |
| How | The whole stack is queued as one burst; each photo carries its own focus distance and the sensor shoots back-to-back | Move lens → wait until the camera reports it parked → shoot → repeat |
| Speed | As fast as the phone can capture and save, typically many photos per second | Roughly 2–4 photos per second |
| If frames come out soft | Raise **Lens settle between photos** (frames the motor gets to arrive) | Raise **Extra wait per frame** |

Things that also keep it fast:
- **Photo size Standard (≤ 16 MP)** is the sensor's fast binned mode. *Maximum* (50/200 MP) can be many times slower per photo.
- **Auto exposure stays at 1/30 s or faster** so it can't throttle the burst. Manual exposure with a slow shutter will slow the burst to match.
- **Start delay** adds its seconds before the first photo. Set it to *Off* if you use a Bluetooth remote.

## What it does for you

| Problem with Pro mode + autoclicker | What MacroStack does |
| --- | --- |
| Slow, one tap per photo | Continuous burst, like a camera's focus bracketing |
| Focus moved by hand, uneven steps | Evenly spaced steps between exactly the two points you set |
| Brightness/colour flicker between frames | Exposure and white balance are locked for the whole stack |
| Tapping the screen shakes the phone | Start delay and volume-key / Bluetooth remote trigger |
| Photos mixed into the camera roll | One folder per stack |

Exposure can be **AUTO** (metered, then frozen when the stack starts, with EV ±) or **MANUAL** (ISO and shutter, if the phone allows it).

## Settings (⚙)

Grouped, and applied as you tap. Format and size changes take effect when you close the sheet.

- **Photos:** format (JPEG / RAW / RAW + JPEG), photo size (Standard / Maximum), JPEG processing (High quality / Fast), JPEG quality.
- **Speed:** shooting mode (Fast / Precise), plus *Lens settle between photos* in Fast mode (None / 1 / 2 / 3 frames, default 1) or *Extra wait per photo* in Precise mode.
- **Shooting:** start delay (Off / 2 / 5 / 10 s), volume keys and remotes, beep when done, optical stabilization (turn it off on a tripod).
- **Focus peaking:** sensitivity and colour.
- **Help:** the how-to (also shown on first launch) and **Camera info**, a full report of every lens the phone exposes, how each one can be reached, burst speed limits and live lens state. Tap *Copy* and send it over if something doesn't work.

## If something doesn't work

- **A lens button shows an error.** The message lists each method that was tried and why it failed. Open **Settings → Camera info → Copy** and share the text; it shows exactly what the phone allows.
- *"This lens doesn't give apps manual focus control"*: the phone won't let apps move that lens's focus motor. Try another lens button.
- **Soft frames in Fast mode:** raise *Lens settle*, or use Precise mode.

## How it works (for the curious)

- Camera2 with autofocus off (`CONTROL_AF_MODE_OFF`); the focus motor is set directly through `LENS_FOCUS_DISTANCE` (diopters), the same control Pro mode's slider uses.
- Steps are evenly spaced in diopters. With a clip-on macro lens of power P, the sharp plane sits at 1/(P + d). P is much larger than the phone's focus range d, so even diopter steps give nearly even depth steps.
- **Fast:** one `captureBurst` holds, for every step, *n* settle frames plus one JPEG still, each with its own focus distance. Burst requests are never interleaved with other requests. Per-frame results report `LENS_STATE`, so frames exposed while the lens was moving are counted. Dropped frames are re-shot individually at the end.
- **Precise:** move → wait for preview frames showing the new focus applied *and* `LENS_STATE = STATIONARY` → single still.
- Zero-shutter-lag is disabled on stills so a photo can never come from the previous focus step.
- JPEGs are matched to their frame by sensor timestamp and written by three threads. The queue is bounded, so a slow disk slows the camera rather than exhausting memory.
- RAW: a `RAW_SENSOR` stream sits next to (or replaces) JPEG. RAW buffers are too large to copy, so the camera's own buffers (at most 6) go straight to the writer threads. Each one is paired with its frame's capture metadata by timestamp, and `DngCreator` writes the DNG using the characteristics of the lens that was actually active. The lens-shading map is enabled so DNGs carry vignetting correction.
- Lenses: `LensCatalog` enumerates public IDs, `getPhysicalCameraIds()` of multi-lens cameras, and probes unlisted IDs 0–63. Each lens gets an ordered list of `LensAccess` methods (direct → physical stream → zoom ratio).
- In auto exposure, the ISO and shutter AE settled on are frozen into manual values when the stack starts. Phones without manual-sensor support fall back to AE/AWB lock.
- Focus peaking is drawn on the GPU in the same pass as the preview, at full preview resolution, so it never lags the image. It marks a pixel when its edge steepness (Sobel) is a large share of the brightness range in the surrounding 7×7 pixels. That ratio depends on sharpness, not contrast, so blurred high-contrast edges stay dark. Edge enhancement is turned off on the preview, so the viewfinder isn't artificially sharpened. On a synthetic test frame, 6 % of highlights fall on blurred edges at the default level, against 49 % for plain gradient peaking (`PeakingTest`).

Code layout (`app/src/main/java/com/macrostack/app/`):

- `camera/Lens.kt`: lens discovery and the ways of reaching each lens
- `camera/CameraController.kt`: Camera2 session, lens routing, manual focus/exposure, still and burst capture
- `camera/CameraCaps.kt`: what each camera supports, and the Camera info report
- `stack/StackRunner.kt`: Fast and Precise shooting loops
- `stack/FrameWriter.kt`, `stack/FrameMatcher.kt`, `stack/TimestampPairer.kt`, `stack/StackSaver.kt`, `stack/StackPlan.kt`: parallel saving, JPEG↔frame matching, RAW↔metadata pairing, MediaStore output (JPEG/DNG), focus steps
- `ui/FocusDial.kt`, `ui/ShutterButton.kt`, `ui/SegmentedControl.kt`: the focus dial, shutter and option buttons
- `ui/BottomSheet.kt`, `ui/SettingsSheet.kt`, `ui/ExposureSheet.kt`, `ui/InfoSheets.kt`: the slide-up sheets
- `camera/PreviewRenderer.kt`, `camera/Peaking.kt`: the OpenGL preview with focus peaking (with a plain preview if OpenGL fails)
- `fusion/`: the stacking engine, in pure Kotlin with no Android code, tested on the desktop with synthetic focus stacks (and, opt-in, real ones: `RealStackTest`). `Aligner.kt` handles registration, `DepthMapFuser.kt` and `PyramidFuser.kt` the two merges, `Warp.kt` the resampling and `StackFusion.kt` the pipeline.
- `stacking/FusionManager.kt`, `stacking/StackFiles.kt`, `stacking/StackingService.kt`, `ui/StackingSheet.kt`: running it in the app, and keeping it running outside it
- `MainActivity.kt`: screen wiring

## Building

Requires JDK 17 and the Android SDK (path in `local.properties`).

```bash
./gradlew assembleDebug testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Possible next steps

- Handling subjects that move a lot (local, per-region alignment)
- Wi-Fi transfer of a finished stack straight to the laptop
- Auto-suggested frame count from a quick test sweep
