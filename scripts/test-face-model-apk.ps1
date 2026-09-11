$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

# Run the real build-resource and audit stages, without compiling the app,
# signing, publishing, or touching the shared manual-build directory.
$buildText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'build-debug.ps1'))
$auditText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'audit-production-apk.ps1'))
foreach ($source in @($buildText, $auditText)) {
    $parseErrors = $null
    $tree = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$null, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'PRODUCTION_SCRIPT_PARSE_FAILED' }
    foreach ($function in $tree.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -in @('Assert-Exit', 'Assert-SameSet', 'Stop-Audit')
    }, $true)) {
        . ([scriptblock]::Create($function.Extent.Text))
    }
}

function Get-Stage([string]$source, [string]$start, [string]$end) {
    $begin = $source.IndexOf($start, [StringComparison]::Ordinal)
    if ($begin -lt 0) { throw "TEST_STAGE_START_NOT_FOUND: $start" }
    $finish = $source.IndexOf($end, $begin, [StringComparison]::Ordinal)
    if ($finish -le $begin) { throw "TEST_STAGE_END_NOT_FOUND: $end" }
    return $source.Substring($begin, $finish - $begin)
}

$resourceStageText = Get-Stage $buildText '& $aapt2 link -o $baseApk' '$dexFiles ='
$buildAuditStageText = Get-Stage $buildText 'Write-Output "APK_DEX_ENTRIES=' '$permissions ='
$productionAuditStageText = Get-Stage $auditText '$actualModels = @(' '$resourceResult ='
$aapt2 = 'C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt2.exe'
$aapt = 'C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt.exe'
$androidJar = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platforms\android-34\android.jar'
# Keep the full GUID for isolation, but leave room for the vendor's long model
# filenames when the build supplies a nested workspace TEMP on Windows.
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('fm-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$projectRoot = Join-Path $fixtureRoot 'p'
$modelPaths = @(
    'assets/face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1',
    'assets/face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5',
    'assets/face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4',
    'assets/face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3',
    'assets/face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3',
    'assets/face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1',
    'assets/face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1'
)
# Literal SHA-256 of the independently defined fixture bytes "abc".
$fixtureSha256 = 'BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD'
$faceArtifacts = @($modelPaths | ForEach-Object {
    [pscustomobject]@{ RelativePath = 'app/src/main/' + $_; Kind = 'MODEL'; Size = 3; Sha256 = $fixtureSha256 }
})
$expectedModels = @($modelPaths | ForEach-Object {
    [pscustomobject]@{ Path = $_; Size = 3; Sha256 = $fixtureSha256 }
})
$failures = [Collections.Generic.List[string]]::new()
$passed = 0

function Test-Case([string]$name, [scriptblock]$body) {
    try {
        & $body | Out-Null
        $script:passed++
        Write-Output "PASS $name"
    } catch {
        $script:failures.Add($name + ': ' + $_.Exception.Message)
        Write-Output "FAIL $name : $($_.Exception.Message)"
    }
}

function Assert-Rejected([scriptblock]$body, [string]$expectedCode) {
    try { & $body | Out-Null } catch {
        if (-not $_.Exception.Message.Contains($expectedCode)) { throw }
        return
    }
    throw "EXPECTED_REJECTION_NOT_RAISED: $expectedCode"
}

function Get-ZipSha256([string]$path) {
    # Keep child PowerShell tests independent of inherited PSModulePath settings.
    $stream = [IO.File]::OpenRead($path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '') }
    finally { $sha256.Dispose(); $stream.Dispose() }
}

