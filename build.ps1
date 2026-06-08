param(
    [string]$DeepseekKey = "",
    [string]$SiliconflowKey = "",
    [string]$KeystorePassword = $env:KEYSTORE_PASSWORD,
    [string]$KeyAlias = $env:KEY_ALIAS
)

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  AiChat Build Script v1.2.0" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

if ($DeepseekKey) { Write-Host "DeepSeek Key:      (set)" -ForegroundColor Green }
else { Write-Host "DeepSeek Key:      (sk-xxx placeholder)" -ForegroundColor Yellow }
if ($SiliconflowKey) { Write-Host "SiliconFlow Key:   (set)" -ForegroundColor Green }
else { Write-Host "SiliconFlow Key:   (sk-xxx placeholder)" -ForegroundColor Yellow }
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
if ($DeepseekKey)    { $gradleArgs += "-PdeepseekKey=$DeepseekKey" }
if ($SiliconflowKey) { $gradleArgs += "-PsiliconflowKey=$SiliconflowKey" }
if ($KeystorePassword) { $gradleArgs += "-PkeystorePassword=$KeystorePassword" }
if ($KeyAlias)       { $gradleArgs += "-PkeyAlias=$KeyAlias" }

Write-Host "Running: $gradleCmd $gradleArgs" -ForegroundColor Gray
Write-Host ""

& $gradleCmd @gradleArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED!" -ForegroundColor Red
    exit $LASTEXITCODE
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmm"
$output = "AiChat-v1.2.0-$timestamp-release.apk"
Copy-Item "app\build\outputs\apk\release\app-release.apk" $output -Force

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Build Complete" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Output: $output" -ForegroundColor Green
