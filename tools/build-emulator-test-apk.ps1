param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$buildTools = Join-Path $sdkRoot "build-tools\35.0.0"
$aapt = Join-Path $buildTools "aapt.exe"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$sourceRoot = Join-Path $projectRoot "manual-build\v14"
$outputRoot = Join-Path $sourceRoot "emulator-test"
$stageRoot = Join-Path $outputRoot "stage"
$unsignedApk = Join-Path $outputRoot "smart-locker-kiosk-v14-java-unsigned.apk"
$alignedApk = Join-Path $outputRoot "smart-locker-kiosk-v14-java-aligned.apk"
$signedApk = Join-Path $outputRoot "smart-locker-kiosk-v14-java-test.apk"
$baseApk = Join-Path $sourceRoot "apk\base-unsigned.apk"
$dex = Join-Path $sourceRoot "dex\classes.dex"
$keystore = Join-Path $projectRoot "manual-build\debug.keystore"

foreach ($required in @($aapt, $zipalign, $apksigner, $baseApk, $dex,
        $keystore)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing emulator-test input: $required"
    }
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null
Copy-Item -LiteralPath $baseApk -Destination $unsignedApk -Force
Copy-Item -LiteralPath $dex -Destination (Join-Path $stageRoot "classes.dex") -Force

$previousLocation = Get-Location
Set-Location $stageRoot
try {
    & $aapt add $unsignedApk "classes.dex"
    if ($LASTEXITCODE -ne 0) { throw "aapt add failed: $LASTEXITCODE" }
} finally {
    Set-Location $previousLocation
}

& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }
& $apksigner sign `
    --ks $keystore `
    --ks-key-alias androiddebugkey `
    --ks-pass pass:android `
    --key-pass pass:android `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $signedApk `
    $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }

& $apksigner verify --verbose $signedApk
if ($LASTEXITCODE -ne 0) { throw "signature verification failed: $LASTEXITCODE" }
$entries = @(& $aapt list $signedApk)
if ($LASTEXITCODE -ne 0) { throw "APK listing failed: $LASTEXITCODE" }
$nativeEntries = @($entries | Where-Object { $_ -match '^lib/.+\.so$' })
if ($nativeEntries.Count -ne 0) {
    throw "Unexpected emulator native libraries: $($nativeEntries -join ',')"
}

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk).Hash
Write-Output "EMULATOR_TEST_APK=$signedApk"
Write-Output "EMULATOR_TEST_ABI=java-only"
Write-Output "EMULATOR_TEST_SHA256=$hash"
Write-Output "NOTE=Test-only package; production ARM APK is unchanged"
