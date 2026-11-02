# Third-party notices

This is an unofficial community project. It is not affiliated with or endorsed
by Kyutai, VolgaGerm, Microsoft, Google, mackron, Google Play, Android, or Layla.

## Included or used software

| Component | Purpose | Upstream | License |
| --- | --- | --- | --- |
| Pocket TTS | Model architecture, Python export source, and model weights | [kyutai-labs/pocket-tts](https://github.com/kyutai-labs/pocket-tts) | Code: MIT; model weights: CC BY 4.0 plus the upstream access/use conditions |
| Pocket TTS official voices | Default reference recordings bundled with release model packs | [kyutai/pocket-tts](https://huggingface.co/kyutai/pocket-tts) and [kyutai/tts-voices](https://huggingface.co/kyutai/tts-voices) | CC BY 4.0; per-voice sources and modifications are listed in `VOICE_ATTRIBUTION.md` |
| PocketTTS.cpp | C++/ONNX inference runtime used as the native foundation | [VolgaGerm/PocketTTS.cpp](https://github.com/VolgaGerm/PocketTTS.cpp) | MIT |
| ONNX Runtime Android 1.20.0 | ARM64 ONNX inference | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT |
| SentencePiece 0.2.1 | Text tokenization | [google/sentencepiece](https://github.com/google/sentencepiece) | Apache-2.0 |
| dr_libs / dr_wav | WAV decoding | [mackron/dr_libs](https://github.com/mackron/dr_libs) | Public domain or MIT-0, at the user's option |
| AndroidX Core and AppCompat | Android application support libraries | [androidx/androidx](https://github.com/androidx/androidx) | Apache-2.0 |
| Material Components for Android | UI components | [material-components/material-components-android](https://github.com/material-components/material-components-android) | Apache-2.0 |
| Inter | User-interface typeface (bundled in `app/src/main/res/font`) | [rsms/inter](https://github.com/rsms/inter), via [google/fonts](https://github.com/google/fonts/tree/main/ofl/inter) | SIL Open Font License 1.1 |
| Literata | Reading typeface of the player (bundled in `app/src/main/res/font`) | [googlefonts/literata](https://github.com/googlefonts/literata), via [google/fonts](https://github.com/google/fonts/tree/main/ofl/literata) | SIL Open Font License 1.1 |
| Sonic | Streaming speech speed and pitch processing | [waywardgeek/sonic](https://github.com/waywardgeek/sonic) | Apache-2.0; pinned source and license in `vendor/sonic` |
| Kotlin | Android application language/runtime | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| Gradle | Build system | [gradle/gradle](https://github.com/gradle/gradle) | Apache-2.0 |

The Inter and Literata files are static instances (Inter 400/500/600/700, Literata
400/600) generated from the variable fonts in the google/fonts repository and
subset to Latin, Greek and Cyrillic with fontTools. Neither font declares a
Reserved Font Name. Their licenses are shipped in the app as
`assets/licenses/Inter-OFL.txt` and `assets/licenses/Literata-OFL.txt` and are
shown under Engine → Open-source licenses.

The snapshot under `vendor/pocket-tts` is based on upstream commit
`d108410d23eef7e01db282f9442891162dbc3db6`. Its original MIT license is kept
at `vendor/pocket-tts/LICENSE`.

The files under `vendor/PocketTTS.cpp` are based on upstream commit
`e801e7d6c2692121a39e80ae525cb5265174a495` and contain Android/multilingual
compatibility changes. The original MIT license is kept at
`vendor/PocketTTS.cpp/LICENSE`.

The Android build fetches SentencePiece at tag `v0.2.1` and dr_libs at commit
`50bb723e6a459dbb781e26cefee4fd9ca6714d6a`.

The release packs contain only Kyutai's official default voice catalog entries:
Alba, Jürgen, Giovanni, Rafael, and Lola. They are converted to 24 kHz mono PCM
WAV for Android packaging. See `VOICE_ATTRIBUTION.md`; user-imported recordings
are never part of the repository or release artifacts.

## Related application

This engine was developed for use with [Layla](https://www.layla-network.ai/).
Layla supports voices exposed by Android system TTS engines; its multilingual
TTS setup is described in the
[Layla guide](https://blog.layla-network.ai/post/how-to-add-multilingual-text-to-speech-for-your-characters-in-layla).
Layla is not included in this repository and is not a dependency of the engine.

The complete license texts and notices distributed by downloaded dependencies
remain controlling for those components.
