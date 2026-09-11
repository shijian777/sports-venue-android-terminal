package com.codex.lockertest;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Source-level delivery contracts for the isolated v17 build. */
public final class V17BuildContractSourceTest {
    private static final Path PROJECT_ROOT = Paths.get(
            System.getProperty("codex.projectRoot",
                    System.getProperty("v17.projectRoot", ".")))
            .toAbsolutePath().normalize();
    private static final Path EXACT_DIFF_SCRIPT =
            PROJECT_ROOT.resolve("scripts/assert-exact-task-diff.ps1");

    @Test
    public void v17MetadataAndVariantOutputsAreExact() throws Exception {
        String gradle = source("app/build.gradle");
        String script = source("scripts/build-debug.ps1");
        assertTrue(gradle.contains("versionCode 21"));
        assertTrue(gradle.contains("versionName \"21.0-frontend-integration-test\""));
        assertTrue(script.contains("[ValidateSet('localDemo','production','all')]"));
        assertTrue(script.contains("manual-build\\v17"));
        assertTrue(script.contains("智能更衣柜-v21-localDemo.apk"));
        assertTrue(script.contains("production-dex"));
        assertTrue(script.contains("智能更衣柜-v21-production测试版.apk"));
        assertTrue(script.contains("audit-production-apk.ps1"));
    }

    @Test
    public void buildScriptPinsUtf8ForRedirectedNativeToolOutput() throws Exception {
        String script = source("scripts/build-debug.ps1");

        assertTrue(script.contains(
                "$nativeUtf8 = [System.Text.UTF8Encoding]::new($false)"));
        assertTrue(script.contains("[Console]::InputEncoding = $nativeUtf8"));
        assertTrue(script.contains("[Console]::OutputEncoding = $nativeUtf8"));
        assertTrue(script.contains("$OutputEncoding = $nativeUtf8"));
    }

    @Test
    public void buildScriptProtectsV15InputsBeforeAndAfterBuild() throws Exception {
        String script = source("scripts/build-debug.ps1");
        assertTrue(script.contains(".superpowers\\baseline\\v15-source.sha256"));
        assertTrue(script.contains(".superpowers\\baseline\\v15-apk.sha256"));
        assertTrue(script.contains("Assert-V15Baseline \"PRE\""));
        assertTrue(script.contains("Assert-V15Baseline \"POST\""));
    }

    @Test
    public void buildScriptDoesNotWriteGeneratedArtifactsToV15Project() throws Exception {
        String script = source("scripts/build-debug.ps1");
        assertFalse(script.contains("Join-Path $v15ProjectRoot \"manual-build"));
        assertFalse(script.contains("Join-Path $v15ProjectRoot \"..\\..\\outputs"));
    }

    @Test
    public void productionBootstrapCredentialsCannotComeFromHostOrBuildState()
            throws Exception {
        String buildScript = source("scripts/build-debug.ps1");
        String gradle = source("app/build.gradle");
        String bootstrapProductionSource = sourceTree(
                "app/src/production/java/com/codex/lockertest/server")
                + sourceTree(
                        "app/src/production/java/com/codex/lockertest/bootstrap");
        String allProductionSource = sourceTree("app/src/main/java")
                + sourceTree("app/src/production/java");
        String buildSurface = buildScript + "\n" + gradle;

        for (String forbidden : Arrays.asList(
                "CENTRAL_CONTROL_SYS_CODE",
                "GeneratedCentralControlSysCode",
                "generated-secret")) {
            assertFalse(forbidden, buildSurface.contains(forbidden));
            assertFalse(forbidden, allProductionSource.contains(forbidden));
        }
        assertFalse("System.getenv(", allProductionSource.contains("System.getenv("));
        for (String forbidden : Arrays.asList(
                "System.getProperty(",
                "BuildConfig",
                "SharedPreferences",
                "android.content.Intent",
                "java.lang.reflect",
                "java.io.File")) {
            assertFalse(forbidden, bootstrapProductionSource.contains(forbidden));
        }
        assertFalse(buildScript.contains("GetEnvironmentVariable"));
        assertFalse(buildScript.contains("$env:CENTRAL_CONTROL_SYS_CODE"));
        assertFalse(gradle.contains("buildConfigField"));
        assertFalse(gradle.contains("resValue"));
    }

