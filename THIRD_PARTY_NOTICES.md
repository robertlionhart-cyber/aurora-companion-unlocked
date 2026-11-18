# Third-party notices

Aurora Companion for Android (Unlocked edition) uses the following third-party software and models. They are made and
owned by their authors, not by the author of Aurora Companion, and each is under its own license.

## Built into the APK

| Component | Use | License | Source |
|---|---|---|---|
| llama.cpp / ggml (commit 9214bdb, tag b12465) | on-device chat AI engine | MIT | https://github.com/ggml-org/llama.cpp |
| `lib/` module (llama.cpp Android example, `com.arm.aichat`) | JNI bridge to llama.cpp | MIT (llama.cpp) | https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android |
| sherpa-onnx 1.13.8 (includes ONNX Runtime, MIT) | offline speech recognition, TTS, VAD | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| Moonshine Tiny EN (int8, sherpa-onnx export) | speech recognition model | MIT | https://github.com/moonshine-ai/moonshine |
| Silero VAD | voice activity detection model | MIT | https://github.com/snakers4/silero-vad |
| espeak-ng-data | phoneme data used by the Piper voices | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng (full source there) |
| Piper (voice format) | text-to-speech voice format | MIT | https://github.com/rhasspy/piper |
| en_US-robert-medium | Robert's own Piper voice | Personal-use only, see below | (not published separately) |
| AndroidX / Jetpack Compose / Kotlin | app framework | Apache-2.0 | https://developer.android.com/jetpack |

**en_US-robert-medium** is Robert's own voice model, included for personal use with Aurora Companion
only. Do not redistribute it or use it to impersonate him.

## Downloaded in the app, only if you choose to

| Component | License | Source |
|---|---|---|
| Llama 3.2 1B / 3B Instruct (GGUF Q4_K_M) | Llama 3.2 Community License and Acceptable Use Policy ("Built with Llama") | https://huggingface.co/meta-llama, quantized by https://huggingface.co/bartowski |
| Avatar packs | the author's own clips | `avatars-v1` releases of this repo and of robertlionhart-cyber/aurora-companion-android |
| **18+ only:** Llama 3.2 3B Instruct abliterated (Q3_K_M) and Llama 3.2 1B Instruct abliterated (Q4_K_M) | Llama 3.2 Community License and Acceptable Use Policy ("Built with Llama") | abliteration by https://huggingface.co/huihui-ai, GGUF by https://huggingface.co/mradermacher |
| **18+ only:** Llama 3.2 3B Instruct abliterated (Q4_K_M, older) | Llama 3.2 Community License and Acceptable Use Policy ("Built with Llama") | abliteration by huihui-ai, GGUF by https://huggingface.co/QuantFactory |
| **18+ only:** Gemma 2 2B it abliterated (Q4_K_M) | Gemma Terms of Use and Prohibited Use Policy (https://ai.google.dev/gemma/terms) | abliteration by https://huggingface.co/IlyaGusev, GGUF by https://huggingface.co/bartowski |
| **18+ only:** Qwen2.5 1.5B Instruct abliterated (retired, delete-only) | Apache-2.0 (Qwen2.5) | abliteration by huihui-ai, GGUF by mradermacher |
| **18+ only:** The Kama Sutra | Public domain (Project Gutenberg) | https://www.gutenberg.org |
| en_US-libritts_r-medium (Piper voice) | CC BY 4.0 (LibriTTS-R dataset) | https://huggingface.co/rhasspy/piper-voices |
| 82 public-domain books | Public domain (Project Gutenberg texts, Gutenberg headers removed for indexing) | https://www.gutenberg.org via https://github.com/robertlionhart-cyber/aurora-companion-beta |

## Avatars

The companion avatar clips (built in and in the avatar packs) are the author's own creations, re-encoded from the
Aurora desktop app's avatars. No real people are depicted.
