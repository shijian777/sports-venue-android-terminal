param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $projectRoot)
$sourceManifestPath = Join-Path $PSScriptRoot "manifest.json"
$assetRoot = Join-Path $projectRoot "app\src\main\res\drawable-nodpi"
$outputPath = Join-Path $workspaceRoot "outputs\v16-ui-asset-manifest.json"

Add-Type -AssemblyName System.Drawing

$source = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceManifestPath | ConvertFrom-Json
$screens = @($source.screens)
if ($screens.Count -ne 57) {
    throw "v16 UI source manifest must contain exactly 57 screens; actual=$($screens.Count)"
}

$ids = @{}
$enums = @{}
$drawables = @{}
$entries = @()
foreach ($screen in $screens) {
    $id = [int]$screen.id
    $enumName = [string]$screen.enumName
    $drawable = [string]$screen.drawableName
    if ($ids.ContainsKey($id) -or $enums.ContainsKey($enumName) -or $drawables.ContainsKey($drawable)) {
        throw "duplicate v16 UI catalog entry: id=$id enum=$enumName drawable=$drawable"
    }
    $ids[$id] = $true
    $enums[$enumName] = $true
    $drawables[$drawable] = $true

    $fileName = "$drawable.png"
    $assetPath = Join-Path $assetRoot $fileName
    if (-not (Test-Path -LiteralPath $assetPath -PathType Leaf)) {
        throw "missing v16 UI asset: $assetPath"
    }

    $image = [System.Drawing.Image]::FromFile($assetPath)
    try {
        $width = $image.Width
        $height = $image.Height
    } finally {
        $image.Dispose()
    }
    if ($width -ne 1280 -or $height -ne 800) {
        throw "invalid v16 UI dimensions for ${fileName}: ${width}x${height}"
    }

    $entries += [ordered]@{
        id = $id
        enum = $enumName
        drawable = $drawable
        fileName = $fileName
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $assetPath).Hash.ToLowerInvariant()
        width = $width
        height = $height
        template = [string]$screen.template
        dynamicRegion = [string]$screen.dynamicRegion
        actionRoles = @($screen.actions | ForEach-Object { [string]$_ })
        referenceImage = [string]$screen.referenceImage
    }
}

for ($expectedId = 1; $expectedId -le 57; $expectedId++) {
    if (-not $ids.ContainsKey($expectedId)) {
        throw "missing v16 UI catalog id: $expectedId"
    }
}

$document = [ordered]@{
    schemaVersion = 1
    designSize = [ordered]@{ width = 1280; height = 800 }
    protectedBrand = [ordered]@{
        title = [string]$source.protectedBrandTitle
        footer = [string]$source.protectedFooterBrand
    }
    sourceCatalog = "tools/zip-ui-v16/manifest.json"
    assetCount = $entries.Count
    screens = $entries
}

$outputDirectory = Split-Path -Parent $outputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$json = $document | ConvertTo-Json -Depth 8
$normalizedJson = $json.Replace("`r`n", "`n") + "`n"
[System.IO.File]::WriteAllText($outputPath, $normalizedJson, [System.Text.UTF8Encoding]::new($false))

Write-Output "V16_UI_ASSET_MANIFEST=PASS SCREENS=$($entries.Count) OUTPUT=$outputPath"
