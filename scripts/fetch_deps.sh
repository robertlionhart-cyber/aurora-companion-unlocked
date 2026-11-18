#!/usr/bin/env bash
# Fetches the build dependencies that are not stored in git into third_party/.
# Usage (from the repo root):  bash scripts/fetch_deps.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

LLAMA_COMMIT=9214bdb554544c35e4a9e2c2e8b75d8cfee330ab   # llama.cpp tag b12465
AAR_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
AAR_SHA=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
VOICE_URL=https://github.com/robertlionhart-cyber/aurora-companion-android/releases/download/build-assets-1/aurora-voice-assets-1.zip
VOICE_SHA=baf48dc538e65cc6af0aa820bbc3333ee5ce85bb3226ef63de78a21bac12382c

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
get_checked() { # url out sha
  if [ -f "$2" ] && [ "$(sha "$2")" = "$3" ]; then echo "ok (cached) $2"; return; fi
  echo "downloading $1"
  curl -fL --retry 3 -o "$2" "$1"
  local h; h="$(sha "$2")"
  if [ "$h" != "$3" ]; then rm -f "$2"; echo "checksum mismatch for $2 ($h)" >&2; exit 1; fi
}

L="$TP/llama.cpp"
if [ ! -d "$L/.git" ]; then git init -q "$L"; git -C "$L" remote add origin https://github.com/ggml-org/llama.cpp.git; fi
if [ "$(git -C "$L" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$LLAMA_COMMIT" ]; then
  git -C "$L" fetch --depth 1 origin "$LLAMA_COMMIT"
  git -C "$L" checkout -q --detach FETCH_HEAD
fi
echo "ok llama.cpp $LLAMA_COMMIT"

get_checked "$AAR_URL" "$TP/sherpa-onnx-1.13.8.aar" "$AAR_SHA"

Z="$TP/aurora-voice-assets-1.zip"
get_checked "$VOICE_URL" "$Z" "$VOICE_SHA"
if [ ! -f "$TP/voice-assets/robert/en_US-robert-medium.onnx" ]; then
  mkdir -p "$TP/voice-assets"; unzip -oq "$Z" -d "$TP/voice-assets"
fi
echo "ok voice-assets"
echo "All dependencies are in $TP"
