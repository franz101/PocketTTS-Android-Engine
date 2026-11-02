#!/usr/bin/env bash
# On-device Pocket TTS benchmark. Usage:
#   DEVICE=<serial> tools/bench/run_bench.sh "<label>" [extra pockettts_cli args...]
# Expects /data/local/tmp/pt/{pockettts_cli,libonnxruntime.so,models/,voices/} on the device.
# Prints one CSV row per run: label,run,load_s,ttfa_ms,audio_s,gen_s,rtfx,main_ms,dec_ms,underruns/ms
set -uo pipefail
DEVICE=${DEVICE:?set DEVICE}
LABEL=$1; shift
RUNS=${RUNS:-3}
VOICE=${VOICE:-alba.wav}
TEXT=${TEXT:-"The old lighthouse keeper climbed the narrow stairs each evening, counting every step as his father had taught him. Below, the sea pressed against the rocks, patient and grey, while the first stars appeared over the harbour."}
for i in $(seq 1 "$RUNS"); do
  out=$(adb -s "$DEVICE" shell "cd /data/local/tmp/pt && ${BENCH_ENV:-} LD_LIBRARY_PATH=. ./pockettts_cli --profile --precision ${PREC:-fp32} --temperature 0.3 $* '$TEXT' $VOICE /data/local/tmp/pt/out.wav 2>&1" </dev/null)
  load=$(grep -oE 'Loaded in [0-9.]+' <<<"$out" | awk '{print $3}')
  ttfa=$(grep -oE 'First chunk latency: [0-9.]+' <<<"$out" | awk '{print $4}')
  read -r audio gen rtf < <(grep -oE '[0-9.]+s audio in [0-9.]+s \(RTFx: [0-9.]+' <<<"$out" | sed -E 's/s audio in / /; s/s \(RTFx: / /')
  if [ -z "${rtf:-}" ]; then echo "$LABEL,$i,ERROR"; echo "$out" | tail -5 >&2; continue; fi
  main=$(awk '/^run:flow_lm_main/{print $2}' <<<"$out"); dec=$(awk '/^run:mimi_decoder/{print $2}' <<<"$out")
  stall=$(grep -oE 'underruns: [0-9]+ totalling [0-9]+' <<<"$out" | awk '{print $2"/"$4}')
  echo "$LABEL,$i,$load,$ttfa,$audio,$gen,$rtf,${main%.*},${dec%.*},$stall"
  [ -n "${VERBOSE:-}" ] && echo "$out" | sed -n '/Profil\|run:/p' >&2
done
