param(
    [ValidateSet('localDemo','production','all')]
    [string]$Variant = 'all',
    [string]$DeliveryRoot
)

$ErrorActionPreference = "Stop"
$nativeUtf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $nativeUtf8
[Console]::OutputEncoding = $nativeUtf8
$OutputEncoding = $nativeUtf8
$projectRoot = Split-Path -Parent $PSScriptRoot
$defaultOutputsRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '..\..\outputs'))
$explicitDeliveryRoot = $PSBoundParameters.ContainsKey('DeliveryRoot')
if ($explicitDeliveryRoot) {
    if ([string]::IsNullOrWhiteSpace($DeliveryRoot)) {
        throw '显式交付目录不能为空'
    }
    $resolvedDeliveryRoot = [System.IO.Path]::GetFullPath($DeliveryRoot)
    $requiredOutputsPrefix = $defaultOutputsRoot.TrimEnd('\', '/') +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedDeliveryRoot.StartsWith(
            $requiredOutputsPrefix,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'DELIVERY_ROOT_OUTSIDE_OUTPUTS: 显式交付目录必须位于 outputs 工作区的独立子目录内'
    }
    if (Test-Path -LiteralPath $resolvedDeliveryRoot -PathType Leaf) {
        throw '显式交付目录不能是文件'
    }
} else {
    $resolvedDeliveryRoot = $defaultOutputsRoot
}
$sdkRoot = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$buildTools = Join-Path $sdkRoot "build-tools\35.0.0"
$jbrHome = "C:\Users\Administrator\.jdks\jbr-21.0.11"
$java = Join-Path $jbrHome "bin\java.exe"
$javac = Join-Path $jbrHome "bin\javac.exe"
$jar = Join-Path $jbrHome "bin\jar.exe"
$androidJar = Join-Path $sdkRoot "platforms\android-34\android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$aapt = Join-Path $buildTools "aapt.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$junit = Join-Path $projectRoot "manual-build\tooling\junit-4.13.2.jar"
$hamcrest = Join-Path $projectRoot "manual-build\tooling\hamcrest-core-1.3.jar"
$keystore = Join-Path $projectRoot "manual-build\debug.keystore"
$manualBuildRoot = Join-Path $projectRoot "manual-build"
$buildRoot = Join-Path $manualBuildRoot 'v17'
$localDemoBuildRoot = Join-Path $buildRoot 'localDemo'
$productionBuildRoot = Join-Path $buildRoot 'production'
$aarPath = Join-Path $projectRoot "app\libs\FaceSDK_8.5_20241220-release.aar"
$palmAarPath = Join-Path $projectRoot "app\libs\JXPalm-release-2.2.17_RK356x_build3.aar"
$localDemoDeliverable = Join-Path $resolvedDeliveryRoot '智能更衣柜-v21-localDemo.apk'
$productionDeliverable = Join-Path $resolvedDeliveryRoot '智能更衣柜-v21-production测试版.apk'
$v14Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-百度RGB活体可选版-v14.apk"
$v6Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-ABC分区联调版-v6.apk"
$v7Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-动态选柜联调版-v7.apk"
$v8Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-4x8动态选柜联调版-v8.apk"
$v9Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-ID卡联调版-v9.apk"
$v10Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-ID卡联调版-v10.apk"
$v11Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-ID卡二维码联调版-v11.apk"
$v12Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-ID卡二维码8秒恢复联调版-v12.apk"
$v12BuildApk = Join-Path $projectRoot "manual-build\v12\apk\smart-locker-kiosk-v12.apk"
$v13Deliverable = Join-Path $projectRoot "..\..\outputs\智能更衣柜-百度人脸本地联调版-v13.apk"
$frozenManifest = Join-Path $projectRoot "manual-build\v7-baseline\frozen-source.sha256"
$adminBaseline = Join-Path $projectRoot "manual-build\task7-baseline\MainActivity.java.baseline"
$expectedFrozenManifestSha256 = "52176132906A473AD2731DFD944F3F881511637F482D12B8E6D28D41E0AA1EF0"
$expectedV6Sha256 = "B26449E570566DBD00AAE7A847A6FE1F3313E8225BA834CD3D6E52EB6FC3B9E0"
$expectedV6Size = 135873
$expectedV7Sha256 = "AD56907CB7533173B4AD4D3FEF6A42ABE0D7D892535D27BBC51B8CAC4B371155"
$expectedV7Size = 144065
$expectedV8Sha256 = "2ED4B1191BCC01DD18E7130EF0764E1451FF8E577EA57163D46993025C9A92BB"
$expectedV8Size = 144065
$expectedV9Sha256 = "102C62AF2F7FE18AB3265AC8D1F1BDD4E1614B191D8CC42505D9BAF6E0001F1B"
$expectedV9Size = 148161
$expectedV10Sha256 = "34E822B5CEC8E88BFB369320E6ABA9125CF057168656461D07F9511A4D6477ED"
$expectedV10Size = 148161
$expectedV11Sha256 = "4CA97921C63644FE46A543D5C27957CA5E8318364389C76E457C04074C715F4C"
$expectedV11Size = 148161
$expectedV12Sha256 = "ED12E785B9DF67D3683D434E438D9AC4938719A7899148BB60B1364D36AEBF17"
$expectedV12Size = 148161
$expectedV13Sha256 = "7D04A37C8BF122602EF3C7449810DDD4761471347F8AA1D775B177DC698F1C12"
$expectedV13Size = 12736705
$expectedV14Sha256 = "77DAF096711413D7DFF9B4175210CF2CDDD350489F0FDCC2A2B57D9EC751DB51"
$expectedV14Size = 14833996
$expectedAdminBaselineSha256 = "F76BCC2DA49DED2C702411B555D82F89B026CF02883F533D1F3842655428F457"
$expectedSigningCertificateSha256 = "8a04a1200db74368bf67d8982990d56d81d05c3197df2ce3806be9e95f8362e3"
$activationCodePattern = '(?<![A-Z0-9])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}(?![A-Z0-9])'
$v15ProjectRoot = Join-Path (Split-Path -Parent $projectRoot) "smart-locker-serial-test-v15"
$v15SourceManifest = Join-Path $projectRoot ".superpowers\baseline\v15-source.sha256"
$v15ApkManifest = Join-Path $projectRoot ".superpowers\baseline\v15-apk.sha256"
$v15OutputsRoot = Join-Path (Split-Path -Parent (Split-Path -Parent $projectRoot)) "outputs"

if ($explicitDeliveryRoot) {
    $selectedDeliverables = @()
    if ($Variant -ceq 'localDemo' -or $Variant -ceq 'all') {
        $selectedDeliverables += $localDemoDeliverable
    }
    if ($Variant -ceq 'production' -or $Variant -ceq 'all') {
        $selectedDeliverables += $productionDeliverable
    }
    foreach ($selectedDeliverable in $selectedDeliverables) {
        if (Test-Path -LiteralPath $selectedDeliverable) {
            throw "DELIVERY_TARGET_EXISTS: 显式交付目标已存在，拒绝覆盖: $selectedDeliverable"
        }
    }
}

$faceArtifacts = @(
    [pscustomobject]@{ RelativePath = "app\libs\FaceSDK_8.5_20241220-release.aar"; Sha256 = "E77439F9DC4F530FF739F423EC80DA5D0AA1F5F055155FED58CC0BD1B43487E5"; Size = 6435103; Kind = "AAR" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\detect\detect_rgb-customized-pa-192.model.float32-0.0.18.1"; Sha256 = "080B7123EA0B01AFDB7D972916272D702338C9F41A89F2F7CF402F257EAA32B1"; Size = 948451; Kind = "MODEL" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\align\align_rgb-customized-pa-fast.model.float32-0.7.5.5"; Sha256 = "22205B4AF4D15C7B553481D0B5FCB99B1FA3B964FB813C715DEB7B2B4901D4A7"; Size = 1233870; Kind = "MODEL" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\align\align_rgb-customized-pa-80.model.float32-6.4.14.4"; Sha256 = "A6C478F38C40448F0640BA3144DD29A35BAE09E1B6C70983E140B51F266D398F"; Size = 2792512; Kind = "MODEL" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\blur\blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3"; Sha256 = "16B33D67D648D02284B1B91FB434B7E96D84051CF1A60A29E00C7E07002E1FB2"; Size = 133739; Kind = "MODEL" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\occlusion\occlusion-customized-pa-paddle.model.float32-2.0.7.3"; Sha256 = "422AF339B14F61E9D505E056B1B3771AE29147813A0B6501A1E99437D9AC4AF7"; Size = 391504; Kind = "MODEL" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\best_image\best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1"; Sha256 = "EE5E69447D0AB603BCB79FAC43FDBB71565F44740734EDCC2DA4662FDF0C6E0A"; Size = 1118807; Kind = "MODEL" }
    [pscustomobject]@{ RelativePath = "app\src\main\assets\face-sdk-models\silent_live\liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1"; Sha256 = "015A0F9C54338DAF401266FEDFC19FE9FCF57E7F553CAE6CAA4D3E07C8D75A38"; Size = 2089103; Kind = "MODEL" }
)

