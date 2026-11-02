"""Score generated WAVs: ASR word error rate (faster-whisper) + UTMOS22 naturalness.

Usage: uv run --python 3.12 --with faster-whisper==1.1.1 --with torch==2.5.1 --with torchaudio==2.5.1 \
         --with jiwer==3.1.0 --with librosa==0.10.2.post1 tools/bench/score_quality.py <quality dir>
<quality dir>/<variant>/<n>.wav, n = line number in tools/bench/sentences.txt.
UTMOS is a learned MOS predictor (1-5, higher is better); real speech scores ~4+.
"""
import re, sys
from pathlib import Path
import jiwer, librosa, torch
from faster_whisper import WhisperModel

root = Path(sys.argv[1])
refs = (Path(__file__).parent / "sentences.txt").read_text().strip().splitlines()
asr = WhisperModel("small.en", device="cpu", compute_type="int8")
mos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True)

def norm(s):
    s = s.lower().replace("1847", "eighteen forty seven")
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9' ]", " ", s)).strip()

rows = []
for vdir in sorted(p for p in root.iterdir() if p.is_dir()):
    wers, moss, worst = [], [], (0, "")
    for i, ref in enumerate(refs, 1):
        wav = vdir / f"{i}.wav"
        if not wav.exists(): continue
        y, sr = librosa.load(str(wav), sr=16000)
        hyp = " ".join(s.text for s in asr.transcribe(y, language="en", beam_size=5)[0])
        w = jiwer.wer(norm(ref), norm(hyp))
        m = float(mos(torch.from_numpy(y).unsqueeze(0), 16000))
        wers.append(w); moss.append(m)
        if w > worst[0]: worst = (w, f"#{i}: {hyp.strip()[:90]}")
    rows.append((vdir.name, sum(wers)/len(wers), sum(moss)/len(moss), min(moss), worst))
print(f"{'variant':16} {'WER':>6} {'UTMOS':>6} {'minMOS':>6}  worst transcript")
for name, w, m, mn, worst in sorted(rows, key=lambda r: -r[2]):
    print(f"{name:16} {w*100:5.1f}% {m:6.2f} {mn:6.2f}  {worst[1] if worst[0] > 0 else '-'}")
