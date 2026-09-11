$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$script:ProcessIndex = 0
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\..')).TrimEnd('\')
$outputsRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'outputs')).TrimEnd('\')
$verificationRoot = Join-Path $projectRoot 'manual-build\bootstrap-verification'
$runId = ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')) + '-' +
        ([Guid]::NewGuid().ToString('N'))
$runRoot = Join-Path $verificationRoot $runId
$previousRoot = Join-Path $runRoot 'previous'
$logsRoot = Join-Path $runRoot 'logs'
$runStartUtc = [DateTime]::UtcNow

function Write-Utf8([string]$path, [string[]]$lines) {
    [IO.File]::WriteAllLines($path, $lines, $script:Utf8NoBom)
}

function Join-UnicodeCharacters([int[]]$codePoints) {
    if ($null -eq $codePoints -or $codePoints.Count -eq 0) {
        throw 'VERIFICATION_OUTPUT_NAME_INVALID'
    }
    return -join @($codePoints | ForEach-Object { [char]$_ })
}

function Get-ExactOutputPath([string]$name) {
    $path = [IO.Path]::GetFullPath((Join-Path $outputsRoot $name))
    $parent = [IO.Path]::GetFullPath((Split-Path -Parent $path)).TrimEnd('\')
    if ($parent -cne $outputsRoot -or [IO.Path]::GetFileName($path) -cne $name) {
        throw 'VERIFICATION_OUTPUT_PATH_INVALID'
    }
    return $path
}

function Quote-NativeArgument([string]$value) {
    if ($null -eq $value -or $value.Contains('"') -or
            $value.Contains("`r") -or $value.Contains("`n")) {
        throw 'VERIFICATION_ARGUMENT_INVALID'
    }
    return '"' + $value + '"'
}

function Invoke-CheckedNative(
        [string]$name,
        [string]$filePath,
        [string[]]$arguments,
        [int[]]$allowedExitCodes = @(0)) {
    $script:ProcessIndex++
    $safeName = $name -replace '[^A-Za-z0-9_.-]', '_'
    $prefix = ('{0:D2}-{1}' -f $script:ProcessIndex, $safeName)
    $stdout = Join-Path $logsRoot ($prefix + '.stdout.log')
    $stderr = Join-Path $logsRoot ($prefix + '.stderr.log')
    $argumentText = (@($arguments | ForEach-Object {
        Quote-NativeArgument ([string]$_)
    }) -join ' ')
    $process = Start-Process -FilePath $filePath `
        -ArgumentList $argumentText `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -Wait -PassThru -WindowStyle Hidden
    $exitCode = [int]$process.ExitCode
    if ($allowedExitCodes -notcontains $exitCode) {
        throw "VERIFICATION_PROCESS_FAILED=$name EXIT=$exitCode"
    }
    return [pscustomobject]@{
        Name = $name
        ExitCode = $exitCode
        Stdout = $stdout
        Stderr = $stderr
    }
}

function Assert-FreshArtifact([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "VERIFICATION_FRESH_APK_MISSING=$path"
    }
    $item = Get-Item -LiteralPath $path
    if ($item.LastWriteTimeUtc -lt $runStartUtc.AddSeconds(-2)) {
        throw "VERIFICATION_STALE_APK=$path"
    }
    return $item
}

function Assert-LogMarker([string]$path, [string]$marker) {
    $text = [IO.File]::ReadAllText($path)
    if (-not $text.Contains($marker)) {
        throw "VERIFICATION_EVIDENCE_MISSING=$marker"
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'app\build.gradle') -PathType Leaf)) {
    throw 'VERIFICATION_PROJECT_ROOT_INVALID'
}
if (-not (Test-Path -LiteralPath $outputsRoot -PathType Container)) {
    New-Item -ItemType Directory -Path $outputsRoot | Out-Null
}
New-Item -ItemType Directory -Path $previousRoot -Force | Out-Null
New-Item -ItemType Directory -Path $logsRoot -Force | Out-Null
Write-Utf8 (Join-Path $runRoot 'run.txt') @(
    "RUN_ID=$runId",
    "START_UTC=$($runStartUtc.ToString('o'))")

$outputNamePrefix = Join-UnicodeCharacters @(
    0x667A, 0x80FD, 0x66F4, 0x8863, 0x67DC)
$testEdition = Join-UnicodeCharacters @(0x6D4B, 0x8BD5, 0x7248)
$localOutput = Get-ExactOutputPath ($outputNamePrefix + '-v21-localDemo.apk')
$productionOutput = Get-ExactOutputPath (
    $outputNamePrefix + '-v21-production' + $testEdition + '.apk')
foreach ($oldOutput in @($localOutput, $productionOutput)) {
    if (Test-Path -LiteralPath $oldOutput -PathType Leaf) {
        $destination = Join-Path $previousRoot ([IO.Path]::GetFileName($oldOutput))
        if (Test-Path -LiteralPath $destination) {
            throw 'VERIFICATION_PREVIOUS_COLLISION'
        }
        Move-Item -LiteralPath $oldOutput -Destination $destination
    }
}
foreach ($output in @($localOutput, $productionOutput)) {
    if (Test-Path -LiteralPath $output) {
        throw "VERIFICATION_OUTPUT_ISOLATION_FAILED=$output"
    }
}

$env:CENTRAL_CONTROL_SYS_CODE = $null
$powershell = (Get-Command powershell.exe -ErrorAction Stop).Source
$build = Invoke-CheckedNative 'build-all' $powershell @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'build-debug.ps1'),
    '-Variant', 'all')

