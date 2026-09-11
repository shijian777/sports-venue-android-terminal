package com.codex.lockertest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public final class Api25CompatibilityScriptSourceTest {
    @Test
    public void lintScriptUsesAnIsolatedApi25ProjectAndChecksEveryExit() throws Exception {
        String script = source("scripts/check-api25-compatibility.ps1");

        assertTrue(script.contains("cmdline-tools\\latest\\bin\\lint.bat"));
        assertTrue(script.contains("android:minSdkVersion=\"25\""));
        assertTrue(script.contains("android:targetSdkVersion=\"30\""));
        assertTrue(script.contains("target=android-34"));
        assertTrue(script.contains("--check', 'NewApi'"));
        assertTrue(script.contains("'--exitcode'"));
        assertTrue(script.contains("'--quiet'"));
        assertTrue(script.contains("'--compile-sdk-version', '34'"));
        assertTrue(script.contains("'--sdk-home', $sdkRoot"));
        assertTrue(script.contains("'--sources', $mainSources"));
        assertTrue(script.contains("'--sources', $variantSources"));
        assertTrue(script.contains("'--resources', $resources"));
        assertTrue(script.contains("'--classpath', $appClasses"));
        assertTrue(script.contains("$lintExit = $LASTEXITCODE"));
        assertTrue(script.contains("if ($lintExit -ne 0)"));
        assertTrue(script.contains("API25_LINT_PRODUCTION=PASS"));
        assertTrue(script.contains("API25_LINT_LOCAL_DEMO=PASS"));
    }

    @Test
    public void buildRunsTheMatchingLintAfterAndroidCompileAndBeforeDex() throws Exception {
        String build = source("scripts/build-debug.ps1");
        int compile = build.indexOf("Android Java compile for $name");
        int lint = build.indexOf("check-api25-compatibility.ps1", compile);
        int dex = build.indexOf("$name DEX", compile);

        assertTrue(compile >= 0);
        assertTrue(lint > compile);
        assertTrue(dex > lint);
        assertTrue(build.substring(lint, dex).contains("-Variant $name"));
        assertTrue(build.substring(lint, dex).contains("$api25Exit = $LASTEXITCODE"));
        assertTrue(build.substring(lint, dex).contains("if ($api25Exit -ne 0)"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("codex.projectRoot", "."))
                .toAbsolutePath().normalize();
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }
}
