param()
$ErrorActionPreference = 'Stop'
$v17Root = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $v17Root)
$v16Root = Join-Path $workspaceRoot 'work\smart-locker-serial-test-v16'
$v16Apk = Join-Path $workspaceRoot 'outputs\智能更衣柜-ZIP高还原交互版-v16.apk'
$baselineRoot = Join-Path $v17Root '.superpowers\baseline'

function Get-SourceManifest([string]$root) {
    $manualBuild = [IO.Path]::GetFullPath((Join-Path $root 'manual-build'))
    Get-ChildItem -LiteralPath $root -Recurse -File -Force |
        Where-Object { -not $_.FullName.StartsWith($manualBuild + '\', [StringComparison]::OrdinalIgnoreCase) } |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
            '{0} *{1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant(), $relative
        }
}

function Assert-Lines([string]$name, [string[]]$expected, [string[]]$actual) {
    if (@(Compare-Object $expected $actual).Count -ne 0) { throw "$name 基线发生变化" }
    Write-Output "$name=PASS"
}

Assert-Lines 'V16_SOURCE_FROZEN' ([IO.File]::ReadAllLines((Join-Path $baselineRoot 'v16-source.sha256'))) @(Get-SourceManifest $v16Root)
$apkLine = '{0} *{1}' -f (Get-FileHash -LiteralPath $v16Apk -Algorithm SHA256).Hash.ToUpperInvariant(), (Split-Path -Leaf $v16Apk)
Assert-Lines 'V16_APK_FROZEN' ([IO.File]::ReadAllLines((Join-Path $baselineRoot 'v16-apk.sha256'))) @($apkLine)
