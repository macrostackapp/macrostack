"""Scores each engine against the source frames themselves (no ground truth needed).

For every block: the detail the sharpest source frame has there (after aligning the frames like our
engine did), versus what each engine's result has. Detail is measured at two scales on luma, on a
2x-downsampled image (so single-pixel noise and JPEG specks count less).
  kept   = result detail / best frame's detail, over blocks with real detail (median; % below 0.7)
  extra  = % of blocks where the result has > 1.5x the detail of every frame (made-up: halos, noise, artifacts)
usage: python bench_score.py TAG LEFT TOP PPM_DIR [BLOCK=32]
"""
import glob
import os
import re
import sys
import numpy as np
from PIL import Image

B = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work", "bench").replace("\\", "/") + "/"
tag, ppm = sys.argv[1], sys.argv[4]
_log = open(B + tag + "_ours.log").read()
_m = re.search(r"crop IntRect\(left=(\d+), top=(\d+)", _log)
left, top = (int(_m.group(1)), int(_m.group(2))) if sys.argv[2] == "auto" else (int(sys.argv[2]), int(sys.argv[3]))
BLOCK = int(sys.argv[5]) if len(sys.argv) > 5 else 32

def parse_transforms(log):
    """(a, b, c, d, tx, ty) per frame: u = a*X + b*Y + tx, v = c*X + d*Y + ty (centred coordinates)."""
    out = []
    for m in re.finditer(r"frame \d+: (Similarity|Affine)\(([^)]*)\)", log):
        v = dict((k.strip(), float(x)) for k, x in (kv.split("=") for kv in m.group(2).split(",")))
        if m.group(1) == "Similarity":
            out.append((v["a"], -v["b"], v["b"], v["a"], v["tx"], v["ty"]))
        else:
            out.append((v["a"], v["b"], v["c"], v["d"], v["tx"], v["ty"]))
    return out


files = sorted(glob.glob(ppm + "/frame_*.ppm"))
T = parse_transforms(open(B + tag + "_ours.log").read())[:len(files)]
dm = Image.open(B + tag + "_depth_map.png").convert("RGB")
w, h = dm.size


def aligned(k):
    im = Image.open(files[k]).convert("RGB")
    W_, H_ = im.size
    cx, cy = (W_ - 1) / 2, (H_ - 1) / 2
    a, b, c_, d, tx, ty = T[k]
    A, Bq, C = a, b, a * (left - cx) + b * (top - cy) + tx + cx
    D, E, F = c_, d, c_ * (left - cx) + d * (top - cy) + ty + cy
    return im.transform((w, h), Image.AFFINE, (A, Bq, C, D, E, F), resample=Image.BILINEAR)


