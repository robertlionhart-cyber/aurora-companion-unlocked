# Fetches the build dependencies that are not stored in git into third_party/.
# Usage (from the repo root):  powershell -ExecutionPolicy Bypass -File scripts\fetch_deps.ps1
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$TP = Join-Path $Root 'third_party'
New-Item -ItemType Directory -Force $TP | Out-Null

$LlamaCommit = '9214bdb554544c35e4a9e2c2e8b75d8cfee330ab'   # llama.cpp tag b12465
$AarUrl  = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar'
$AarSha  = '633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96'
$VoiceUrl = 'https://github.com/robertlionhart-cyber/aurora-companion-android/releases/download/build-assets-1/aurora-voice-assets-1.zip'
$VoiceSha = 'BAF48DC538E65CC6AF0AA820BBC3333EE5CE85BB3226EF63DE78A21BAC12382C'

function Get-Checked($url, $out, $sha) {
    if ((Test-Path $out) -and ((Get-FileHash $out -Algorithm SHA256).Hash -eq $sha)) { Write-Host "ok (cached) $out"; return }
    Write-Host "downloading $url"
    curl.exe -fL --retry 3 -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    $h = (Get-FileHash $out -Algorithm SHA256).Hash
    if ($h -ne $sha) { Remove-Item $out; throw "checksum mismatch for $out ($h)" }
}

# 1. llama.cpp at a pinned commit (shallow)
$L = Join-Path $TP 'llama.cpp'
if (-not (Test-Path (Join-Path $L '.git'))) {
    git init -q $L
    git -C $L remote add origin https://github.com/ggml-org/llama.cpp.git
}
$cur = (git -C $L rev-parse -q --verify HEAD 2>$null)
if ($cur -ne $LlamaCommit) {
    git -C $L fetch --depth 1 origin $LlamaCommit
    git -C $L checkout -q --detach FETCH_HEAD
}
Write-Host "ok llama.cpp $LlamaCommit"

# 2. sherpa-onnx AAR (official release)
Get-Checked $AarUrl (Join-Path $TP 'sherpa-onnx-1.13.8.aar') $AarSha

# 3. Voice/ASR/VAD assets bundled into the APK
$Z = Join-Path $TP 'aurora-voice-assets-1.zip'
Get-Checked $VoiceUrl $Z $VoiceSha
$V = Join-Path $TP 'voice-assets'
if (-not (Test-Path (Join-Path $V 'robert\en_US-robert-medium.onnx'))) {
    Expand-Archive -Force $Z -DestinationPath $V
}
Write-Host "ok voice-assets"
Write-Host "All dependencies are in $TP"
