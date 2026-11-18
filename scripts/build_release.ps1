# Builds the APK. Needs JDK 17 and the Android SDK (with NDK 29.0.13113456 and CMake 3.31.6).
# Set JAVA_HOME and ANDROID_HOME first (or sdk.dir in local.properties), then run scripts\fetch_deps.ps1 once.
# Release builds are signed with the key from keystore.properties if present (see README), otherwise the debug key.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
    Get-ChildItem -Recurse -Filter '*.apk' (Join-Path $Root 'app\build\outputs\apk') | Select-Object FullName, Length
} finally { Pop-Location }
