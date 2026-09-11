param(
    [Parameter(Position = 0)]
    [ValidateSet('test', 'render', 'verify', 'contact-sheet', 'all')]
    [string]$Command = 'all'
)

$ErrorActionPreference = 'Stop'
$ToolDir = $PSScriptRoot
$ProjectRoot = (Resolve-Path (Join-Path $ToolDir '..\..')).Path
if ((Split-Path -Leaf $ProjectRoot) -ne 'smart-locker-serial-test-v16') {
    throw "run-tool.ps1 must live under smart-locker-serial-test-v16: $ProjectRoot"
}
$BuildDir = Join-Path $ToolDir 'build'
$SourceDir = Join-Path $ToolDir 'src'
$TestDir = Join-Path $ToolDir 'test'
$ClassDir = Join-Path $BuildDir 'classes'

function Compile-Tests {
    New-Item -ItemType Directory -Force -Path $ClassDir | Out-Null
    & javac -encoding UTF-8 -d $ClassDir (Join-Path $SourceDir 'ZipUiAssetTool.java') `
        (Join-Path $TestDir 'ZipUiManifestTest.java') `
        (Join-Path $TestDir 'ZipUiAssetTest.java') `
        (Join-Path $TestDir 'ZipUiSemanticContractTest.java') `
        (Join-Path $TestDir 'ZipUiPathSafetyTest.java')
    if ($LASTEXITCODE -ne 0) { throw "javac tests failed with exit code $LASTEXITCODE" }
}

function Compile-Tool {
    New-Item -ItemType Directory -Force -Path $ClassDir | Out-Null
    & javac -encoding UTF-8 -d $ClassDir (Join-Path $SourceDir 'ZipUiAssetTool.java')
    if ($LASTEXITCODE -ne 0) { throw "javac tool failed with exit code $LASTEXITCODE" }
}

function Run-Test {
    Compile-Tests
    & java "-Dzip.ui.projectRoot=$ProjectRoot" -cp $ClassDir ZipUiManifestTest
    if ($LASTEXITCODE -ne 0) { throw "ZipUiManifestTest failed with exit code $LASTEXITCODE" }
    & java "-Dzip.ui.projectRoot=$ProjectRoot" -cp $ClassDir ZipUiPathSafetyTest
    if ($LASTEXITCODE -ne 0) { throw "ZipUiPathSafetyTest failed with exit code $LASTEXITCODE" }
    & java "-Dzip.ui.projectRoot=$ProjectRoot" -cp $ClassDir ZipUiSemanticContractTest
    if ($LASTEXITCODE -ne 0) { throw "ZipUiSemanticContractTest failed with exit code $LASTEXITCODE" }
    & java "-Dzip.ui.projectRoot=$ProjectRoot" -cp $ClassDir ZipUiAssetTest
    if ($LASTEXITCODE -ne 0) { throw "ZipUiAssetTest failed with exit code $LASTEXITCODE" }
}

function Run-Tool([string]$Mode) {
    Compile-Tool
    & java "-Dzip.ui.projectRoot=$ProjectRoot" -cp $ClassDir ZipUiAssetTool $Mode
    if ($LASTEXITCODE -ne 0) { throw "ZipUiAssetTool $Mode failed with exit code $LASTEXITCODE" }
}

switch ($Command) {
    'test' { Run-Test }
    'render' { Run-Tool 'render' }
    'verify' { Run-Tool 'verify' }
    'contact-sheet' { Run-Tool 'contact-sheet' }
    'all' {
        Run-Tool 'render'
        Run-Tool 'verify'
        Run-Tool 'contact-sheet'
    }
}
