"""Rows of crops from several results that share a reference frame but not a crop: offsets found by
phase correlation against the first. usage: python offset_sheet.py OUT 'x,y,w,h;...' zoom img1 img2 ..."""
import sys
import numpy as np
from PIL import Image, ImageDraw
out, rects, z = sys.argv[1], [[int(v) for v in r.split(",")] for r in sys.argv[2].split(";")], int(sys.argv[3])
ims = [Image.open(p).convert("RGB") for p in sys.argv[4:]]
def L(im):
    a = np.asarray(im.convert("L"), np.float32); return a - a.mean()
base = L(ims[0])
offs = [(0, 0)]
for im in ims[1:]:
    a = L(im)
    H, W = min(base.shape[0], a.shape[0]), min(base.shape[1], a.shape[1])
    R = np.fft.fft2(base[:H, :W]) * np.conj(np.fft.fft2(a[:H, :W])); R /= np.abs(R) + 1e-6
    r = np.real(np.fft.ifft2(R)); iy, ix = np.unravel_index(np.argmax(r), r.shape)
    dy = iy if iy < H // 2 else iy - H; dx = ix if ix < W // 2 else ix - W
    offs.append((-dx, -dy))
print("offsets", offs)
w, h = max(r[2] for r in rects) * z, max(r[3] for r in rects) * z
S = Image.new("RGB", (len(ims) * (w + 6), len(rects) * (h + 6) + 14), "white")
d = ImageDraw.Draw(S)
for i, p in enumerate(sys.argv[4:]):
    d.text((i * (w + 6) + 3, 1), p.split("/")[-1], fill="black")
for j, (x, y, rw, rh) in enumerate(rects):
    for i, (im, (ox, oy)) in enumerate(zip(ims, offs)):
        S.paste(im.crop((x + ox, y + oy, x + ox + rw, y + oy + rh)).resize((rw * z, rh * z), Image.NEAREST), (i * (w + 6), 14 + j * (h + 6)))
S.save(out)