    @Test
    public void sourceAuditRejectsCredentialMarkersInAnyMainPackage()
            throws Exception {
        for (String unsafeSource : Arrays.asList(
                "package fixture; final class Unsafe { String value = "
                        + "\"CENTRAL_CONTROL_SYS_CODE\"; }",
                "package fixture; final class Unsafe { Object value = "
                        + "System.getenv(\"BOOTSTRAP_CREDENTIAL\"); }")) {
            String invocation = "$sourceByRelative = @{ "
                    + "'app/src/main/java/fixture/Unsafe.java' = '"
                    + powerShellLiteral(unsafeSource)
                    + "' }; Assert-NoProductionCredentialSourceMarkers "
                    + "$sourceByRelative";

            CommandResult result = runAuditFunction(invocation);

            assertTrue(result.output, result.exitCode != 0);
            assertEquals("PRODUCTION_SOURCE_FORBIDDEN="
                            + "SOURCE_BOOTSTRAP_CREDENTIAL_PATH",
                    result.output.trim());
        }
    }

    @Test
    public void bootstrapAuditPinsReadOnlyLiveSourceAndDexContracts()
            throws Exception {
        String audit = source("scripts/audit-production-apk.ps1");

        assertTrue(audit.contains("function Assert-BootstrapSourceContract"));
        for (String rule : Arrays.asList(
                "SOURCE_BOOTSTRAP_ENDPOINT_CONTRACT",
                "SOURCE_BOOTSTRAP_TRANSPORT_CONTRACT",
                "SOURCE_BOOTSTRAP_HEADER_CONTRACT",
                "SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT",
                "SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION",
                "SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT",
                "DEX_FORBIDDEN_BOOTSTRAP_TEST_KEY")) {
            assertTrue(rule, audit.contains(rule));
        }
        for (String sentinel : Arrays.asList(
                "HttpsUrlConnectionTransport",
                "ProductionBootstrapService",
                "Rk3288DeviceSerialProvider",
                "ProductionBootstrapRuntime",
                "ProductionBootstrapRuntimeFactory",
                "ProductionBootstrapScheduler",
                "ProductionSecretProvider",
                "ProductionBootstrapContractGate")) {
            assertTrue(sentinel, audit.contains("Name = '" + sentinel + "'"));
        }
        assertTrue(audit.contains("BOOTSTRAP_SOURCE_CONTRACT=PINNED_LIVE_READ_ONLY"));
        assertTrue(audit.contains("DEX_FORBIDDEN_BOOTSTRAP_CREDENTIAL_PLAINTEXT"));
    }

    @Test
    public void finalVerificationRunnerUsesPerProcessExitCodesAndFreshArtifacts()
            throws Exception {
        String runner = source("scripts/verify-bootstrap-integration.ps1");

        assertTrue(runner.contains("function Invoke-CheckedNative"));
        assertTrue(runner.contains("Start-Process"));
        assertTrue(runner.contains(".ExitCode"));
        assertTrue(runner.contains("manual-build\\bootstrap-verification"));
        assertTrue(runner.contains("-Variant', 'all'"));
        assertTrue(runner.contains("audit-production-apk.ps1"));
        assertTrue(runner.contains("assert-v16-frozen.ps1"));
        assertTrue(runner.contains("check-api25-compatibility.ps1"));
        assertTrue(runner.contains("git"));
        assertTrue(runner.contains("diff', '--check'"));
        assertTrue(runner.contains("VERIFICATION=PASS RUN_ID="));
        assertFalse(runner.contains("$LASTEXITCODE"));
        assertFalse(runner.contains("Tee-Object"));
        assertTrue(runner.contains("$outputNamePrefix = Join-UnicodeCharacters"));
        assertTrue("Windows PowerShell 5.1 requires this no-BOM script to be ASCII-only",
                runner.codePoints().allMatch(codePoint -> codePoint <= 0x7f));
    }

