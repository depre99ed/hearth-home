# Builds Hearth Home (tests included) and signs it with your key (Windows PowerShell).
#   $env:HEARTH_KEYSTORE = 'C:\keys\hearth.jks'; $env:HEARTH_KEY_ALIAS = 'hearth'; $env:HEARTH_KEY_PASSWORD = '...'
#   tools\hearth\build-release.ps1
# Needs JDK 17 (JAVA_HOME) and the Android SDK with build-tools 34.0.0 (ANDROID_HOME). Output: out\Hearth-Home.apk.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
foreach ($v in 'HEARTH_KEYSTORE', 'HEARTH_KEY_ALIAS', 'HEARTH_KEY_PASSWORD', 'ANDROID_HOME') {
    if (-not [Environment]::GetEnvironmentVariable($v)) { throw "set $v first (see the top of this script)" }
}
$tools = Join-Path $env:ANDROID_HOME 'build-tools\34.0.0'
Push-Location "$root\launcher"
try {
    & .\gradlew.bat --no-daemon testReleaseUnitTest assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
} finally { Pop-Location }
$out = New-Item -ItemType Directory -Force "$root\out"
& "$tools\zipalign.exe" -f -p 4 "$root\launcher\app\build\outputs\apk\release\app-release.apk" "$out\aligned.apk"
& "$tools\apksigner.bat" sign --ks $env:HEARTH_KEYSTORE --ks-key-alias $env:HEARTH_KEY_ALIAS `
    --ks-pass env:HEARTH_KEY_PASSWORD --key-pass env:HEARTH_KEY_PASSWORD --v4-signing-enabled false --out "$out\Hearth-Home.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'signing failed' }
Remove-Item "$out\aligned.apk"
Write-Host "signed $out\Hearth-Home.apk"
