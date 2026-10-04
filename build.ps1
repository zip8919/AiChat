param(
    [string]$SensenovaKey = "",
    [string]$KeystorePassword = $env:KEYSTORE_PASSWORD,
    [string]$KeyAlias = $env:KEY_ALIAS
)

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  AiChat Build Script v1.3.5" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

if ($SensenovaKey) { Write-Host "SenseNova Key:     (set - baked into APK)" -ForegroundColor Green }
else { Write-Host "SenseNova Key:     (empty - fill it in on the device)" -ForegroundColor Yellow }
if ($KeystorePassword) { Write-Host "Keystore Password: (set)" -ForegroundColor Green }
else { Write-Host "Keystore Password: (not set - signing may fail)" -ForegroundColor Red }
if ($KeyAlias) { Write-Host "Key Alias:         $KeyAlias" -ForegroundColor Green }
else { Write-Host "Key Alias:         (not set - signing may fail)" -ForegroundColor Red }
Write-Host ""

# Find Gradle
$gradleCmd = if (Test-Path ".\gradlew.bat") { ".\gradlew.bat" }
elseif (Test-Path "D:\Program Files\gradle-8.2\bin\gradle.bat") { "D:\Program Files\gradle-8.2\bin\gradle.bat" }
else { "gradle.bat" }

$gradleArgs = @("assembleRelease")
if ($SensenovaKey) { $gradleArgs += "-PsensenovaKey=$SensenovaKey" }
if ($KeystorePassword) { $gradleArgs += "-PkeystorePassword=$KeystorePassword" }
if ($KeyAlias)       { $gradleArgs += "-PkeyAlias=$KeyAlias" }

Write-Host "Running: $gradleCmd $gradleArgs" -ForegroundColor Gray
Write-Host ""

& $gradleCmd @gradleArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED!" -ForegroundColor Red
    exit $LASTEXITCODE
}

$versionName = if ((Get-Content "app\build.gradle" | Select-String 'versionName\s+"([^"]+)"').Matches) { (Get-Content "app\build.gradle" | Select-String 'versionName\s+"([^"]+)"').Matches[0].Groups[1].Value } else { "unknown" }
$timestamp = Get-Date -Format "yyyyMMdd-HHmm"

# 按 ABI 分包：app-<abi>-release.apk / app-universal-release.apk
$apks = Get-ChildItem "app\build\outputs\apk\release\app-*-release.apk" | Sort-Object Name
if (-not $apks) { Write-Host "No split APK found!" -ForegroundColor Red; exit 1 }
$outputs = @()
foreach ($apk in $apks) {
    $abi = $apk.BaseName -replace '^app-', '' -replace '-release$', ''
    $name = "AiChat-v$versionName-$timestamp-$abi.apk"
    Copy-Item $apk.FullName $name -Force
    $outputs += $name
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Build Complete" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
foreach ($o in $outputs) { Write-Host "Output: $o" -ForegroundColor Green }
