function Invoke-FaceModelApk(
        [string]$Apk,
        [object[]]$Artifacts,
        [switch]$RepairWindowsModelPaths) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $models = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($artifact in @($Artifacts | Where-Object { $_.Kind -ceq 'MODEL' })) {
        # Only filesystem manifest paths are normalized. APK entry names are exact.
        $sourcePath = $artifact.RelativePath.Replace('\', '/')
        if (-not $sourcePath.StartsWith('app/src/main/assets/face-sdk-models/', [StringComparison]::Ordinal)) {
            throw 'APK_MODEL_MANIFEST_INVALID'
        }
        $models.Add($sourcePath.Substring('app/src/main/'.Length), $artifact)
    }
    if ($models.Count -ne 7) { throw 'APK_MODEL_MANIFEST_INVALID' }

    $repairs = [Collections.Generic.List[object]]::new()
    $seenModels = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    # Validate the entire inventory and pinned bytes before opening for any writes.
    $archive = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        foreach ($entry in $archive.Entries) {
            $rawName = $entry.FullName
            $name = $rawName
            if ($rawName.Contains('\')) {
                $candidate = $rawName.Replace('\', '/')
                if (-not $RepairWindowsModelPaths -or -not $models.ContainsKey($candidate)) {
                    throw "APK_NON_CANONICAL_ENTRY: $rawName"
                }
                $name = $candidate
                $repairs.Add([pscustomobject]@{ RawName = $rawName; Name = $name })
            }
            if (-not $name.StartsWith('assets/face-sdk-models/', [StringComparison]::OrdinalIgnoreCase)) {
                continue
            }
            if (-not $models.ContainsKey($name) -or -not $seenModels.Add($name)) {
                throw "APK_MODEL_SET_INVALID: $rawName"
            }
            $artifact = $models[$name]
            $stream = $entry.Open()
            $sha256 = [Security.Cryptography.SHA256]::Create()
            try {
                $hash = [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '')
            } finally {
                $sha256.Dispose()
                $stream.Dispose()
            }
            if ($entry.Length -ne $artifact.Size -or $hash -cne $artifact.Sha256) {
                throw "APK_MODEL_PIN_INVALID: $rawName"
            }
        }
        foreach ($name in $models.Keys) {
            if (-not $seenModels.Contains($name)) { throw "APK_MODEL_FILE_MISSING: $name" }
            if (-not $RepairWindowsModelPaths -and $null -eq $archive.GetEntry($name)) {
                throw "APK_MODEL_FILE_MISSING: $name"
            }
        }
    } finally { $archive.Dispose() }

    if ($repairs.Count -gt 0) {
        # Called immediately after aapt2 link, before DEX/native packaging,
        # zipalign, and all signature schemes. Other entries are not renamed.
        $archive = [IO.Compression.ZipFile]::Open($Apk, [IO.Compression.ZipArchiveMode]::Update)
        try {
            foreach ($repair in $repairs) {
                $original = $archive.GetEntry($repair.RawName)
                $replacement = $archive.CreateEntry($repair.Name, [IO.Compression.CompressionLevel]::Optimal)
                $replacement.LastWriteTime = $original.LastWriteTime
                $replacement.ExternalAttributes = $original.ExternalAttributes
                $inputStream = $original.Open()
                $outputStream = $replacement.Open()
                try { $inputStream.CopyTo($outputStream) }
                finally { $outputStream.Dispose(); $inputStream.Dispose() }
                $original.Delete()
            }
        } finally { $archive.Dispose() }
        Write-Output "APK_FACE_MODEL_PATH_REPAIRS=$($repairs.Count)"
        # Reopen the resulting ZIP and require exact paths and the same pins.
        Invoke-FaceModelApk -Apk $Apk -Artifacts $Artifacts
        return
    }
    Write-Output "APK_FACE_MODEL_ASSETS=$(@($models.Keys | Sort-Object) -join ',')"
    Write-Output 'APK_FACE_MODEL_PINS=PASS EXPECTED=7 EXACT_PATHS=7'
}
