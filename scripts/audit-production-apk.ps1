param(
    [Parameter(Mandatory = $true)]
    [string]$Apk
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
$buildTools = Join-Path $sdkRoot 'build-tools\35.0.0'
$apkanalyzer = Join-Path $sdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$aapt = Join-Path $buildTools 'aapt.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$dexdump = Join-Path $buildTools 'dexdump.exe'
$auditRoot = Join-Path $projectRoot 'manual-build\v17\audit-production'
$scratchName = 'run-' + [Guid]::NewGuid().ToString('N')
$evidenceRunName = 'evidence-' + [Guid]::NewGuid().ToString('N')
$scratch = Join-Path $auditRoot $scratchName
$scratchCreated = $false
$evidenceStaged = $false
$evidencePublished = $false
$failureLine = $null
$passLines = @()
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$strictUtf8NoBom = [System.Text.UTF8Encoding]::new($false, $true)

function Stop-Audit([string]$code) {
    throw [System.InvalidOperationException]::new($code)
}

function Stop-Source([string]$rule) {
    throw [System.InvalidOperationException]::new(
        "PRODUCTION_SOURCE_FORBIDDEN=$rule")
}

function Get-FullPath([string]$path) {
    $fullPath = [System.IO.Path]::GetFullPath($path)
    $pathRoot = [System.IO.Path]::GetPathRoot($fullPath)
    if ($fullPath.Length -eq $pathRoot.Length) {
        return $pathRoot
    }
    return $fullPath.TrimEnd('\')
}

function Test-ReparsePoint([string]$path) {
    $item = Get-Item -LiteralPath $path -Force
    return (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
}

function Assert-PlainDirectory([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Container) -or
            (Test-ReparsePoint $path)) {
        Stop-Audit 'AUDIT_ROOT_REPARSE_POINT'
    }
    $resolved = Get-FullPath (Resolve-Path -LiteralPath $path).Path
    if (-not [string]::Equals(
            $resolved,
            (Get-FullPath $path),
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Audit 'AUDIT_ROOT_REPARSE_POINT'
    }
}

function Assert-NoReparseAncestorChain([string]$path) {
    $fullPath = [System.IO.Path]::GetFullPath($path)
    $volumeRoot = [System.IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($volumeRoot)) {
        Stop-Audit 'AUDIT_ROOT_INVALID'
    }
    Assert-PlainDirectory $volumeRoot
    $current = $volumeRoot
    $relative = $fullPath.Substring($volumeRoot.Length).TrimEnd('\')
    if ($relative.Length -eq 0) {
        return
    }
    foreach ($segment in $relative.Split('\')) {
        if ([string]::IsNullOrWhiteSpace($segment)) {
            Stop-Audit 'AUDIT_ROOT_INVALID'
        }
        $current = Join-Path $current $segment
        Assert-PlainDirectory $current
    }
}

function Ensure-PlainChildDirectory([string]$path, [string]$parent) {
    Assert-PlainDirectory $parent
    $fullPath = Get-FullPath $path
    if (-not [string]::Equals(
            (Split-Path -Parent $fullPath),
            (Get-FullPath $parent),
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Audit 'AUDIT_ROOT_INVALID'
    }
    if (Test-Path -LiteralPath $fullPath) {
        Assert-PlainDirectory $fullPath
        return
    }
    New-Item -ItemType Directory -Path $fullPath | Out-Null
    Assert-PlainDirectory $fullPath
}

function Write-Evidence([string]$name, [string[]]$lines) {
    if (-not $evidenceStaged -or
            [string]::IsNullOrWhiteSpace($resolvedEvidenceStaging) -or
            $name -notmatch '^[a-z0-9-]+\.txt$') {
        Stop-Audit 'AUDIT_EVIDENCE_STAGE_INVALID'
    }
    $evidencePath = Join-Path $resolvedEvidenceStaging $name
    [System.IO.File]::WriteAllLines($evidencePath, $lines, $utf8NoBom)
}

function Publish-Evidence {
    if (-not $evidenceStaged -or $evidencePublished -or
            -not (Test-Path -LiteralPath $resolvedEvidenceStaging -PathType Container) -or
            (Test-Path -LiteralPath $resolvedEvidenceRun)) {
        Stop-Audit 'AUDIT_EVIDENCE_PUBLISH_INVALID'
    }
    Assert-PlainDirectory $resolvedEvidenceStaging
    Assert-PlainDirectory $resolvedAuditRoot
    if (-not [string]::Equals(
            (Split-Path -Parent $resolvedEvidenceRun),
            $resolvedAuditRoot,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Audit 'AUDIT_EVIDENCE_PUBLISH_INVALID'
    }
    [System.IO.Directory]::Move($resolvedEvidenceStaging, $resolvedEvidenceRun)
    $script:evidencePublished = $true
}

function Get-FileSha256([string]$path) {
    $stream = [System.IO.File]::Open(
        $path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString(
            $sha256.ComputeHash($stream)).Replace('-', '')
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

function Get-TextSha256([string]$text) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString(
            $sha256.ComputeHash($utf8NoBom.GetBytes($text))).Replace('-', '')
    } finally {
        $sha256.Dispose()
    }
}

function Get-NormalizedUtf8SourceText([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and
            $bytes[0] -eq 0xEF -and
            $bytes[1] -eq 0xBB -and
            $bytes[2] -eq 0xBF) {
        Stop-Source 'SOURCE_UTF8_INVALID'
    }
    try {
        $text = $strictUtf8NoBom.GetString($bytes)
    } catch [System.Text.DecoderFallbackException] {
        Stop-Source 'SOURCE_UTF8_INVALID'
    }
    return $text.Replace("`r`n", "`n").Replace("`r", "`n")
}

function Get-NormalizedUtf8SourceSha256([string]$path) {
    return Get-TextSha256 (Get-NormalizedUtf8SourceText $path)
}

function Assert-NoProductionCredentialSourceMarkers(
        [hashtable]$sourceByRelative) {
    foreach ($relative in $sourceByRelative.Keys) {
        $source = [string]$sourceByRelative[$relative]
        $surface = ([string]$relative) + "`n" + $source
        if ($surface.Contains('CENTRAL_CONTROL_SYS_CODE') -or
                $surface.Contains('GeneratedCentralControlSysCode') -or
                $surface.Contains('generated-secret') -or
                [regex]::IsMatch(
                    $source,
                    '(?s)\b(?:java\s*\.\s*lang\s*\.\s*)?System\s*\.\s*getenv\s*\(')) {
            Stop-Source 'SOURCE_BOOTSTRAP_CREDENTIAL_PATH'
        }
    }
}

function Assert-BootstrapSourceContract(
        [hashtable]$sourceByRelative) {
    $endpointPath = 'app/src/main/java/com/codex/lockertest/server/ApiEndpoint.java'
    if (-not $sourceByRelative.ContainsKey($endpointPath)) {
        return 'BOOTSTRAP_SOURCE_CONTRACT=NOT_PRESENT'
    }

    $configPath = 'app/src/production/java/com/codex/lockertest/server/ProductionServerConfig.java'
    $transportPath = 'app/src/production/java/com/codex/lockertest/server/HttpsUrlConnectionTransport.java'
    $headersPath = 'app/src/main/java/com/codex/lockertest/server/BootstrapHeaders.java'
    $requestPath = 'app/src/main/java/com/codex/lockertest/server/TransportRequest.java'
    $servicePath = 'app/src/production/java/com/codex/lockertest/server/ProductionBootstrapService.java'
    $providerPath = 'app/src/production/java/com/codex/lockertest/server/ProductionSecretProvider.java'
    $gatePath = 'app/src/production/java/com/codex/lockertest/bootstrap/ProductionBootstrapContractGate.java'
    $assemblyPath = 'app/src/production/java/com/codex/lockertest/bootstrap/BootstrapAssembly.java'
    $factoryPath = 'app/src/production/java/com/codex/lockertest/bootstrap/ProductionBootstrapRuntimeFactory.java'
    $schedulerPath = 'app/src/production/java/com/codex/lockertest/bootstrap/ProductionBootstrapScheduler.java'
    $mapperPath = 'app/src/main/java/com/codex/lockertest/ui/BootstrapReadinessMapper.java'
    $readinessPath = 'app/src/main/java/com/codex/lockertest/ui/TerminalReadiness.java'
    $requiredPaths = @(
        $endpointPath, $configPath, $transportPath, $headersPath, $requestPath,
        $servicePath, $providerPath, $gatePath, $assemblyPath, $factoryPath,
        $schedulerPath,
        $mapperPath, $readinessPath)
    foreach ($requiredPath in $requiredPaths) {
        if (-not $sourceByRelative.ContainsKey($requiredPath)) {
            Stop-Source 'SOURCE_BOOTSTRAP_CONTRACT_MISSING'
        }
    }

    $endpointSource = [string]$sourceByRelative[$endpointPath]
    $endpointMatches = [regex]::Matches(
        $endpointSource,
        '(?m)^\s*([A-Z][A-Z0-9_]*)\("([^"\r\n]+)"\)\s*[,;]')
    $actualEndpoints = @($endpointMatches | ForEach-Object {
        "$($_.Groups[1].Value)=$($_.Groups[2].Value)"
    })
    $expectedEndpoints = @(
        'CHECK_DEVICE=/v2/central_control_screen/checkDevice',
        'BASE_SETTING=/v2/central_control_screen/baseSetting',
        'BASIC_DATA=/v2/central_control_screen/basicData')
    if ($actualEndpoints.Count -ne 3 -or
            @(Compare-Object $expectedEndpoints $actualEndpoints -CaseSensitive).Count -ne 0) {
        Stop-Source 'SOURCE_BOOTSTRAP_ENDPOINT_CONTRACT'
    }
    foreach ($relative in $sourceByRelative.Keys) {
        if ($relative -notmatch '^app/src/(?:main|production)/java/com/codex/lockertest/(?:bootstrap|server)/') {
            continue
        }
        $source = [string]$sourceByRelative[$relative]
        if ([regex]::IsMatch(
                $source,
                '(?i)"[^"\r\n]*/[^"\r\n]*(?:unlock|open[_-]?door|return|customer|credential|authorize|face|palm)[^"\r\n]*"')) {
            Stop-Source 'SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION'
        }
        if ([regex]::IsMatch(
                $source,
                '(?m)^\s*import\s+com\.codex\.lockertest\.(?:serial|unlock|returnflow|face)(?:\.|;)') -or
                [regex]::IsMatch(
                    $source,
                    '(?m)^\s*import\s+com\.codex\.lockertest\.ui\.(?:CustomerActionBoundary|KioskFlowModel|Return|Unlock)')) {
            Stop-Source 'SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION'
        }
    }

    $configSource = [string]$sourceByRelative[$configPath]
    $transportSource = [string]$sourceByRelative[$transportPath]
    if (-not $configSource.Contains(
                'public static final String BASE_URL = "https://devyoga.gmtfit.com";') -or
            [regex]::Matches($configSource, '(?i)https?://').Count -ne 1 -or
            -not $transportSource.Contains('HttpsURLConnection') -or
            -not $transportSource.Contains('setInstanceFollowRedirects(false)') -or
            $transportSource.Contains('setInstanceFollowRedirects(true)') -or
            [regex]::IsMatch(
                $transportSource,
                '(?i)HostnameVerifier|SSLSocketFactory|ALLOW_ALL|trustAll|http://') -or
            -not $transportSource.Contains('"https".equals(url.getProtocol())') -or
            -not $transportSource.Contains('"devyoga.gmtfit.com".equals(url.getHost())') -or
            -not $transportSource.Contains('endpointUrl(request.endpointPath())') -or
            -not $transportSource.Contains('endpointPath.equals(url.getPath())')) {
        Stop-Source 'SOURCE_BOOTSTRAP_TRANSPORT_CONTRACT'
    }

    $headersSource = [string]$sourceByRelative[$headersPath]
    $requestSource = [string]$sourceByRelative[$requestPath]
    $headerConstants = [regex]::Matches(
        $headersSource,
        '(?m)^\s*public\s+static\s+final\s+String\s+[A-Z0-9_]+\s*=\s*"([^"\r\n]+)";')
    $actualHeaders = @($headerConstants | ForEach-Object { $_.Groups[1].Value })
    $expectedHeaders = @('gmt-merchant-auth', 'device-no')
    if ($actualHeaders.Count -ne 2 -or
            @(Compare-Object $expectedHeaders $actualHeaders -CaseSensitive).Count -ne 0 -or
            -not $headersSource.Contains('source.size() != 2') -or
            [regex]::IsMatch(
                $headersSource + "`n" + $transportSource,
                '(?i)X-HTTP-Method-Override|X-Method-Override|Proxy-Authorization|\r\n|\x00') -or
            -not $transportSource.Contains('setRequestProperty("Content-Type", REQUEST_CONTENT_TYPE)') -or
            -not $transportSource.Contains('setRequestProperty("Accept", ACCEPT)') -or
            [regex]::Matches($transportSource, 'setRequestProperty\s*\(').Count -ne 3 -or
            $requestSource.Contains('String url') -or
            $requestSource.Contains('String method') -or
            $requestSource.Contains('Map<String, String> headers')) {
        Stop-Source 'SOURCE_BOOTSTRAP_HEADER_CONTRACT'
    }

    $providerSource = [string]$sourceByRelative[$providerPath]
    $providerMethods = [regex]::Matches(
        $providerSource,
        '(?m)^\s*public\s+static\s+char\[\]\s+([A-Za-z][A-Za-z0-9_]*)\s*\(\s*\)')
    $gateSource = [string]$sourceByRelative[$gatePath]
    $gateMethods = [regex]::Matches(
        $gateSource,
        '(?m)^\s*public\s+static\s+boolean\s+([A-Za-z][A-Za-z0-9_]*)\s*\(\s*\)')
    $providerHash = Get-TextSha256 $providerSource
    $gateHash = Get-TextSha256 $gateSource
    if ($providerMethods.Count -ne 1 -or
            $providerMethods[0].Groups[1].Value -cne 'copyOrEmpty' -or
            $providerHash -cne 'A676F2A158F62B1AD145A40730018B9F1B596881C97F7D8AAF9000B6D411260B' -or
            $gateMethods.Count -ne 1 -or
            $gateMethods[0].Groups[1].Value -cne 'isLiveApproved' -or
            $gateHash -cne '86209B418EE2B53ECCDF6734C64C41862181D88AE637364CAF68E9566C7E4733' -or
            -not [regex]::IsMatch(
                $gateSource,
                '(?s)public\s+static\s+boolean\s+isLiveApproved\s*\(\s*\)\s*\{\s*return\s+true\s*;\s*\}')) {
        Stop-Source 'SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT'
    }
    $credentialSurface = $providerSource + "`n" + $gateSource + "`n" +
        [string]$sourceByRelative[$factoryPath]
    if ([regex]::IsMatch(
            $credentialSurface,
            '(?i)System\s*\.\s*(?:getenv|getProperty)|SharedPreferences|BuildConfig|Intent|Bundle|KeyStore|SecretKey|java\.io\.(?:File|InputStream)|android\.provider\.Settings|Base64\s*\.\s*decode|toCharArray\s*\(')) {
        Stop-Source 'SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT'
    }

    $assemblySource = [string]$sourceByRelative[$assemblyPath]
    $factorySource = [string]$sourceByRelative[$factoryPath]
    if (-not $assemblySource.Contains('ProductionBootstrapRuntimeFactory.create()') -or
            $assemblySource.Contains('ProductionBootstrapService') -or
            $assemblySource.Contains('HttpsUrlConnectionTransport') -or
            -not $factorySource.Contains('ProductionBootstrapService.createLive(sysCode)') -or
            -not $factorySource.Contains('new ProductionBootstrapScheduler()') -or
            -not $factorySource.Contains('new Rk3288DeviceSerialProvider()') -or
            -not $factorySource.Contains('new ProductionBootstrapRuntime(') -or
            $factorySource.Contains('new HttpsUrlConnectionTransport') -or
            $factorySource.Contains('new SystemProtocolClock')) {
        Stop-Source 'SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT'
    }

    $serviceSource = [string]$sourceByRelative[$servicePath]
    $constructors = [regex]::Matches(
        $serviceSource,
        '(?m)^\s{4}ProductionBootstrapService\s*\(([^)]*)\)')
    if ($constructors.Count -ne 1 -or
            [regex]::Matches($constructors[0].Groups[1].Value, 'char\s*\[\s*\]').Count -ne 1 -or
            [regex]::IsMatch(
                $serviceSource,
                '(?m)^\s*(?:public|protected|private)\s+ProductionBootstrapService\s*\(')) {
        Stop-Source 'SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT'
    }
    foreach ($relative in $sourceByRelative.Keys) {
        $serviceConstructorCalls = [regex]::Matches(
                [string]$sourceByRelative[$relative],
                '\bnew\s+ProductionBootstrapService\s*\(').Count
        if (($relative -ceq $servicePath -and $serviceConstructorCalls -ne 1) -or
                ($relative -cne $servicePath -and $serviceConstructorCalls -ne 0)) {
            Stop-Source 'SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT'
        }
    }
    if (-not [regex]::IsMatch(
            $serviceSource,
            '(?s)public\s+static\s+ProductionBootstrapService\s+createLive\s*\(\s*char\s*\[\s*\]\s+callerSysCode\s*\).*?new\s+HttpsUrlConnectionTransport\s*\(\s*\).*?new\s+SystemProtocolClock\s*\(\s*\)')) {
        Stop-Source 'SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT'
    }

    $testCallers = [System.Collections.Generic.List[string]]::new()
    $productionTestRoot = Join-Path $projectRoot 'app\src\productionTest\java'
    if (-not (Test-Path -LiteralPath $productionTestRoot -PathType Container)) {
        Stop-Source 'SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT'
    }
    foreach ($testFile in @(Get-ChildItem -LiteralPath $productionTestRoot -Recurse -File |
            Where-Object { $_.Extension -in @('.java', '.kt') } |
            Sort-Object FullName -Unique -CaseSensitive)) {
        $testSource = Get-NormalizedUtf8SourceText $testFile.FullName
        if ([regex]::IsMatch(
                $testSource,
                '\bnew\s+ProductionBootstrapService\s*\(')) {
            $testCallers.Add(
                $testFile.FullName.Substring($projectRoot.Length + 1).Replace('\', '/'))
        }
    }
    $expectedTestCallers = @(
        'app/src/productionTest/java/com/codex/lockertest/server/ProductionBootstrapServiceTest.java',
        'app/src/productionTest/java/com/codex/lockertest/server/BootstrapCancellationIntegrationTest.java')
    if ($testCallers.Count -ne 2 -or
            @(Compare-Object $expectedTestCallers $testCallers.ToArray() -CaseSensitive).Count -ne 0) {
        Stop-Source 'SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT'
    }

    $mapperSource = [string]$sourceByRelative[$mapperPath]
    $readinessSource = [string]$sourceByRelative[$readinessPath]
    if (-not [regex]::IsMatch(
                $mapperSource,
                '(?s)case\s+READY_READ_ONLY\s*:\s*return\s+TerminalReadiness\.readyReadOnly\s*\(\s*\)\s*;') -or
            $mapperSource.Contains('TerminalReadiness.localDemoReady()') -or
            -not [regex]::IsMatch(
                $readinessSource,
                '(?s)boolean\s+customerActionsEnabled\s*\(\s*\)\s*\{\s*return\s+state\s*==\s*State\.READY_LOCAL_DEMO\s*;\s*\}')) {
        Stop-Source 'SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION'
    }

    return 'BOOTSTRAP_SOURCE_CONTRACT=PINNED_LIVE_READ_ONLY'
}

function Get-BusinessEndpointContractEntries {
    return @(
        'MOBILE_SMS_CODE=/v2/central_control_screen/mobileSmsCode',
        'RIG_LOGIN=/v2/central_control_screen/rigLogin',
        'CONTROL_PANEL_PREVIEW=/v2/central_control_screen/controlPanelPreview',
        'STOREY_CABINET=/v2/central_control_screen/storeyCabinet',
        'INSTALLATION_VERIFY=/v2/central_control_screen/installationVerify',
        'MBR_LOGIN=/v2/central_control_screen/mbrLogin',
        'QUICK_CLEAR_CABINET=/v2/central_control_screen/quickClearCabinet',
        'USER_INFO=/v2/central_control_screen/userInfo',
        'MEMBER_DYNAMIC_CODE=/v2/central_control_screen/memberDynamicCode',
        'BIND_USER_HAND=/v2/central_control_screen/bindUserHand',
        'USER_BOARD=/v2/central_control_screen/userBoard',
        'OPEN_BOARD=/v2/central_control_screen/openBoard',
        'USE_CABINET_LIST=/v2/central_control_screen/useCabinetList')
}

function Get-BusinessEndpointContractPaths {
    return @(Get-BusinessEndpointContractEntries | ForEach-Object {
        $_.Substring($_.IndexOf('=') + 1)
    })
}

function Get-BootstrapEndpointContractPaths {
    return @(
        '/v2/central_control_screen/checkDevice',
        '/v2/central_control_screen/baseSetting',
        '/v2/central_control_screen/basicData')
}

function Assert-BusinessSourceContract(
        [hashtable]$sourceByRelative) {
    $endpointPath = 'app/src/main/java/com/codex/lockertest/business/BusinessEndpoint.java'
    if (-not $sourceByRelative.ContainsKey($endpointPath)) {
        $authoritySurface = @($sourceByRelative.Keys | Where-Object {
            $relative = [string]$_
            $relative -match
                '^app/src/(?:main|production)/java/com/codex/lockertest/(?:business/|ui/business/)' -or
            $relative -ceq
                'app/src/production/java/com/codex/lockertest/server/ProductionBusinessService.java' -or
            $relative -ceq
                'app/src/main/java/com/codex/lockertest/integration/OnlineUnlockDispatchPermit.java' -or
            $relative -ceq
                'app/src/main/java/com/codex/lockertest/unlock/AuthorizedUnlockRequest.java' -or
            [regex]::IsMatch(
                [string]$sourceByRelative[$relative],
                '(?m)^\s*package\s+com\.codex\.lockertest\.(?:business(?:\.|;)|ui\.business(?:\.|;))')
        })
        if ($authoritySurface.Count -ne 0) {
            Stop-Source 'SOURCE_BUSINESS_CONTRACT_MISSING'
        }
        return 'BUSINESS_SOURCE_CONTRACT=NOT_PRESENT'
    }

    $requestPath = 'app/src/main/java/com/codex/lockertest/server/TransportRequest.java'
    $transportPath = 'app/src/production/java/com/codex/lockertest/server/HttpsUrlConnectionTransport.java'
    $servicePath = 'app/src/production/java/com/codex/lockertest/server/ProductionBusinessService.java'
    foreach ($requiredPath in @($requestPath, $transportPath, $servicePath)) {
        if (-not $sourceByRelative.ContainsKey($requiredPath)) {
            Stop-Source 'SOURCE_BUSINESS_CONTRACT_MISSING'
        }
    }

    $endpointSource = [string]$sourceByRelative[$endpointPath]
    $endpointMatches = [regex]::Matches(
        $endpointSource,
        '(?m)^\s*([A-Z][A-Z0-9_]*)\("([^"\r\n]+)"\)\s*[,;]')
    $actualEndpoints = @($endpointMatches | ForEach-Object {
        "$($_.Groups[1].Value)=$($_.Groups[2].Value)"
    })
    $expectedEndpoints = @(Get-BusinessEndpointContractEntries)
    $actualEndpointPaths = @([regex]::Matches(
        $endpointSource,
        '"(/v2/central_control_screen/[^"\r\n]+)"') | ForEach-Object {
            $_.Groups[1].Value
        })
    if ($actualEndpoints.Count -ne $expectedEndpoints.Count -or
            @(Compare-Object $expectedEndpoints $actualEndpoints -CaseSensitive).Count -ne 0 -or
            $actualEndpointPaths.Count -ne $expectedEndpoints.Count -or
            @(Compare-Object (Get-BusinessEndpointContractPaths) `
                $actualEndpointPaths -CaseSensitive).Count -ne 0) {
        Stop-Source 'SOURCE_BUSINESS_ENDPOINT_CONTRACT'
    }

    $apiEndpointPath = 'app/src/main/java/com/codex/lockertest/server/ApiEndpoint.java'
    $faceUploadSourcePath = 'app/src/production/java/com/codex/lockertest/server/ProductionFaceImageUploadAdapter.java'
    $faceUploadEndpointPath = '/v2/central_control_screen/uploadImagePublic'
    $faceUploadEndpointCount = 0
    $businessEndpointPaths = @(Get-BusinessEndpointContractPaths)
    $bootstrapEndpointPaths = @(Get-BootstrapEndpointContractPaths)
    foreach ($relative in $sourceByRelative.Keys) {
        foreach ($literalMatch in [regex]::Matches(
                [string]$sourceByRelative[$relative],
                '"(/v2/central_control_screen/[^"\r\n]+)"')) {
            $candidate = $literalMatch.Groups[1].Value
            $ownedByBusinessEndpoint = $relative -ceq $endpointPath -and
                $businessEndpointPaths -ccontains $candidate
            $ownedByApiEndpoint = $relative -ceq $apiEndpointPath -and
                $bootstrapEndpointPaths -ccontains $candidate
            $ownedByFaceUpload = $relative -ceq $faceUploadSourcePath -and
                $candidate -ceq $faceUploadEndpointPath
            if ($ownedByFaceUpload) {
                $faceUploadEndpointCount++
            }
            if (-not $ownedByBusinessEndpoint -and -not $ownedByApiEndpoint -and
                    -not $ownedByFaceUpload) {
                Stop-Source 'SOURCE_BUSINESS_ENDPOINT_CONTRACT'
            }
        }
    }
    if ($faceUploadEndpointCount -ne 1) {
        Stop-Source 'SOURCE_BUSINESS_ENDPOINT_CONTRACT'
    }

    $requestSource = [string]$sourceByRelative[$requestPath]
    $transportSource = [string]$sourceByRelative[$transportPath]
    $requestConstructors = [regex]::Matches(
        $requestSource,
        '(?m)^\s*(?:(?:public|protected|private)\s+)?TransportRequest\s*\(([^)]*)\)')
    $apiConstructors = @($requestConstructors | Where-Object {
        $_.Groups[1].Value -match '\bApiEndpoint\s+[A-Za-z][A-Za-z0-9_]*' -and
            $_.Groups[1].Value -notmatch '\bBusinessEndpoint\b'
    })
    $businessConstructors = @($requestConstructors | Where-Object {
        $_.Groups[1].Value -match '\bBusinessEndpoint\s+[A-Za-z][A-Za-z0-9_]*' -and
            $_.Groups[1].Value -notmatch '\bApiEndpoint\b'
    })
    if ($requestConstructors.Count -ne 2 -or
            $apiConstructors.Count -ne 1 -or
            $businessConstructors.Count -ne 1 -or
            -not $requestSource.Contains('private final BusinessEndpoint businessEndpoint;') -or
            -not $requestSource.Contains('private final String endpointPath;') -or
            -not $requestSource.Contains('this.endpointPath = endpoint.path();') -or
            [regex]::Matches(
                $requestSource,
                'this\.endpointPath\s*=\s*endpoint\.path\s*\(\s*\)\s*;').Count -ne 2 -or
            [regex]::Matches(
                $requestSource,
                'this\.endpointPath\s*=').Count -ne 2 -or
            -not [regex]::IsMatch(
                $requestSource,
                '(?s)TransportRequest\s*\(\s*BusinessEndpoint\b[^)]*\).*?if\s*\(\s*headers\.isEmpty\s*\(\s*\)\s*\)') -or
            [regex]::IsMatch(
                $requestSource,
                '(?m)public\s+TransportRequest\s*\([^)]*\bString\s+(?:url|path|method)\b') -or
            [regex]::IsMatch(
                $requestSource,
                '(?im)\b(?:String\s+(?!(?:endpointPath|body)\b)[A-Za-z0-9_]*(?:url|uri|path|method)[A-Za-z0-9_]*|Map\s*<\s*String\s*,\s*String\s*>\s+headers)\b') -or
            [regex]::IsMatch(
                $requestSource,
                '(?is)\([^)]*\bString\s+[A-Za-z][A-Za-z0-9_]*(?:url|uri|path|method)[A-Za-z0-9_]*\b') -or
            -not $transportSource.Contains('endpointUrl(request.endpointPath())') -or
            -not [regex]::IsMatch(
                $transportSource,
                '(?m)^\s*private\s+static\s+URL\s+endpointUrl\s*\(\s*String\s+endpointPath\s*\)') -or
            -not $transportSource.Contains(
                'endpointPath.startsWith("/v2/central_control_screen/")') -or
            -not $transportSource.Contains('endpointPath.equals(url.getPath())') -or
            [regex]::Matches(
                $transportSource,
                '\.setRequestMethod\s*\(').Count -ne 1 -or
            [regex]::Matches(
                $transportSource,
                '\.setRequestMethod\s*\(\s*"POST"\s*\)').Count -ne 1 -or
            [regex]::Matches(
                $transportSource,
                '\.setInstanceFollowRedirects\s*\(').Count -ne 1 -or
            [regex]::Matches(
                $transportSource,
                '\.setInstanceFollowRedirects\s*\(\s*false\s*\)').Count -ne 1 -or
            [regex]::IsMatch(
                $transportSource,
                '\.setFollowRedirects\s*\(') -or
            [regex]::IsMatch(
                $transportSource,
                '(?i)HostnameVerifier|SSLSocketFactory|ALLOW_ALL|trustAll|http://') -or
            [regex]::Matches($transportSource, 'setRequestProperty\s*\(').Count -ne 3 -or
            [regex]::IsMatch($transportSource, '\.addRequestProperty\s*\(') -or
            [regex]::IsMatch(
                $requestSource + "`n" + $transportSource,
                '(?i)X-HTTP-Method-Override|X-Method-Override|Proxy-Authorization|\r\n|\x00')) {
        Stop-Source 'SOURCE_BUSINESS_TRANSPORT_CONTRACT'
    }

    # Pin the direct authorization inputs and producers separately from the
    # reviewed selected-cabinet consumers. These files do not gain any broad
    # physical-import exemption by being authority dependencies.
    $authorityDependencyPinned = @{
        'app/src/production/java/com/codex/lockertest/server/ProductionBusinessService.java' = '32610E586659BA8F0DBAF6D407D9765A6AD7199CCAD92C6D2850D15C5BA81862'
        'app/src/production/java/com/codex/lockertest/business/OnlineCustomerAssembly.java' = '3FBF4D739A06847624025DAADBDF412906450572D93D0437631B48B7B7C20372'
        'app/src/main/java/com/codex/lockertest/business/BusinessResponseParsers.java' = '0D3668F150CF25EC0FAF807FDF2FA4A81B7152CE68352AC2EE5BC9C7A2EE7B30'
        'app/src/main/java/com/codex/lockertest/business/AssignedCabinet.java' = '44236E0FC3BD453317D32FA078813F6FA6D5C3275764DCE295558E30A4048655'
        'app/src/main/java/com/codex/lockertest/business/ControlPanelPreview.java' = '45737C1C485177FF2B57324E07218B73311D4FFCDB70B5B968BC42D41320935E'
        'app/src/main/java/com/codex/lockertest/unlock/AuthorizedUnlockRequest.java' = 'F34C3F5347E4FF78EF46D4C7D7F8ECE710E8857F2033B61F56B9D950A5EC0ECE'
        'app/src/main/java/com/codex/lockertest/model/LockerTarget.java' = '489E630EE64FB21C949D93CC24F51CBBB4DAB3F307728796539F08EBCB6BFCE0'
        'app/src/main/java/com/codex/lockertest/model/LockerZone.java' = 'DAF13E5294AFE98A50D0182A1346BAB5B2C2B34024163872E66A35E2E6881A20'
        'app/src/main/java/com/codex/lockertest/business/BusinessValues.java' = 'EC81E69F22EA753A8BD6F2C05369230AF4DAA490EE7FD28284AEEA4AAFB24372'
        'app/src/main/java/com/codex/lockertest/server/StrictJson.java' = '7ABAEACE7B0244ADC7FF967C69D62FA3C4F44DFC453470FEA573089C58212453'
        'app/src/main/java/com/codex/lockertest/server/JsonNumber.java' = 'BBE49A5EEB4DF6BD19076FC473E6253FCADB193498D0D6B269655BC0ED8E7FD5'
        'app/src/main/java/com/codex/lockertest/server/ApiResult.java' = '2E321FE84016EDA2ADF7B38D09E3E019B7BAFAAE5D0935D7B086322754B78E40'
        $faceUploadSourcePath = '2F44060165E9634B70431DED266A0EDC9951DF95F12B3B5E00374B9F0E6C396D'
        'app/src/main/java/com/codex/lockertest/ui/business/HidScanFrameDispatcher.java' = '42107FFCC8458E2FB71E39329F88FD0C97918A43DB6FA4661572EACACFAD200C'
        'app/src/main/java/com/codex/lockertest/face/verification/OnlineFaceVerificationClient.java' = '1923A28DC7329204281324FA2CA3B8CC0593461C0AFA06E68AE2F8C437816E13'
        'app/src/main/java/com/codex/lockertest/business/FaceAuthenticationPipeline.java' = '8746D37AB6A74A51A2A4D3688ECA119A6E90851682CD37034680DD9FDBFBDC2D'
        'app/src/main/java/com/codex/lockertest/business/UsedCabinetList.java' = '0A3EF64D0BE030B1D68CEA7DDF03CE0B3C2BF677DFC85E76C2D62A5A0ED90986'
        'app/src/main/java/com/codex/lockertest/face/FaceCaptureSession.java' = '482382AF14CF4028BFB5B609C5D5F2C59F9ADDF0EB43B9F4DC654F75C270095F'
        'app/src/main/java/com/codex/lockertest/face/FaceRecognitionController.java' = 'AE2186142CAD6509D48AEE6530858563B3B1B06E5DB7BF8EF735C9238234A436'
    }
    foreach ($authorityPath in $authorityDependencyPinned.Keys) {
        if (-not $sourceByRelative.ContainsKey($authorityPath) -or
                (Get-TextSha256 ([string]$sourceByRelative[$authorityPath])) -cne
                    $authorityDependencyPinned[$authorityPath]) {
            Stop-Source 'SOURCE_BUSINESS_AUTHORITY_DEPENDENCY'
        }
    }

    # Only the reviewed selected-cabinet adapter may bridge server allocation to
    # the existing hardware executor. All other business code remains isolated.
    $onlineCoordinatorPath = 'app/src/main/java/com/codex/lockertest/business/journey/OnlineCustomerCoordinator.java'
    $onlinePinned = @{
        $onlineCoordinatorPath = '2647D64135DFA6C4F2C781E6FF83DC1331D6C24E3F762E180969C43E386B6572'
        'app/src/main/java/com/codex/lockertest/business/journey/OnlineCustomerSnapshot.java' = 'F30B483302E9C240888276B983A3FE6B8EA6E342E81EBEEF1E5796E365715009'
        'app/src/main/java/com/codex/lockertest/business/journey/OnlineUnlockExecutor.java' = 'CCC41FAB7505111E54241F541A9CE96F54FC64647986381C5AA63C43724142F3'
        'app/src/main/java/com/codex/lockertest/business/journey/OnlineCabinetUnlockMapper.java' = '8BEC27A9BD114C13A6F19005027A0300A70F73055FD0DF6E49F33E342DF3FCF9'
        'app/src/main/java/com/codex/lockertest/integration/OnlineUnlockDispatchPermit.java' = 'F0B6DAB1D886145CB15A80EA44580C041BF80F92C110DA70A0CA50B7B9D3364F'
        # Reviewed 1280x800 typography, label bounds and modal presentation only.
        # Selection remains local; existing confirmation guards and callbacks are unchanged.
        'app/src/main/java/com/codex/lockertest/ui/business/OnlineCabinetView.java' = '26B39868E5C14752AC96F1F8E00E836A8BC4216B2D18CD2ECFB51737AED53BC3'
        'app/src/main/java/com/codex/lockertest/ui/business/OnlineCustomerHost.java' = 'ADF083419D12F783BC448B13BF1C2182FFE36B3A7CA4132D57CADF8E84E6D0FD'
    }
    foreach ($onlinePath in $onlinePinned.Keys) {
        if (-not $sourceByRelative.ContainsKey($onlinePath) -or
                (Get-TextSha256 ([string]$sourceByRelative[$onlinePath])) -cne $onlinePinned[$onlinePath]) {
            Stop-Source 'SOURCE_BUSINESS_ONLINE_UNLOCK_BINDING'
        }
    }
    foreach ($relative in $sourceByRelative.Keys) {
        $businessOwned = $relative -match
            '^app/src/(?:main|production)/java/com/codex/lockertest/(?:business/|ui/business/)' -or
            $relative -ceq $servicePath
        if ($businessOwned -and -not $onlinePinned.ContainsKey($relative)) {
            $surface = ([string]$relative) + "`n" + [string]$sourceByRelative[$relative]
            if ([regex]::IsMatch(
                    $surface,
                    '(?m)^\s*import\s+com\.codex\.lockertest\.(?:serial|unlock|returnflow|palm)(?:\.|;)') -or
                    [regex]::IsMatch(
                        $surface,
                        '\b(?:SerialGateway|CustomerSerialTransmitter|UnlockCoordinator|ReturnFlowController|CustomerActionBoundary|JxPalmTestDriver|JXPalm[A-Za-z0-9_]*)\b')) {
                Stop-Source 'SOURCE_BUSINESS_PHYSICAL_ISOLATION'
            }
        }

        $customerSurface = $relative -match
            '^app/src/(?:main|production)/java/com/codex/lockertest/(?:business/journey/|ui/business/)' -or
            $relative -match
                '^app/src/(?:localDemo|production)/java/com/codex/lockertest/business/OnlineCustomerAssembly\.(?:java|kt)$' -or
            $relative -ceq 'app/src/main/java/com/codex/lockertest/MainActivity.java'
        if ($customerSurface -and $relative -cne $onlineCoordinatorPath -and [regex]::IsMatch(
                [string]$sourceByRelative[$relative],
                '\.\s*(?:userBoard|openBoard)\s*\(')) {
            Stop-Source 'SOURCE_BUSINESS_MUTATION_BINDING'
        }
    }

    return 'BUSINESS_SOURCE_CONTRACT=PINNED_NETWORK_AND_SELECTED_UNLOCK'
}

function Invoke-NativeLines([scriptblock]$command) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $command)
        $exitCode = $LASTEXITCODE
        return [pscustomobject]@{
            Lines = @($lines | ForEach-Object { [string]$_ })
            ExitCode = $exitCode
        }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Expand-AuditEntries([string]$archivePath, [string]$destinationRoot) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Path $destinationRoot | Out-Null
    $resolvedDestination = Get-FullPath $destinationRoot
    $destinationPrefix = $resolvedDestination + '\'
    $archive = $null
    try {
        try {
            $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
        } catch {
            Stop-Audit 'APK_ARCHIVE_READ_FAILED'
        }
        foreach ($entry in $archive.Entries) {
            $normalizedEntry = $entry.FullName.Replace('\', '/')
            if ([string]::IsNullOrWhiteSpace($normalizedEntry) -or
                    $normalizedEntry.StartsWith('/', [StringComparison]::Ordinal) -or
                    $normalizedEntry -match '(^|/)\.\.(/|$)' -or
                    $normalizedEntry -match ':') {
                Stop-Audit 'APK_ARCHIVE_ENTRY_INVALID'
            }
            $extract = $normalizedEntry -cmatch '^classes(?:[2-9][0-9]*)?\.dex$'
            if (-not $extract -or $normalizedEntry.EndsWith('/', [StringComparison]::Ordinal)) {
                continue
            }
            $target = Get-FullPath (Join-Path $resolvedDestination $normalizedEntry.Replace('/', '\'))
            if (-not $target.StartsWith(
                    $destinationPrefix,
                    [StringComparison]::OrdinalIgnoreCase)) {
                Stop-Audit 'APK_ARCHIVE_ENTRY_OUTSIDE_SCRATCH'
            }
            if (Test-Path -LiteralPath $target) {
                Stop-Audit 'APK_ARCHIVE_DUPLICATE_ENTRY'
            }
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            $inputStream = $entry.Open()
            $outputStream = [System.IO.File]::Open(
                $target,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None)
            try {
                $inputStream.CopyTo($outputStream)
            } finally {
                $outputStream.Dispose()
                $inputStream.Dispose()
            }
        }
    } finally {
        if ($archive -ne $null) {
            $archive.Dispose()
        }
    }
}

function Get-AuditArchiveFileInventory([string]$archivePath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $rawOrdinal = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    $rawIgnoreCase = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $normalizedOrdinal = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    $normalizedIgnoreCase = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $files = [System.Collections.Generic.List[string]]::new()
    $archive = $null
    try {
        try {
            $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
        } catch {
            Stop-Audit 'APK_ARCHIVE_READ_FAILED'
        }
        foreach ($entry in $archive.Entries) {
            $rawName = [string]$entry.FullName
            $normalizedName = $rawName.Replace('\', '/')
            if ([string]::IsNullOrWhiteSpace($rawName) -or
                    $normalizedName.StartsWith('/', [StringComparison]::Ordinal) -or
                    $normalizedName -match '(^|/)\.\.(/|$)' -or
                    $normalizedName -match ':') {
                Stop-Audit 'APK_ARCHIVE_ENTRY_INVALID'
            }
            if (-not $rawOrdinal.Add($rawName) -or
                    -not $normalizedOrdinal.Add($normalizedName)) {
                Stop-Audit 'APK_ARCHIVE_DUPLICATE_ENTRY'
            }
            if (-not $rawIgnoreCase.Add($rawName) -or
                    -not $normalizedIgnoreCase.Add($normalizedName)) {
                Stop-Audit 'APK_ARCHIVE_CASE_COLLISION'
            }
            if (-not $normalizedName.EndsWith('/', [StringComparison]::Ordinal)) {
                $files.Add($normalizedName)
            }
        }
    } finally {
        if ($archive -ne $null) {
            $archive.Dispose()
        }
    }
    if ($files.Count -eq 0) {
        Stop-Audit 'APK_ARCHIVE_EMPTY'
    }
    return @($files.ToArray() | Sort-Object -CaseSensitive)
}

function Get-RegexCount([string]$text, [string]$pattern) {
    return [System.Text.RegularExpressions.Regex]::Matches(
        $text,
        $pattern,
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase -bor
        [System.Text.RegularExpressions.RegexOptions]::Singleline).Count
}

function Assert-SameSet(
        [string[]]$actual,
        [string[]]$expected,
        [string]$failureCode) {
    $actualSorted = @($actual | Sort-Object -Unique -CaseSensitive)
    $expectedSorted = @($expected | Sort-Object -Unique -CaseSensitive)
    $difference = @(Compare-Object $expectedSorted $actualSorted -CaseSensitive)
    if ($actual.Count -ne $actualSorted.Count -or
            $expected.Count -ne $expectedSorted.Count -or
            $difference.Count -ne 0) {
        Stop-Audit $failureCode
    }
}

function Assert-UniqueExactLine(
        [string[]]$lines,
        [string]$expected,
        [string]$failureCode) {
    if (@($lines | Where-Object { $_ -ceq $expected }).Count -ne 1) {
        Stop-Audit $failureCode
    }
}

function Assert-CompiledResourceSecurity([string]$resourceText) {
    if ($null -eq $resourceText) {
        Stop-Audit 'APK_RESOURCE_FORBIDDEN'
    }
    $resourceWithoutSchema = $resourceText.Replace(
        'http://schemas.android.com/apk/res/android', '')
    if ($resourceWithoutSchema -match '(?i)http://') {
        Stop-Audit 'APK_RESOURCE_FORBIDDEN'
    }
    foreach ($forbiddenValue in @(
            '13800138000', '123456', '0014872138', '888888',
            '111993413628001787216027', 'local-demo:',
            '8A 01 01 11 9B', '8A0101119B')) {
        if ($resourceText.Contains($forbiddenValue)) {
            Stop-Audit 'APK_RESOURCE_FORBIDDEN'
        }
    }
}

function Assert-FullFrameLogContracts(
        [hashtable]$sourceByRelative,
        [hashtable]$sourceHashByRelative) {
    $required = @(
        'app/src/main/java/com/codex/lockertest/serial/SerialGateway.java',
        'app/src/main/java/com/codex/lockertest/integration/CustomerSerialTransmitter.java',
        'app/src/main/java/com/codex/lockertest/runtime/RuntimeSerialLog.java',
        'app/src/main/java/com/codex/lockertest/AdminSerialActivity.java',
        'app/src/main/java/com/codex/lockertest/MainActivity.java'
    )
    $present = @($required | Where-Object { $sourceByRelative.ContainsKey($_) })
    if ($present.Count -eq 0) {
        foreach ($relative in $sourceByRelative.Keys) {
            if ((Get-RegexCount $sourceByRelative[$relative] '\bHexCodec\.format\s*\(') -ne 0 -or
                    (Get-RegexCount $sourceByRelative[$relative] '\bRuntimeSerialLog\b') -ne 0) {
                Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
            }
        }
        return 'COMMAND_LOG_CONTRACT=NOT_APPLICABLE'
    }
    if ($present.Count -ne $required.Count) {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }

    $requiredHashes = @{
        $required[0] = 'B881BD5858E9B8B410F41968E047139FC3AD5BD60D378ADF43BB7B88B92FD44B'
        $required[1] = 'AB7AC10DFDFFF9876B17CDFC567A52B201072B9893B55F8ECBEEEB4ED3C79359'
        $required[2] = 'F8F46C067B698E9854CCE2A3A4F12C892398F73B1D91509159E7164084274253'
        $required[3] = 'F88DBE63E29EB7F23BE759C1B0C9FDA56FF550A649B2169FBBFE65520966A075'
        $required[4] = 'AF977E210136E332C35C46C294904B6724786AD425D9CDCFEDFB50E578D7D5CF'
    }
    foreach ($relative in $required) {
        if (-not $sourceHashByRelative.ContainsKey($relative) -or
                $sourceHashByRelative[$relative] -cne $requiredHashes[$relative]) {
            Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
        }
    }

    $serial = $sourceByRelative[$required[0]]
    $customer = $sourceByRelative[$required[1]]
    $runtime = $sourceByRelative[$required[2]]
    $admin = $sourceByRelative[$required[3]]
    $main = $sourceByRelative[$required[4]]

    $expectedHexCounts = @{
        $required[0] = 3
        $required[1] = 1
        $required[3] = 2
    }
    foreach ($relative in $sourceByRelative.Keys) {
        $count = Get-RegexCount $sourceByRelative[$relative] '\bHexCodec\.format\s*\('
        $expected = 0
        if ($expectedHexCounts.ContainsKey($relative)) {
            $expected = $expectedHexCounts[$relative]
        }
        if ($count -ne $expected) {
            Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
        }
    }

    if ((Get-RegexCount $serial 'appendRuntimeLog\s*\([^;]{0,800}?HexCodec\.format\s*\(') -ne 3 -or
            (Get-RegexCount $serial 'private\s+synchronized\s+void\s+appendRuntimeLog\s*\(') -ne 1 -or
            (Get-RegexCount $serial 'runtimeLog\.append\s*\(') -ne 1) {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }
    if ((Get-RegexCount $runtime 'MAX_CHARACTERS\s*=\s*48_000') -ne 1 -or
            (Get-RegexCount $runtime 'MAX_LINES\s*=\s*600') -ne 1 -or
            (Get-RegexCount $runtime 'new\s+BoundedLogBuffer\s*\(\s*MAX_CHARACTERS\s*,\s*MAX_LINES\s*\)') -ne 1) {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }
    if ($runtime -match '(?i)android\.util\.Log|System\.(out|err)|java\.io\.File|java\.net\.|https?://') {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }

    if ((Get-RegexCount $customer '" HEX="\s*\+\s*HexCodec\.format\s*\(\s*safePayload\s*\)') -ne 1 -or
            (Get-RegexCount $customer '\binterface\s+AuditSink\b') -ne 1 -or
            (Get-RegexCount $customer 'audit\.append\s*\(\s*auditLine\s*\)') -ne 1 -or
            (Get-RegexCount $customer 'Status\.AUDIT_FAILED') -lt 1) {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }
    $auditIndex = $customer.IndexOf('audit.append(auditLine)', [StringComparison]::Ordinal)
    $writeIndex = $customer.IndexOf('writer.write(', [StringComparison]::Ordinal)
    if ($auditIndex -lt 0 -or $writeIndex -le $auditIndex -or
            $customer -match '(?i)android\.util\.Log|System\.(out|err)|java\.io\.File|java\.net\.|https?://') {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }

    if ((Get-RegexCount $admin 'renderResult\s*\([^;]{0,300}?HexCodec\.format\s*\(') -ne 2 -or
            (Get-RegexCount $admin 'logBuffer\.addListener\s*\(') -ne 1 -or
            (Get-RegexCount $admin 'logBuffer\.snapshot\s*\(') -ne 1) {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }
    if ((Get-RegexCount $main 'this::appendCustomerLog') -ne 1 -or
            (Get-RegexCount $main 'private\s+synchronized\s+void\s+appendCustomerLog\s*\([^)]*\)\s*\{\s*runtimeLog\.append\s*\(') -ne 1 -or
            $main -match 'runtimeLog\.(snapshot|addListener)\s*\(') {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }

    $runtimeSharedCallers = @()
    $runtimeViewers = @()
    $runtimeSerialLogReferences = @()
    $runtimeSerialLogAppendCallers = @()
    foreach ($relative in $sourceByRelative.Keys) {
        $source = $sourceByRelative[$relative]
        if ($source -match 'RuntimeSerialLog\.shared\s*\(') {
            $runtimeSharedCallers += $relative
        }
        if ($source -match '\bRuntimeSerialLog\b') {
            $runtimeSerialLogReferences += $relative
        }
        if ($source -match '\b(?:runtimeLog|logBuffer)\.append\s*\(') {
            $runtimeSerialLogAppendCallers += $relative
        }
        if ($source -match '(logBuffer|runtimeLog)\.(snapshot|addListener)\s*\(') {
            $runtimeViewers += $relative
        }
    }
    Assert-SameSet $runtimeSharedCallers @($required[0], $required[3], $required[4]) `
        'SOURCE_COMMAND_LOG_CONTRACT'
    $expectedRuntimeSerialLogReferences = @(
        $required[0], $required[2], $required[3], $required[4])
    $expectedRuntimeSerialLogAppendCallers = @(
        $required[0], $required[3], $required[4])
    if ($runtimeSerialLogReferences.Count -ne $expectedRuntimeSerialLogReferences.Count -or
            @(Compare-Object $expectedRuntimeSerialLogReferences `
                $runtimeSerialLogReferences -CaseSensitive).Count -ne 0 -or
            $runtimeSerialLogAppendCallers.Count -ne
                $expectedRuntimeSerialLogAppendCallers.Count -or
            @(Compare-Object $expectedRuntimeSerialLogAppendCallers `
                $runtimeSerialLogAppendCallers -CaseSensitive).Count -ne 0) {
        Stop-Source 'SOURCE_COMMAND_LOG_CONTRACT'
    }
    Assert-SameSet $runtimeViewers @($required[3]) 'SOURCE_COMMAND_LOG_CONTRACT'
    return 'COMMAND_LOG_CONTRACT=PINNED'
}

function Assert-SourceSecurity {
    $sourceFiles = @()
    foreach ($relativeRoot in @('app\src\main', 'app\src\production')) {
        $root = Join-Path $projectRoot $relativeRoot
        if (Test-Path -LiteralPath $root -PathType Container) {
            $sourceFiles += @(Get-ChildItem -LiteralPath $root -Recurse -File |
                Where-Object { $_.Extension -in @('.java', '.kt') })
        }
    }
    $sourceFiles = @($sourceFiles | Sort-Object FullName -Unique -CaseSensitive)
    if ($sourceFiles.Count -eq 0) {
        Stop-Audit 'SOURCE_FILES_MISSING'
    }

    $sourceByRelative = @{}
    $sourceHashByRelative = @{}
    foreach ($file in $sourceFiles) {
        $source = Get-NormalizedUtf8SourceText $file.FullName
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        $sourceByRelative[$relative] = $source
        $sourceHashByRelative[$relative] = Get-TextSha256 $source
        if ([regex]::IsMatch($source, '(?i)http://')) {
            Stop-Source 'SOURCE_CLEARTEXT_URL'
        }
        if ([regex]::IsMatch($source, '(?i)\btrustAll[a-z0-9_]*\b')) {
            Stop-Source 'SOURCE_TRUST_ALL'
        }
        if ([regex]::IsMatch(
                $source,
                '(?is)\bHostnameVerifier\b.{0,1200}?(?:->\s*true\b|return\s+true\s*;|ALLOW_ALL)')) {
            Stop-Source 'SOURCE_HOSTNAME_VERIFIER'
        }
    }

    Assert-NoProductionCredentialSourceMarkers $sourceByRelative
    $bootstrapContract = Assert-BootstrapSourceContract $sourceByRelative
    $businessContract = Assert-BusinessSourceContract $sourceByRelative
    $commandContract = Assert-FullFrameLogContracts `
        $sourceByRelative $sourceHashByRelative
    $zipPixelShell = 'app/src/main/java/com/codex/lockertest/ui/zip/ZipPixelShell.java'
    # UI alignment/version repair: the three existing resource/viewport Log.e sinks are unchanged.
    $zipPixelShellHash = 'C46BFEACCC7AB1B6FEFFECBB9F606CB880F740BDE8D1FF63AB66AB86E1D7CA67'
    $zipPresent = $sourceByRelative.ContainsKey($zipPixelShell)
    if ($zipPresent -and
            $sourceHashByRelative[$zipPixelShell] -cne $zipPixelShellHash) {
        Stop-Source 'SOURCE_LOG_CONTRACT'
    }
    if ($commandContract -ceq 'COMMAND_LOG_CONTRACT=PINNED' -and -not $zipPresent) {
        Stop-Source 'SOURCE_LOG_CONTRACT'
    }
    $loggingSinkPattern = '(?is)(?:\b(?:android\s*\.\s*util\s*\.\s*)?Log\s*\.\s*(?:v|d|i|w|e|wtf|println)\s*\(|\bTimber\b[^;]{0,400}?\.\s*(?:v|d|i|w|e|wtf|log)\s*\(|\bSystem\s*\.\s*(?:out|err)\s*\.\s*(?:print|println|printf|format|write|append)\s*\(|\b(?:print|println)\s*\(|\.\s*(?:trace|debug|info|warn|warning|fatal|severe|fine|finer|finest|config|logp|logrb)\s*\(|\b(?:logger|receiver|log|sink)\s*\.\s*(?:error|log)\s*\()'
    foreach ($relative in $sourceByRelative.Keys) {
        if ($relative -ceq $zipPixelShell) {
            continue
        }
        $source = $sourceByRelative[$relative]
        if ([regex]::IsMatch($source, $loggingSinkPattern) -or
                ($source -match '(?i)\b(?:java\.util\.logging|org\.slf4j|org\.apache\.commons\.logging|LoggerFactory)\b' -and
                $source -match '(?is)\.\s*(?:error|log)\s*\(')) {
            Stop-Source 'SOURCE_LOG_SINK'
        }
    }

    $manifestFiles = @()
    foreach ($relativeManifest in @(
            'app\src\main\AndroidManifest.xml',
            'app\src\production\AndroidManifest.xml')) {
        $manifestPath = Join-Path $projectRoot $relativeManifest
        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            $manifestFiles += Get-Item -LiteralPath $manifestPath
        }
    }
    if ($manifestFiles.Count -eq 0) {
        Stop-Audit 'SOURCE_MANIFEST_MISSING'
    }
    foreach ($manifestFile in $manifestFiles) {
        $manifestSource = [System.IO.File]::ReadAllText(
            $manifestFile.FullName,
            [System.Text.Encoding]::UTF8)
        if ($manifestSource -match '(?i)usesCleartextTraffic\s*=\s*"true"' -or
                $manifestSource -match '(?i)networkSecurityConfig\s*=') {
            Stop-Source 'SOURCE_MANIFEST_CLEARTEXT'
        }
        $withoutAndroidSchema = $manifestSource.Replace(
            'http://schemas.android.com/apk/res/android', '')
        if ($withoutAndroidSchema -match '(?i)http://') {
            Stop-Source 'SOURCE_CLEARTEXT_URL'
        }
    }

    $textResourceFiles = @()
    $textResourceExtensions = @(
        '.xml', '.json', '.properties', '.txt', '.yaml', '.yml', '.csv', '.conf', '.ini')
    foreach ($relativeRoot in @('app\src\main', 'app\src\production')) {
        $root = Join-Path $projectRoot $relativeRoot
        if (Test-Path -LiteralPath $root -PathType Container) {
            $textResourceFiles += @(Get-ChildItem -LiteralPath $root -Recurse -File |
                Where-Object {
                    $textResourceExtensions -contains $_.Extension.ToLowerInvariant()
                })
        }
    }
    $textResourceFiles = @(
        $textResourceFiles | Sort-Object FullName -Unique -CaseSensitive)
    $forbiddenResourceValues = @(
        '13800138000', '123456', '0014872138', '888888',
        '111993413628001787216027', 'local-demo:')
    foreach ($resourceFile in $textResourceFiles) {
        $resourceText = [System.IO.File]::ReadAllText(
            $resourceFile.FullName,
            [System.Text.Encoding]::UTF8)
        $resourceWithoutSchema = $resourceText.Replace(
            'http://schemas.android.com/apk/res/android', '')
        if ($resourceWithoutSchema -match '(?i)http://') {
            Stop-Source 'SOURCE_RESOURCE_CLEARTEXT'
        }
        foreach ($forbiddenResourceValue in $forbiddenResourceValues) {
            if ($resourceText.Contains($forbiddenResourceValue)) {
                Stop-Source 'SOURCE_RESOURCE_DEMO_VALUE'
            }
        }
    }

    Write-Evidence 'source-scan.txt' @(
        "JAVA_KOTLIN_FILES=$($sourceFiles.Count)",
        "MANIFEST_FILES=$($manifestFiles.Count)",
        "TEXT_RESOURCE_FILES=$($textResourceFiles.Count)",
        $bootstrapContract,
        $businessContract,
        $commandContract,
        "ZIP_PIXEL_SHELL_PINNED=$zipPresent",
        'SOURCE_SECURITY=PASS'
    )
}

function Get-DirectChildElements([System.Xml.XmlElement]$element) {
    return @($element.ChildNodes | Where-Object {
        $_ -is [System.Xml.XmlElement]
    })
}

function Get-AndroidAttribute(
        [System.Xml.XmlElement]$element,
        [string]$name) {
    return $element.GetAttribute(
        $name,
        'http://schemas.android.com/apk/res/android')
}

function Assert-AndroidAttributeSet(
        [System.Xml.XmlElement]$element,
        [string[]]$expected,
        [string]$failureCode) {
    $actual = [System.Collections.Generic.List[string]]::new()
    foreach ($attribute in $element.Attributes) {
        if ($attribute.NamespaceURI -cne
                'http://schemas.android.com/apk/res/android') {
            Stop-Audit $failureCode
        }
        $actual.Add("$($attribute.LocalName)=$($attribute.Value)")
    }
    Assert-SameSet $actual.ToArray() $expected $failureCode
}

function Assert-IntentFilterContract(
        [System.Xml.XmlElement]$activity,
        [string[]]$expectedActions,
        [string[]]$expectedCategories,
        [string]$failureCode) {
    $activityChildren = @(Get-DirectChildElements $activity)
    if ($activityChildren.Count -ne 1 -or
            $activityChildren[0].LocalName -cne 'intent-filter') {
        Stop-Audit $failureCode
    }
    $intentFilter = $activityChildren[0]
    if ($intentFilter.Attributes.Count -ne 0) {
        Stop-Audit $failureCode
    }
    $actions = [System.Collections.Generic.List[string]]::new()
    $categories = [System.Collections.Generic.List[string]]::new()
    foreach ($child in @(Get-DirectChildElements $intentFilter)) {
        if ($child.LocalName -cne 'action' -and
                $child.LocalName -cne 'category') {
            Stop-Audit $failureCode
        }
        Assert-AndroidAttributeSet $child @(
            "name=$(Get-AndroidAttribute $child 'name')") $failureCode
        if ($child.LocalName -ceq 'action') {
            $actions.Add((Get-AndroidAttribute $child 'name'))
        } else {
            $categories.Add((Get-AndroidAttribute $child 'name'))
        }
    }
    Assert-SameSet $actions.ToArray() $expectedActions $failureCode
    Assert-SameSet $categories.ToArray() $expectedCategories $failureCode
}

function Convert-AaptAttributeValue([string]$rawValue) {
    if ($null -eq $rawValue) {
        Stop-Audit 'MANIFEST_TREE_INVALID'
    }
    $value = $rawValue.Trim()
    if ($value.StartsWith('"', [StringComparison]::Ordinal)) {
        $rawMarker = '" (Raw: '
        $rawMarkerIndex = $value.IndexOf($rawMarker, [StringComparison]::Ordinal)
        if ($rawMarkerIndex -gt 0) {
            $value = $value.Substring(1, $rawMarkerIndex - 1)
        } elseif ($value.Length -ge 2 -and
                $value.EndsWith('"', [StringComparison]::Ordinal)) {
            $value = $value.Substring(1, $value.Length - 2)
        } else {
            Stop-Audit 'MANIFEST_TREE_INVALID'
        }
    }
    if ($value -cmatch '^0x[0-9a-fA-F]+$') {
        $digits = $value.Substring(2).TrimStart('0').ToLowerInvariant()
        if ($digits.Length -eq 0) {
            $digits = '0'
        }
        return '0x' + $digits
    }
    if ($value -cmatch '^@0x[0-9a-fA-F]+$') {
        $digits = $value.Substring(3).TrimStart('0').ToLowerInvariant()
        if ($digits.Length -eq 0) {
            $digits = '0'
        }
        return '@ref/0x' + $digits
    }
    return $value
}

function Convert-AaptXmlTreeToDocument([string[]]$lines) {
    if ($null -eq $lines -or $lines.Count -eq 0) {
        Stop-Audit 'MANIFEST_TREE_INVALID'
    }
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    $namespaceSeen = $false
    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $false
    $document.XmlResolver = $null
    $stack = [System.Collections.Generic.List[object]]::new()
    $rootSeen = $false

    foreach ($rawLine in $lines) {
        $line = [string]$rawLine
        if ($line -cmatch '^N: android=(\S+) \(line=[0-9]+\)$') {
            if ($namespaceSeen -or $Matches[1] -cne $androidNamespace) {
                Stop-Audit 'MANIFEST_TREE_INVALID'
            }
            $namespaceSeen = $true
            continue
        }
        if ($line -cmatch '^(?<indent> *)E: (?<name>[A-Za-z0-9_.:-]+) \(line=[0-9]+\)$') {
            $indent = $Matches['indent'].Length
            while ($stack.Count -gt 0 -and
                    $stack[$stack.Count - 1].Indent -ge $indent) {
                $stack.RemoveAt($stack.Count - 1)
            }
            $element = $document.CreateElement($Matches['name'])
            if ($stack.Count -eq 0) {
                if ($rootSeen) {
                    Stop-Audit 'MANIFEST_TREE_INVALID'
                }
                [void]$document.AppendChild($element)
                $rootSeen = $true
            } else {
                [void]$stack[$stack.Count - 1].Element.AppendChild($element)
            }
            $stack.Add([pscustomobject]@{ Indent = $indent; Element = $element })
            continue
        }
        if ($line -cmatch '^(?<indent> *)A: (?:(?<namespace>https?://\S+):)?(?<name>[A-Za-z0-9_.-]+)(?:\(0x[0-9a-fA-F]+\))?=(?<value>.*)$') {
            if ($stack.Count -eq 0 -or
                    $Matches['indent'].Length -le $stack[$stack.Count - 1].Indent) {
                Stop-Audit 'MANIFEST_TREE_INVALID'
            }
            $attributeNamespace = $Matches['namespace']
            $attributeName = $Matches['name']
            $attributeValue = Convert-AaptAttributeValue $Matches['value']
            if ([string]::IsNullOrEmpty($attributeNamespace)) {
                $stack[$stack.Count - 1].Element.SetAttribute(
                    $attributeName,
                    $attributeValue)
            } elseif ($attributeNamespace -ceq $androidNamespace) {
                $stack[$stack.Count - 1].Element.SetAttribute(
                    $attributeName,
                    $androidNamespace,
                    $attributeValue)
            } else {
                Stop-Audit 'MANIFEST_TREE_INVALID'
            }
            continue
        }
        Stop-Audit 'MANIFEST_TREE_INVALID'
    }
    if (-not $namespaceSeen -or -not $rootSeen -or
            $document.DocumentElement.LocalName -cne 'manifest') {
        Stop-Audit 'MANIFEST_TREE_INVALID'
    }
    return $document
}

function Assert-ManifestContract([string]$manifestText) {
    $document = [System.Xml.XmlDocument]::new()
    $document.PreserveWhitespace = $true
    $document.XmlResolver = $null
    try {
        $document.LoadXml($manifestText)
    } catch {
        Stop-Audit 'MANIFEST_XML_INVALID'
    }
    if ($document.DocumentType -ne $null -or
            $document.DocumentElement -eq $null -or
            $document.DocumentElement.LocalName -cne 'manifest') {
        Stop-Audit 'MANIFEST_XML_INVALID'
    }
    $manifest = $document.DocumentElement
    if ($manifest.GetAttribute('package') -cne 'com.codex.lockertest' -or
            (Get-AndroidAttribute $manifest 'versionCode') -cne '21' -or
            (Get-AndroidAttribute $manifest 'versionName') -cne
            '21.0-frontend-integration-test') {
        Stop-Audit 'MANIFEST_IDENTITY_INVALID'
    }

    $manifestChildren = @(Get-DirectChildElements $manifest)
    $usesSdk = @($manifestChildren | Where-Object { $_.LocalName -ceq 'uses-sdk' })
    $permissions = @($manifestChildren | Where-Object {
        $_.LocalName -ceq 'uses-permission'
    })
    $features = @($manifestChildren | Where-Object {
        $_.LocalName -ceq 'uses-feature'
    })
    $applications = @($manifestChildren | Where-Object {
        $_.LocalName -ceq 'application'
    })
    if ($usesSdk.Count -ne 1 -or $permissions.Count -ne 3 -or
            $features.Count -ne 1 -or $applications.Count -ne 1 -or
            $manifestChildren.Count -ne 6) {
        Stop-Audit 'MANIFEST_STRUCTURE_INVALID'
    }
    Assert-AndroidAttributeSet $usesSdk[0] @(
        'minSdkVersion=21',
        'targetSdkVersion=30') 'MANIFEST_SDK_INVALID'

    $permissionNames = [System.Collections.Generic.List[string]]::new()
    foreach ($permission in $permissions) {
        $permissionName = Get-AndroidAttribute $permission 'name'
        Assert-AndroidAttributeSet $permission @(
            "name=$permissionName") 'MANIFEST_PERMISSIONS_INVALID'
        $permissionNames.Add($permissionName)
    }
    Assert-SameSet $permissionNames.ToArray() @(
        'android.permission.CAMERA',
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE') `
        'MANIFEST_PERMISSIONS_INVALID'
    Assert-AndroidAttributeSet $features[0] @(
        'name=android.hardware.camera',
        'required=false') 'MANIFEST_FEATURE_INVALID'

    $application = $applications[0]
    $cleartextPresent = $application.HasAttribute(
        'usesCleartextTraffic',
        'http://schemas.android.com/apk/res/android')
    if (($cleartextPresent -and
            (Get-AndroidAttribute $application 'usesCleartextTraffic') -cne 'false') -or
            $application.HasAttribute(
                'networkSecurityConfig',
                'http://schemas.android.com/apk/res/android') -or
            (Get-AndroidAttribute $application 'allowBackup') -cne 'false' -or
            ($application.HasAttribute(
                'debuggable',
                'http://schemas.android.com/apk/res/android') -and
            (Get-AndroidAttribute $application 'debuggable') -cne 'false')) {
        Stop-Audit 'MANIFEST_SECURITY_INVALID'
    }

    $activities = [System.Collections.Generic.List[System.Xml.XmlElement]]::new()
    foreach ($component in @(Get-DirectChildElements $application)) {
        if ($component.LocalName -cne 'activity') {
            Stop-Audit 'MANIFEST_COMPONENT_SET_INVALID'
        }
        $activities.Add($component)
    }
    if ($activities.Count -ne 4) {
        Stop-Audit 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
    }
    $activityByName = @{}
    foreach ($activity in $activities) {
        $activityName = Get-AndroidAttribute $activity 'name'
        if ([string]::IsNullOrWhiteSpace($activityName) -or
                $activityByName.ContainsKey($activityName)) {
            Stop-Audit 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
        }
        $activityByName[$activityName] = $activity
    }
    Assert-SameSet @($activityByName.Keys) @(
        '.MainActivity',
        '.AdminSerialActivity',
        '.FaceSdkAdminActivity',
        'com.baidu.liantian.LiantianActivity') `
        'MANIFEST_ACTIVITY_CONTRACT_INVALID'

    $mainActivity = $activityByName['.MainActivity']
    Assert-AndroidAttributeSet $mainActivity @(
        'name=.MainActivity',
        'exported=true',
        'screenOrientation=0',
        'configChanges=0x4f0',
        'windowSoftInputMode=0x3') 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
    Assert-IntentFilterContract $mainActivity @(
        'android.intent.action.MAIN') @(
        'android.intent.category.LAUNCHER') `
        'MANIFEST_INTENT_CONTRACT_INVALID'

    $adminActivity = $activityByName['.AdminSerialActivity']
    Assert-AndroidAttributeSet $adminActivity @(
        'name=.AdminSerialActivity',
        'exported=false',
        'screenOrientation=0',
        'configChanges=0x4a0') 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
    if (@(Get-DirectChildElements $adminActivity).Count -ne 0) {
        Stop-Audit 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
    }

    $faceActivity = $activityByName['.FaceSdkAdminActivity']
    Assert-AndroidAttributeSet $faceActivity @(
        'name=.FaceSdkAdminActivity',
        'exported=false',
        'screenOrientation=0',
        'configChanges=0x4a0') 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
    if (@(Get-DirectChildElements $faceActivity).Count -ne 0) {
        Stop-Audit 'MANIFEST_ACTIVITY_CONTRACT_INVALID'
    }

    $liantian = $activityByName['com.baidu.liantian.LiantianActivity']
    Assert-AndroidAttributeSet $liantian @(
        'theme=@ref/0x103000f',
        'name=com.baidu.liantian.LiantianActivity',
        'exported=true',
        'excludeFromRecents=true',
        'launchMode=0') 'MANIFEST_LIANTIAN_EXCEPTION_INVALID'
    Assert-IntentFilterContract $liantian @(
        'com.baidu.action.Liantian.VIEW') @(
        'com.baidu.category.liantian',
        'android.intent.category.DEFAULT') `
        'MANIFEST_LIANTIAN_EXCEPTION_INVALID'
}

function Inspect-DexClassBlock(
        [string[]]$lines,
        [object[]]$forbiddenValues) {
    $descriptorLines = @($lines | Where-Object {
        $_ -match '^\s*Class descriptor\s*:'
    })
    if ($descriptorLines.Count -ne 1 -or
            $descriptorLines[0] -notmatch
            "^\s*Class descriptor\s*:\s*'(L[^'\s]+;)'\s*$") {
        Stop-Audit 'APK_DEXDUMP_DESCRIPTOR_FORMAT'
    }
    $descriptor = $Matches[1]
    $isAppClass = $descriptor.StartsWith(
        'Lcom/codex/lockertest/',
        [System.StringComparison]::Ordinal)
    $apkWideCredentialRule = 'DEX_FORBIDDEN_BOOTSTRAP_CREDENTIAL_PLAINTEXT'
    foreach ($line in $lines) {
        foreach ($forbiddenValue in $forbiddenValues) {
            if ($forbiddenValue.Rule -ceq $apkWideCredentialRule -and
                    $line.Contains($forbiddenValue.Value)) {
                Stop-Audit $forbiddenValue.Rule
            }
        }
    }
    $businessEndpointPaths = [System.Collections.Generic.List[string]]::new()
    $bootstrapEndpointPaths = [System.Collections.Generic.List[string]]::new()
    $expectedBusinessEndpointPaths = @(Get-BusinessEndpointContractPaths)
    $expectedBootstrapEndpointPaths = @(Get-BootstrapEndpointContractPaths)
    $isBusinessEndpoint = $descriptor -ceq
        'Lcom/codex/lockertest/business/BusinessEndpoint;'
    $isBootstrapEndpoint = $descriptor -ceq
        'Lcom/codex/lockertest/server/ApiEndpoint;'
    $faceUploadDescriptor =
        'Lcom/codex/lockertest/server/ProductionFaceImageUploadAdapter;'
    $faceUploadEndpointPath = '/v2/central_control_screen/uploadImagePublic'
    $isFaceUploadEndpoint = $descriptor -ceq $faceUploadDescriptor
    $faceUploadStaticEndpointCount = 0
    $faceUploadInstructionEndpointCount = 0
    foreach ($line in $lines) {
        $endpointCandidates = [System.Collections.Generic.List[string]]::new()
        foreach ($endpointMatch in [regex]::Matches(
                $line,
                '"(/v2/central_control_screen/[^"\r\n]+)"')) {
            $endpointCandidates.Add($endpointMatch.Groups[1].Value)
        }
        $staticEndpoint = [regex]::Match(
            $line,
            '^\s*value\s*:\s*(/v2/central_control_screen/\S+)\s*$')
        if ($staticEndpoint.Success) {
            $endpointCandidates.Add($staticEndpoint.Groups[1].Value)
        }
        foreach ($candidate in $endpointCandidates) {
            if ($isBusinessEndpoint) {
                $businessEndpointPaths.Add($candidate)
            } elseif ($isBootstrapEndpoint) {
                $bootstrapEndpointPaths.Add($candidate)
            } elseif ($isFaceUploadEndpoint -and $candidate -ceq $faceUploadEndpointPath) {
                if ($line -match '^\s*value\s*:') {
                    $faceUploadStaticEndpointCount++
                } elseif ($line -match '\bconst-string(?:/jumbo)?\b') {
                    $faceUploadInstructionEndpointCount++
                } else {
                    Stop-Audit 'APK_BUSINESS_ENDPOINT_CONTRACT'
                }
            } else {
                Stop-Audit 'APK_BUSINESS_ENDPOINT_CONTRACT'
            }
        }
    }
    if ($isBusinessEndpoint -and
            ($businessEndpointPaths.Count -ne $expectedBusinessEndpointPaths.Count -or
            @(Compare-Object $expectedBusinessEndpointPaths `
                $businessEndpointPaths.ToArray() -CaseSensitive).Count -ne 0)) {
        Stop-Audit 'APK_BUSINESS_ENDPOINT_CONTRACT'
    }
    if ($isBootstrapEndpoint -and
            ($bootstrapEndpointPaths.Count -ne $expectedBootstrapEndpointPaths.Count -or
            @(Compare-Object $expectedBootstrapEndpointPaths `
                $bootstrapEndpointPaths.ToArray() -CaseSensitive).Count -ne 0)) {
        Stop-Audit 'APK_BUSINESS_ENDPOINT_CONTRACT'
    }
    if ($isFaceUploadEndpoint -and
            ($faceUploadStaticEndpointCount -ne 1 -or
            $faceUploadInstructionEndpointCount -ne 1)) {
        Stop-Audit 'APK_BUSINESS_ENDPOINT_CONTRACT'
    }
    if (-not $isAppClass) {
        return [pscustomobject]@{
            IsApp = $false
            DescriptorHash = $null
            StringRecordHashes = @()
            StringRecordCount = 0
            IsFaceBuildVariant = $false
            FaceBuildVariantIsProduction = $false
        }
    }

    $stringRecordHashes = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        foreach ($forbiddenValue in $forbiddenValues) {
            if ($forbiddenValue.Rule -cne $apkWideCredentialRule -and
                    $line.Contains($forbiddenValue.Value) -and
                    ([string]::IsNullOrEmpty($forbiddenValue.AllowedDescriptor) -or
                    $descriptor -cne $forbiddenValue.AllowedDescriptor)) {
                Stop-Audit $forbiddenValue.Rule
            }
        }

        $isStringRecord = $false
        if ($line -match '\bconst-string(?:/jumbo)?\b') {
            if ($line -notmatch
                    '^\s*[0-9a-f]+:\s+[0-9a-f ]+\|\s*[0-9a-f]+:\s+const-string(?:/jumbo)?\s+v[0-9]+,\s+".*"\s+// string@[0-9a-f]+\s*$') {
                Stop-Audit 'APK_DEXDUMP_STRING_FORMAT'
            }
            $isStringRecord = $true
        } elseif ($line -match '^\s*value\s*:') {
            if ($line -notmatch '^\s*value\s*:\s*\S.*$') {
                Stop-Audit 'APK_DEXDUMP_STRING_FORMAT'
            }
            $isStringRecord = $true
        } elseif ($line -match '^\s*VISIBILITY_(?:BUILD|RUNTIME|SYSTEM)\b.*\bvalue=') {
            if ($line -notmatch
                    '^\s*VISIBILITY_(?:BUILD|RUNTIME|SYSTEM)\s+\S+\s+.*\bvalue=\S.*$') {
                Stop-Audit 'APK_DEXDUMP_STRING_FORMAT'
            }
            $isStringRecord = $true
        }
        if ($isStringRecord) {
            $stringRecordHashes.Add((Get-TextSha256 $line))
        }
    }

    $isFaceBuildVariant = $descriptor -ceq
        'Lcom/codex/lockertest/face/FaceBuildVariant;'
    $faceBuildVariantIsProduction = $false
    if ($isFaceBuildVariant) {
        $faceMethodHeaders = @($lines | Where-Object {
            $_ -match
            '^\s*[0-9a-f]+:\s*\|\[[0-9a-f]+\]\s+com\.codex\.lockertest\.face\.FaceBuildVariant\.isLocalDemo:\(\)Z\s*$'
        })
        if ($faceMethodHeaders.Count -ne 1) {
            Stop-Audit 'APK_FACE_BUILD_VARIANT_INVALID'
        }
        $blockText = $lines -join "`n"
        $productionBodyPattern =
            '(?m)^\s*[0-9a-f]+:\s*\|\[[0-9a-f]+\]\s+com\.codex\.lockertest\.face\.FaceBuildVariant\.isLocalDemo:\(\)Z\s*$' +
            "`n" +
            '^\s*[0-9a-f]+:\s+[0-9a-f ]+\|\s*0000:\s+const/4\s+v([0-9]+),\s+#int 0\s+// #0\s*$' +
            "`n" +
            '^\s*[0-9a-f]+:\s+[0-9a-f ]+\|\s*0001:\s+return\s+v\1\s*$'
        $faceBuildVariantIsProduction = [regex]::Matches(
            $blockText,
            $productionBodyPattern).Count -eq 1
    }

    return [pscustomobject]@{
        IsApp = $true
        DescriptorHash = Get-TextSha256 $descriptor
        StringRecordHashes = $stringRecordHashes.ToArray()
        StringRecordCount = $stringRecordHashes.Count
        IsFaceBuildVariant = $isFaceBuildVariant
        FaceBuildVariantIsProduction = $faceBuildVariantIsProduction
    }
}

try {
    $resolvedProjectRoot = Get-FullPath $projectRoot
    $resolvedManualBuildRoot = Get-FullPath (Join-Path $resolvedProjectRoot 'manual-build')
    $resolvedV17Root = Get-FullPath (Join-Path $resolvedProjectRoot 'manual-build\v17')
    $expectedAuditRoot = Get-FullPath (Join-Path $resolvedV17Root 'audit-production')
    $resolvedAuditRoot = Get-FullPath $auditRoot
    $resolvedScratch = Get-FullPath $scratch
    $resolvedEvidenceStaging = Get-FullPath (Join-Path $resolvedScratch 'evidence')
    $resolvedEvidenceRun = Get-FullPath (Join-Path $resolvedAuditRoot $evidenceRunName)
    $auditPrefix = $resolvedAuditRoot + '\'

    if (-not [string]::Equals(
            $resolvedAuditRoot,
            $expectedAuditRoot,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Audit 'AUDIT_ROOT_INVALID'
    }
    if (-not $resolvedScratch.StartsWith(
            $auditPrefix,
            [System.StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals(
                (Split-Path -Parent $resolvedScratch),
                $resolvedAuditRoot,
                [System.StringComparison]::OrdinalIgnoreCase) -or
            $scratchName -notmatch '^run-[0-9a-f]{32}$' -or
            $evidenceRunName -notmatch '^evidence-[0-9a-f]{32}$' -or
            -not [string]::Equals(
                (Split-Path -Parent $resolvedEvidenceRun),
                $resolvedAuditRoot,
                [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Audit 'AUDIT_SCRATCH_OUTSIDE_ROOT'
    }
    Assert-NoReparseAncestorChain $resolvedProjectRoot
    Assert-PlainDirectory $resolvedProjectRoot
    Ensure-PlainChildDirectory $resolvedManualBuildRoot $resolvedProjectRoot
    Ensure-PlainChildDirectory $resolvedV17Root $resolvedManualBuildRoot
    if (Test-ReparsePoint $resolvedV17Root) {
        Stop-Audit 'AUDIT_ROOT_REPARSE_POINT'
    }
    if (Test-Path -LiteralPath $resolvedAuditRoot) {
        Assert-PlainDirectory $resolvedAuditRoot
    } else {
        New-Item -ItemType Directory -Path $resolvedAuditRoot | Out-Null
        Assert-PlainDirectory $resolvedAuditRoot
    }
    Assert-NoReparseAncestorChain $resolvedAuditRoot
    if (Test-Path -LiteralPath $resolvedScratch) {
        Stop-Audit 'AUDIT_SCRATCH_ALREADY_EXISTS'
    }
    New-Item -ItemType Directory -Path $resolvedScratch | Out-Null
    $scratchCreated = $true
    Assert-NoReparseAncestorChain $resolvedScratch
    if ((Test-ReparsePoint $resolvedScratch) -or
            -not [string]::Equals(
                (Get-FullPath (Resolve-Path -LiteralPath $resolvedScratch).Path),
                $resolvedScratch,
                [System.StringComparison]::OrdinalIgnoreCase)) {
        Stop-Audit 'AUDIT_SCRATCH_REPARSE_POINT'
    }
    New-Item -ItemType Directory -Path $resolvedEvidenceStaging | Out-Null
    $evidenceStaged = $true
    Assert-PlainDirectory $resolvedEvidenceStaging

    foreach ($tool in @($apkanalyzer, $apksigner, $aapt, $aapt2, $dexdump)) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
            Stop-Audit 'AUDIT_TOOL_MISSING'
        }
    }
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        Stop-Audit 'APK_INPUT_MISSING'
    }

    Assert-SourceSecurity

    $auditApk = Join-Path $resolvedScratch 'input.apk'
    Copy-Item -LiteralPath $Apk -Destination $auditApk
    $archiveFiles = @(Get-AuditArchiveFileInventory $auditApk)

    $packageResult = Invoke-NativeLines {
        & $apkanalyzer dex packages --defined-only $auditApk 2>&1
    }
    $packageLines = @($packageResult.Lines)
    if ($packageResult.ExitCode -ne 0) {
        Stop-Audit 'APK_DEX_PACKAGE_SCAN_FAILED'
    }
    $appPackageLines = @($packageLines | ForEach-Object {
        $line = [string]$_
        if ($line -match '^[PC]\s+d\s+\d+\s+\d+\s+\d+\s+com\.codex\.lockertest(?:\s|$|\.)') {
            $line
        }
    } | Where-Object { $_ })
    $classNames = @($packageLines | ForEach-Object {
        $line = [string]$_
        if ($line -match '^C\s+d\s+\d+\s+\d+\s+\d+\s+(com\.codex\.lockertest\S+)\s*$') {
            $Matches[1]
        }
    } | Where-Object { $_ } | Sort-Object -Unique -CaseSensitive)
    if ($classNames.Count -eq 0) {
        Stop-Audit 'APK_APP_CLASSES_MISSING'
    }

    $forbiddenClasses = @(
        [pscustomobject]@{ Symbol = 'DemoCredentials'; Name = 'DemoCredentials' },
        [pscustomobject]@{ Symbol = 'DemoFeatureFlags'; Name = 'DemoFeatureFlags' },
        [pscustomobject]@{ Symbol = 'LocalDemoAdminCapabilityPolicy'; Name = 'LocalDemoAdminCapabilityPolicy' },
        [pscustomobject]@{ Symbol = 'LocalDemoPreferenceStore'; Name = 'LocalDemoPreferenceStore' },
        [pscustomobject]@{ Symbol = 'LocalDemoReturnServiceClient'; Name = 'LocalDemoReturnServiceClient' },
        [pscustomobject]@{ Symbol = 'LocalDemoAdminCredentialPolicy'; Name = 'LocalDemoAdminCredentialPolicy' },
        [pscustomobject]@{ Symbol = 'LocalDemoCredentialAdmissionPolicy'; Name = 'LocalDemoCredentialAdmissionPolicy' },
        [pscustomobject]@{ Symbol = 'LocalDemoCustomerUnlockAuthorizer'; Name = 'LocalDemoCustomerUnlockAuthorizer' },
        [pscustomobject]@{ Symbol = 'LocalDemoInitialLayoutPolicy'; Name = 'LocalDemoInitialLayoutPolicy' },
        [pscustomobject]@{ Symbol = 'LocalDemoLegacyLayoutSource'; Name = 'LocalDemoLegacyLayoutSource' },
        [pscustomobject]@{ Symbol = 'LegacyV6LockerLayoutSource'; Name = 'LegacyV6LockerLayoutSource' },
        [pscustomobject]@{ Symbol = 'LocalPassFaceVerificationClient'; Name = 'LocalPassFaceVerificationClient' },
        [pscustomobject]@{ Symbol = 'FaceDemoBanner'; Name = 'FaceDemoBanner' }
    )
    foreach ($forbiddenClass in $forbiddenClasses) {
        $classPattern = '(?:^|\.)' + [regex]::Escape($forbiddenClass.Name) + '(?:\$|$)'
        if (@($classNames | Where-Object { $_ -match $classPattern }).Count -ne 0) {
            Stop-Audit "PRODUCTION_APK_FORBIDDEN=$($forbiddenClass.Symbol)"
        }
    }
    $productionSentinels = @(
        [pscustomobject]@{ Name = 'ProductionAdminCapabilityPolicy'; Fqcn = 'com.codex.lockertest.admin.ProductionAdminCapabilityPolicy' },
        [pscustomobject]@{ Name = 'UnavailableFaceVerificationClient'; Fqcn = 'com.codex.lockertest.face.verification.UnavailableFaceVerificationClient' },
        [pscustomobject]@{ Name = 'FailClosedReturnServiceClient'; Fqcn = 'com.codex.lockertest.returnflow.FailClosedReturnServiceClient' },
        [pscustomobject]@{ Name = 'EmptyInitialLayoutPolicy'; Fqcn = 'com.codex.lockertest.runtime.EmptyInitialLayoutPolicy' },
        [pscustomobject]@{ Name = 'FailClosedCustomerUnlockAuthorizer'; Fqcn = 'com.codex.lockertest.runtime.FailClosedCustomerUnlockAuthorizer' },
        [pscustomobject]@{ Name = 'RejectingCredentialAdmissionPolicy'; Fqcn = 'com.codex.lockertest.runtime.RejectingCredentialAdmissionPolicy' },
        [pscustomobject]@{ Name = 'UnprovisionedAdminCredentialPolicy'; Fqcn = 'com.codex.lockertest.runtime.UnprovisionedAdminCredentialPolicy' },
        [pscustomobject]@{ Name = 'HttpsUrlConnectionTransport'; Fqcn = 'com.codex.lockertest.server.HttpsUrlConnectionTransport' },
        [pscustomobject]@{ Name = 'ProductionBootstrapService'; Fqcn = 'com.codex.lockertest.server.ProductionBootstrapService' },
        [pscustomobject]@{ Name = 'BusinessEndpoint'; Fqcn = 'com.codex.lockertest.business.BusinessEndpoint' },
        [pscustomobject]@{ Name = 'ProductionBusinessService'; Fqcn = 'com.codex.lockertest.server.ProductionBusinessService' },
        [pscustomobject]@{ Name = 'Rk3288DeviceSerialProvider'; Fqcn = 'com.codex.lockertest.bootstrap.Rk3288DeviceSerialProvider' },
        [pscustomobject]@{ Name = 'ProductionBootstrapRuntime'; Fqcn = 'com.codex.lockertest.bootstrap.ProductionBootstrapRuntime' },
        [pscustomobject]@{ Name = 'ProductionBootstrapRuntimeFactory'; Fqcn = 'com.codex.lockertest.bootstrap.ProductionBootstrapRuntimeFactory' },
        [pscustomobject]@{ Name = 'ProductionBootstrapScheduler'; Fqcn = 'com.codex.lockertest.bootstrap.ProductionBootstrapScheduler' },
        [pscustomobject]@{ Name = 'ProductionSecretProvider'; Fqcn = 'com.codex.lockertest.server.ProductionSecretProvider' },
        [pscustomobject]@{ Name = 'ProductionBootstrapContractGate'; Fqcn = 'com.codex.lockertest.bootstrap.ProductionBootstrapContractGate' }
    )
    foreach ($productionSentinel in $productionSentinels) {
        if (@($classNames | Where-Object {
                    $_ -ceq $productionSentinel.Fqcn
                }).Count -ne 1) {
            Stop-Audit 'APK_PRODUCTION_SENTINEL_MISSING'
        }
    }
    Write-Evidence 'dex-packages.txt' @($appPackageLines | ForEach-Object { [string]$_ })

    $apkZip = Join-Path $resolvedScratch 'input.zip'
    $expanded = Join-Path $resolvedScratch 'expanded'
    Copy-Item -LiteralPath $auditApk -Destination $apkZip
    Expand-AuditEntries $apkZip $expanded
    $dexFiles = @(Get-ChildItem -LiteralPath $expanded -File |
        Where-Object { $_.Name -match '^classes(?:[2-9][0-9]*)?\.dex$' } |
        Sort-Object Name)
    if ($dexFiles.Count -eq 0) {
        Stop-Audit 'APK_DEX_FILES_MISSING'
    }

    $localDemoBanner = -join ([char[]]@(
        0x672C, 0x673A, 0x8054, 0x8C03, 0xFF1A, 0x672A,
        0x8FDB, 0x884C, 0x8EAB, 0x4EFD, 0x6BD4, 0x5BF9))
    $bootstrapCredentialPlaintext = -join ([char[]]@(
        0x0068, 0x0038, 0x0054, 0x0044, 0x0047, 0x0053, 0x0074, 0x0046,
        0x0067, 0x0061, 0x0037, 0x0075, 0x0037, 0x0038, 0x0039, 0x0057))
    $forbiddenValues = @(
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_PHONE'; Value = '13800138000'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_PASSWORD'; Value = '123456'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_ID_CARD'; Value = '0014872138'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_ADMIN_PIN'; Value = '888888'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_QR'; Value = '111993413628001787216027'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_LOCAL_DEMO'; Value = 'local-demo:'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_LOCAL_DEMO_BANNER'; Value = $localDemoBanner; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_BOOTSTRAP_TEST_KEY'; Value = 'TEST_BOOTSTRAP_KEY_DO_NOT_SHIP'; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_BOOTSTRAP_CREDENTIAL_PLAINTEXT'; Value = $bootstrapCredentialPlaintext; AllowedDescriptor = $null },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_COMMAND'; Value = '8A 01 01 11 9B'; AllowedDescriptor = 'Lcom/codex/lockertest/AdminSerialActivity;' },
        [pscustomobject]@{ Rule = 'DEX_FORBIDDEN_COMMAND'; Value = '8A0101119B'; AllowedDescriptor = 'Lcom/codex/lockertest/AdminSerialActivity;' }
    )
    $descriptorMarkers = 0
    $parsedDescriptors = 0
    $appDescriptors = 0
    $appStringRecords = 0
    $faceBuildVariantCount = 0
    $productionFaceBuildVariantCount = 0
    $appEvidence = [System.Collections.Generic.List[string]]::new()
    foreach ($dexFile in $dexFiles) {
        $rawDump = Join-Path $resolvedScratch ($dexFile.BaseName + '.dump')
        $rawError = Join-Path $resolvedScratch ($dexFile.BaseName + '.stderr')
        try {
            if ($dexFile.FullName.Contains('"')) {
                Stop-Audit 'APK_DEXDUMP_FAILED'
            }
            $dexdumpStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
            $dexdumpStartInfo.FileName = $dexdump
            $dexdumpStartInfo.Arguments =
                '-a -d -l plain "' + $dexFile.FullName + '"'
            $dexdumpStartInfo.UseShellExecute = $false
            $dexdumpStartInfo.CreateNoWindow = $true
            $dexdumpStartInfo.RedirectStandardOutput = $true
            $dexdumpStartInfo.RedirectStandardError = $true
            $dexdumpStartInfo.StandardOutputEncoding = $strictUtf8NoBom
            $dexdumpStartInfo.StandardErrorEncoding = $strictUtf8NoBom
            $dexdumpProcess = [System.Diagnostics.Process]::new()
            $dexdumpProcess.StartInfo = $dexdumpStartInfo
            try {
                if (-not $dexdumpProcess.Start()) {
                    Stop-Audit 'APK_DEXDUMP_FAILED'
                }
                $dexdumpStdoutTask = $dexdumpProcess.StandardOutput.ReadToEndAsync()
                $dexdumpStderrTask = $dexdumpProcess.StandardError.ReadToEndAsync()
                $dexdumpProcess.WaitForExit()
                $dexdumpExit = $dexdumpProcess.ExitCode
                $dexdumpStdout = $dexdumpStdoutTask.GetAwaiter().GetResult()
                $dexdumpStderr = $dexdumpStderrTask.GetAwaiter().GetResult()
            } finally {
                $dexdumpProcess.Dispose()
            }
            [System.IO.File]::WriteAllText(
                $rawDump, $dexdumpStdout, $utf8NoBom)
            [System.IO.File]::WriteAllText(
                $rawError, $dexdumpStderr, $utf8NoBom)
            if ($dexdumpExit -ne 0 -or
                    -not (Test-Path -LiteralPath $rawDump -PathType Leaf)) {
                Stop-Audit 'APK_DEXDUMP_FAILED'
            }

            $activeClassNumber = $null
            $seenClassNumbers = [System.Collections.Generic.HashSet[int]]::new()
            $classBlock = [System.Collections.Generic.List[string]]::new()
            $reader = [System.IO.File]::OpenText($rawDump)
            try {
                while (($line = $reader.ReadLine()) -ne $null) {
                    if ($line -cmatch '^Class #') {
                        if ($line -cnotmatch
                                '^Class #([0-9]+)(?:\s+annotations:|\s+-)\s*$') {
                            Stop-Audit 'APK_DEXDUMP_CLASS_FORMAT'
                        }
                        $lineClassNumber = [int]$Matches[1]
                        if ($activeClassNumber -eq $null -or
                                $lineClassNumber -ne $activeClassNumber) {
                            if ($activeClassNumber -ne $null) {
                                $inspection = Inspect-DexClassBlock `
                                    $classBlock.ToArray() $forbiddenValues
                                $descriptorMarkers++
                                $parsedDescriptors++
                                if ($inspection.IsApp) {
                                    $appDescriptors++
                                    $appStringRecords += $inspection.StringRecordCount
                                    $appEvidence.Add(
                                        "CLASS_DESCRIPTOR_SHA256=$($inspection.DescriptorHash)")
                                    foreach ($recordHash in $inspection.StringRecordHashes) {
                                        $appEvidence.Add(
                                            "STRING_RECORD_SHA256=$recordHash")
                                    }
                                    if ($inspection.IsFaceBuildVariant) {
                                        $faceBuildVariantCount++
                                        if ($inspection.FaceBuildVariantIsProduction) {
                                            $productionFaceBuildVariantCount++
                                        }
                                    }
                                }
                                $classBlock.Clear()
                            }
                            if (-not $seenClassNumbers.Add($lineClassNumber)) {
                                Stop-Audit 'APK_DEXDUMP_CLASS_FORMAT'
                            }
                            $activeClassNumber = $lineClassNumber
                        }
                    }
                    if ($activeClassNumber -ne $null) {
                        $classBlock.Add($line)
                    }
                }
            }
            finally {
                $reader.Dispose()
            }
            if ($activeClassNumber -eq $null -or $classBlock.Count -eq 0) {
                Stop-Audit 'APK_DEXDUMP_CLASS_FORMAT'
            }
            $inspection = Inspect-DexClassBlock `
                $classBlock.ToArray() $forbiddenValues
            $descriptorMarkers++
            $parsedDescriptors++
            if ($inspection.IsApp) {
                $appDescriptors++
                $appStringRecords += $inspection.StringRecordCount
                $appEvidence.Add(
                    "CLASS_DESCRIPTOR_SHA256=$($inspection.DescriptorHash)")
                foreach ($recordHash in $inspection.StringRecordHashes) {
                    $appEvidence.Add("STRING_RECORD_SHA256=$recordHash")
                }
                if ($inspection.IsFaceBuildVariant) {
                    $faceBuildVariantCount++
                    if ($inspection.FaceBuildVariantIsProduction) {
                        $productionFaceBuildVariantCount++
                    }
                }
            }
        } finally {
            if (Test-Path -LiteralPath $rawDump -PathType Leaf) {
                Remove-Item -LiteralPath $rawDump -Force
            }
            if (Test-Path -LiteralPath $rawError -PathType Leaf) {
                Remove-Item -LiteralPath $rawError -Force
            }
        }
    }
    if ($descriptorMarkers -eq 0 -or $parsedDescriptors -ne $descriptorMarkers) {
        Stop-Audit 'APK_DEXDUMP_DESCRIPTOR_FORMAT'
    }
    if ($appDescriptors -eq 0 -or $appDescriptors -ne $classNames.Count) {
        Stop-Audit 'APK_DEX_APP_CLASS_COUNT_MISMATCH'
    }
    if ($faceBuildVariantCount -ne 1 -or
            $productionFaceBuildVariantCount -ne 1) {
        Stop-Audit 'APK_FACE_BUILD_VARIANT_INVALID'
    }
    $appEvidence.Insert(0, "APP_CLASS_COUNT=$appDescriptors")
    $appEvidence.Insert(1, "APP_STRING_RECORD_COUNT=$appStringRecords")
    $appEvidence.Insert(2, 'FACE_BUILD_VARIANT_PRODUCTION=true')
    Write-Evidence 'dex-app-code.txt' $appEvidence.ToArray()

    $signatureResult = Invoke-NativeLines {
        & $apksigner verify --verbose --print-certs $auditApk 2>&1
    }
    $signatureLines = @($signatureResult.Lines)
    if ($signatureResult.ExitCode -ne 0) {
        Stop-Audit 'APK_SIGNATURE_INVALID'
    }
    foreach ($signatureContract in @(
            'Verified using v1 scheme (JAR signing): true',
            'Verified using v2 scheme (APK Signature Scheme v2): true',
            'Verified using v3 scheme (APK Signature Scheme v3): true',
            'Verified using v3.1 scheme (APK Signature Scheme v3.1): false',
            'Verified using v4 scheme (APK Signature Scheme v4): false',
            'Number of signers: 1',
            'Signer #1 certificate SHA-256 digest: 8a04a1200db74368bf67d8982990d56d81d05c3197df2ce3806be9e95f8362e3')) {
        Assert-UniqueExactLine $signatureLines $signatureContract `
            'APK_SIGNATURE_CONTRACT_INVALID'
    }
    if (@($signatureLines | Where-Object {
        $_ -match '^Number of signers:'
    }).Count -ne 1) {
        Stop-Audit 'SIGNATURE_SIGNER_COUNT_INVALID'
    }
    if (@($signatureLines | Where-Object {
        $_ -match '^Signer #[0-9]+ certificate SHA-256 digest:'
    }).Count -ne 1) {
        Stop-Audit 'SIGNATURE_CERTIFICATE_COUNT_INVALID'
    }
    Write-Evidence 'signature.txt' @(
        'V1=true', 'V2=true', 'V3=true', 'V3_1=false', 'V4=false',
        'SIGNERS=1',
        'CERT_SHA256=8A04A1200DB74368BF67D8982990D56D81D05C3197DF2CE3806BE9E95F8362E3'
    )

    $manifestResult = Invoke-NativeLines {
        & $aapt2 dump xmltree --file AndroidManifest.xml $auditApk 2>&1
    }
    $manifestLines = @($manifestResult.Lines)
    if ($manifestResult.ExitCode -ne 0) {
        Stop-Audit 'APK_MANIFEST_SCAN_FAILED'
    }
    $manifestDocument = Convert-AaptXmlTreeToDocument $manifestLines
    Write-Evidence 'manifest.txt' @($manifestLines | ForEach-Object { [string]$_ })
    Assert-ManifestContract $manifestDocument.OuterXml

    $fileResult = Invoke-NativeLines {
        & $aapt list $auditApk 2>&1
    }
    $fileLines = @($fileResult.Lines)
    if ($fileResult.ExitCode -ne 0) {
        Stop-Audit 'APK_FILE_SCAN_FAILED'
    }
    $normalizedFileLines = @($fileLines | ForEach-Object {
        ([string]$_).Trim().TrimStart('/').Replace('\', '/')
    } | Where-Object { $_ })
    Assert-SameSet $normalizedFileLines $archiveFiles 'APK_FILE_INVENTORY_INVALID'
    $normalizedFiles = @($normalizedFileLines | Sort-Object -Unique -CaseSensitive)
    Write-Evidence 'files.txt' $normalizedFiles
    foreach ($requiredArchiveFile in @('AndroidManifest.xml', 'classes.dex', 'resources.arsc')) {
        if ($normalizedFiles -cnotcontains $requiredArchiveFile) {
            Stop-Audit 'APK_REQUIRED_FILE_MISSING'
        }
    }

    $expectedNative = @(
        'lib/armeabi-v7a/libserial_port.so',
        'lib/armeabi-v7a/libliantian.so',
        'lib/armeabi-v7a/libc++_shared.so',
        'lib/armeabi-v7a/libbdface_sdk.so',
        'lib/armeabi-v7a/libbd_facecollect_unifylicense.so',
        'lib/armeabi-v7a/libaikl_cluster_arm.so',
        'lib/armeabi-v7a/libaikl_calc_arm.so',
        'lib/arm64-v8a/libserial_port.so',
        'lib/arm64-v8a/libliantian.so',
        'lib/arm64-v8a/libc++_shared.so',
        'lib/arm64-v8a/libbdface_sdk.so',
        'lib/arm64-v8a/libbd_facecollect_unifylicense.so',
        'lib/arm64-v8a/libaikl_calc_arm.so'
    )
    $actualNative = @($normalizedFiles | Where-Object { $_ -match '^lib/[^/]+/[^/]+$' })
    Assert-SameSet $actualNative $expectedNative 'APK_NATIVE_SET_INVALID'

    $expectedModels = @(
        [pscustomobject]@{ Path = 'assets/face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1'; Size = 948451; Sha256 = '080B7123EA0B01AFDB7D972916272D702338C9F41A89F2F7CF402F257EAA32B1' },
        [pscustomobject]@{ Path = 'assets/face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5'; Size = 1233870; Sha256 = '22205B4AF4D15C7B553481D0B5FCB99B1FA3B964FB813C715DEB7B2B4901D4A7' },
        [pscustomobject]@{ Path = 'assets/face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4'; Size = 2792512; Sha256 = 'A6C478F38C40448F0640BA3144DD29A35BAE09E1B6C70983E140B51F266D398F' },
        [pscustomobject]@{ Path = 'assets/face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3'; Size = 133739; Sha256 = '16B33D67D648D02284B1B91FB434B7E96D84051CF1A60A29E00C7E07002E1FB2' },
        [pscustomobject]@{ Path = 'assets/face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3'; Size = 391504; Sha256 = '422AF339B14F61E9D505E056B1B3771AE29147813A0B6501A1E99437D9AC4AF7' },
        [pscustomobject]@{ Path = 'assets/face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1'; Size = 1118807; Sha256 = 'EE5E69447D0AB603BCB79FAC43FDBB71565F44740734EDCC2DA4662FDF0C6E0A' },
        [pscustomobject]@{ Path = 'assets/face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1'; Size = 2089103; Sha256 = '015A0F9C54338DAF401266FEDFC19FE9FCF57E7F553CAE6CAA4D3E07C8D75A38' }
    )
    $actualModels = @($normalizedFiles | Where-Object {
        $_.StartsWith('assets/face-sdk-models/', [StringComparison]::Ordinal)
    })
    Assert-SameSet $actualModels @($expectedModels | ForEach-Object { $_.Path }) `
        'APK_MODEL_SET_INVALID'
    $modelArchive = [System.IO.Compression.ZipFile]::OpenRead($apkZip)
    try {
        foreach ($entry in $modelArchive.Entries) {
            if ($entry.FullName.Contains('\')) {
                Stop-Audit 'APK_MODEL_PATH_INVALID'
            }
        }
        foreach ($model in $expectedModels) {
            $matchingEntries = @($modelArchive.Entries | Where-Object {
                $_.FullName -ceq $model.Path
            })
            if ($matchingEntries.Count -ne 1) {
                Stop-Audit 'APK_MODEL_FILE_MISSING'
            }
            $modelEntry = $modelArchive.GetEntry($model.Path)
            $modelStream = $modelEntry.Open()
            $modelSha256 = [System.Security.Cryptography.SHA256]::Create()
            try {
                $modelHash = [BitConverter]::ToString(
                    $modelSha256.ComputeHash($modelStream)).Replace('-', '')
            } finally {
                $modelSha256.Dispose()
                $modelStream.Dispose()
            }
            if ($modelEntry.Length -ne $model.Size -or $modelHash -cne $model.Sha256) {
                Stop-Audit 'APK_MODEL_PIN_INVALID'
            }
        }
    } finally {
        $modelArchive.Dispose()
    }

    $resourceResult = Invoke-NativeLines {
        & $aapt2 dump resources $auditApk 2>&1
    }
    $resourceLines = @($resourceResult.Lines)
    if ($resourceResult.ExitCode -ne 0) {
        Stop-Audit 'APK_RESOURCE_SCAN_FAILED'
    }
    $resourceText = ($resourceLines | ForEach-Object { [string]$_ }) -join "`n"
    Write-Evidence 'resources.txt' @($resourceLines | ForEach-Object { [string]$_ })
    if ($resourceText -notmatch '(?m)^Binary APK$' -or
            $resourceText -notmatch '(?m)^Package name=com\.codex\.lockertest\b') {
        Stop-Audit 'APK_RESOURCE_TABLE_INVALID'
    }
    Assert-CompiledResourceSecurity $resourceText

    $passLines = @(
        'PRODUCTION_SOURCE_SECURITY=PASS',
        'PRODUCTION_APK_DEX=PASS',
        'PRODUCTION_APK_SIGNATURE=PASS',
        'PRODUCTION_APK_MANIFEST=PASS',
        'PRODUCTION_APK_PERMISSIONS=PASS',
        'PRODUCTION_APK_FILES=PASS',
        'PRODUCTION_APK_RESOURCES=PASS',
        'PRODUCTION_APK_NATIVE=PASS',
        'PRODUCTION_APK_MODELS=PASS',
        'PRODUCTION_APK_ISOLATION=PASS'
    )
    Publish-Evidence
} catch {
    $failureCode = [string]$_.Exception.Message
    if ($failureCode -match '^PRODUCTION_APK_FORBIDDEN=[A-Za-z0-9_]+$' -or
            $failureCode -match '^PRODUCTION_SOURCE_FORBIDDEN=[A-Z0-9_]+$') {
        $failureLine = $failureCode
    } elseif ($failureCode -match '^[A-Z][A-Z0-9_]+$') {
        $failureLine = "PRODUCTION_APK_AUDIT_FAILED=$failureCode"
    } else {
        $failureLine = 'PRODUCTION_APK_AUDIT_FAILED=UNEXPECTED'
    }
} finally {
    if ($scratchCreated) {
        try {
            Assert-NoReparseAncestorChain $resolvedScratch
            Assert-PlainDirectory $resolvedProjectRoot
            Assert-PlainDirectory $resolvedManualBuildRoot
            Assert-PlainDirectory $resolvedV17Root
            Assert-PlainDirectory $resolvedAuditRoot
            if (-not (Test-Path -LiteralPath $resolvedScratch -PathType Container)) {
                Stop-Audit 'AUDIT_SCRATCH_MISSING_BEFORE_CLEANUP'
            }
            $scratchBeforeRemoval = Get-FullPath (
                (Resolve-Path -LiteralPath $resolvedScratch).Path)
            if (-not [string]::Equals(
                    $scratchBeforeRemoval,
                    $resolvedScratch,
                    [System.StringComparison]::OrdinalIgnoreCase) -or
                    -not [string]::Equals(
                        (Split-Path -Parent $scratchBeforeRemoval),
                        $resolvedAuditRoot,
                        [System.StringComparison]::OrdinalIgnoreCase) -or
                    (Test-ReparsePoint $resolvedScratch)) {
                Stop-Audit 'AUDIT_SCRATCH_INVALID_BEFORE_CLEANUP'
            }
            Remove-Item -LiteralPath $resolvedScratch -Recurse -Force
        } catch {
            $passLines = @()
            $failureLine = 'PRODUCTION_APK_AUDIT_FAILED=AUDIT_SCRATCH_CLEANUP_FAILED'
        }
    }
}

if ($failureLine) {
    Write-Output $failureLine
    exit 1
}
$passLines | Write-Output
exit 0
