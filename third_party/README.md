# third_party/

These are NOT stored in git. `scripts/fetch_deps.ps1` (Windows) or `scripts/fetch_deps.sh` (macOS/Linux) puts them here:

| Folder / file | What | From |
|---|---|---|
| `llama.cpp/` | llama.cpp source, pinned to commit `9214bdb554544c35e4a9e2c2e8b75d8cfee330ab` (tag b12465) | https://github.com/ggml-org/llama.cpp |
| `sherpa-onnx-1.13.8.aar` | sherpa-onnx Android library (offline ASR, TTS, VAD) | https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8 |
| `voice-assets/` | Files bundled into the APK: Robert's Piper voice, espeak-ng-data, Moonshine Tiny EN (int8) speech recognition, Silero VAD | `aurora-voice-assets-1.zip` on this repo's [`build-assets-1` release](https://github.com/robertlionhart-cyber/aurora-companion-android/releases/tag/build-assets-1) |

Every download is checked against a SHA-256 in the script.

Why not in git: the Robert voice model is 63.5 MB and the Moonshine models are 44 MB, over GitHub's
50 MB warning size. Keeping them as a release asset keeps the repo small and avoids Git LFS.

You can point the build somewhere else with `local.properties`:

```
llama.dir=C:/path/to/llama.cpp
aurora.voiceAssets=C:/path/to/voice-assets
aurora.sherpaAar=C:/path/to/sherpa-onnx-1.13.8.aar
```

**Robert voice:** en_US-robert-medium is Robert's own voice model, included for personal use with
Aurora Companion only. Do not redistribute it or use it to impersonate him.