function New-ModelZip([string]$name, [bool]$backslashes, [string]$extra = '', [bool]$tampered = $false) {
    $path = Join-Path $fixtureRoot $name
    $archive = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($modelPath in $modelPaths) {
            $raw = $modelPath
            if ($backslashes) { $raw = 'assets/' + $modelPath.Substring(7).Replace('/', '\') }
            $entry = $archive.CreateEntry($raw)
            $stream = $entry.Open()
            try {
                $bytes = [byte[]](0x61, 0x62, 0x63)
                if ($tampered -and $modelPath -ceq $modelPaths[0]) { $bytes[2] = 0x64 }
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
        foreach ($other in @('AndroidManifest.xml', 'resources.arsc', 'classes.dex', 'lib/armeabi-v7a/fixture.so', 'lib/arm64-v8a/fixture.so', 'assets/notice.txt', $extra)) {
            if ($other.Length -eq 0) { continue }
            $entry = $archive.CreateEntry($other)
            $stream = $entry.Open()
            try { $stream.WriteByte(0x42) } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
    return $path
}

function Assert-ExactModels([string]$path) {
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try {
        foreach ($modelPath in $modelPaths) {
            $entry = $archive.GetEntry($modelPath)
            if ($null -eq $entry) { throw "EXACT_MODEL_LOOKUP_FAILED: $modelPath" }
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $stream.Dispose(); $sha.Dispose() }
            if ($entry.Length -ne 3 -or $hash -cne $fixtureSha256) { throw "MODEL_BYTES_CHANGED: $modelPath" }
        }
        if (@($archive.Entries | Where-Object { $_.FullName.Contains('\') }).Count -ne 0) {
            throw 'NON_CANONICAL_ZIP_ENTRY_REMAINS'
        }
    } finally { $archive.Dispose() }
}

function Invoke-BuildModelAudit([string]$path) {
    $signedApk = $path
    $archiveEntries = @(& $aapt list $path)
    Assert-Exit 'Fixture aapt list'
    $actualDexEntries = @('classes.dex')
    & $buildAuditStage
}

function Invoke-ProductionModelAudit([string]$path) {
    $apkZip = $path
    $archive = [IO.Compression.ZipFile]::OpenRead($path)
    try { $normalizedFiles = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') }) }
    finally { $archive.Dispose() }
    & $productionAuditStage
}

try {
    $resourceStage = Join-Path $fixtureRoot 'resource-stage.ps1'
    $buildAuditStage = Join-Path $fixtureRoot 'build-audit-stage.ps1'
    $productionAuditStage = Join-Path $fixtureRoot 'production-audit-stage.ps1'
    [IO.File]::WriteAllText($resourceStage, $resourceStageText, [Text.UTF8Encoding]::new($true))
    [IO.File]::WriteAllText($buildAuditStage, $buildAuditStageText, [Text.UTF8Encoding]::new($true))
    [IO.File]::WriteAllText($productionAuditStage, $productionAuditStageText, [Text.UTF8Encoding]::new($true))
    $modelHelper = Join-Path $PSScriptRoot 'face-model-apk.ps1'
    Copy-Item -LiteralPath $modelHelper -Destination $fixtureRoot
    . $modelHelper
    foreach ($modelPath in $modelPaths) {
        $path = Join-Path $projectRoot ('app/src/main/' + $modelPath)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        [IO.File]::WriteAllBytes($path, [byte[]](0x61, 0x62, 0x63))
    }
    [IO.File]::WriteAllText((Join-Path $projectRoot 'app/src/main/AndroidManifest.xml'),
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.codex.fixture"><application /></manifest>')
    $baseApk = Join-Path $fixtureRoot 'base.apk'
    $unsignedApk = Join-Path $fixtureRoot 'unsigned.apk'
    $generatedSources = Join-Path $fixtureRoot 'generated'
    New-Item -ItemType Directory -Path $generatedSources | Out-Null
    $flatResources = @()

    # Removing the build's repair step must fail exact Android-style lookup.
    Test-Case 'resource_stage_emits_exact_seven_android_asset_paths' {
        & $resourceStage
        Assert-ExactModels $unsignedApk
    }
    $good = New-ModelZip 'canonical.apk' $false
    $bad = New-ModelZip 'backslash.apk' $true
    Test-Case 'build_audit_accepts_canonical_pinned_models' { Invoke-BuildModelAudit $good }
    # Normalizing archive names before comparison is the bug these catch.
    Test-Case 'build_audit_rejects_backslash_model_paths' {
        Assert-Rejected { Invoke-BuildModelAudit $bad } 'APK_NON_CANONICAL_ENTRY'
    }
    Test-Case 'production_audit_accepts_canonical_pinned_models' { Invoke-ProductionModelAudit $good }
    Test-Case 'production_audit_rejects_backslash_model_paths' {
        Assert-Rejected { Invoke-ProductionModelAudit $bad } 'APK_MODEL_PATH_INVALID'
    }
    # The repair must not alter model bytes, unrelated assets, DEX, resources,
    # or either ABI. Expectations use literal fixture data, not repair helpers.
    Test-Case 'repair_preserves_models_and_all_non_model_entries' {
        $repaired = New-ModelZip 'repair.apk' $true
        Invoke-FaceModelApk -Apk $repaired -Artifacts $faceArtifacts -RepairWindowsModelPaths
        Assert-ExactModels $repaired
        $archive = [IO.Compression.ZipFile]::OpenRead($repaired)
        try {
            if ($archive.Entries.Count -ne 13) { throw 'ZIP_INVENTORY_CHANGED' }
            foreach ($path in @('AndroidManifest.xml', 'resources.arsc', 'classes.dex', 'lib/armeabi-v7a/fixture.so', 'lib/arm64-v8a/fixture.so', 'assets/notice.txt')) {
                $entry = $archive.GetEntry($path)
                if ($null -eq $entry -or $entry.Length -ne 1) { throw "NON_MODEL_ENTRY_CHANGED: $path" }
                $stream = $entry.Open()
                try { if ($stream.ReadByte() -ne 0x42) { throw "NON_MODEL_BYTES_CHANGED: $path" } }
                finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
    }
    Test-Case 'canonical_repair_is_byte_identical_no_op' {
        $before = Get-ZipSha256 $good
        Invoke-FaceModelApk -Apk $good -Artifacts $faceArtifacts -RepairWindowsModelPaths
        if ((Get-ZipSha256 $good) -cne $before) {
            throw 'CANONICAL_APK_WAS_REWRITTEN'
        }
    }
    $tampered = New-ModelZip 'tampered.apk' $false '' $true
    Test-Case 'build_audit_rejects_same_length_tampered_model' {
        Assert-Rejected { Invoke-BuildModelAudit $tampered } 'APK_MODEL_PIN_INVALID'
    }
    Test-Case 'production_audit_rejects_same_length_tampered_model' {
        Assert-Rejected { Invoke-ProductionModelAudit $tampered } 'APK_MODEL_PIN_INVALID'
    }
    foreach ($fixture in @(
        [pscustomobject]@{ Name = 'unknown-asset.apk'; Extra = 'assets/other\bad.txt'; Tampered = $false; Code = 'APK_NON_CANONICAL_ENTRY' },
        [pscustomobject]@{ Name = 'extra-model.apk'; Extra = 'assets/face-sdk-models/extra.model'; Tampered = $false; Code = 'APK_MODEL_SET_INVALID' },
        [pscustomobject]@{ Name = 'model-collision.apk'; Extra = $modelPaths[0]; Tampered = $false; Code = 'APK_MODEL_SET_INVALID' },
        [pscustomobject]@{ Name = 'tampered-repair.apk'; Extra = ''; Tampered = $true; Code = 'APK_MODEL_PIN_INVALID' }
    )) {
        Test-Case ('repair_rejects_before_write_' + $fixture.Name) {
            $path = New-ModelZip $fixture.Name $true $fixture.Extra $fixture.Tampered
            $before = Get-ZipSha256 $path
            Assert-Rejected {
                Invoke-FaceModelApk -Apk $path -Artifacts $faceArtifacts -RepairWindowsModelPaths
            } $fixture.Code
            if ((Get-ZipSha256 $path) -cne $before) {
                throw 'REJECTED_APK_WAS_CHANGED'
            }
        }
    }
    $missing = New-ModelZip 'missing.apk' $false
    $archive = [IO.Compression.ZipFile]::Open($missing, [IO.Compression.ZipArchiveMode]::Update)
    try { $archive.GetEntry($modelPaths[0]).Delete() } finally { $archive.Dispose() }
    Test-Case 'build_audit_rejects_missing_model' {
        Assert-Rejected { Invoke-BuildModelAudit $missing } 'APK_MODEL_FILE_MISSING'
    }
    Test-Case 'production_audit_rejects_missing_model' {
        Assert-Rejected { Invoke-ProductionModelAudit $missing } 'APK_MODEL_SET_INVALID'
    }
    $duplicate = New-ModelZip 'duplicate.apk' $false $modelPaths[0]
    Test-Case 'build_audit_rejects_duplicate_model' {
        Assert-Rejected { Invoke-BuildModelAudit $duplicate } 'APK_MODEL_SET_INVALID'
    }
    Test-Case 'production_audit_rejects_duplicate_model' {
        Assert-Rejected { Invoke-ProductionModelAudit $duplicate } 'APK_MODEL_SET_INVALID'
    }
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedFixture.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedFixture) -notmatch '^fm-[a-f0-9]{32}$') {
        throw 'UNSAFE_FIXTURE_CLEANUP_PATH'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
Write-Output "FACE_MODEL_APK_TESTS PASSED=$passed FAILED=$($failures.Count)"
if ($failures.Count -ne 0) { throw ($failures -join "`n") }
