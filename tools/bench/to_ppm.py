"""Converts JPEG frames (upright, via EXIF) into binary PPMs for RealStackTest.

usage: python to_ppm.py OUT_DIR frame1.jpg frame2.jpg ...   (in shooting order)
"""
import os
import sys

from PIL import Image, ImageOps

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for i, path in enumerate(sys.argv[2:]):
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    name = os.path.join(out, "frame_%03d.ppm" % i)
    with open(name, "wb") as f:
        f.write(b"P6\n%d %d\n255\n" % im.size)
        f.write(im.tobytes())
    print(name, im.size)
