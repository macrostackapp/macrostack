#!/bin/sh
# Benchmarks MacroStack (Depth map and Pyramid) against focus-stack on test stacks, and scores them.
#
# usage: sh tools/bench/bench_all.sh LABEL [SET ...]
#   With no sets: every set in tools/bench/sets.txt. A set is a name from sets.txt, or a folder in samples\.
#   Scores are appended to tools/bench/work/bench/score_LABEL.txt (see tools/bench/README.md).
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WROOT=$(cd "$ROOT" && (pwd -W 2>/dev/null || pwd))   # C:/... form for Python on Windows (Git Bash)
T=$WROOT/tools/bench
W=$ROOT/tools/bench/work
WW=$WROOT/tools/bench/work
FS=${FOCUS_STACK:-$ROOT/tools/focus-stack/focus-stack/focus-stack.exe}
SHINE=${SHINE_PYTHON:-$ROOT/tools/shinestacker-env/Scripts/python.exe}   # optional; NO_SHINE=1 skips it
REG=$ROOT/tools/bench/sets.txt
label=$1; shift
sets=${*:-$(grep -v '^#' $REG | awk 'NF { print $1 }')}
mkdir -p $W/bench
for tag in $sets; do
  pattern=$(grep -v '^#' $REG | awk -v t=$tag '$1 == t { print $3 }')
  if [ -n "$pattern" ]; then
    src=$(cd $ROOT && ls $pattern 2>/dev/null | sed "s#^#$ROOT/#" | tr "\n" " ")
  else
    src=$(ls "$ROOT/samples/$tag"/*.jpg "$ROOT/samples/$tag"/*.JPG "$ROOT/samples/$tag"/*.jpeg "$ROOT/samples/$tag"/*.tif 2>/dev/null | tr "\n" " ")
  fi
  if [ -z "$src" ]; then
    echo "$tag: no frames found (not in sets.txt, and no samples/$tag folder with .jpg/.tif frames)"
    continue
  fi
  ppm=$W/ppm_$tag
  [ -d $ppm ] || python $T/to_ppm.py $ppm $src > /dev/null
  if [ ! -f $W/bench/${tag}_focusstack.png ]; then
    s=$(date +%s.%N); $FS --align-keep-size --output=$W/bench/${tag}_focusstack.png $src > $W/bench/${tag}_fs.log 2>&1; e=$(date +%s.%N)
    python -c "print('$tag focus-stack: %.1f s' % ($e - $s))" | tee $W/bench/${tag}_fs_time.txt
  fi
  if [ -x "$SHINE" ] && [ -z "$NO_SHINE" ] && [ ! -f $W/bench/${tag}_shine_pyramid.png ]; then
    $SHINE $T/shinestacker_run.py $tag $src > $W/bench/${tag}_shine.log 2>&1
    tail -1 $W/bench/${tag}_shine.log
  fi
  sh $ROOT/tools/bench/bench.sh "$WW/ppm_$tag" $tag | grep -v Starting
  python $T/bench_score.py $tag auto 0 $WW/ppm_$tag | tee -a $W/bench/score_$label.txt
done