    @Test
    public void productionAuditUsesStrictNormalizedUtf8OnlyForSourceContracts()
            throws Exception {
        String audit = source("scripts/audit-production-apk.ps1");

        assertTrue(audit.contains("function Get-NormalizedUtf8SourceSha256"));
        assertTrue(audit.contains(
                "[System.Text.UTF8Encoding]::new($false, $true)"));
        assertTrue(audit.contains("SOURCE_UTF8_INVALID"));
        assertTrue(audit.contains(".Replace(\"`r`n\", \"`n\")"));
        assertTrue(audit.contains(".Replace(\"`r\", \"`n\")"));
        assertTrue(audit.contains(
                "$sourceHashByRelative[$relative] = "
                        + "Get-TextSha256 $source"));
        assertFalse(audit.contains(
                "$sourceHashByRelative[$relative] = Get-FileSha256 $file.FullName"));
        assertFalse(audit.contains(
                "$sourceHashByRelative[$relative] = "
                        + "Get-NormalizedUtf8SourceSha256 $file.FullName"));

        int rawHashStart = audit.indexOf("function Get-FileSha256");
        int rawHashEnd = audit.indexOf("function Get-TextSha256", rawHashStart);
        assertTrue(rawHashStart >= 0 && rawHashEnd > rawHashStart);
        String rawHashFunction = audit.substring(rawHashStart, rawHashEnd);
        assertTrue(rawHashFunction.contains("ComputeHash($stream)"));
        assertFalse(rawHashFunction.contains("Replace(\"`r"));
    }

    @Test
    public void productionAuditUsesStableBuildToolsForManifestAndFileInventory()
            throws Exception {
        String audit = source("scripts/audit-production-apk.ps1");

        assertTrue(audit.contains("function Convert-AaptXmlTreeToDocument"));
        assertTrue(audit.contains(
                "& $aapt2 dump xmltree --file AndroidManifest.xml $auditApk"));
        assertTrue(audit.contains("& $aapt list $auditApk"));
        assertTrue(audit.contains("Assert-ManifestContract $manifestDocument.OuterXml"));
        assertFalse(audit.contains("& $apkanalyzer manifest print"));
        assertFalse(audit.contains("& $apkanalyzer files list"));
    }

    @Test
    public void aaptXmlTreeConversionPreservesNamespacedManifestValues()
            throws Exception {
        String invocation = "$tree = @(";
        invocation +=
                "'N: android=http://schemas.android.com/apk/res/android (line=2)',"
                + "'  E: manifest (line=2)',"
                + "'    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=17',"
                + "'    A: package=\"com.codex.lockertest\" (Raw: \"com.codex.lockertest\")',"
                + "'      E: application (line=12)',"
                + "'        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false',"
                + "'          E: activity (line=17)',"
                + "'            A: http://schemas.android.com/apk/res/android:theme(0x01010000)=@0x0103000f',"
                + "'            A: http://schemas.android.com/apk/res/android:configChanges(0x0101001f)=0x000004f0'"
                + "); $document = Convert-AaptXmlTreeToDocument $tree; "
                + "$android = 'http://schemas.android.com/apk/res/android'; "
                + "$manifest = $document.DocumentElement; "
                + "$application = $manifest.SelectSingleNode('application'); "
                + "$activity = $application.SelectSingleNode('activity'); "
                + "if ($manifest.GetAttribute('package') -cne 'com.codex.lockertest' -or "
                + "$manifest.GetAttribute('versionCode', $android) -cne '17' -or "
                + "$application.GetAttribute('allowBackup', $android) -cne 'false' -or "
                + "$activity.GetAttribute('theme', $android) -cne '@ref/0x103000f' -or "
                + "$activity.GetAttribute('configChanges', $android) -cne '0x4f0') "
                + "{ throw 'conversion failed' }";

        CommandResult result = runAuditFunction(invocation);

        assertEquals(result.output, 0, result.exitCode);
    }

