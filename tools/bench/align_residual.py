"""How well consecutive aligned frames line up: phase correlation of gradient patches on a grid,
then a similarity fit of the leftover shifts (scale error, rotation error, shift error).

usage: python align_residual.py ours TAG PPM_DIR          (warps PPMs with transforms from bench/TAG_ours.log)
       python align_residual.py files 'GLOB'               (already-aligned frames, e.g. focus-stack's)
"""
import glob
import os
import re
import sys
import numpy as np
from PIL import Image

B = "C:/dev/macro-app/tools/bench/work/bench/"

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



def load_ours(tag, ppm):
    files = sorted(glob.glob(ppm + "/frame_*.ppm"))
    log = open(B + tag + "_ours.log").read()
    T = parse_transforms(log)[:len(files)]
    m = re.search(r"crop IntRect\(left=(\d+), top=(\d+), right=(\d+), bottom=(\d+)", log)
    l, t, r, b_ = (int(v) for v in m.groups())
    out = []
    for f, (a, b, c_, d, tx, ty) in zip(files, T):
        im = Image.open(f).convert("L")
        W_, H_ = im.size
        cx, cy = (W_ - 1) / 2, (H_ - 1) / 2
        A, Bq, C = a, b, a * (l - cx) + b * (t - cy) + tx + cx
        D, E, F = c_, d, c_ * (l - cx) + d * (t - cy) + ty + cy
        out.append(np.asarray(im.transform((r - l, b_ - t), Image.AFFINE, (A, Bq, C, D, E, F), resample=Image.BILINEAR), np.float32))
    return out


def grad(x):
    gx = np.zeros_like(x); gy = np.zeros_like(x)
    gx[:, 1:-1] = x[:, 2:] - x[:, :-2]; gy[1:-1] = x[2:] - x[:-2]
    return np.hypot(gx, gy)


def blur3(x):
    for ax in (0, 1):
        x = (np.roll(x, 1, ax) + 2 * x + np.roll(x, -1, ax)) / 4
    return x


def shift(p1, p2):
    P = p1.shape[0]
    g1 = grad(blur3(blur3(p1))); g2 = grad(blur3(blur3(p2)))
    if g1.std() < 2 or g2.std() < 2:
        return None
    g1 -= g1.mean(); g2 -= g2.mean()
    win = np.outer(np.hanning(P), np.hanning(P))
    R = np.fft.fft2(g1 * win) * np.conj(np.fft.fft2(g2 * win)); R /= np.abs(R) + 1e-6
    r = np.fft.fftshift(np.real(np.fft.ifft2(R)))
    iy, ix = np.unravel_index(np.argmax(r), r.shape)
    if r.max() < 0.08 or not (1 <= iy < P - 1 and 1 <= ix < P - 1):
        return None

    def sub(m, c, p):  # parabola through three samples
        d = m - 2 * c + p
        return 0 if d == 0 else 0.5 * (m - p) / d
    dy = iy - P // 2 + sub(r[iy - 1, ix], r[iy, ix], r[iy + 1, ix])
    dx = ix - P // 2 + sub(r[iy, ix - 1], r[iy, ix], r[iy, ix + 1])
    return (dx, dy)


if sys.argv[1] == "ours":
    frames = load_ours(sys.argv[2], sys.argv[3])
else:
    frames = [np.asarray(Image.open(f).convert("L"), np.float32) for f in sorted(glob.glob(sys.argv[2]))]
H, W = frames[0].shape
P = min(192, (min(H, W) // 4) // 2 * 2)
all_med, fits, rests = [], [], []
GAP = int(os.environ.get('GAP', '1'))
for k in range(len(frames) - GAP):
    pts = []
    for y in range(P // 2, H - P // 2 - P // 4, P // 2 + P // 4):
        for x in range(P // 2, W - P // 2 - P // 4, P // 2 + P // 4):
            v = shift(frames[k][y - P // 2:y + P // 2, x - P // 2:x + P // 2], frames[k + GAP][y - P // 2:y + P // 2, x - P // 2:x + P // 2])
            if v is not None and np.hypot(*v) < P / 4:
                pts.append((x - W / 2, y - H / 2, v[0], v[1]))
    if len(pts) < 6:
        continue
    a = np.array(pts)
    mags = np.hypot(a[:, 2], a[:, 3])
    # Similarity fit of the leftover shifts: d = (s-1)*p + rot*perp(p) + t
    A = np.zeros((2 * len(a), 4)); bvec = np.zeros(2 * len(a))
    A[0::2] = np.c_[a[:, 0], -a[:, 1], np.ones(len(a)), np.zeros(len(a))]
    A[1::2] = np.c_[a[:, 1], a[:, 0], np.zeros(len(a)), np.ones(len(a))]
    bvec[0::2], bvec[1::2] = a[:, 2], a[:, 3]
    sol = np.linalg.lstsq(A, bvec, rcond=None)[0]
    rest = np.hypot(*(bvec - A @ sol).reshape(-1, 2).T)
    all_med.append(np.median(mags)); fits.append(sol); rests.append(np.median(rest))
    print("pair %2d-%2d: median %.2f px | fit: scale %+.1e rot %+.1e shift %+.2f,%+.2f | left after fit %.2f px" % (
        k, k + GAP, np.median(mags), sol[0], sol[1], sol[2], sol[3], np.median(rest)))
f = np.array(fits)
print("overall: median %.2f px; |scale err| %.1e (%.2f px at the edge), |rot| %.1e, |shift| %.2f px, left after fit %.2f px" % (
    np.median(all_med), np.median(np.abs(f[:, 0])), np.median(np.abs(f[:, 0])) * W / 2, np.median(np.abs(f[:, 1])),
    np.median(np.hypot(f[:, 2], f[:, 3])), np.median(rests)))