function Assert-Tool([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "缺少构建工具: $path"
    }
}

function Assert-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$step 失败，退出码 $LASTEXITCODE"
    }
}

function Resolve-FullPath([string]$path) {
    return [System.IO.Path]::GetFullPath($path).TrimEnd('\')
}

function Get-Sha256ManifestLines([string]$root, [string]$kind) {
    if ($kind -ceq "SOURCE") {
        $manualBuild = Resolve-FullPath (Join-Path $root "manual-build")
        return @(Get-ChildItem -LiteralPath $root -Recurse -File -Force |
            Where-Object { -not $_.FullName.StartsWith($manualBuild + '\', [System.StringComparison]::OrdinalIgnoreCase) } |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
                '{0} *{1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant(), $relative
            })
    }
    if ($kind -ceq "APK") {
        return @(Get-ChildItem -LiteralPath $root -File -Filter '*v15*.apk' |
            Sort-Object FullName |
            ForEach-Object {
                $relative = $_.FullName.Substring($root.Length + 1).Replace('\', '/')
                '{0} *{1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant(), $relative
            })
    }
    throw "未知的 v15 哈希清单类型: $kind"
}

function Assert-V15Manifest([string]$manifestPath, [string]$root, [string]$kind, [string]$phase) {
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "缺少 v15 $kind 哈希基线: $manifestPath"
    }
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        throw "缺少受保护的 v15 $kind 目录: $root"
    }
    $expected = @([System.IO.File]::ReadAllLines($manifestPath, [System.Text.UTF8Encoding]::new($false)) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $actual = @(Get-Sha256ManifestLines (Resolve-FullPath $root) $kind)
    $difference = @(Compare-Object $expected $actual)
    if ($expected.Count -eq 0 -or $difference.Count -ne 0) {
        throw "v15 $kind 哈希基线在 $phase 阶段发生变化"
    }
    Write-Output "V15_${kind}_BASELINE_${phase}=PASS ENTRIES=$($actual.Count)"
}

function Assert-V15Baseline([string]$phase) {
    Assert-V15Manifest $v15SourceManifest $v15ProjectRoot "SOURCE" $phase
    Assert-V15Manifest $v15ApkManifest $v15OutputsRoot "APK" $phase
}

function Assert-PackagingPaths() {
    $resolvedProjectRoot = Resolve-FullPath $projectRoot
    $resolvedManualBuildRoot = Resolve-FullPath $manualBuildRoot
    $resolvedBuildRoot = Resolve-FullPath $buildRoot
    $resolvedLocalDemoBuildRoot = Resolve-FullPath $localDemoBuildRoot
    $resolvedProductionBuildRoot = Resolve-FullPath $productionBuildRoot
    $expectedBuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v17")
    $forbiddenV16BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v16")
    $forbiddenV15BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v15")
    $forbiddenV6BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v6")
    $forbiddenV7BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v7")
    $forbiddenV8BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v8")
    $forbiddenV9BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v9")
    $forbiddenV10BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v10")
    $forbiddenV11BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v11")
    $forbiddenV12BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v12")
    $forbiddenV13BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v13")
    $forbiddenV14BuildRoot = Resolve-FullPath (Join-Path $projectRoot "manual-build\v14")
    $resolvedLocalDemoSignedApk = Resolve-FullPath (Join-Path $localDemoBuildRoot "apk\smart-locker-kiosk-v17-localDemo.apk")
    $resolvedProductionSignedApk = Resolve-FullPath (Join-Path $productionBuildRoot "apk\smart-locker-kiosk-v17-production.apk")
    $resolvedLocalDemoDeliverable = Resolve-FullPath $localDemoDeliverable
    $resolvedProductionDeliverable = Resolve-FullPath $productionDeliverable
    $resolvedV6Deliverable = Resolve-FullPath $v6Deliverable
    $resolvedV7Deliverable = Resolve-FullPath $v7Deliverable
    $resolvedV8Deliverable = Resolve-FullPath $v8Deliverable
    $resolvedV9Deliverable = Resolve-FullPath $v9Deliverable
    $resolvedV10Deliverable = Resolve-FullPath $v10Deliverable
    $resolvedV11Deliverable = Resolve-FullPath $v11Deliverable
    $resolvedV12Deliverable = Resolve-FullPath $v12Deliverable
    $resolvedV13Deliverable = Resolve-FullPath $v13Deliverable
    $resolvedV14Deliverable = Resolve-FullPath $v14Deliverable

    if (-not [string]::Equals($resolvedBuildRoot, $expectedBuildRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "v17 构建目录不是预期的隔离目录: $resolvedBuildRoot"
    }
    if ([string]::Equals($resolvedBuildRoot, $forbiddenV16BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV15BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV6BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV7BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV8BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV9BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV10BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV11BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV12BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV13BuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($resolvedBuildRoot, $forbiddenV14BuildRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "禁止使用 v6/v7/v8/v9/v10/v11/v12/v13/v14/v15/v16 构建目录: $resolvedBuildRoot"
    }
    if (-not [string]::Equals((Split-Path -Parent $resolvedBuildRoot), $resolvedManualBuildRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "v17 构建目录不在 manual-build 的直接子目录中: $resolvedBuildRoot"
    }
    if (-not $resolvedLocalDemoSignedApk.StartsWith($resolvedLocalDemoBuildRoot + '\', [System.StringComparison]::OrdinalIgnoreCase) -or
            -not $resolvedProductionSignedApk.StartsWith($resolvedProductionBuildRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "已签名源 APK 不在对应的 v17 variant 构建目录内"
    }
    $v17Deliverables = @($resolvedLocalDemoDeliverable, $resolvedProductionDeliverable)
    $historicalDeliverables = @(
        $resolvedV6Deliverable, $resolvedV7Deliverable, $resolvedV8Deliverable,
        $resolvedV9Deliverable, $resolvedV10Deliverable, $resolvedV11Deliverable,
        $resolvedV12Deliverable, $resolvedV13Deliverable, $resolvedV14Deliverable)
    if ([string]::Equals($resolvedLocalDemoDeliverable, $resolvedProductionDeliverable, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "v17 localDemo 与 production 交付路径不得相同"
    }
    foreach ($resolvedDeliverable in $v17Deliverables) {
        if (@($historicalDeliverables | Where-Object {
                    [string]::Equals($_, $resolvedDeliverable, [System.StringComparison]::OrdinalIgnoreCase)
                }).Count -ne 0) {
        throw "v17 与 v6/v7/v8/v9/v10/v11/v12/v13/v14 交付路径不得相同"
        }
    }
    if (-not [string]::Equals($resolvedProjectRoot, (Resolve-FullPath $projectRoot), [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "项目根目录解析不稳定"
    }
    Write-Output "PATH_GUARD=PASS BUILD_ROOT=$resolvedBuildRoot DELIVERY_DISTINCT=True"
}

function Assert-V6Deliverable([string]$phase) {
    $resolvedV6Deliverable = Resolve-FullPath $v6Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV6Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v6 APK: $resolvedV6Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV6Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV6Deliverable).Hash
    if ($hash -cne $expectedV6Sha256) {
        throw "受保护的 v6 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV6Size) {
        throw "受保护的 v6 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V6_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V7Deliverable([string]$phase) {
    $resolvedV7Deliverable = Resolve-FullPath $v7Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV7Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v7 APK: $resolvedV7Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV7Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV7Deliverable).Hash
    if ($hash -cne $expectedV7Sha256) {
        throw "受保护的 v7 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV7Size) {
        throw "受保护的 v7 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V7_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V8Deliverable([string]$phase) {
    $resolvedV8Deliverable = Resolve-FullPath $v8Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV8Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v8 APK: $resolvedV8Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV8Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV8Deliverable).Hash
    if ($hash -cne $expectedV8Sha256) {
        throw "受保护的 v8 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV8Size) {
        throw "受保护的 v8 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V8_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V9Deliverable([string]$phase) {
    $resolvedV9Deliverable = Resolve-FullPath $v9Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV9Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v9 APK: $resolvedV9Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV9Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV9Deliverable).Hash
    if ($hash -cne $expectedV9Sha256) {
        throw "受保护的 v9 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV9Size) {
        throw "受保护的 v9 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V9_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V10Deliverable([string]$phase) {
    $resolvedV10Deliverable = Resolve-FullPath $v10Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV10Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v10 APK: $resolvedV10Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV10Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV10Deliverable).Hash
    if ($hash -cne $expectedV10Sha256) {
        throw "受保护的 v10 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV10Size) {
        throw "受保护的 v10 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V10_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V11Deliverable([string]$phase) {
    $resolvedV11Deliverable = Resolve-FullPath $v11Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV11Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v11 APK: $resolvedV11Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV11Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV11Deliverable).Hash
    if ($hash -cne $expectedV11Sha256) {
        throw "受保护的 v11 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV11Size) {
        throw "受保护的 v11 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V11_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V12Deliverable([string]$phase) {
    $protectedV12Apks = @(
        (Resolve-FullPath $v12BuildApk),
        (Resolve-FullPath $v12Deliverable)
    )
    foreach ($protectedV12Apk in $protectedV12Apks) {
        if (-not (Test-Path -LiteralPath $protectedV12Apk -PathType Leaf)) {
            throw "缺少受保护的 v12 APK: $protectedV12Apk"
        }
        $item = Get-Item -LiteralPath $protectedV12Apk
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $protectedV12Apk).Hash
        if ($hash -cne $expectedV12Sha256) {
            throw "受保护的 v12 APK 哈希不正确: $protectedV12Apk -> $hash"
        }
        if ($item.Length -ne $expectedV12Size) {
            throw "受保护的 v12 APK 大小不正确: $protectedV12Apk -> $($item.Length)"
        }
        Write-Output "V12_$($phase)_PATH=$protectedV12Apk SHA256=$hash SIZE_BYTES=$($item.Length)"
    }
}

function Assert-V13Deliverable([string]$phase) {
    $resolvedV13Deliverable = Resolve-FullPath $v13Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV13Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v13 APK: $resolvedV13Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV13Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV13Deliverable).Hash
    if ($hash -cne $expectedV13Sha256) {
        throw "受保护的 v13 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV13Size) {
        throw "受保护的 v13 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V13_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-V14Deliverable([string]$phase) {
    $resolvedV14Deliverable = Resolve-FullPath $v14Deliverable
    if (-not (Test-Path -LiteralPath $resolvedV14Deliverable -PathType Leaf)) {
        throw "缺少受保护的 v14 APK: $resolvedV14Deliverable"
    }
    $item = Get-Item -LiteralPath $resolvedV14Deliverable
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedV14Deliverable).Hash
    if ($hash -cne $expectedV14Sha256) {
        throw "受保护的 v14 APK 哈希不正确: $hash"
    }
    if ($item.Length -ne $expectedV14Size) {
        throw "受保护的 v14 APK 大小不正确: $($item.Length)"
    }
    Write-Output "V14_${phase}_SHA256=$hash SIZE_BYTES=$($item.Length)"
}

function Assert-FrozenBaseline([string]$phase) {
    if (-not (Test-Path -LiteralPath $frozenManifest -PathType Leaf)) {
        throw "缺少冻结清单: $frozenManifest"
    }
    $manifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $frozenManifest).Hash
    if ($manifestHash -cne $expectedFrozenManifestSha256) {
        throw "冻结清单自身哈希不正确: $manifestHash"
    }
    $lines = @(Get-Content -LiteralPath $frozenManifest -Encoding UTF8)
    if ($lines.Count -ne 24) {
        throw "冻结清单必须恰好包含 24 条记录，实际为 $($lines.Count)"
    }
    $seenPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    $matches = 0
    $onlineSerialOverrides = 0
    $task8UiOverrides = 0
    foreach ($line in $lines) {
        $record = [System.Text.RegularExpressions.Regex]::Match($line, '^([0-9A-Fa-f]{64})  (.+)$')
        if (-not $record.Success) {
            throw "冻结清单记录格式无效: $line"
        }
        $expectedHash = $record.Groups[1].Value.ToUpperInvariant()
        $relativePath = $record.Groups[2].Value
        if ([System.IO.Path]::IsPathRooted($relativePath)) {
            throw "冻结清单只允许相对路径: $relativePath"
        }
        if (-not $seenPaths.Add($relativePath)) {
            throw "冻结清单路径重复: $relativePath"
        }
        $resolvedPath = Resolve-FullPath (Join-Path $projectRoot ($relativePath.Replace('/', '\')))
        if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
            throw "冻结文件不存在: $relativePath"
        }
        $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedPath).Hash
        if ($actualHash -cne $expectedHash) {
            if ($relativePath -ceq 'app/src/main/java/com/codex/lockertest/serial/SerialGateway.java' -and
                    $actualHash -ceq 'B881BD5858E9B8B410F41968E047139FC3AD5BD60D378ADF43BB7B88B92FD44B' -and
                    (Get-TextSha256 (Get-JavaMethodBody $resolvedPath 'send')) -ceq
                    '5D187CCD45D50F6AADF8FDF70E68D3CEEFF584809A1A83A091CFF094DBB814F8') {
                # Approved addition of sendAuthorized; the original send method remains byte-identical.
                $onlineSerialOverrides++
                continue
            }
            if ($relativePath -ceq 'app/src/main/java/com/codex/lockertest/AdminSerialActivity.java' -or
                    $relativePath -ceq 'app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java') {
                $task8UiOverrides++
                continue
            }
            throw "冻结文件哈希不匹配: $relativePath"
        }
        $matches++
    }
    if ($seenPaths.Count -ne 24 -or $matches -ne 21 -or $task8UiOverrides -ne 2 -or $onlineSerialOverrides -ne 1) {
        throw "冻结清单唯一性或匹配数量不正确"
    }
    Write-Output "FROZEN_${phase}_MANIFEST_SHA256=$manifestHash RECORDS=24 UNIQUE=24 MATCHES=$matches TASK8_UI_OVERRIDES=$task8UiOverrides ONLINE_SERIAL_OVERRIDES=$onlineSerialOverrides PROTECTED_MISMATCHES=0"
}

function Get-JavaMethodBody([string]$path, [string]$methodName) {
    $source = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $escapedName = [System.Text.RegularExpressions.Regex]::Escape($methodName)
    $pattern = "(?m)^\s*(?:private|protected|public)\s+[^{;]+\b$escapedName\s*\([^)]*\)\s*\{"
    $match = [System.Text.RegularExpressions.Regex]::Match($source, $pattern)
    if (-not $match.Success) {
        throw "找不到 Java 方法: $methodName ($path)"
    }
    $openingBrace = $source.IndexOf('{', $match.Index)
    $depth = 0
    for ($index = $openingBrace; $index -lt $source.Length; $index++) {
        if ($source[$index] -eq '{') {
            $depth++
        } elseif ($source[$index] -eq '}') {
            $depth--
            if ($depth -eq 0) {
                return $source.Substring($openingBrace, $index - $openingBrace + 1)
            }
        }
    }
    throw "Java 方法花括号未闭合: $methodName ($path)"
}

function Get-TextSha256([string]$value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($value)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

function Assert-AdminBuilderBaseline([string]$root) {
    $current = Join-Path $root "app\src\main\java\com\codex\lockertest\AdminSerialActivity.java"
    $router = Join-Path $root "app\src\main\java\com\codex\lockertest\ui\zip\ZipAdminScreenRouter.java"
    if (-not (Test-Path -LiteralPath $current) -or -not (Test-Path -LiteralPath $router)) {
        throw "缺少 Task 8 管理员串口实现或 typed router"
    }
    $source = [System.IO.File]::ReadAllText($current, [System.Text.Encoding]::UTF8)
    foreach ($required in @(
        'ZipScreenAsset.ADMIN_SERIAL_DISCONNECTED',
        'ZipAdminScreenRouter.assetForSerial(',
        'SerialRequestGate.TIMEOUT_MILLIS',
        'LockerResponseDetector.Result.SUCCESS',
        'LockerResponseDetector.Result.FAILURE',
        'SERIAL_CONNECTION_POLICY.onManualCloseAccepted()',
        'ZipKioskShell.unit(this, designUnits)',
        'ADMIN_PANEL_LEFT = 20',
        'ADMIN_PANEL_TOP = 105',
        'ADMIN_PANEL_RIGHT = 1260',
        'ADMIN_PANEL_BOTTOM = 710')) {
        if (-not $source.Contains($required)) {
            throw "Task 8 Admin 串口合同缺少: $required"
        }
    }
    foreach ($forbidden in @('detail.startsWith(', 'gateway.dispose(', '.dispose()')) {
        if ($source.Contains($forbidden)) {
            throw "Task 8 Admin 串口合同包含禁止模式: $forbidden"
        }
    }
    $stopBody = Get-JavaMethodBody $current 'onStop'
    if ($stopBody.Contains('closePort(') -or $stopBody.Contains('dispose(')) {
        throw "Admin onStop 不得关闭或销毁进程串口"
    }
    Write-Output "ADMIN_TASK8_TYPED_ROUTING=PASS SHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $current).Hash)"
}

function Assert-CustomerViewNoTechnicalLeaks([string]$root) {
    $customerViewRoot = Join-Path $root "app\src\main\java\com\codex\lockertest\ui"
    $customerViewFiles = Get-ChildItem -LiteralPath $customerViewRoot -Filter *.java -File |
        Sort-Object FullName
    if (-not $customerViewFiles -or $customerViewFiles.Count -eq 0) {
        throw "找不到顾客视图源码: $customerViewRoot"
    }

    $literalPattern = [System.Text.RegularExpressions.Regex]::new(
        '"(?:\\.|[^"\\])*"',
        [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $technicalPattern = [System.Text.RegularExpressions.Regex]::new(
        '(?i)(/dev/|(?<![A-Za-z])tty(?:s|usb|acm)?\d*(?![A-Za-z])|(?<![A-Za-z])hex(?![A-Za-z])|板\s*0?[1-3](?!\d)|^\s*(?:8A|80|82)\s*$|(?<![0-9A-F])(?:8A|80|82)(?=(?:[0-9A-F]{2})+|\s+[0-9A-F]{2}))')
    $literalCount = 0
    foreach ($file in $customerViewFiles) {
        $source = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
        foreach ($literalMatch in $literalPattern.Matches($source)) {
            $literalCount++
            $literal = $literalMatch.Value.Substring(1, $literalMatch.Value.Length - 2)
            if ($technicalPattern.IsMatch($literal)) {
                throw "顾客视图包含技术文案: $($file.Name) -> $literal"
            }
        }
    }
    Write-Output "CUSTOMER_VIEW_TECHNICAL_LEAKAGE=PASS FILES=$($customerViewFiles.Count) LITERALS=$literalCount"
}

function Get-JavaSources([string[]]$roots, [switch]$Pure) {
    $sources = @()
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        $sources += @(Get-ChildItem -LiteralPath $root -Recurse -Filter *.java -File)
    }
    if ($Pure) {
        $sources = @($sources | Where-Object {
            $sourceText = Get-Content -Raw -LiteralPath $_.FullName
            $sourceText -notmatch '(?m)^\s*import\s+android(?:\.|_)'
        })
    }
    return @($sources | Sort-Object FullName -Unique | Select-Object -ExpandProperty FullName)
}

function Invoke-JavacWithArgFile(
        [string]$name,
        [string]$classpath,
        [string]$destination,
        [string[]]$sources,
        [string]$argRoot = $buildRoot) {
    $argFile = Join-Path $argRoot $name
    $quote = {
        param([string]$value)
        # javac argfiles treat backslashes as escape characters (for example, \t).
        # Forward slashes are accepted by the Windows JDK and keep every path literal.
        return '"' + $value.Replace('\', '/').Replace('"', '\"') + '"'
    }
    $arguments = @(
        '-encoding', 'UTF-8',
        '-source', '8',
        '-target', '8',
        '-Xlint:none',
        '-nowarn',
        '-cp', (& $quote $classpath),
        '-d', (& $quote $destination))
    foreach ($source in $sources) {
        $arguments += (& $quote $source)
    }
    [System.IO.File]::WriteAllLines(
        $argFile, $arguments, [System.Text.UTF8Encoding]::new($false))
    & $javac "@$argFile"
    $script:JavacExitCode = $LASTEXITCODE
}

function Assert-FaceArtifacts() {
    if ($faceArtifacts.Count -ne 8) {
        throw "百度人脸固定工件必须恰好为 8 个，实际为 $($faceArtifacts.Count)"
    }
    foreach ($artifact in $faceArtifacts) {
        $path = Join-Path $projectRoot $artifact.RelativePath
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "缺少百度人脸固定工件: $($artifact.RelativePath)"
        }
        $item = Get-Item -LiteralPath $path
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
        if ($hash -cne $artifact.Sha256 -or $item.Length -ne $artifact.Size) {
            throw "百度人脸固定工件不匹配: $($artifact.RelativePath) SHA256=$hash SIZE=$($item.Length)"
        }
        Write-Output "FACE_ARTIFACT_PATH=$($artifact.RelativePath) SHA256=$hash SIZE_BYTES=$($item.Length)"
    }
    $expectedModelPaths = @($faceArtifacts |
        Where-Object { $_.Kind -ceq "MODEL" } |
        ForEach-Object { Resolve-FullPath (Join-Path $projectRoot $_.RelativePath) } |
        Sort-Object)
    $modelRoot = Join-Path $projectRoot "app\src\main\assets\face-sdk-models"
    $actualModelPaths = @()
    if (Test-Path -LiteralPath $modelRoot -PathType Container) {
        $actualModelPaths = @(Get-ChildItem -LiteralPath $modelRoot -Recurse -File |
            ForEach-Object { Resolve-FullPath $_.FullName } |
            Sort-Object)
    }
    $modelDifference = @(Compare-Object $expectedModelPaths $actualModelPaths)
    if ($expectedModelPaths.Count -ne 7 -or $actualModelPaths.Count -ne 7 -or
            $modelDifference.Count -ne 0) {
        throw "face-sdk-models 必须只包含固定的七个模型文件"
    }
    Write-Output "FACE_ARTIFACT_ASSERTIONS=8"
    Write-Output "FACE_MODEL_INVENTORY=PASS EXPECTED=7 ACTUAL=7 EXTRAS=0"
}

function Assert-NoActivationCodeInSource() {
    $sourceFiles = @()
    foreach ($root in @(
            (Join-Path $projectRoot "app"),
            (Join-Path $projectRoot "scripts"))) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            $sourceFiles += @(Get-ChildItem -LiteralPath $root -Recurse -File |
                Where-Object { $_.Extension -in @('.java', '.xml', '.gradle', '.ps1', '.properties', '.md') })
        }
    }
    foreach ($path in @(
            (Join-Path $projectRoot "build.gradle"),
            (Join-Path $projectRoot "settings.gradle"),
            (Join-Path $projectRoot "gradle.properties"),
            (Join-Path $projectRoot "README.md"))) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $sourceFiles += Get-Item -LiteralPath $path
        }
    }
    foreach ($file in @($sourceFiles | Sort-Object FullName -Unique)) {
        $source = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
        if ([System.Text.RegularExpressions.Regex]::IsMatch($source, $activationCodePattern)) {
            throw "源码包含疑似激活码格式: $($file.FullName)"
        }
    }
    Write-Output "ACTIVATION_CODE_SOURCE_SCAN=PASS FILES=$(@($sourceFiles | Sort-Object FullName -Unique).Count)"
}

function Assert-NoActivationCodeInDex([string[]]$dexFiles) {
    foreach ($dexFile in $dexFiles) {
        $dexText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($dexFile))
        if ([System.Text.RegularExpressions.Regex]::IsMatch($dexText, $activationCodePattern)) {
            throw "DEX 包含疑似激活码格式: $dexFile"
        }
    }
    Write-Output "ACTIVATION_CODE_DEX_SCAN=PASS FILES=$($dexFiles.Count)"
}

function Compile-Variant([string]$name, [string]$sourceSet, [string]$variantRoot) {
    Write-Output "VARIANT_BUILD_BEGIN=$name ROOT=$variantRoot"
    $commonTestClasses = Join-Path $variantRoot "common-test-classes"
    $variantTestClasses = Join-Path $variantRoot "$name-test-classes"
    $workspaceRoot = Resolve-FullPath (Join-Path $projectRoot '..\..')
    $testTemp = Join-Path $workspaceRoot ".jvm-test-temp\v17\$name"
    $allowedTestTempRoot = (Resolve-FullPath (
        Join-Path $workspaceRoot '.jvm-test-temp\v17')) + '\'
    if (-not (Resolve-FullPath $testTemp).StartsWith(
            $allowedTestTempRoot,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "JVM 测试临时目录越界: $testTemp"
    }
    $appClasses = Join-Path $variantRoot "app-classes"
    $appClassesJar = Join-Path $variantRoot "app-classes.jar"
    $dexDirectory = Join-Path $variantRoot $(if ($name -ceq 'production') { 'production-dex' } else { 'dex' })
    $compiledResources = Join-Path $variantRoot "compiled-res"
    $generatedSources = Join-Path $variantRoot "generated"
    $packageStage = Join-Path $variantRoot "package-stage"
    $apkDirectory = Join-Path $variantRoot "apk"
    $thirdPartyRoot = Join-Path $variantRoot "third-party\FaceSDK_8.5_20241220-release"
    $aarArchiveZip = Join-Path $variantRoot "third-party\FaceSDK_8.5_20241220-release.zip"
    $aarClassesJar = Join-Path $thirdPartyRoot "classes.jar"
    $licenseJar = Join-Path $thirdPartyRoot "libs\bd_facecollect_unifylicense.jar"
    $liantianJar = Join-Path $thirdPartyRoot "libs\liantian.jar"
    $baseApk = Join-Path $apkDirectory "base-unsigned.apk"
    $unsignedApk = Join-Path $apkDirectory "smart-locker-kiosk-unsigned.apk"
    $alignedApk = Join-Path $apkDirectory "smart-locker-kiosk-aligned.apk"
    $signedApk = Join-Path $apkDirectory $(if ($name -ceq 'production') {
        'smart-locker-kiosk-v17-production.apk'
    } else {
        'smart-locker-kiosk-v17-localDemo.apk'
    })
    $deliverable = $(if ($name -ceq 'production') {
        $productionDeliverable
    } else {
        $localDemoDeliverable
    })

    New-Item -ItemType Directory -Force -Path `
        $commonTestClasses, $variantTestClasses, $testTemp, $appClasses, $dexDirectory, $compiledResources, `
        $generatedSources, $packageStage, $apkDirectory, (Split-Path -Parent $thirdPartyRoot) | Out-Null

    # Keep JVM-created fixture repositories reachable by their child PowerShell
    # processes in managed/sandboxed builds.
    $env:TEMP = $testTemp
    $env:TMP = $testTemp

    Copy-Item -LiteralPath $aarPath -Destination $aarArchiveZip
    Expand-Archive -LiteralPath $aarArchiveZip -DestinationPath $thirdPartyRoot
    foreach ($vendorJar in @($aarClassesJar, $licenseJar, $liantianJar)) {
        if (-not (Test-Path -LiteralPath $vendorJar -PathType Leaf)) {
            throw "AAR 缺少固定 vendor JAR: $vendorJar"
        }
    }
    Write-Output "FACE_AAR_EXTRACTED=$thirdPartyRoot"

    # Palm hardware validation stays in localDemo until its server identity contract is settled.
    $palmRoot = $null
    $palmClassesJar = $null
    if ($name -ceq 'localDemo') {
        $palmHash = (Get-FileHash -LiteralPath $palmAarPath -Algorithm SHA256).Hash
        if ($palmHash -cne '33EE6683F382E81F3B3FBB60389FF5B22C8D24CBD0D74D1A21580591E1720D2C') {
            throw 'PALM_AAR_HASH_MISMATCH'
        }
        $palmRoot = Join-Path $variantRoot 'third-party\JXPalm-2.2.17'
        $palmZip = Join-Path $variantRoot 'third-party\JXPalm-2.2.17.zip'
        Copy-Item -LiteralPath $palmAarPath -Destination $palmZip
        Expand-Archive -LiteralPath $palmZip -DestinationPath $palmRoot
        $palmClassesJar = Join-Path $palmRoot 'classes.jar'
        Assert-Tool $palmClassesJar
        Write-Output "PALM_AAR_LOCAL_DEMO_ONLY=PASS SHA256=$palmHash"
    }

    Write-Output "[1/10] JVM tests for common and $name source sets"
    $mainJavaRoot = Join-Path $projectRoot "app\src\main\java"
    $testRoot = Join-Path $projectRoot "app\src\test\java"
    $variantJavaRoot = Join-Path $projectRoot "app\src\$sourceSet\java"
    $variantTestRoot = Join-Path $projectRoot "app\src\${sourceSet}Test\java"

    $commonPureSources = Get-JavaSources @($mainJavaRoot) -Pure
    $commonTestSources = Get-JavaSources @($testRoot)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    Invoke-JavacWithArgFile "common-javac.args" "$junit;$hamcrest" $commonTestClasses ($commonPureSources + $commonTestSources) $variantRoot
    $compileExit = $script:JavacExitCode
    $ErrorActionPreference = $previousPreference
    if ($compileExit -ne 0) { throw "common JVM 测试源码编译失败，退出码 $compileExit" }
    $commonTestNames = $commonTestSources | ForEach-Object {
        $relativeName = $_.Substring($testRoot.Length).TrimStart('\', '/')
        ($relativeName -replace '\.java$', '').Replace('\', '.').Replace('/', '.')
    }
    Write-Output "COMMON_JVM_PRODUCTION_CLASSES=$($commonPureSources.Count)"
    Write-Output "COMMON_JVM_TEST_CLASSES=$($commonTestNames.Count)"
    & $java "-Dcodex.projectRoot=$projectRoot" -cp "$commonTestClasses;$junit;$hamcrest" org.junit.runner.JUnitCore $commonTestNames
    Assert-Exit "common JUnit"

    if ($name -ceq 'localDemo') {
        $variantPureSources = Get-JavaSources @($mainJavaRoot, $variantJavaRoot) -Pure
        $variantTestSources = Get-JavaSources @($variantTestRoot)
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        Invoke-JavacWithArgFile "local-demo-javac.args" "$junit;$hamcrest" $variantTestClasses ($variantPureSources + $variantTestSources) $variantRoot
        $compileExit = $script:JavacExitCode
        $ErrorActionPreference = $previousPreference
        if ($compileExit -ne 0) { throw "localDemo JVM 测试源码编译失败，退出码 $compileExit" }
        $variantTestNames = $variantTestSources | ForEach-Object {
            $relativeName = $_.Substring($variantTestRoot.Length).TrimStart('\', '/')
            ($relativeName -replace '\.java$', '').Replace('\', '.').Replace('/', '.')
        }
        Write-Output "LOCAL_DEMO_JVM_PRODUCTION_CLASSES=$($variantPureSources.Count)"
        Write-Output "LOCAL_DEMO_JVM_TEST_CLASSES=$($variantTestNames.Count)"
        & $java -cp "$variantTestClasses;$junit;$hamcrest" org.junit.runner.JUnitCore $variantTestNames
        Assert-Exit "localDemo JUnit"
    }

    Write-Output "[2/10] Android Java compile for $name"
    $androidCompileClasspath = "$androidJar;$aarClassesJar;$licenseJar;$liantianJar"
    if ($palmClassesJar) { $androidCompileClasspath += ";$palmClassesJar" }
    $appSources = Get-JavaSources @($mainJavaRoot, $variantJavaRoot)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    Invoke-JavacWithArgFile "$name-android-javac.args" $androidCompileClasspath $appClasses $appSources $variantRoot
    $compileExit = $script:JavacExitCode
    $ErrorActionPreference = $previousPreference
    if ($compileExit -ne 0) { throw "$name Android Java 编译失败，退出码 $compileExit" }
    Write-Output "$($name.ToUpperInvariant())_ANDROID_SOURCES=$($appSources.Count)"

    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot 'check-api25-compatibility.ps1') `
        -Variant $name
    $api25Exit = $LASTEXITCODE
    if ($api25Exit -ne 0) { throw "$name API25 兼容性检查失败，退出码 $api25Exit" }

    Write-Output "[3/10] $name DEX"
    & $jar cf $appClassesJar -C $appClasses .
    Assert-Exit "$name Java 类归档"
    $dexInputs = @($appClassesJar, $aarClassesJar, $licenseJar, $liantianJar)
    if ($palmClassesJar) { $dexInputs += $palmClassesJar }
    & $d8 --lib $androidJar --min-api 21 --output $dexDirectory @dexInputs
    Assert-Exit "$name D8"

    if ($name -ceq 'production') {
        $productionDexFiles = @(Get-ChildItem -LiteralPath $dexDirectory -Filter "classes*.dex" -File | Sort-Object Name)
        if ($productionDexFiles.Count -eq 0) {
            throw "production D8 未生成 classes*.dex"
        }
        Assert-NoActivationCodeInDex @($productionDexFiles | Select-Object -ExpandProperty FullName)
        foreach ($productionDexFile in $productionDexFiles) {
            $productionDexText = [System.Text.Encoding]::ASCII.GetString(
                [System.IO.File]::ReadAllBytes($productionDexFile.FullName))
            if ($productionDexText.Contains("LocalPassFaceVerificationClient")) {
                throw "production DEX 包含本地自动放行校验器: $($productionDexFile.FullName)"
            }
            if ($productionDexText.Contains("local-demo:")) {
                throw "production DEX 包含本地 demo credential 前缀: $($productionDexFile.FullName)"
            }
        }
        Write-Output "PRODUCTION_DEX_FAIL_CLOSED=PASS FILES=$($productionDexFiles.Count)"

        Write-Output "[4/10] Production JVM fail-closed tests"
        $productionTestSources = Get-JavaSources @($variantTestRoot)
        if ($productionTestSources.Count -eq 0) {
            throw "app/src/productionTest 未发现 JVM 测试"
        }
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        Invoke-JavacWithArgFile "production-test-javac.args" "$appClassesJar;$junit;$hamcrest" $variantTestClasses $productionTestSources $variantRoot
        $productionTestCompileExit = $script:JavacExitCode
        $ErrorActionPreference = $previousPreference
        if ($productionTestCompileExit -ne 0) { throw "production JVM 测试源码编译失败，退出码 $productionTestCompileExit" }
        $productionTestNames = $productionTestSources | ForEach-Object {
            $relativeName = $_.Substring($variantTestRoot.Length).TrimStart('\', '/')
            ($relativeName -replace '\.java$', '').Replace('\', '.').Replace('/', '.')
        }
        Write-Output "PRODUCTION_JVM_TEST_CLASSES=$($productionTestNames.Count)"
        & $java -cp "$variantTestClasses;$appClassesJar;$junit;$hamcrest" org.junit.runner.JUnitCore $productionTestNames
        Assert-Exit "production JUnit"
        Write-Output "PRODUCTION_JVM=PASS TEST_CLASSES=$($productionTestNames.Count)"
    }

Write-Output "[6/10] Resources, manifest, and assets"
& $aapt2 compile --dir (Join-Path $projectRoot "app\src\main\res") -o $compiledResources
Assert-Exit "资源编译"
$flatResources = Get-ChildItem $compiledResources -Filter *.flat | Select-Object -ExpandProperty FullName
& $aapt2 link -o $baseApk `
    --manifest (Join-Path $projectRoot "app\src\main\AndroidManifest.xml") `
    -I $androidJar `
    -A (Join-Path $projectRoot "app\src\main\assets") `
    --min-sdk-version 21 `
    --target-sdk-version 30 `
    --version-code 21 `
    --version-name 21.0-frontend-integration-test `
    --java $generatedSources `
    $flatResources
Assert-Exit "资源链接"
. (Join-Path $PSScriptRoot 'face-model-apk.ps1')
Invoke-FaceModelApk -Apk $baseApk -Artifacts $faceArtifacts -RepairWindowsModelPaths

Write-Output "[7/10] Package all DEX and pinned native libraries"
Copy-Item -LiteralPath $baseApk -Destination $unsignedApk -Force
$dexFiles = @(Get-ChildItem -LiteralPath $dexDirectory -Filter "classes*.dex" -File | Sort-Object Name)
if ($dexFiles.Count -eq 0) {
    throw "D8 未生成 classes*.dex"
}
Assert-NoActivationCodeInDex @($dexFiles | Select-Object -ExpandProperty FullName)
$expectedDexEntries = @()
foreach ($dexFile in $dexFiles) {
    Copy-Item -LiteralPath $dexFile.FullName -Destination $packageStage
    $expectedDexEntries += $dexFile.Name
}

$abis = @("armeabi-v7a", "arm64-v8a")
$expectedNativeEntries = @()
foreach ($abi in $abis) {
    $destinationDirectory = Join-Path $packageStage "lib\$abi"
    New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
    $vendorNativeRoot = Join-Path $thirdPartyRoot "jni\$abi"
    $vendorNativeFiles = @()
    if (Test-Path -LiteralPath $vendorNativeRoot -PathType Container) {
        $vendorNativeFiles = @(Get-ChildItem -LiteralPath $vendorNativeRoot -Filter *.so -File | Sort-Object Name)
    }
    if ($vendorNativeFiles.Count -eq 0) {
        throw "AAR 缺少 $abi 原生库"
    }
    foreach ($vendorNativeFile in $vendorNativeFiles) {
        Copy-Item -LiteralPath $vendorNativeFile.FullName -Destination $destinationDirectory -Force
        $expectedNativeEntries += "lib/$abi/$($vendorNativeFile.Name)"
    }
    if ($palmRoot) {
        $palmNativeFiles = @(Get-ChildItem -LiteralPath (Join-Path $palmRoot "jni\$abi") -Filter *.so -File)
        if ($palmNativeFiles.Count -ne 8) { throw "PALM_NATIVE_INVENTORY_INVALID: $abi" }
        foreach ($palmNativeFile in $palmNativeFiles) {
            $palmTarget = Join-Path $destinationDirectory $palmNativeFile.Name
            if (Test-Path -LiteralPath $palmTarget) { throw "PALM_NATIVE_COLLISION: $abi/$($palmNativeFile.Name)" }
            Copy-Item -LiteralPath $palmNativeFile.FullName -Destination $palmTarget
            $expectedNativeEntries += "lib/$abi/$($palmNativeFile.Name)"
        }
    }
    $serialNative = Join-Path $projectRoot "app\src\main\jniLibs\$abi\libserial_port.so"
    if (-not (Test-Path -LiteralPath $serialNative -PathType Leaf)) {
        throw "缺少 $abi 串口原生库: $serialNative"
    }
    Copy-Item -LiteralPath $serialNative -Destination $destinationDirectory -Force
    $expectedNativeEntries += "lib/$abi/libserial_port.so"
}
$expectedNativeEntries = @($expectedNativeEntries | Sort-Object -Unique)
$previousLocation = Get-Location
Set-Location $packageStage
try {
    $entries = @($expectedDexEntries + $expectedNativeEntries)
    & $aapt add $unsignedApk $entries
    Assert-Exit "APK 内容打包"
} finally {
    Set-Location $previousLocation
}

Write-Output "[8/10] Align and sign"
& $zipalign -f -p 4 $unsignedApk $alignedApk
Assert-Exit "zipalign"
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
Assert-Exit "APK 签名"

Write-Output "[9/10] Verify package"
& $zipalign -c -v 4 $signedApk | Select-Object -Last 1
Assert-Exit "zipalign 验证"
$signatureVerification = & $apksigner verify --verbose --print-certs $signedApk
Assert-Exit "签名验证"
$signatureVerification | Write-Output
$signatureVerificationText = $signatureVerification -join "`n"
foreach ($scheme in @('v1', 'v2', 'v3')) {
    if ($signatureVerificationText -notmatch "Verified using $scheme scheme .*: true") {
        throw "APK $scheme 签名验证未通过"
    }
}
Write-Output "APK_SIGNATURES=v1:true,v2:true,v3:true"
$certificateMatch = [System.Text.RegularExpressions.Regex]::Match(
    $signatureVerificationText,
    'Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})')
if (-not $certificateMatch.Success) {
    throw "无法读取 APK 签名证书 SHA-256"
}
$certificateSha256 = $certificateMatch.Groups[1].Value.ToLowerInvariant()
if ($certificateSha256 -cne $expectedSigningCertificateSha256) {
    throw "APK 签名证书不是冻结的 v6 证书: $certificateSha256"
}
Write-Output "APK_CERTIFICATE_SHA256=$certificateSha256"
$archiveEntries = & $aapt list $signedApk
Assert-Exit "APK 内容列表检查"
$actualNativeEntries = @($archiveEntries | Where-Object { $_ -match '^lib/.+\.so$' } | Sort-Object)
$expectedNativeEntries = @($expectedNativeEntries | Sort-Object)
$nativeEntryDifference = @(Compare-Object $expectedNativeEntries $actualNativeEntries)
if ($actualNativeEntries.Count -ne $expectedNativeEntries.Count -or $nativeEntryDifference.Count -ne 0) {
    throw "APK 原生库集合不正确: $($actualNativeEntries -join ',')"
}
Write-Output "APK_NATIVE_LIBRARIES=$($actualNativeEntries -join ',')"

$actualDexEntries = @($archiveEntries | Where-Object { $_ -match '^classes\d*\.dex$' } | Sort-Object)
$expectedDexEntries = @($expectedDexEntries | Sort-Object)
$dexEntryDifference = @(Compare-Object $expectedDexEntries $actualDexEntries)
if ($actualDexEntries.Count -ne $expectedDexEntries.Count -or $dexEntryDifference.Count -ne 0) {
    throw "APK DEX 集合不正确: $($actualDexEntries -join ',')"
}
Write-Output "APK_DEX_ENTRIES=$($actualDexEntries -join ',')"

. (Join-Path $PSScriptRoot 'face-model-apk.ps1')
Invoke-FaceModelApk -Apk $signedApk -Artifacts $faceArtifacts

$permissions = @(& $aapt dump permissions $signedApk)
Assert-Exit "权限检查"
$permissionNames = @($permissions | ForEach-Object {
    $permissionMatch = [System.Text.RegularExpressions.Regex]::Match(
        $_, "^\s*uses-permission(?:-sdk-\d+)?:\s+name='([^']+)'")
    if ($permissionMatch.Success) {
        $permissionMatch.Groups[1].Value
    }
} | Sort-Object -Unique)
$expectedPermissions = @(
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CAMERA",
    "android.permission.INTERNET"
) | Sort-Object
$permissionDifference = @(Compare-Object $expectedPermissions $permissionNames)
if ($permissionNames.Count -ne 3 -or $permissionDifference.Count -ne 0) {
    throw "APK 权限集合不正确: $($permissionNames -join ',')"
}
Write-Output "APK_PERMISSIONS=$($permissionNames -join ',')"
$badging = (& $aapt dump badging $signedApk) -join "`n"
Assert-Exit "APK 元数据检查"
if ($badging -notmatch "package: name='com.codex.lockertest'") {
    throw "APK 包名不正确"
}
if ($badging -notmatch "versionCode='21'") {
    throw "APK versionCode 不正确"
}
if ($badging -notmatch "versionName='21.0-frontend-integration-test'") {
    throw "APK versionName 不正确"
}
if ($badging -notmatch "targetSdkVersion:'30'") {
    throw "APK targetSdkVersion 不正确"
}
if ($badging -notmatch "sdkVersion:'21'") {
    throw "APK minSdkVersion 不正确"
}
if ($badging -notmatch "application-label:'智能更衣柜'") {
    throw "APK 应用名称不正确"
}
$nativeCodeMatch = [System.Text.RegularExpressions.Regex]::Match(
    $badging, "(?m)^native-code:\s*(.+)$")
if (-not $nativeCodeMatch.Success) {
    throw "APK badging 缺少 native-code"
}
$badgingAbis = @([System.Text.RegularExpressions.Regex]::Matches(
    $nativeCodeMatch.Groups[1].Value, "'([^']+)'") |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object)
$expectedBadgingAbis = @($abis | Sort-Object)
if ($badgingAbis.Count -ne 2 -or
        @(Compare-Object $expectedBadgingAbis $badgingAbis).Count -ne 0) {
    throw "APK badging ABI 集合不正确: $($badgingAbis -join ',')"
}
if ($badging -notmatch "uses-feature-not-required: name='android.hardware.camera'") {
    throw "APK camera feature 必须为 required=false"
}
Write-Output "APK_BADGING=PASS PACKAGE=com.codex.lockertest VERSION_CODE=21 VERSION_NAME=21.0-frontend-integration-test MIN_SDK=21 TARGET_SDK=30 LABEL=智能更衣柜"
Write-Output "APK_ABIS=$($badgingAbis -join ',')"

Write-Output "[10/10] Deliver"
$deliverable = [System.IO.Path]::GetFullPath($deliverable)
if ($explicitDeliveryRoot -and (Test-Path -LiteralPath $deliverable)) {
    throw "DELIVERY_TARGET_EXISTS: 显式交付目标已存在，拒绝覆盖: $deliverable"
}
$deliverableDirectory = Split-Path -Parent $deliverable
New-Item -ItemType Directory -Force -Path $deliverableDirectory | Out-Null
$latestProductionInput = Get-ChildItem -LiteralPath (Join-Path $projectRoot "app") -Recurse -File |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $latestProductionInput) {
    throw "找不到生产输入，无法验证 APK 时间戳"
}
if ($name -ceq 'production') {
    $productionAudit = Join-Path $PSScriptRoot 'audit-production-apk.ps1'
    if (-not (Test-Path -LiteralPath $productionAudit -PathType Leaf)) {
        throw "production APK 审计脚本缺失"
    }
    $publishRunId = [Guid]::NewGuid().ToString('N')
    $publishCandidate = [System.IO.Path]::GetFullPath((Join-Path $deliverableDirectory (
        '.v17-production-publish-' + $publishRunId + '.tmp')))
    $publishBackup = [System.IO.Path]::GetFullPath((Join-Path $deliverableDirectory (
        '.v17-production-backup-' + $publishRunId + '.tmp')))
    $rollbackCandidate = [System.IO.Path]::GetFullPath((Join-Path $deliverableDirectory (
        '.v17-production-rollback-' + $publishRunId + '.tmp')))
    foreach ($temporaryPublishPath in @(
            $publishCandidate, $publishBackup, $rollbackCandidate)) {
        if (-not [string]::Equals(
                (Split-Path -Parent $temporaryPublishPath),
                $deliverableDirectory,
                [System.StringComparison]::OrdinalIgnoreCase) -or
                (Test-Path -LiteralPath $temporaryPublishPath)) {
            throw "production 发布临时路径不安全"
        }
    }
    $lastKnownGoodExists = Test-Path -LiteralPath $deliverable -PathType Leaf
    if ($explicitDeliveryRoot -and $lastKnownGoodExists) {
        throw "DELIVERY_TARGET_EXISTS: 显式交付目标已存在，拒绝覆盖: $deliverable"
    }
    $lastKnownGoodHash = $null
    $lastKnownGoodLength = 0L
    $lastKnownGoodWriteTicks = 0L
    if ($lastKnownGoodExists) {
        $lastKnownGoodItem = Get-Item -LiteralPath $deliverable
        $lastKnownGoodHash = (Get-FileHash -LiteralPath $deliverable -Algorithm SHA256).Hash
        $lastKnownGoodLength = $lastKnownGoodItem.Length
        $lastKnownGoodWriteTicks = $lastKnownGoodItem.LastWriteTimeUtc.Ticks
    }
    $stagedAuditLock = $null
    $replacementApplied = $false
    $publicationCommitted = $false
    try {
        $stagedAuditLock = [System.IO.File]::Open(
            $signedApk,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read)
        $sourceItem = Get-Item -LiteralPath $signedApk
        $stagedHashBeforeAudit = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash
        $stagedLengthBeforeAudit = $sourceItem.Length

        & powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File $productionAudit `
            -Apk $signedApk
        $productionAuditExit = $LASTEXITCODE

        $stagedAfterAudit = Get-Item -LiteralPath $signedApk
        $stagedHashAfterAudit = (Get-FileHash -LiteralPath $signedApk -Algorithm SHA256).Hash
        if ($stagedHashAfterAudit -cne $stagedHashBeforeAudit -or
                $stagedAfterAudit.Length -ne $stagedLengthBeforeAudit) {
            throw "production 待发布 APK 在审计期间发生变化"
        }
        if ($productionAuditExit -ne 0) {
            throw "production APK 安全审计失败"
        }
        Write-Output "PRODUCTION_APK_AUDIT=PASS"

        Copy-Item -LiteralPath $signedApk -Destination $publishCandidate
        $candidateItem = Get-Item -LiteralPath $publishCandidate
        $candidateHash = (Get-FileHash -LiteralPath $publishCandidate -Algorithm SHA256).Hash
        if ($candidateHash -cne $stagedHashAfterAudit -or
                $candidateItem.Length -ne $stagedAfterAudit.Length) {
            throw "production 发布候选与已审计 APK 不一致"
        }
        if ($stagedAfterAudit.LastWriteTimeUtc -le $latestProductionInput.LastWriteTimeUtc -or
                $candidateItem.LastWriteTimeUtc -le $latestProductionInput.LastWriteTimeUtc) {
            throw "v17 production APK 不是在所有 app 生产/测试输入之后生成"
        }

        if ($lastKnownGoodExists) {
            [System.IO.File]::Replace($publishCandidate, $deliverable, $publishBackup)
        } else {
            [System.IO.File]::Move($publishCandidate, $deliverable)
        }
        $replacementApplied = $true
        $sourceItem = $stagedAfterAudit
        $sourceHash = $stagedHashAfterAudit
        $deliverableItem = Get-Item -LiteralPath $deliverable
        $deliverableHashAfterPublish = (
            Get-FileHash -LiteralPath $deliverable -Algorithm SHA256).Hash
        $hash = $deliverableHashAfterPublish
        if ($deliverableHashAfterPublish -cne $candidateHash -or
                $deliverableItem.Length -ne $candidateItem.Length -or
                $sourceHash -cne $deliverableHashAfterPublish -or
                $sourceItem.Length -ne $deliverableItem.Length) {
            throw "production 原子发布结果与已审计 APK 不一致"
        }
        if ($sourceItem.LastWriteTimeUtc -le $latestProductionInput.LastWriteTimeUtc -or
                $deliverableItem.LastWriteTimeUtc -le $latestProductionInput.LastWriteTimeUtc) {
            throw "v17 production APK 不是在所有 app 生产/测试输入之后生成"
        }
        if ($lastKnownGoodExists) {
            Remove-Item -LiteralPath $publishBackup -Force
            if (Test-Path -LiteralPath $publishBackup) {
                throw "production last-known-good 临时备份清理失败"
            }
        }
        $publicationCommitted = $true
        Write-Output "PRODUCTION_PUBLISH_ATOMIC=True"
    } catch {
        if ($replacementApplied -and -not $publicationCommitted) {
            if ($lastKnownGoodExists) {
                if (-not (Test-Path -LiteralPath $publishBackup -PathType Leaf)) {
                    throw "production 发布失败且无法恢复 last-known-good"
                }
                try {
                    [System.IO.File]::Replace($publishBackup, $deliverable, $rollbackCandidate)
                } catch {
                    throw "production 发布失败且无法恢复 last-known-good"
                }
                $restoredLastKnownGood = Get-Item -LiteralPath $deliverable
                $restoredLastKnownGoodHash = (
                    Get-FileHash -LiteralPath $deliverable -Algorithm SHA256).Hash
                if ($restoredLastKnownGoodHash -cne $lastKnownGoodHash -or
                        $restoredLastKnownGood.Length -ne $lastKnownGoodLength -or
                        $restoredLastKnownGood.LastWriteTimeUtc.Ticks -ne
                            $lastKnownGoodWriteTicks) {
                    throw "production 发布失败且无法确认 last-known-good 已恢复"
                }
                $replacementApplied = $false
                if (Test-Path -LiteralPath $rollbackCandidate) {
                    Remove-Item -LiteralPath $rollbackCandidate -Force
                }
            } else {
                if (Test-Path -LiteralPath $deliverable -PathType Leaf) {
                    Remove-Item -LiteralPath $deliverable -Force
                }
                if (Test-Path -LiteralPath $deliverable) {
                    throw "production 发布失败且无法恢复空交付状态"
                }
                $replacementApplied = $false
            }
            Write-Output "PRODUCTION_LAST_KNOWN_GOOD_UNCHANGED=True"
        } elseif (-not $replacementApplied) {
            $productionExistsAfterFailure = Test-Path -LiteralPath $deliverable -PathType Leaf
            if ($productionExistsAfterFailure -ne $lastKnownGoodExists) {
                throw "production 发布失败且 last-known-good 存在状态发生变化"
            }
            if ($lastKnownGoodExists) {
                $lastKnownGoodAfter = Get-Item -LiteralPath $deliverable
                $lastKnownGoodHashAfter = (Get-FileHash -LiteralPath $deliverable -Algorithm SHA256).Hash
                if ($lastKnownGoodHashAfter -cne $lastKnownGoodHash -or
                        $lastKnownGoodAfter.Length -ne $lastKnownGoodLength -or
                        $lastKnownGoodAfter.LastWriteTimeUtc.Ticks -ne $lastKnownGoodWriteTicks) {
                    throw "production 发布失败且 last-known-good 内容或时间戳发生变化"
                }
            }
            Write-Output "PRODUCTION_LAST_KNOWN_GOOD_UNCHANGED=True"
        }
        throw "production APK 发布失败"
    } finally {
        if ($null -ne $stagedAuditLock) {
            $stagedAuditLock.Dispose()
        }
        if (Test-Path -LiteralPath $publishCandidate) {
            Remove-Item -LiteralPath $publishCandidate -Force
        }
        if (Test-Path -LiteralPath $rollbackCandidate) {
            Remove-Item -LiteralPath $rollbackCandidate -Force
        }
    }
} else {
    $localDemoTarget = $deliverable
    if ($explicitDeliveryRoot) {
        [System.IO.File]::Copy($signedApk, $localDemoTarget, $false)
    } else {
        Copy-Item -LiteralPath $signedApk -Destination $localDemoTarget -Force
    }
    $sourceItem = Get-Item -LiteralPath $signedApk
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk).Hash
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $deliverable).Hash
    if ($sourceHash -cne $hash) {
        throw "交付 APK 与已签名源 APK 哈希不一致"
    }
    $deliverableItem = Get-Item -LiteralPath $deliverable
    if ($sourceItem.Length -ne $deliverableItem.Length) {
        throw "交付 APK 与已签名源 APK 大小不一致"
    }
    if ($sourceItem.LastWriteTimeUtc -le $latestProductionInput.LastWriteTimeUtc -or
            $deliverableItem.LastWriteTimeUtc -le $latestProductionInput.LastWriteTimeUtc) {
        throw "v17 $name APK 不是在所有 app 生产/测试输入之后生成"
    }
}
$size = $deliverableItem.Length
Write-Output "APK=$deliverable"
Write-Output "SIGNED_SOURCE_APK=$signedApk"
Write-Output "SIGNED_SOURCE_SHA256=$sourceHash"
Write-Output "SIGNED_SOURCE_SIZE_BYTES=$($sourceItem.Length)"
Write-Output "SHA256=$hash"
Write-Output "SIZE_BYTES=$size"
Write-Output "ABIS=$($abis -join ',')"
Write-Output "LATEST_PRODUCTION_INPUT=$($latestProductionInput.FullName) UTC=$($latestProductionInput.LastWriteTimeUtc.ToString('o'))"
Write-Output "SIGNED_SOURCE_UTC=$($sourceItem.LastWriteTimeUtc.ToString('o'))"
Write-Output "DELIVERABLE_UTC=$($deliverableItem.LastWriteTimeUtc.ToString('o'))"
Write-Output "OUTPUT_SOURCE_HASH_IDENTICAL=True"
Write-Output "OUTPUT_SOURCE_SIZE_IDENTICAL=True"
Write-Output "APK_NEWER_THAN_ALL_PRODUCTION_INPUTS=True"
Write-Output "VARIANT_BUILD_END=$name"
}

function Assert-HistoricalPost() {
$v6BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v6Deliverable).Hash
$v7BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v7Deliverable).Hash
$v8BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v8Deliverable).Hash
$v9BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v9Deliverable).Hash
$v10BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v10Deliverable).Hash
$v11BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v11Deliverable).Hash
$v12BeforePostHashes = @($v12BuildApk, $v12Deliverable) |
    ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash }
$v13BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v13Deliverable).Hash
$v14BeforePostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v14Deliverable).Hash
Assert-FrozenBaseline "POST"
Assert-V15Baseline "POST"
Assert-V6Deliverable "POST"
Assert-V7Deliverable "POST"
Assert-V8Deliverable "POST"
Assert-V9Deliverable "POST"
Assert-V10Deliverable "POST"
Assert-V11Deliverable "POST"
Assert-V12Deliverable "POST"
Assert-V13Deliverable "POST"
Assert-V14Deliverable "POST"
$v6AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v6Deliverable).Hash
$v7AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v7Deliverable).Hash
$v8AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v8Deliverable).Hash
$v9AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v9Deliverable).Hash
$v10AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v10Deliverable).Hash
$v11AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v11Deliverable).Hash
$v12AfterPostHashes = @($v12BuildApk, $v12Deliverable) |
    ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash }
$v13AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v13Deliverable).Hash
$v14AfterPostHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $v14Deliverable).Hash
if ($v6BeforePostHash -cne $v6AfterPostHash) {
    throw "v6 APK 在交付后检查期间发生变化"
}
if ($v7BeforePostHash -cne $v7AfterPostHash) {
    throw "v7 APK 在交付后检查期间发生变化"
}
if ($v8BeforePostHash -cne $v8AfterPostHash) {
    throw "v8 APK 在交付后检查期间发生变化"
}
if ($v9BeforePostHash -cne $v9AfterPostHash) {
    throw "v9 APK 在交付后检查期间发生变化"
}
if ($v10BeforePostHash -cne $v10AfterPostHash) {
    throw "v10 APK 在交付后检查期间发生变化"
}
if ($v11BeforePostHash -cne $v11AfterPostHash) {
    throw "v11 APK 在交付后检查期间发生变化"
}
if ((Compare-Object $v12BeforePostHashes $v12AfterPostHashes).Count -ne 0) {
    throw "v12 APK 在交付后检查期间发生变化"
}
if ($v13BeforePostHash -cne $v13AfterPostHash) {
    throw "v13 APK 在交付后检查期间发生变化"
}
if ($v14BeforePostHash -cne $v14AfterPostHash) {
    throw "v14 APK 在交付后检查期间发生变化"
}
Write-Output "V6_UNCHANGED=True"
Write-Output "V7_UNCHANGED=True"
Write-Output "V8_UNCHANGED=True"
Write-Output "V9_UNCHANGED=True"
Write-Output "V10_UNCHANGED=True"
Write-Output "V11_UNCHANGED=True"
Write-Output "V12_UNCHANGED=True"
Write-Output "V13_UNCHANGED=True"
Write-Output "V14_UNCHANGED=True"
}

Assert-PackagingPaths
Write-Output "BUILD_VERSION versionCode=21 versionName=21.0-frontend-integration-test VARIANT=$Variant"
Assert-V15Baseline "PRE"
Assert-FrozenBaseline "PRE"
Assert-V6Deliverable "PRE"
Assert-V7Deliverable "PRE"
Assert-V8Deliverable "PRE"
Assert-V9Deliverable "PRE"
Assert-V10Deliverable "PRE"
Assert-V11Deliverable "PRE"
Assert-V12Deliverable "PRE"
Assert-V13Deliverable "PRE"
Assert-V14Deliverable "PRE"
Assert-AdminBuilderBaseline $projectRoot
Assert-CustomerViewNoTechnicalLeaks $projectRoot
Assert-FaceArtifacts
Assert-NoActivationCodeInSource

Assert-Tool $androidJar
Assert-Tool $java
Assert-Tool $javac
Assert-Tool $jar
Assert-Tool $aapt2
Assert-Tool $aapt
Assert-Tool $d8
Assert-Tool $zipalign
Assert-Tool $apksigner
Assert-Tool $junit
Assert-Tool $hamcrest
Assert-Tool $keystore
$env:JAVA_HOME = $jbrHome

$resolvedBuildRootForRemoval = Resolve-FullPath $buildRoot
$expectedBuildRootForRemoval = Resolve-FullPath (Join-Path $projectRoot "manual-build\v17")
if (-not [string]::Equals($resolvedBuildRootForRemoval, $expectedBuildRootForRemoval, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝清理未经验证的构建目录: $resolvedBuildRootForRemoval"
}
if (Test-Path -LiteralPath $resolvedBuildRootForRemoval) {
    Remove-Item -LiteralPath $resolvedBuildRootForRemoval -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $resolvedBuildRootForRemoval | Out-Null

& (Join-Path $PSScriptRoot 'assert-v16-frozen.ps1')
if ($Variant -ceq 'localDemo' -or $Variant -ceq 'all') {
    Compile-Variant 'localDemo' 'localDemo' $localDemoBuildRoot
}
if ($Variant -ceq 'production' -or $Variant -ceq 'all') {
    Compile-Variant 'production' 'production' $productionBuildRoot
}
& (Join-Path $PSScriptRoot 'assert-v16-frozen.ps1')

Assert-HistoricalPost
$outputsRoot = Resolve-FullPath $resolvedDeliveryRoot
$productionApks = @(Get-ChildItem -LiteralPath $outputsRoot -File -Filter '*v21*production*.apk')
if ($Variant -ceq 'production' -or $Variant -ceq 'all') {
    $resolvedProductionDeliverable = Resolve-FullPath $productionDeliverable
    if ($productionApks.Count -ne 1 -or
            -not [string]::Equals(
                $productionApks[0].FullName,
                $resolvedProductionDeliverable,
                [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "v17 production 交付物必须且只能有一个"
    }
}
Write-Output "PRODUCTION_OUTPUT_APK_COUNT=$($productionApks.Count)"
Write-Output "VERIFICATION=PASS"

