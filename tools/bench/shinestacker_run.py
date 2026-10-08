"""Runs Shine Stacker (its own Python environment, tools/shinestacker-env) on one test set, with its
standard settings: align + balance, then Pyramid and Depth map. The middle frame is the reference, as for
MacroStack and focus-stack, so the results line up with theirs.

usage: tools/shinestacker-env/Scripts/python.exe tools/bench/shinestacker_run.py SET FRAME [FRAME ...]
Writes work/bench/SET_shine_pyramid.png, SET_shine_depth_map.png and SET_shine_time.txt.
"""
import os
import shutil
import sys
import time

from PIL import Image

WORK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "work").replace("\\", "/")
tag, frames = sys.argv[1], sys.argv[2:]
job_dir = os.path.join(WORK, "shine", tag)
inputs = os.path.join(job_dir, "frames")
shutil.rmtree(job_dir, ignore_errors=True)
os.makedirs(inputs)
for i, f in enumerate(frames):
    # Shine Stacker takes a folder of frames in name order. Hard links: no copying of big files.
    dst = os.path.join(inputs, "frame_%03d%s" % (i, os.path.splitext(f)[1].lower()))
    try:
        os.link(f, dst)
    except OSError:
        shutil.copyfile(f, dst)

from shinestacker import *  # noqa: E402,F403  (slow import: Qt, OpenCV)

reference = len(frames) // 2 + 1  # Shine Stacker counts from 1; MacroStack and focus-stack use frame n/2 (from 0)
job = StackJob("bench", job_dir, input_path="frames")
job.add_action(CombinedActions("aligned", actions=[AlignFrames(), BalanceFrames()], reference_index=reference))
job.add_action(FocusStack("stacked", PyramidStack(), input_path="aligned", prefix="pyramid_"))
job.add_action(FocusStack("stacked_dm", DepthMapStack(), input_path="aligned", prefix="dmap_"))
t0 = time.time()
job.run()
seconds = time.time() - t0

out = os.path.join(WORK, "bench")
os.makedirs(out, exist_ok=True)
found = {}
for sub, prefix, name in (("stacked", "pyramid_", "shine_pyramid"), ("stacked_dm", "dmap_", "shine_depth_map")):
    folder = os.path.join(job_dir, sub)
    files = [f for f in os.listdir(folder) if f.startswith(prefix)] if os.path.isdir(folder) else []
    if not files:
        print("%s: no %s result in %s" % (tag, name, folder))
        continue
    im = Image.open(os.path.join(folder, files[0]))
    if im.mode not in ("RGB", "L"):
        im = im.convert("RGB")  # 16-bit TIFFs are reduced to 8 bits, like the other engines' results
    im.convert("RGB").save(os.path.join(out, "%s_%s.png" % (tag, name)))
    found[name] = im.size
with open(os.path.join(out, "%s_shine_time.txt" % tag), "w") as f:
    f.write("%s shine-stacker: %.1f s (align + balance + pyramid + depth map)\n" % (tag, seconds))
print("%s shine-stacker: %.1f s, results %s" % (tag, seconds, found))
