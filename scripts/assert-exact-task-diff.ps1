param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Worktree', 'Staged')]
    [string]$Mode,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedPathsCsv
)

$ErrorActionPreference = 'Stop'

function ConvertTo-OrdinalPathSet([string[]]$Paths, [string]$SetName) {
    $pathSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    $normalizedPaths = [System.Collections.Generic.List[string]]::new()

    foreach ($pathValue in @($Paths)) {
        if ($null -eq $pathValue -or $pathValue.Length -eq 0) {
            throw "$SetName contains an empty path"
        }
        $normalizedPath = $pathValue.Replace('\', '/')
        while ($normalizedPath.StartsWith('./', [System.StringComparison]::Ordinal)) {
            $normalizedPath = $normalizedPath.Substring(2)
        }
        if ([string]::IsNullOrWhiteSpace($normalizedPath) -or
                [System.IO.Path]::IsPathRooted($normalizedPath) -or
                @($normalizedPath.Split('/') | Where-Object { $_ -ceq '..' }).Count -ne 0) {
            throw "$SetName contains an invalid repo-relative path: $pathValue"
        }
        if (-not $pathSet.Add($normalizedPath)) {
            throw "$SetName contains a duplicate path: $normalizedPath"
        }
        $normalizedPaths.Add($normalizedPath)
    }

    $result = $normalizedPaths.ToArray()
    [System.Array]::Sort($result, [System.StringComparer]::Ordinal)
    return $result
}

function Assert-ExactOrdinalSet([string[]]$Expected, [string[]]$Actual, [string]$SetName) {
    if ($Expected.Count -ne $Actual.Count) {
        throw "$SetName path count differs. EXPECTED=$($Expected.Count) ACTUAL=$($Actual.Count) ACTUAL_PATHS=$($Actual -join ',')"
    }
    for ($pathIndex = 0; $pathIndex -lt $Expected.Count; $pathIndex++) {
        if ($Expected[$pathIndex] -cne $Actual[$pathIndex]) {
            throw "$SetName paths differ. EXPECTED=$($Expected -join ',') ACTUAL=$($Actual -join ',')"
        }
    }
}

if ([string]::IsNullOrWhiteSpace($ExpectedPathsCsv)) {
    throw 'ExpectedPathsCsv must contain at least one repo-relative path'
}
$expectedInputPaths = @($ExpectedPathsCsv.Split(',') | ForEach-Object { $_.Trim() })
if (@($expectedInputPaths | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {
    throw 'ExpectedPathsCsv contains an empty path'
}
$expectedPaths = @(ConvertTo-OrdinalPathSet $expectedInputPaths 'expected set')

$repoRootOutput = @(& git rev-parse --show-toplevel)
$repoRootExit = $LASTEXITCODE
if ($repoRootExit -ne 0) {
    throw "git rev-parse failed: $repoRootExit"
}
if ($repoRootOutput.Count -ne 1 -or [string]::IsNullOrWhiteSpace($repoRootOutput[0])) {
    throw 'git rev-parse returned an invalid repository root'
}
$repoRoot = [System.IO.Path]::GetFullPath($repoRootOutput[0])

Push-Location -LiteralPath $repoRoot
try {
    if ($Mode -ceq 'Worktree') {
        $trackedPaths = @(& git -c core.quotepath=false diff --name-only HEAD --)
        $trackedExit = $LASTEXITCODE
        if ($trackedExit -ne 0) {
            throw "git tracked diff failed: $trackedExit"
        }
        $untrackedPaths = @(& git -c core.quotepath=false ls-files --others --exclude-standard)
        $untrackedExit = $LASTEXITCODE
        if ($untrackedExit -ne 0) {
            throw "git untracked query failed: $untrackedExit"
        }
        $actualPaths = @(ConvertTo-OrdinalPathSet @($trackedPaths + $untrackedPaths) 'worktree set')
    } else {
        $cachedPaths = @(& git -c core.quotepath=false diff --cached --name-only --)
        $cachedExit = $LASTEXITCODE
        if ($cachedExit -ne 0) {
            throw "git cached diff failed: $cachedExit"
        }
        $unstagedPaths = @(& git -c core.quotepath=false diff --name-only --)
        $unstagedExit = $LASTEXITCODE
        if ($unstagedExit -ne 0) {
            throw "git unstaged diff failed: $unstagedExit"
        }
        $untrackedPaths = @(& git -c core.quotepath=false ls-files --others --exclude-standard)
        $untrackedExit = $LASTEXITCODE
        if ($untrackedExit -ne 0) {
            throw "git untracked query failed: $untrackedExit"
        }

        $residualPaths = @(ConvertTo-OrdinalPathSet @($unstagedPaths + $untrackedPaths) 'residual set')
        if ($residualPaths.Count -ne 0) {
            throw "Staged mode found unstaged or untracked paths: $($residualPaths -join ',')"
        }
        $actualPaths = @(ConvertTo-OrdinalPathSet $cachedPaths 'cached set')
    }

    Assert-ExactOrdinalSet $expectedPaths $actualPaths "$Mode set"
    Write-Output "EXACT_TASK_DIFF=PASS MODE=$Mode COUNT=$($actualPaths.Count)"
} finally {
    Pop-Location
}