    @Test
    public void buildPassesProjectRootAndExactDiffChecksEveryGitChangeSet() throws Exception {
        String buildScript = source("scripts/build-debug.ps1");
        String exactDiffScript = source("scripts/assert-exact-task-diff.ps1");

        assertTrue(buildScript.contains(
                "& $java \"-Dcodex.projectRoot=$projectRoot\" -cp "
                        + "\"$commonTestClasses;$junit;$hamcrest\" "
                        + "org.junit.runner.JUnitCore $commonTestNames"));
        assertTrue(exactDiffScript.contains("$trackedPaths"));
        assertTrue(exactDiffScript.contains("diff --name-only HEAD --"));
        assertTrue(exactDiffScript.contains("$untrackedPaths"));
        assertTrue(exactDiffScript.contains("ls-files --others --exclude-standard"));
        assertTrue(exactDiffScript.contains("$cachedPaths"));
        assertTrue(exactDiffScript.contains("diff --cached --name-only --"));
        assertTrue(exactDiffScript.contains("$unstagedPaths"));
        assertTrue(exactDiffScript.contains("diff --name-only --"));

        int gitCalls = countOccurrences(exactDiffScript, "& git ");
        Matcher immediateExitCapture = Pattern.compile(
                "& git[^\\r\\n]*\\r?\\n\\s*\\$[A-Za-z][A-Za-z0-9]*Exit = \\$LASTEXITCODE")
                .matcher(exactDiffScript);
        int capturedExits = 0;
        while (immediateExitCapture.find()) {
            capturedExits++;
        }
        assertTrue(gitCalls > 0);
        assertEquals(gitCalls, capturedExits);
    }

    @Test
    public void exactDiffRejectsLeadingSpaceWorktreePathSubstitution() throws Exception {
        Path repository = createIsolatedRepository();
        try {
            Files.write(repository.resolve(" leading-space.txt"),
                    Arrays.asList("unexpected"), StandardCharsets.UTF_8);

            CommandResult result = runExactDiff(repository, "Worktree", "leading-space.txt");

            assertTrue("leading-space worktree path was accepted:\n" + result.output,
                    result.exitCode != 0);
        } finally {
            deleteRecursively(repository);
        }
    }

    @Test
    public void exactDiffRejectsLeadingSpaceStagedPathSubstitution() throws Exception {
        Path repository = createIsolatedRepository();
        try {
            String actualPath = " leading-space.txt";
            Files.write(repository.resolve(actualPath),
                    Arrays.asList("unexpected"), StandardCharsets.UTF_8);
            assertCommandSucceeds(repository, "git", "add", "--", actualPath);

            CommandResult result = runExactDiff(repository, "Staged", "leading-space.txt");

            assertTrue("leading-space staged path was accepted:\n" + result.output,
                    result.exitCode != 0);
        } finally {
            deleteRecursively(repository);
        }
    }

    @Test
    public void exactDiffStillTrimsExpectedCsvTokens() throws Exception {
        Path repository = createIsolatedRepository();
        try {
            Files.write(repository.resolve("expected.txt"),
                    Arrays.asList("expected"), StandardCharsets.UTF_8);

            CommandResult result = runExactDiff(repository, "Worktree", "  expected.txt  ");

            assertEquals(result.output, 0, result.exitCode);
            assertTrue(result.output,
                    result.output.contains("EXACT_TASK_DIFF=PASS MODE=Worktree COUNT=1"));
        } finally {
            deleteRecursively(repository);
        }
    }

    @Test
    public void readmeDocumentsExactV17VariantBuildAuditAndDeliveryCommands()
            throws Exception {
        String readme = source("README.md");
        assertTrue(readme.contains("# 智能更衣柜 v21 前端集成测试版"));
        assertTrue(readme.contains("在 v17 工程根目录运行："));
        assertTrue(readme.contains(".\\scripts\\build-debug.ps1 -Variant localDemo"));
        assertTrue(readme.contains(".\\scripts\\build-debug.ps1 -Variant production"));
        assertTrue(readme.contains(".\\scripts\\build-debug.ps1 -Variant all"));
        assertTrue(readme.contains(".\\scripts\\audit-production-apk.ps1 -Apk ..\\..\\outputs\\智能更衣柜-v21-production测试版.apk"));
        assertTrue(readme.contains("..\\..\\outputs\\智能更衣柜-v21-localDemo.apk"));
        assertTrue(readme.contains("..\\..\\outputs\\智能更衣柜-v21-production测试版.apk"));
        assertTrue(readme.contains("production 审计失败时不会复制或覆盖交付 APK"));
        assertTrue(readme.contains("57 个页面与对应交互已接入"));
        assertFalse(readme.contains("不宣称 57 页已经完成"));
    }

