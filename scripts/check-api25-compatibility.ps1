param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('production', 'localDemo')]
    [string]$Variant
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$sdkRoot = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$lint = Join-Path $sdkRoot 'cmdline-tools\latest\bin\lint.bat'
$mainSources = Join-Path $projectRoot 'app\src\main\java'
$variantSources = Join-Path $projectRoot "app\src\$Variant\java"
$resources = Join-Path $projectRoot 'app\src\main\res'
$appClasses = Join-Path $projectRoot "manual-build\v17\$Variant\app-classes"
$lintRoot = Join-Path $projectRoot "manual-build\v17\api25-lint\$Variant"
$allowedRoot = [IO.Path]::GetFullPath(
        (Join-Path $projectRoot 'manual-build\v17\api25-lint')).TrimEnd('\') + '\'
$resolvedLintRoot = [IO.Path]::GetFullPath($lintRoot)

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'app\build.gradle') -PathType Leaf)) {
    throw 'API25_PROJECT_ROOT_INVALID'
}
if (-not $resolvedLintRoot.StartsWith(
        $allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'API25_LINT_ROOT_OUT_OF_BOUNDS'
}
foreach ($required in @($lint, $mainSources, $variantSources, $resources, $appClasses)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "API25_LINT_INPUT_MISSING=$required"
    }
}

if (Test-Path -LiteralPath $resolvedLintRoot) {
    Remove-Item -LiteralPath $resolvedLintRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $resolvedLintRoot -Force | Out-Null

$manifest = '<?xml version="1.0" encoding="utf-8"?>' + "`n" +
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.codex.lockertest.api25lint">' + "`n" +
        '  <uses-sdk android:minSdkVersion="25" android:targetSdkVersion="30" />' + "`n" +
        '</manifest>' + "`n"
[IO.File]::WriteAllText(
        (Join-Path $resolvedLintRoot 'AndroidManifest.xml'),
        $manifest,
        [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText(
        (Join-Path $resolvedLintRoot 'project.properties'),
        "target=android-34`n",
        [Text.UTF8Encoding]::new($false))

$lintArguments = @(
    '--check', 'NewApi',
    '--exitcode',
    '--quiet',
    '--compile-sdk-version', '34',
    '--sdk-home', $sdkRoot,
    '--sources', $mainSources,
    '--sources', $variantSources,
    '--resources', $resources,
    '--classpath', $appClasses,
    $resolvedLintRoot
)

Push-Location -LiteralPath $projectRoot
try {
    & $lint @lintArguments
    $lintExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($lintExit -ne 0) {
    throw "$Variant API25 lint failed: $lintExit"
}
if ($Variant -ceq 'production') {
    Write-Output 'API25_LINT_PRODUCTION=PASS'
} else {
    Write-Output 'API25_LINT_LOCAL_DEMO=PASS'
}
