#!/usr/bin/env bash
# Generate $SENTENCES (default tools/bench/sentences.txt) on-device with $VOICE (default alba.wav) for one variant, pull WAVs.
# Usage: DEVICE=<serial> tools/bench/gen_quality.sh <label> <outdir> [pockettts_cli args...]
set -uo pipefail
DEVICE=${DEVICE:?}; LABEL=$1; OUT=$2; shift 2
mkdir -p "$OUT/$LABEL"
i=0
while IFS= read -r line; do
  i=$((i+1)); esc=${line//\'/\'\\\'\'}
  adb -s "$DEVICE" shell "cd /data/local/tmp/pt && ${BENCH_ENV:-} LD_LIBRARY_PATH=. ./pockettts_cli $* '$esc' ${VOICE:-alba.wav} q_$i.wav >/dev/null 2>&1" </dev/null
  adb -s "$DEVICE" pull /data/local/tmp/pt/q_$i.wav "$OUT/$LABEL/$i.wav" >/dev/null
done < "${SENTENCES:-$(dirname "$0")/sentences.txt}"
echo "$LABEL: $(ls "$OUT/$LABEL" | wc -l | tr -d ' ') files"
