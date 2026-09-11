package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public final class BaiduFaceBuildContractSourceTest {
    private static final String ACTIVATION_CODE_PATTERN =
            "(?<![A-Z0-9])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}(?![A-Z0-9])";

    @Test
    public void gradlePinsV17FaceSdkFlavorsAndOnlySupportedAbis() throws Exception {
        String gradle = read("app/build.gradle");

        assertContains(gradle, "compileSdk 34");
        assertContains(gradle, "applicationId 'com.codex.lockertest'");
        assertContains(gradle, "minSdk 21");
        assertContains(gradle, "targetSdk 30");
        assertContains(gradle, "versionCode 21");
        assertTrue(gradle.contains("versionName '21.0-frontend-integration-test'")
                || gradle.contains("versionName \"21.0-frontend-integration-test\""));
        assertContains(gradle, "flavorDimensions 'faceVerification'");
        assertContains(gradle, "localDemo {");
        assertContains(gradle, "production {");
        assertContains(gradle, "implementation files('libs/FaceSDK_8.5_20241220-release.aar')");
        assertContains(gradle, "sourceCompatibility JavaVersion.VERSION_1_8");
        assertContains(gradle, "targetCompatibility JavaVersion.VERSION_1_8");
        assertContains(gradle, "minifyEnabled false");
        assertContains(gradle, "useLegacyPackaging true");

        Matcher abiFilters = Pattern.compile("abiFilters\\s+([^\\r\\n]+)").matcher(gradle);
        assertTrue("missing abiFilters", abiFilters.find());
        String normalized = abiFilters.group(1).replace("'", "").replace("\"", "")
                .replaceAll("\\s+", "");
        assertEquals("armeabi-v7a,arm64-v8a", normalized);
    }

    @Test
    public void visibleFooterPreservesBrandButUsesCurrentTestVersion() throws Exception {
        String shell = read("app/src/main/java/com/codex/lockertest/ui/ZipKioskShell.java");
        String brand = read("app/src/main/java/com/codex/lockertest/ui/zip/ZipBrand.java");

        assertContains(brand,
                "public static final String FOOTER = \"乾卦SaaS管理系统(gmtfit.com)\"");
        assertContains(brand,
                "public static final String VERSION = \"版本：v21 测试版\"");
        assertContains(shell, "ZipBrand.FOOTER");
        assertContains(shell, "ZipBrand.VERSION");
        assertFalse(shell.contains("智能更衣柜管理系统"));
        assertFalse(shell.contains("版本：v15.0 Demo"));
    }

    @Test
    public void manifestHasExactMinimalFaceSdkSurface() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");

        assertEquals(3, occurrences(manifest, "<uses-permission"));
        assertEquals(1, occurrences(manifest, "android.permission.CAMERA"));
        assertEquals(1, occurrences(manifest, "android.permission.INTERNET"));
        assertEquals(1, occurrences(manifest, "android.permission.ACCESS_NETWORK_STATE"));
        assertContains(manifest, "<uses-feature");
        assertContains(manifest, "android:name=\"android.hardware.camera\"");
        assertContains(manifest, "android:required=\"false\"");
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("READ_PHONE_STATE"));
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"));
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"));
        assertFalse(manifest.contains("requestLegacyExternalStorage"));
        assertFalse(manifest.contains("largeHeap"));

        assertContains(manifest, "android:name=\"com.baidu.liantian.LiantianActivity\"");
        assertContains(manifest, "android:excludeFromRecents=\"true\"");
        assertContains(manifest, "android:exported=\"true\"");
        assertContains(manifest, "android:launchMode=\"standard\"");
        assertContains(manifest, "android:theme=\"@android:style/Theme.Translucent\"");
        assertContains(manifest, "android:name=\"com.baidu.action.Liantian.VIEW\"");
        assertContains(manifest, "android:name=\"com.baidu.category.liantian\"");
        assertContains(manifest, "android:name=\"android.intent.category.DEFAULT\"");
    }

    @Test
    public void builderUsesIsolatedV17WorkspaceAndProtectsV6ThroughV16() throws Exception {
        String script = read("scripts/build-debug.ps1");

        assertContains(script, "manual-build\\v17");
        assertContains(script, "$forbiddenV16BuildRoot");
        assertContains(script, "$expectedBuildRootForRemoval");
        assertContains(script, "Remove-Item -LiteralPath $resolvedBuildRootForRemoval -Recurse -Force");
        for (int version = 6; version <= 14; version++) {
            assertContains(script, "Assert-V" + version + "Deliverable \"PRE\"");
            assertContains(script, "Assert-V" + version + "Deliverable \"POST\"");
            assertContains(script, "V" + version + "_UNCHANGED=True");
        }
        assertContains(script,
                "ED12E785B9DF67D3683D434E438D9AC4938719A7899148BB60B1364D36AEBF17");
        assertContains(script, "$expectedV12Size = 148161");
        assertContains(script,
                "77DAF096711413D7DFF9B4175210CF2CDDD350489F0FDCC2A2B57D9EC751DB51");
        assertContains(script, "$expectedV14Size = 14833996");
        assertContains(script, "versionCode=21");
        assertContains(script, "versionName=21.0-frontend-integration-test");
        assertContains(script, "--version-code 21");
        assertContains(script, "--version-name 21.0-frontend-integration-test");
        assertContains(script, "智能更衣柜-百度人脸本地联调版-v13.apk");
        assertContains(script, "智能更衣柜-百度RGB活体可选版-v14.apk");
        assertContains(script, "Assert-V15Baseline \"PRE\"");
        assertContains(script, "Assert-V15Baseline \"POST\"");
        assertContains(script, ".superpowers\\baseline\\v15-source.sha256");
        assertContains(script, ".superpowers\\baseline\\v15-apk.sha256");
        assertContains(script, "assert-v16-frozen.ps1");
        assertEquals(2, occurrences(script, "assert-v16-frozen.ps1"));
    }

    @Test
    public void builderCompilesBothVariantsAndPackagesCompleteSdk() throws Exception {
        String script = read("scripts/build-debug.ps1");

        assertContains(script, "third-party\\FaceSDK_8.5_20241220-release");
        assertContains(script, "Expand-Archive");
        assertContains(script, "classes.jar");
        assertContains(script, "bd_facecollect_unifylicense.jar");
        assertContains(script, "liantian.jar");
        assertContains(script,
                "function Compile-Variant([string]$name, [string]$sourceSet, [string]$variantRoot)");
        assertContains(script, "app\\src\\$sourceSet\\java");
        assertContains(script, "app\\src\\${sourceSet}Test\\java");
        assertContains(script, "Compile-Variant 'localDemo' 'localDemo' $localDemoBuildRoot");
        assertContains(script, "Compile-Variant 'production' 'production' $productionBuildRoot");
        assertContains(script, "COMMON_JVM");
        assertContains(script, "LOCAL_DEMO_JVM");
        assertContains(script, "PRODUCTION_DEX_FAIL_CLOSED=PASS");
        assertContains(script, "PRODUCTION_APK_AUDIT=PASS");
        assertContains(script, "--min-api 21");
        assertContains(script, "-A");
        assertContains(script, "app\\src\\main\\assets");
        assertContains(script, "classes*.dex");

        Matcher abis = Pattern.compile("\\$abis\\s*=\\s*@\\(([^\\r\\n]+)\\)").matcher(script);
        assertTrue("missing explicit ABI list", abis.find());
        String normalized = abis.group(1).replace("\"", "").replace("'", "")
                .replaceAll("\\s+", "");
        assertEquals("armeabi-v7a,arm64-v8a", normalized);
    }

    @Test
    public void builderDiscoversCompilesAndRunsProductionJvmTestsFailClosed() throws Exception {
        String script = read("scripts/build-debug.ps1");

        assertContains(script, "$variantTestClasses = Join-Path $variantRoot \"$name-test-classes\"");
        assertContains(script,
                "$variantTestRoot = Join-Path $projectRoot \"app\\src\\${sourceSet}Test\\java\"");
        assertContains(script, "$productionTestSources = Get-JavaSources @($variantTestRoot)");
        assertContains(script,
                "Invoke-JavacWithArgFile \"production-test-javac.args\" \"$appClassesJar;$junit;$hamcrest\" $variantTestClasses $productionTestSources $variantRoot");
        assertContains(script, "[System.IO.File]::WriteAllLines(");
        assertContains(script, "& $javac \"@$argFile\"");
        assertContains(script,
                "-cp \"$variantTestClasses;$appClassesJar;$junit;$hamcrest\" org.junit.runner.JUnitCore $productionTestNames");
        assertContains(script,
                "if ($productionTestCompileExit -ne 0) { throw \"production JVM 测试源码编译失败");
        assertContains(script, "Assert-Exit \"production JUnit\"");
        assertContains(script, "PRODUCTION_JVM_TEST_CLASSES=");
        assertContains(script, "PRODUCTION_JVM=PASS");

        int productionDex = script.indexOf("PRODUCTION_DEX_FAIL_CLOSED=PASS");
        int productionJvm = script.indexOf("PRODUCTION_JVM_TEST_CLASSES=");
        assertTrue("production JVM tests must run after production compile and D8",
                productionDex >= 0 && productionJvm > productionDex);
    }

    @Test
    public void builderPinsEightArtifactsAndRejectsExtraModels() throws Exception {
        String script = read("scripts/build-debug.ps1");
        String[][] artifacts = new String[][] {
                {"FaceSDK_8.5_20241220-release.aar",
                        "E77439F9DC4F530FF739F423EC80DA5D0AA1F5F055155FED58CC0BD1B43487E5",
                        "6435103"},
                {"detect_rgb-customized-pa-192.model.float32-0.0.18.1",
                        "080B7123EA0B01AFDB7D972916272D702338C9F41A89F2F7CF402F257EAA32B1",
                        "948451"},
                {"align_rgb-customized-pa-fast.model.float32-0.7.5.5",
                        "22205B4AF4D15C7B553481D0B5FCB99B1FA3B964FB813C715DEB7B2B4901D4A7",
                        "1233870"},
                {"align_rgb-customized-pa-80.model.float32-6.4.14.4",
                        "A6C478F38C40448F0640BA3144DD29A35BAE09E1B6C70983E140B51F266D398F",
                        "2792512"},
                {"blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3",
                        "16B33D67D648D02284B1B91FB434B7E96D84051CF1A60A29E00C7E07002E1FB2",
                        "133739"},
                {"occlusion-customized-pa-paddle.model.float32-2.0.7.3",
                        "422AF339B14F61E9D505E056B1B3771AE29147813A0B6501A1E99437D9AC4AF7",
                        "391504"},
                {"best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1",
                        "EE5E69447D0AB603BCB79FAC43FDBB71565F44740734EDCC2DA4662FDF0C6E0A",
                        "1118807"},
                {"liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1",
                        "015A0F9C54338DAF401266FEDFC19FE9FCF57E7F553CAE6CAA4D3E07C8D75A38",
                        "2089103"}
        };
        for (String[] artifact : artifacts) {
            assertContains(script, artifact[0]);
            assertContains(script, artifact[1]);
            assertContains(script, artifact[2]);
        }
        assertContains(script, "FACE_ARTIFACT_ASSERTIONS=8");
        assertContains(script, "FACE_MODEL_INVENTORY=PASS EXPECTED=7 ACTUAL=7 EXTRAS=0");
        assertContains(script, "Compare-Object");
    }

    @Test
    public void builderRejectsActivationCodePatternInSourceAndDex() throws Exception {
        String script = read("scripts/build-debug.ps1");

        assertContains(script, ACTIVATION_CODE_PATTERN);
        assertContains(script, "ACTIVATION_CODE_SOURCE_SCAN=PASS");
        assertContains(script, "ACTIVATION_CODE_DEX_SCAN=PASS");
        assertContains(script, "classes*.dex");
    }

    @Test
    public void builderProducesExactModelPathsAndRejectsMalformedOrTamperedAssets() throws Exception {
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-File",
                projectRoot().resolve("scripts/test-face-model-apk.ps1").toString())
                .directory(projectRoot().toFile()).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        assertEquals(output.toString("UTF-8"), 0, process.waitFor());
    }

    @Test
    public void builderChecksOptionalCameraUsingAaptBadgingShape() throws Exception {
        String script = read("scripts/build-debug.ps1");

        assertContains(script,
                "uses-feature-not-required: name='android.hardware.camera'");
    }

    @Test
    public void readmeDescribesV13TwoAbiAndExactThreePermissionContract() throws Exception {
        String readme = read("README.md");

        assertContains(readme, "armeabi-v7a + arm64-v8a 两 ABI");
        assertContains(readme,
                "CAMERA、INTERNET、ACCESS_NETWORK_STATE 三项权限精确检查");
        assertFalse(readme.contains("四 ABI"));
        assertFalse(readme.contains("无权限检查"));
    }

    @Test
    public void faceVariantBoundaryUsesSameFqcnWithOppositeFailClosedConstants() throws Exception {
        String localDemo = read(
                "app/src/localDemo/java/com/codex/lockertest/face/FaceBuildVariant.java");
        String production = read(
                "app/src/production/java/com/codex/lockertest/face/FaceBuildVariant.java");

        assertContains(localDemo, "package com.codex.lockertest.face;");
        assertContains(production, "package com.codex.lockertest.face;");
        assertContains(localDemo, "public final class FaceBuildVariant");
        assertContains(production, "public final class FaceBuildVariant");
        assertContains(localDemo, "public static boolean isLocalDemo()");
        assertContains(production, "public static boolean isLocalDemo()");
        assertContains(localDemo, "return true;");
        assertContains(production, "return false;");
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))
                    && Files.isRegularFile(candidate.resolve("scripts/build-debug.ps1"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate smart-locker-serial-test project root");
    }

    private static void assertContains(String source, String expected) {
        assertTrue("missing source contract token: " + expected, source.contains(expected));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