$localItem = Assert-FreshArtifact $localOutput
$productionItem = Assert-FreshArtifact $productionOutput
$localHash = (Get-FileHash -LiteralPath $localOutput -Algorithm SHA256).Hash
$productionHashBefore = (Get-FileHash -LiteralPath $productionOutput -Algorithm SHA256).Hash
Write-Utf8 (Join-Path $runRoot 'artifacts.txt') @(
    "RUN_ID=$runId",
    "LOCAL_SIZE=$($localItem.Length)",
    "LOCAL_SHA256=$localHash",
    "PRODUCTION_SIZE=$($productionItem.Length)",
    "PRODUCTION_SHA256=$productionHashBefore")

$audit = Invoke-CheckedNative 'production-audit' $powershell @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'audit-production-apk.ps1'),
    '-Apk', $productionOutput)
$productionHashAfter = (Get-FileHash -LiteralPath $productionOutput -Algorithm SHA256).Hash
if ($productionHashAfter -cne $productionHashBefore) {
    throw 'VERIFICATION_AUDIT_MUTATED_APK'
}

$frozen = Invoke-CheckedNative 'v16-frozen' $powershell @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'assert-v16-frozen.ps1'))
$apiProduction = Invoke-CheckedNative 'api25-production' $powershell @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'check-api25-compatibility.ps1'),
    '-Variant', 'production')
$apiLocal = Invoke-CheckedNative 'api25-local-demo' $powershell @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'check-api25-compatibility.ps1'),
    '-Variant', 'localDemo')
Assert-LogMarker $apiProduction.Stdout 'API25_LINT_PRODUCTION=PASS'
Assert-LogMarker $apiLocal.Stdout 'API25_LINT_LOCAL_DEMO=PASS'

$git = (Get-Command git.exe -ErrorAction Stop).Source
$null = Invoke-CheckedNative 'git-diff-check' $git @('diff', '--check')

$rg = (Get-Command rg.exe -ErrorAction Stop).Source
$sourceScan = Invoke-CheckedNative 'forbidden-source-scan' $rg @(
    '--fixed-strings', '--line-number',
    '-e', 'System.getenv(',
    '-e', 'GeneratedCentralControlSysCode',
    '-e', 'generated-secret',
    'app/src/main', 'app/src/production') @(0, 1)
if ($sourceScan.ExitCode -eq 0) {
    throw 'VERIFICATION_FORBIDDEN_SOURCE_MATCH'
}
$apkScan = Invoke-CheckedNative 'forbidden-apk-scan' $rg @(
    '--text', '--fixed-strings', '--line-number',
    '-e', 'TEST_BOOTSTRAP_KEY_DO_NOT_SHIP',
    '-e', 'CENTRAL_CONTROL_SYS_CODE',
    $productionOutput) @(0, 1)
if ($apkScan.ExitCode -eq 0) {
    throw 'VERIFICATION_FORBIDDEN_APK_MATCH'
}

$pass = "VERIFICATION=PASS RUN_ID=$runId PRODUCTION_SHA256=$productionHashAfter"
Write-Utf8 (Join-Path $runRoot 'result.txt') @($pass)
Write-Output $pass
