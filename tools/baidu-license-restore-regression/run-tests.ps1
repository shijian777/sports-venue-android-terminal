param([ValidateSet('red','green')][string]$Phase = 'green')
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$javaBin = 'C:/Users/Administrator/.jdks/jbr-21.0.11/bin'
$out = Join-Path $project ('.superpowers/v22-license-restore/' + $Phase + '-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $out 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$junit = Join-Path $project 'manual-build/tooling/junit-4.13.2.jar'
$hamcrest = Join-Path $project 'manual-build/tooling/hamcrest-core-1.3.jar'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs'),(Join-Path $PSScriptRoot 'tests') -Recurse -Filter '*.java' -File | Select-Object -ExpandProperty FullName)
$sources += @(
    "$project/app/src/main/java/com/codex/lockertest/face/baidu/license/BdFaceAuth.java",
    "$project/app/src/main/java/com/codex/lockertest/face/baidu/license/OfficialFaceAuthOperation.java",
    "$project/app/src/main/java/com/codex/lockertest/face/baidu/license/CodeDetail.java",
    "$project/app/src/main/java/com/codex/lockertest/face/FaceLicenseStateMachine.java",
    "$project/app/src/test/java/com/codex/lockertest/face/baidu/license/OfficialFaceAuthOperationTest.java",
    "$project/app/src/test/java/com/codex/lockertest/face/FaceLicenseStateMachineTest.java"
)
& "$javaBin/javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:none -nowarn -cp "$junit;$hamcrest" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compile failed; not a behavioral RED' }
& "$javaBin/java.exe" -cp "$classes;$junit;$hamcrest" org.junit.runner.JUnitCore com.codex.lockertest.face.baidu.license.BdFaceAuthRestoreTest com.codex.lockertest.face.baidu.license.OfficialFaceAuthOperationTest com.codex.lockertest.face.FaceLicenseStateMachineTest 2>&1 | Tee-Object -FilePath (Join-Path $out 'junit.log')
$testExit = $LASTEXITCODE
Write-Output "RESTORE_TEST_OUTPUT=$out"
Write-Output "RESTORE_TEST_EXIT=$testExit"
if ($Phase -eq 'red') {
    if ($testExit -eq 0) { throw 'Expected behavioral RED but tests passed' }
} elseif ($testExit -ne 0) { throw 'Restore regression failed' }
