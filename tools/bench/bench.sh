#!/bin/sh
# usage: bench.sh PPM_DIR TAG [METHODS]  -> tools/bench/work/bench/TAG_{depth_map,pyramid}.png (+ _depth), timing in TAG_ours.log
S=C:/dev/macro-app/tools/bench/work
cd /c/dev/macro-app
export MACROSTACK_REAL="$1"
export MACROSTACK_METHODS="${3:-DEPTH_MAP,PYRAMID}"
unset MACROSTACK_PROBE
./gradlew --no-daemon -q testDebugUnitTest --rerun --tests "*RealStackTest*" -i 2>&1 | grep -E "DEPTH_MAP:|PYRAMID:|aligning took|gains|frame [0-9]+:|step [0-9]+:|^e: |FAILED|Exception|OutOfMemory" > $S/bench/$2_ours.log
cat $S/bench/$2_ours.log | grep -v "frame [0-9]*:"
for m in $(echo "$MACROSTACK_METHODS" | tr ',' ' '); do
  t=$(echo $m | tr 'A-Z' 'a-z')
  cp app/build/fusion-test/real_$t.png $S/bench/$2_$t.png 2>/dev/null
  cp app/build/fusion-test/real_${t}_depth.png $S/bench/$2_${t}_depth.png 2>/dev/null
done
