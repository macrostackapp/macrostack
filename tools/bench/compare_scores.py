"""Compares two benchmark score files and flags regressions of MacroStack's methods.

usage: python tools/bench/compare_scores.py BASE NEW
  e.g. python tools/bench/compare_scores.py tools/bench/history/score_v2.0.txt tools/bench/work/bench/score_v2.1.txt

A regression (marked !!) is, for either of our methods on any set: soft areas up by more than 2 points,
made-up detail up by more than 0.5 points, or kept detail down by more than 0.03. Sets marked holdout in
sets.txt are listed separately: those are the ones that show whether a change generalises.
Exit code 1 if anything regressed.
"""
import os
import re
import sys

ENGINES = {"ours depth map": "Depth map", "ours pyramid": "Pyramid", "focus-stack": "focus-stack"}
SOFT_TOLERANCE = 2.0
MADE_UP_TOLERANCE = 0.5
KEPT_TOLERANCE = 0.03


def parse(path):
    """{set: {engine: (kept, soft, made_up)}}"""
    scores, current = {}, None
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"(\S+): \d+ frames", line)
        if m:
            current = m.group(1)
            scores[current] = {}
            continue
        m = re.match(r"\s+(.+?)\s+kept median ([\d.]+), soft \(<0.7\)\s+([\d.]+)%, made-up detail\s+([\d.]+)%", line)
        if m and current:
            scores[current][m.group(1)] = tuple(float(m.group(i)) for i in (2, 3, 4))
    return scores


def roles():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sets.txt")
    out = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if parts and not line.startswith("#") and len(parts) >= 2:
            out[parts[0]] = parts[1]
    return out


base, new = parse(sys.argv[1]), parse(sys.argv[2])
role = roles()
regressed = False
for group in ("tune", "holdout", "unregistered"):
    names = [s for s in new if role.get(s, "unregistered") == group]
    if not names:
        continue
    print("\n%s sets" % group.upper())
    print("%-10s %-12s %24s %24s %24s" % ("set", "engine", "kept (higher=better)", "soft % (lower)", "made-up % (lower)"))
    for s in names:
        for key, name in ENGINES.items():
            if key not in new[s]:
                continue
            n = new[s][key]
            b = base.get(s, {}).get(key)
            cells = []
            flags = []
            for i, (label, tol, better_high) in enumerate((("kept", KEPT_TOLERANCE, True), ("soft", SOFT_TOLERANCE, False),
                                                           ("made-up", MADE_UP_TOLERANCE, False))):
                if b is None:
                    cells.append("%24s" % ("%.2f (new set)" % n[i] if i == 0 else "%.1f (new set)" % n[i]))
                    continue
                d = n[i] - b[i]
                worse = (-d if better_high else d) > tol
                fmt = "%.2f -> %.2f (%+.2f)" if i == 0 else "%.1f -> %.1f (%+.1f)"
                cells.append("%24s" % (fmt % (b[i], n[i], d)))
                if worse and key != "focus-stack":
                    flags.append(label)
            mark = "  !! " + ", ".join(flags) if flags else ""
            regressed |= bool(flags)
            print("%-10s %-12s %s%s" % (s, name, "".join(cells), mark))
missing = [s for s in base if s not in new]
if missing:
    print("\nnot in the new run: " + ", ".join(missing))
print("\nRESULT: " + ("regressions found (!!) - look at those sets before keeping the change" if regressed else "no regressions"))
sys.exit(1 if regressed else 0)
