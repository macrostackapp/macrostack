"""Contact sheet of one region across source frames (unaligned; fine for small breathing).
usage: python frames_sheet.py OUT.png x,y,w,h zoom cols frame1 frame2 ...   (x,y in source-frame pixels)"""
import sys
from PIL import Image, ImageDraw, ImageOps
out, rect, z, cols = sys.argv[1], [int(v) for v in sys.argv[2].split(",")], int(sys.argv[3]), int(sys.argv[4])
files = sys.argv[5:]
x, y, w, h = rect
tiles = []
for f in files:
    im = ImageOps.exif_transpose(Image.open(f)).convert("RGB")
    tiles.append(im.crop((x, y, x + w, y + h)).resize((w * z, h * z), Image.NEAREST))
rows = (len(tiles) + cols - 1) // cols
S = Image.new("RGB", (cols * (w * z + 6), rows * (h * z + 20)), "white")
d = ImageDraw.Draw(S)
for i, t in enumerate(tiles):
    cx, cy = (i % cols) * (w * z + 6), (i // cols) * (h * z + 20)
    S.paste(t, (cx, cy + 16))
    d.text((cx + 3, cy + 2), "frame %d" % i, fill="black")
S.save(out)
