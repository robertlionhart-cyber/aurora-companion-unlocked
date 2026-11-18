# Aurora Companion for Android (beta) - Unlocked edition

A private AI companion for Android. The chat AI, speech recognition and voices all run **on your
phone or tablet**: no account, no cloud, no API keys.

The **Unlocked edition** is the same app as the
[Family edition](https://github.com/robertlionhart-cyber/aurora-companion-android), plus an **optional 18+ mode**
(off by default) and a few extra avatar looks. It installs as a separate app ("Aurora Unlocked"), so both editions
can sit side by side. **If anyone under 18 uses the device, install the Family edition instead.**

## 18+ mode (optional, off by default)

- **Off by default.** Until you turn it on, Unlocked behaves like the Family edition.
- **Self-confirmed age gate:** Settings > Adult mode asks you to confirm that you are 18 or older. The app cannot
  verify age; by confirming you accept responsibility. You can switch it off any time.
- **What it changes, only while on:**
  - characters **you** create or import and mark as **"Adult character (18+)"** may discuss mature and sexual themes,
    and the 18+ chat modes (Romantic, Unhinged) are offered for them;
  - optional uncensored community models (see below) can be downloaded from Hugging Face;
  - an adult-only public-domain book (the Kama Sutra) can be downloaded for the library.
- **What it never changes:** the built-in companions (Aurora, Pebbie, Robbi, Leo, Barry) and the companions from avatar
  packs always stay family-friendly, in every mode. Avatar clips are never explicit. Model licenses and their
  acceptable-use policies (including the bans on illegal content and on anything involving minors) always apply.

Please read [DISCLAIMER.txt](DISCLAIMER.txt) before using 18+ mode.

## How to install on Android

1. **On your phone or tablet**, open the
   [latest release](https://github.com/robertlionhart-cyber/aurora-companion-unlocked/releases/latest).
2. Under **Assets**, tap **`AuroraCompanion-Unlocked-<version>.apk`** (about 135 MB). If the link does nothing inside
   the X app, open the page in Chrome or Samsung Internet first.
3. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**, then tap **Install**.
   If **Google Play Protect** doesn't recognise the app, tap **More details > Install anyway**.
4. Open **Aurora Unlocked**, pick a companion, and download a chat model when asked (Wi-Fi recommended).

**Updating:** Settings > Updates > Check for updates downloads the newest release from **this** repo, checks its
size, SHA-256 and signature, and hands it to Android's installer. Your data is kept.

Requirements: Android 8.0+, 64-bit (arm64), 4 GB RAM or more (6-8 GB for the 3B models). Tested on a Galaxy A54 and
a Xiaomi Pad 5.

## Features

Everything in the Family edition:

- Five built-in companions with animated avatars that react with emotion clips and doze off when it's quiet
- **Avatar packs** (Settings > Companion > Avatars) with new companions and looks: Scott, Globe, Amy, Anastasia,
  Clover, Cybele, Kumi, Lucky, Pebble and **Aurora Classic**; a few adult looks include ordinary beach (swimwear)
  clips that only play when you talk about the beach or swimming
- Chat modes (Best Friend, Supportive, Playful, Mentor / Coach, Storyteller, Assistant; Romantic and Unhinged in 18+ mode)
- Online / Offline switch for weather, news and quick look-ups (off by default)
- Quick menu (person button), character creation and import (SillyTavern / TavernAI cards), offline voice chat and
  phone-call mode, reminders, an 82-book public-domain library, conversation history and running memory
- In-app updates

## Models

Models are **not** hosted here. The app downloads them from Hugging Face, from the repos below, only when you tap
Download; each file is pinned to a revision and checked against its SHA-256.

| In the app | Model | GGUF by | License |
|---|---|---|---|
| Family · Light | Llama 3.2 1B Instruct (Q4_K_M) | bartowski | Meta Llama 3.2 Community License |
| Family · Smart (powerful devices) | Llama 3.2 3B Instruct (Q4_K_M) | bartowski | Meta Llama 3.2 Community License |
| Unlocked · Light (18+) | Llama 3.2 1B Instruct abliterated (Q4_K_M) | mradermacher | Meta Llama 3.2 Community License |
| Unlocked · Smart (18+) | Llama 3.2 3B Instruct abliterated (Q3_K_M) | mradermacher | Meta Llama 3.2 Community License |
| Unlocked · Balanced (18+) | Gemma 2 2B it abliterated (Q4_K_M) | bartowski | Gemma Terms of Use |

**Built with Llama.** Llama 3.2 is licensed under the Llama 3.2 Community License, Copyright (c) Meta Platforms, Inc.
All Rights Reserved; use is subject to Meta's [Acceptable Use Policy](https://www.llama.com/llama3_2/use-policy/).
Gemma is provided under and subject to the [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and Prohibited Use
Policy. The "abliterated" builds are community modifications (by huihui-ai and IlyaGusev) of the original models;
GGUF conversions by mradermacher, bartowski and QuantFactory. None of these are made, owned or endorsed by the author
of Aurora Companion.

## Privacy

- Chats, voice, history and memory notes never leave your device. No account, ads, analytics or telemetry.
- Network use: downloads when you tap them; the update check (GitHub) if you leave it on; and, only while **Online**
  is on, the words of a weather, news or look-up question (or your city) go to Open-Meteo, BBC News / Google News,
  Wikipedia or DuckDuckGo. Your chats are never sent.

## Disclaimer

AI replies can be wrong, offensive or made up, and uncensored models refuse less. They are fiction, not advice.
Use this edition only as an adult, in private, and at your own risk. See [DISCLAIMER.txt](DISCLAIMER.txt) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build from source

You need **JDK 17**, the **Android SDK** (platform 36, build-tools 36.0.0, **NDK 29.0.13113456**, **CMake 3.31.6**)
and git. No signing key is needed for your own builds.

```powershell
git clone https://github.com/robertlionhart-cyber/aurora-companion-unlocked.git
cd aurora-companion-unlocked
powershell -ExecutionPolicy Bypass -File scripts\fetch_deps.ps1     # macOS/Linux: bash scripts/fetch_deps.sh
echo sdk.dir=C:/path/to/Android/sdk > local.properties
.\gradlew.bat :app:assembleUnlockedDebug                            # or :app:assembleFamilyDebug
```

`fetch_deps` downloads llama.cpp (pinned commit), the sherpa-onnx AAR and the bundled voice/ASR assets (from the
Family repo's `build-assets-1` release) into `third_party/`. Release builds use `keystore.properties` if present
(see the Family README), otherwise the debug key; APKs you sign yourself can't install over the official release.

Layout: `app/src/main` shared code, `app/src/unlocked` and `app/src/family` the edition settings, `lib/` the
llama.cpp JNI bridge, `scripts/` helpers, `third_party/` fetched dependencies (not in git).

## Licenses

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Avatar clips are the author's own creations (no real people).
`en_US-robert-medium` is Robert's own voice model, included for personal use with Aurora Companion only. Do not
redistribute it or use it to impersonate him.
