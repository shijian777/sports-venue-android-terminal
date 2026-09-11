$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$buildScript = Join-Path $PSScriptRoot 'build-debug.ps1'
$outputsRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\..\outputs'))
$runId = [Guid]::NewGuid().ToString('N')
$outsideRoot = Join-Path $projectRoot ('.delivery-root-outside-' + $runId)
$fixtureRoot = Join-Path $outputsRoot ('.delivery-root-contract-' + $runId)

$command = Get-Command $buildScript
if (-not $command.Parameters.ContainsKey('DeliveryRoot')) {
    throw 'DELIVERY_ROOT_PARAMETER_MISSING'
}

function Invoke-RejectedBuild([string]$variant, [string]$deliveryRoot) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'powershell.exe'
    $startInfo.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' +
        $buildScript + '" -Variant "' + $variant + '" -DeliveryRoot "' +
        $deliveryRoot + '"'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw 'BUILD_PROCESS_DID_NOT_START'
        }
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $exitCode = $process.ExitCode
    } finally {
        $process.Dispose()
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $standardOutput + $standardError
    }
}

try {
    $outside = Invoke-RejectedBuild 'production' $outsideRoot
    $outsideHasExpectedMessage = ([string]$outside.Output).Contains(
        'DELIVERY_ROOT_OUTSIDE_OUTPUTS')
    if ($outside.ExitCode -eq 0 -or
            -not $outsideHasExpectedMessage) {
        throw "OUTSIDE_DELIVERY_ROOT_NOT_REJECTED EXIT=$($outside.ExitCode) MESSAGE=$outsideHasExpectedMessage`n$($outside.Output)"
    }
    if (Test-Path -LiteralPath $outsideRoot) {
        throw 'OUTSIDE_DELIVERY_ROOT_WAS_CREATED'
    }

    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    $fixtures = @(
        [pscustomobject]@{
            Variant = 'production'
            Name = '智能更衣柜-v21-production测试版.apk'
        },
        [pscustomobject]@{
            Variant = 'localDemo'
            Name = '智能更衣柜-v21-localDemo.apk'
        }
    )
    foreach ($fixture in $fixtures) {
        $target = Join-Path $fixtureRoot $fixture.Name
        $expectedBytes = [byte[]](0x17, 0xA5, 0x5A)
        [System.IO.File]::WriteAllBytes($target, $expectedBytes)

        $result = Invoke-RejectedBuild $fixture.Variant $fixtureRoot
        $existingHasExpectedMessage = ([string]$result.Output).Contains(
            'DELIVERY_TARGET_EXISTS')
        if ($result.ExitCode -eq 0 -or
                -not $existingHasExpectedMessage) {
            throw "EXISTING_$($fixture.Variant)_NOT_REJECTED EXIT=$($result.ExitCode) MESSAGE=$existingHasExpectedMessage`n$($result.Output)"
        }
        $actualBytes = [System.IO.File]::ReadAllBytes($target)
        if ($actualBytes.Length -ne $expectedBytes.Length -or
                [Convert]::ToBase64String($actualBytes) -cne
                    [Convert]::ToBase64String($expectedBytes)) {
            throw "EXISTING_$($fixture.Variant)_WAS_CHANGED"
        }
        Remove-Item -LiteralPath $target -Force
    }

    Write-Output 'DELIVERY_ROOT_OUTSIDE_OUTPUTS_REJECTED=PASS'
    Write-Output 'DELIVERY_ROOT_EXISTING_TARGETS_UNCHANGED=PASS VARIANTS=production,localDemo'
    Write-Output 'VERIFICATION=PASS'
} finally {
    $resolvedFixtureRoot = [System.IO.Path]::GetFullPath($fixtureRoot)
    $requiredPrefix = $outputsRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if ((Test-Path -LiteralPath $resolvedFixtureRoot) -and
            $resolvedFixtureRoot.StartsWith(
                $requiredPrefix,
                [System.StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedFixtureRoot).StartsWith(
                '.delivery-root-contract-',
                [System.StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedFixtureRoot -Recurse -Force
    }
}