def detail(im):
    a = np.asarray(im, dtype=np.float32)
    y = a[..., 0] * 0.299 + a[..., 1] * 0.587 + a[..., 2] * 0.114
    hh, ww = (y.shape[0] // 2) * 2, (y.shape[1] // 2) * 2
    y = y[:hh, :ww].reshape(hh // 2, 2, ww // 2, 2).mean(axis=(1, 3))
    lap = np.zeros_like(y)
    lap[1:-1, 1:-1] = 4 * y[1:-1, 1:-1] - y[:-2, 1:-1] - y[2:, 1:-1] - y[1:-1, :-2] - y[1:-1, 2:]
    e = lap ** 2
    b = BLOCK // 2
    gh, gw = e.shape[0] // b, e.shape[1] // b
    return e[:gh * b, :gw * b].reshape(gh, b, gw, b).mean(axis=(1, 3))


_g = re.search(r"gains ([\d. ]+)", open(B + tag + "_ours.log").read())
GAINS = [float(v) for v in _g.group(1).split()] if _g else [1.0] * len(files)
# Frames as the engine used them: brightness matched (detail energy scales with gain squared).
src = np.stack([detail(aligned(k)) * GAINS[k] ** 2 for k in range(len(files))])
best = src.max(axis=0)
floor = np.percentile(src.min(axis=0), 50)  # what the blurriest frame shows: noise level, roughly
# Real detail: well above noise, and peaked (one part of the stack is much sharper than the rest);
# blocks where every frame shows a similar amount are noise or texture deep in focus, not a test.
real = (best > 8 * floor) & (best > 3 * np.median(src, axis=0))
engines = {"focus-stack": Image.open(B + tag + "_focusstack.png").convert("RGB").crop((left, top, left + w, top + h)),
           "ours depth map": dm, "ours pyramid": Image.open(B + tag + "_pyramid.png").convert("RGB")}
# Other stackers, if their results are there (they share the middle frame's geometry, uncropped).
for _name, _label in (("shine_pyramid", "shine pyramid"), ("shine_depth_map", "shine depth map")):
    if os.path.exists(B + tag + "_" + _name + ".png"):
        engines[_label] = Image.open(B + tag + "_" + _name + ".png").convert("RGB").crop((left, top, left + w, top + h))
print("%s: %d frames, %d%% of blocks have real detail (noise floor %.1f)" % (tag, len(files), 100 * real.mean(), floor))
for name, im in engines.items():
    e = detail(im)
    kept = e[real] / best[real]
    extra = e > 1.5 * best + 2 * floor
    print("  %-15s kept median %.2f, soft (<0.7) %4.1f%%, made-up detail %4.1f%%" % (
        name, np.median(kept), 100 * np.mean(kept < 0.7), 100 * np.mean(extra)))
np.save(B + tag + "_srcbest.npy", best)

# Optional: sheet of the blocks where engine argv[6] kept least, with the sharpest frame for reference.
if len(sys.argv) > 6:
    from PIL import ImageDraw
    which = {"dm": "ours depth map", "py": "ours pyramid", "fs": "focus-stack"}[sys.argv[6]]
    n = int(sys.argv[7]) if len(sys.argv) > 7 else 6
    e = detail(engines[which])
    score = np.where(real, np.log2(best / (e + 1e-3)) * np.sqrt(best), -np.inf)  # big loss on strong detail first
    bestk = src.argmax(axis=0)
    b = BLOCK // 2  # block size in full-res pixels = BLOCK
    picks = []
    s = score.copy()
    for _ in range(n):
        i = np.argmax(s)
        gy, gx = divmod(i, s.shape[1])
        if not np.isfinite(s[gy, gx]):
            break
        picks.append((gx, gy))
        s[max(0, gy - 4):gy + 5, max(0, gx - 4):gx + 5] = -np.inf
    BOX, z = 112, 2
    cols = ["best frame", "focus-stack", "ours depth map", "ours pyramid"]
    out = Image.new("RGB", (4 * (BOX * z + 6), len(picks) * (BOX * z + 18) + 16), "white")
    d = ImageDraw.Draw(out)
    for i, c in enumerate(cols):
        d.text((i * (BOX * z + 6) + 4, 2), c, fill="black")
    cache = {}
    for r, (gx, gy) in enumerate(picks):
        cx, cy = gx * BLOCK + BLOCK // 2, gy * BLOCK + BLOCK // 2
        x0, y0 = min(max(0, cx - BOX // 2), w - BOX), min(max(0, cy - BOX // 2), h - BOX)
        k = int(bestk[gy, gx])
        if k not in cache:
            cache[k] = aligned(k)
        tiles = [cache[k]] + [engines[c] for c in cols[1:]]
        yy = 16 + r * (BOX * z + 18)
        d.text((4, yy), "(%d,%d) sharpest frame %d, kept %.2f" % (x0, y0, k, e[gy, gx] / best[gy, gx]), fill="black")
        print("  worst %s: box %d,%d frame %d kept %.2f" % (which, x0, y0, k, e[gy, gx] / best[gy, gx]))
        for i, im in enumerate(tiles):
            out.paste(im.crop((x0, y0, x0 + BOX, y0 + BOX)).resize((BOX * z, BOX * z), Image.NEAREST), (i * (BOX * z + 6), yy + 14))
    out.save(B + "%s_worst_%s.png" % (tag, sys.argv[6]))
