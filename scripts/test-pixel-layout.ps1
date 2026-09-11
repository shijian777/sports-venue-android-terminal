param(
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$AppApk,
    [string]$AppClassesJar
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$sdk = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$buildTools = Join-Path $sdk 'build-tools\35.0.0'
$javaBin = 'C:\Users\Administrator\.jdks\jbr-21.0.11\bin'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
$appJar = if ([string]::IsNullOrWhiteSpace($AppClassesJar)) {
    Join-Path $project 'manual-build\v17\production\app-classes.jar'
} else { [System.IO.Path]::GetFullPath($AppClassesJar) }
if (-not (Test-Path -LiteralPath $appJar -PathType Leaf)) {
    throw 'AppClassesJar must point to the class archive from the APK variant being tested.'
}
$work = Join-Path $project ('manual-build\ui-regression\' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$null = New-Item -ItemType Directory -Path $classes, $dex -Force
$keystore = Join-Path $project 'manual-build\debug.keystore'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
function Assert-Step([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step failed: $LASTEXITCODE" } }
function Sign-Apk([string]$inputApk, [string]$outputApk) {
    $aligned = $inputApk + '.aligned.apk'
    & "$buildTools\zipalign.exe" -f 4 $inputApk $aligned
    Assert-Step 'zipalign'
    & "$javaBin\java.exe" -jar "$buildTools\lib\apksigner.jar" sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $outputApk $aligned
    Assert-Step 'sign test artifact'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
if ($AppApk) {
    # Emulator-only derived artifact: preserve app DEX/resources, remove unsupported ARM libraries.
    # This is NOT the deliverable and cannot validate hardware or the Baidu native SDK.
    $unsignedApp = Join-Path $work 'app-ui-only-unsigned.apk'
    Copy-Item -LiteralPath $AppApk -Destination $unsignedApp
    $archive = [System.IO.Compression.ZipFile]::Open($unsignedApp, 'Update')
    try {
        @($archive.Entries | Where-Object { $_.FullName.StartsWith('lib/') -or $_.FullName.StartsWith('META-INF/') }) | ForEach-Object { $_.Delete() }
    } finally { $archive.Dispose() }
    $signedApp = Join-Path $work 'app-ui-only.apk'
    Sign-Apk $unsignedApp $signedApp
    & $adb -s $Serial install --no-streaming -r $signedApp
    Assert-Step 'install emulator-only app copy'
}
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $project 'app\src\androidTest\java') -Filter '*.java' -File -Recurse | Select-Object -ExpandProperty FullName)
& "$javaBin\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp "$androidJar;$appJar" -d $classes @testSources
Assert-Step 'compile Android regression'
$testJar = Join-Path $work 'test.jar'
& "$javaBin\jar.exe" cf $testJar -C $classes .
Assert-Step 'package regression classes'
& "$javaBin\java.exe" -cp "$buildTools\lib\d8.jar" com.android.tools.r8.D8 --lib $androidJar --classpath $appJar --min-api 21 --output $dex $testJar
Assert-Step 'dex regression'
$unsignedTest = Join-Path $work 'test-unsigned.apk'
& "$buildTools\aapt.exe" package -f -M (Join-Path $project 'app\src\androidTest\AndroidManifest.xml') -I $androidJar -F $unsignedTest
Assert-Step 'package regression manifest'
$archive = [System.IO.Compression.ZipFile]::Open($unsignedTest, 'Update')
try { $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dex 'classes.dex'), 'classes.dex') }
finally { $archive.Dispose() }
$signedTest = Join-Path $work 'test.apk'
Sign-Apk $unsignedTest $signedTest
& $adb -s $Serial install --no-streaming -r $signedTest
Assert-Step 'install regression runner'
$result = & $adb -s $Serial shell am instrument -w com.codex.lockertest.review/com.codex.lockertest.review.PixelLayoutRegression
Assert-Step 'run regression'
$result
if (($result -join "`n") -notmatch 'PIXEL_LAYOUT_REGRESSION=PASS') { throw 'Pixel layout regression failed' }