    private static String source(String relativePath) throws Exception {
        return new String(Files.readAllBytes(PROJECT_ROOT.resolve(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static String sourceTree(String relativeRoot) throws Exception {
        Path root = PROJECT_ROOT.resolve(relativeRoot);
        if (!Files.isDirectory(root)) {
            return "";
        }
        StringBuilder combined = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java")
                            || file.toString().endsWith(".kt"))
                    .sorted()::iterator) {
                combined.append(source(PROJECT_ROOT.relativize(path)
                        .toString().replace('\\', '/'))).append('\n');
            }
        }
        return combined.toString();
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static Path createIsolatedRepository() throws Exception {
        Path repository = Files.createTempDirectory("exact-task-diff-test-");
        try {
            assertCommandSucceeds(repository, "git", "init");
            assertCommandSucceeds(repository,
                    "git", "-c", "user.name=Codex Test", "-c",
                    "user.email=codex-test@example.invalid", "commit",
                    "--allow-empty", "-m", "baseline");
            return repository;
        } catch (Exception error) {
            deleteRecursively(repository);
            throw error;
        }
    }

    private static CommandResult runExactDiff(
            Path repository, String mode, String expectedPathsCsv) throws Exception {
        return run(repository,
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", EXACT_DIFF_SCRIPT.toString(),
                "-Mode", mode, "-ExpectedPathsCsv", expectedPathsCsv);
    }

    private static CommandResult runAuditFunction(String invocation) throws Exception {
        Path fixture = Files.createTempDirectory("v17-audit-source-contract-");
        Path harness = fixture.resolve("audit-function-harness.ps1");
        String audit = PROJECT_ROOT.resolve("scripts/audit-production-apk.ps1")
                .toString();
        String script = "$ErrorActionPreference = 'Stop'\n"
                + "$tokens = $null; $errors = $null\n"
                + "$ast = [System.Management.Automation.Language.Parser]::ParseFile('"
                + powerShellLiteral(audit)
                + "', [ref]$tokens, [ref]$errors)\n"
                + "if ($errors.Count -ne 0) { exit 97 }\n"
                + "$functions = $ast.FindAll({ param($node) $node -is "
                + "[System.Management.Automation.Language.FunctionDefinitionAst] "
                + "}, $true)\n"
                + "foreach ($definition in $functions) { "
                + "Invoke-Expression $definition.Extent.Text }\n"
                + "try { " + invocation + "; exit 0 } catch {\n"
                + "  $message = [string]$_.Exception.Message\n"
                + "  if ($message -match '^PRODUCTION_SOURCE_FORBIDDEN=')"
                + " { Write-Output $message } else { Write-Output 'UNEXPECTED' }\n"
                + "  exit 1\n"
                + "}\n";
        Files.write(harness, script.getBytes(StandardCharsets.UTF_8));
        try {
            return run(fixture,
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", harness.toString());
        } finally {
            deleteRecursively(fixture);
        }
    }

    private static String powerShellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void assertCommandSucceeds(Path workingDirectory, String... command)
            throws Exception {
        CommandResult result = run(workingDirectory, command);
        assertEquals(result.output, 0, result.exitCode);
    }

    private static CommandResult run(Path workingDirectory, String... command)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        int exitCode = process.waitFor();
        return new CommandResult(exitCode,
                new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws java.io.IOException {
                file.toFile().setWritable(true);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, java.io.IOException error)
                    throws java.io.IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String output;

        private CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
