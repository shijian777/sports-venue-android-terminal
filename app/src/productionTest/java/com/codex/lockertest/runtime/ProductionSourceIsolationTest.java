package com.codex.lockertest.runtime;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class ProductionSourceIsolationTest {
    private static final String CURRENT_MAIN_ACTIVITY_SOURCE_HASH =
            "AF977E210136E332C35C46C294904B6724786AD425D9CDCFEDFB50E578D7D5CF";
    private static final String PREVIOUS_MAIN_ACTIVITY_SOURCE_HASH =
            "FBA505AECD40BAE3CEFD3135879949D9F163371ECE99F36C32C6B8C0E314F7D6";
    private static final String FORBIDDEN_SYMBOL =
            "PRODUCTION_APK_FORBIDDEN=DemoCredentials";
    private static final String[] PINNED_COMMAND_SOURCE_PATHS = new String[] {
            "app/src/main/java/com/codex/lockertest/serial/SerialGateway.java",
            "app/src/main/java/com/codex/lockertest/integration/CustomerSerialTransmitter.java",
            "app/src/main/java/com/codex/lockertest/runtime/RuntimeSerialLog.java",
            "app/src/main/java/com/codex/lockertest/AdminSerialActivity.java",
            "app/src/main/java/com/codex/lockertest/MainActivity.java"
    };

    @Test
    public void auditRejectsSelfContainedDemoDexWithOnlySymbolicFailure() throws Exception {
        Path root = projectRoot();
        Path audit = root.resolve("scripts/audit-production-apk.ps1");
        Path fixtureRoot = Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize().resolve(
                        "v17-forbidden-apk-"
                                + UUID.randomUUID().toString().replace("-", ""));
        Path fixture = fixtureRoot.resolve("intentional-forbidden.apk");

        Files.createDirectories(fixtureRoot);
        createAppDexApk(fixture, Arrays.asList(
                "com.codex.lockertest.runtime.DemoCredentials"));
        assertTrue("production audit script missing", Files.isRegularFile(audit));

        try {
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", audit.toString(), "-Apk", fixture.toString())
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(process.getInputStream());
            int exitCode = process.waitFor();

            assertNotEquals("forbidden localDemo DEX must fail closed", 0, exitCode);
            assertEquals(singleNonBlankLine(output), FORBIDDEN_SYMBOL);
            for (String sensitive : sensitiveFixtures()) {
                assertFalse("audit output exposed a credential or command",
                        output.contains(sensitive));
            }
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    @Test
    public void auditPinsEveryVariantSpecificClassAndProductionSentinel()
            throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        for (String forbidden : Arrays.asList(
                "LocalDemoAdminCapabilityPolicy",
                "LocalDemoPreferenceStore",
                "LocalPassFaceVerificationClient",
                "LocalDemoReturnServiceClient",
                "DemoCredentials",
                "DemoFeatureFlags",
                "LocalDemoAdminCredentialPolicy",
                "LocalDemoCredentialAdmissionPolicy",
                "LocalDemoCustomerUnlockAuthorizer",
                "LocalDemoInitialLayoutPolicy",
                "LocalDemoLegacyLayoutSource",
                "LegacyV6LockerLayoutSource",
                "FaceDemoBanner")) {
            assertTrue("missing forbidden class contract: " + forbidden,
                    script.contains("Name = '" + forbidden + "'"));
        }
        for (String sentinel : productionSentinels()) {
            assertTrue("missing production sentinel contract: " + sentinel,
                    script.contains("Name = '" + sentinel + "'"));
        }
        assertTrue(script.contains("APK_PRODUCTION_SENTINEL_MISSING"));
        assertFalse("localDemo-only banner must not remain in the main source set",
                Files.isRegularFile(projectRoot().resolve(
                        "app/src/main/java/com/codex/lockertest/ui/FaceDemoBanner.java")));
        assertFalse("the old demo banner class must not remain as an unused localDemo island",
                Files.isRegularFile(projectRoot().resolve(
                        "app/src/localDemo/java/com/codex/lockertest/ui/FaceDemoBanner.java")));
        String staleOutputDependency = "../../outputs/智能更衣柜-v17-"
                + "localDemo.apk";
        assertFalse(read("app/src/productionTest/java/com/codex/lockertest/runtime/"
                + "ProductionSourceIsolationTest.java").contains(
                staleOutputDependency));
    }

    @Test
    public void bootstrapSourceAuditRejectsEveryReadOnlyLiveBoundaryMutation()
            throws Exception {
        List<BootstrapMutation> mutations = Arrays.asList(
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/ApiEndpoint.java",
                        "BASIC_DATA(\"/v2/central_control_screen/basicData\");",
                        "BASIC_DATA(\"/v2/central_control_screen/basicData\"),\n"
                                + "    CUSTOMER_UNLOCK(\"/v2/customer/unlock\");",
                        "SOURCE_BOOTSTRAP_ENDPOINT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "HttpsUrlConnectionTransport.java",
                        "setInstanceFollowRedirects(false)",
                        "setInstanceFollowRedirects(true)",
                        "SOURCE_BOOTSTRAP_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/BootstrapHeaders.java",
                        "\"device-no\"", "\"device-number\"",
                        "SOURCE_BOOTSTRAP_HEADER_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionSecretProvider.java",
                        "50, 98, 14, 30", "51, 98, 14, 30",
                        "SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionSecretProvider.java",
                        "int[] masked = {",
                        "String unsafe = System.getenv(\"BOOTSTRAP_KEY\");\n"
                                + "        int[] masked = {",
                        "SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/bootstrap/"
                                + "ProductionBootstrapContractGate.java",
                        "return true;", "return false;",
                        "SOURCE_BOOTSTRAP_CREDENTIAL_CONTRACT"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/bootstrap/"
                                + "BootstrapRuntime.java",
                        "package com.codex.lockertest.bootstrap;",
                        "package com.codex.lockertest.bootstrap;\n"
                                + "import com.codex.lockertest.serial.SerialGateway;",
                        "SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBootstrapService.java",
                        "public final class ProductionBootstrapService implements BootstrapService, AutoCloseable {",
                        "public final class ProductionBootstrapService implements BootstrapService, AutoCloseable {\n"
                                + "    private static final String BAD = \"/v2/customer/unlock\";",
                        "SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/ui/TerminalReadiness.java",
                        "return state == State.READY_LOCAL_DEMO;", "return true;",
                        "SOURCE_BOOTSTRAP_PHYSICAL_ISOLATION"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBootstrapService.java",
                        "    ProductionBootstrapService(",
                        "    public ProductionBootstrapService(",
                        "SOURCE_BOOTSTRAP_CONSTRUCTOR_CONTRACT"));

        for (BootstrapMutation mutation : mutations) {
            AuditResult result = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$path='" + powerShellLiteral(mutation.path) + "';"
                            + "$before='" + powerShellLiteral(mutation.before) + "';"
                            + "$after='" + powerShellLiteral(mutation.after) + "';"
                            + "$sourceByRelative[$path]="
                            + "$sourceByRelative[$path].Replace($before,$after);"
                            + "Assert-BootstrapSourceContract $sourceByRelative");

            assertNotEquals(mutation.rule, 0, result.exitCode);
            assertEquals(mutation.path,
                    "PRODUCTION_SOURCE_FORBIDDEN=" + mutation.rule,
                    singleNonBlankLine(result.output));
        }
    }

    @Test
    public void businessSourceAuditAcceptsOnlyTheDocumentedNetworkSurface()
            throws Exception {
        AuditResult result = runAuditFunction(
                bootstrapSourceMapInvocation()
                        + "Assert-BusinessSourceContract $sourceByRelative");

        assertEquals(result.output, 0, result.exitCode);
        assertEquals("BUSINESS_SOURCE_CONTRACT=PINNED_NETWORK_AND_SELECTED_UNLOCK",
                singleNonBlankLine(result.output));
    }

    @Test
    public void bootstrapSourceAuditAcceptsTheTypedBusinessTransportOverload()
            throws Exception {
        AuditResult result = runAuditFunction(
                bootstrapSourceMapInvocation()
                        + "Assert-BootstrapSourceContract $sourceByRelative");

        assertEquals(result.output, 0, result.exitCode);
        assertEquals("BOOTSTRAP_SOURCE_CONTRACT=PINNED_LIVE_READ_ONLY",
                singleNonBlankLine(result.output));
    }

    @Test
    public void businessSourceAuditRejectsEndpointTransportAndPhysicalMutations()
            throws Exception {
        List<BootstrapMutation> mutations = Arrays.asList(
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/BusinessEndpoint.java",
                        "USE_CABINET_LIST(\"/v2/central_control_screen/useCabinetList\");",
                        "USE_CABINET_LIST(\"/v2/central_control_screen/useCabinetList\"),\n"
                                + "    UNDECLARED_ENDPOINT(\"/v2/central_control_screen/undeclared\");",
                        "SOURCE_BUSINESS_ENDPOINT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/TransportRequest.java",
                        "private final String endpointPath;",
                        "private final String endpointPath;\n"
                                + "    private final String arbitraryUrl;",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/TransportRequest.java",
                        "    public ApiEndpoint endpoint() {",
                        "    TransportRequest(String endpointPath, String body,\n"
                                + "            BootstrapHeaders headers, CallToken token) {\n"
                                + "        this.endpoint = null;\n"
                                + "        this.businessEndpoint = null;\n"
                                + "        this.endpointPath = endpointPath;\n"
                                + "        this.body = body;\n"
                                + "        this.headers = headers;\n"
                                + "        this.token = token;\n"
                                + "    }\n\n"
                                + "    public ApiEndpoint endpoint() {",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBusinessService.java",
                        "package com.codex.lockertest.server;",
                        "package com.codex.lockertest.server;\n"
                                + "final class UndeclaredBusinessPath {\n"
                                + "    static final String VALUE = "
                                + "\"/v2/central_control_screen/installationVerify\";\n} ",
                        "SOURCE_BUSINESS_ENDPOINT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "HttpsUrlConnectionTransport.java",
                        "connection.setRequestMethod(\"POST\");",
                        "connection.setRequestMethod(\"POST\");\n"
                                + "        connection.setRequestMethod(\"GET\");",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "HttpsUrlConnectionTransport.java",
                        "connection.setInstanceFollowRedirects(false);",
                        "connection.setInstanceFollowRedirects(false);\n"
                                + "        connection.setInstanceFollowRedirects(Boolean.TRUE);",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "HttpsUrlConnectionTransport.java",
                        "connection.setUseCaches(false);",
                        "connection.setUseCaches(false);\n"
                                + "        HttpsURLConnection.setFollowRedirects(true);",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "HttpsUrlConnectionTransport.java",
                        "connection.setRequestProperty(\"Accept\", ACCEPT);",
                        "connection.setRequestProperty(\"Accept\", ACCEPT);\n"
                                + "        connection.setRequestProperty("
                                + "\"Proxy-Authorization\", \"unsafe\");",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "HttpsUrlConnectionTransport.java",
                        "connection.setRequestProperty(\"Accept\", ACCEPT);",
                        "connection.setRequestProperty(\"Accept\", ACCEPT);\n"
                                + "        connection.addRequestProperty("
                                + "\"X-Arbitrary\", \"value\");",
                        "SOURCE_BUSINESS_TRANSPORT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBusinessService.java",
                        "package com.codex.lockertest.server;",
                        "package com.codex.lockertest.server;\n"
                                + "import com.codex.lockertest.serial.SerialGateway;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/journey/"
                                + "OnlineCustomerCoordinator.java",
                        "result = service.controlPanelPreview(",
                        "result = service.userBoard(",
                        "SOURCE_BUSINESS_ONLINE_UNLOCK_BINDING"));

        for (BootstrapMutation mutation : mutations) {
            AuditResult result = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$path='" + powerShellLiteral(mutation.path) + "';"
                            + "$before='" + powerShellLiteral(mutation.before) + "';"
                            + "$after='" + powerShellLiteral(mutation.after) + "';"
                            + "$sourceByRelative[$path]="
                            + "$sourceByRelative[$path].Replace($before,$after);"
                            + "Assert-BusinessSourceContract $sourceByRelative");

            assertNotEquals(mutation.rule, 0, result.exitCode);
            assertEquals(mutation.path,
                    "PRODUCTION_SOURCE_FORBIDDEN=" + mutation.rule,
                    singleNonBlankLine(result.output));
        }
    }

    @Test
    public void businessSourceAuditAllowsOnlyThePinnedPublicFaceUploadOwner()
            throws Exception {
        AuditResult accepted = runAuditFunction(
                bootstrapSourceMapInvocation()
                        + "Assert-BusinessSourceContract $sourceByRelative");
        assertEquals(accepted.output, 0, accepted.exitCode);

        String uploadPath =
                "app/src/production/java/com/codex/lockertest/server/"
                        + "ProductionFaceImageUploadAdapter.java";
        String endpoint = "/v2/central_control_screen/uploadImagePublic";
        List<BootstrapMutation> mutations = Arrays.asList(
                new BootstrapMutation(
                        uploadPath,
                        "private static final String PATH = \"" + endpoint + "\";",
                        "private static final String PATH = \"" + endpoint + "Changed\";",
                        "SOURCE_BUSINESS_ENDPOINT_CONTRACT"),
                new BootstrapMutation(
                        uploadPath,
                        "private static final String PATH = \"" + endpoint + "\";",
                        "private static final String PATH = \"" + endpoint + "\";\n"
                                + "    private static final String DUPLICATE_PATH = \""
                                + endpoint + "\";",
                        "SOURCE_BUSINESS_ENDPOINT_CONTRACT"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBusinessService.java",
                        "package com.codex.lockertest.server;",
                        "package com.codex.lockertest.server;\n"
                                + "final class UploadPathDecoy { static final String PATH = \""
                                + endpoint + "\"; }",
                        "SOURCE_BUSINESS_ENDPOINT_CONTRACT"));

        for (BootstrapMutation mutation : mutations) {
            assertTrue("Missing mutation anchor: " + mutation.path,
                    read(mutation.path).contains(mutation.before));
            AuditResult rejected = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$path='" + powerShellLiteral(mutation.path) + "';"
                            + "$before='" + powerShellLiteral(mutation.before) + "';"
                            + "$after='" + powerShellLiteral(mutation.after) + "';"
                            + "$sourceByRelative[$path]="
                            + "$sourceByRelative[$path].Replace($before,$after);"
                            + "Assert-BusinessSourceContract $sourceByRelative");
            assertNotEquals(mutation.path, 0, rejected.exitCode);
            assertEquals(mutation.path,
                    "PRODUCTION_SOURCE_FORBIDDEN=" + mutation.rule,
                    singleNonBlankLine(rejected.output));
        }
    }

    @Test
    public void businessSourceAuditRejectsOnlineUnlockAuthorityTampering() throws Exception {
        List<BootstrapMutation> mutations = Arrays.asList(
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/journey/OnlineCustomerCoordinator.java",
                        "if (current != operation || closed) return;", "if (false) return;",
                        "SOURCE_BUSINESS_ONLINE_UNLOCK_BINDING"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/journey/OnlineUnlockExecutor.java",
                        "return false;", "return true;",
                        "SOURCE_BUSINESS_ONLINE_UNLOCK_BINDING"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/journey/OnlineCabinetUnlockMapper.java",
                        "new AuthorizedUnlockRequest(", "unsafeAuthorize(",
                        "SOURCE_BUSINESS_ONLINE_UNLOCK_BINDING"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/integration/OnlineUnlockDispatchPermit.java",
                        "consumed = true;", "consumed = false;",
                        "SOURCE_BUSINESS_ONLINE_UNLOCK_BINDING"));
        for (BootstrapMutation mutation : mutations) {
            assertTrue("Missing mutation anchor: " + mutation.path, read(mutation.path).contains(mutation.before));
            AuditResult result = runAuditFunction(bootstrapSourceMapInvocation()
                    + "$path='" + powerShellLiteral(mutation.path) + "';"
                    + "$sourceByRelative[$path]=$sourceByRelative[$path].Replace('"
                    + powerShellLiteral(mutation.before) + "','" + powerShellLiteral(mutation.after) + "');"
                    + "Assert-BusinessSourceContract $sourceByRelative");
            assertNotEquals(0, result.exitCode);
            assertEquals("PRODUCTION_SOURCE_FORBIDDEN=" + mutation.rule, singleNonBlankLine(result.output));
        }
    }

    @Test
    public void businessSourceAuditRejectsDirectAuthorityDependencyTampering()
            throws Exception {
        List<BootstrapMutation> mutations = Arrays.asList(
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBusinessService.java",
                        "appendNumber(fields, \"fc_id\", fcId);\n"
                                + "        return execute(BusinessEndpoint.USER_BOARD,",
                        "appendNumber(fields, \"fc_id\", fcId);\n"
                                + "        openBoard(user, BoardAction.OPEN, fcId, token);\n"
                                + "        return execute(BusinessEndpoint.USER_BOARD,",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionBusinessService.java",
                        "return execute(BusinessEndpoint.USER_BOARD,",
                        "return execute(BusinessEndpoint.OPEN_BOARD,",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/"
                                + "BusinessResponseParsers.java",
                        "return new AssignedCabinet(number(\n"
                                + "                        object.get(\"fc_id\"), 1, Long.MAX_VALUE));",
                        "return new AssignedCabinet(1L);",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/"
                                + "BusinessResponseParsers.java",
                        "text(object.get(\"open_command\"), 512, false),",
                        "\"FORGED_COMMAND\",",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/AssignedCabinet.java",
                        "this.fcId = BusinessValues.positive(fcId, \"Assigned cabinet id\");",
                        "this.fcId = 1L;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/"
                                + "ControlPanelPreview.java",
                        "this.openCommand = BusinessValues.text(openCommand, "
                                + "\"Open command\", 512);",
                        "this.openCommand = \"FORGED_COMMAND\";",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/unlock/"
                                + "AuthorizedUnlockRequest.java",
                        "value.length != 5 || !Arrays.equals(value, expected)",
                        "value.length != 5",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/business/"
                                + "OnlineCustomerAssembly.java",
                        "OnlineUnlockExecutor executor) {\n"
                                + "        return new OnlineCustomerHost(",
                        "OnlineUnlockExecutor executor) {\n"
                                + "        executor.execute(null, null);\n"
                                + "        return new OnlineCustomerHost(",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/production/java/com/codex/lockertest/server/"
                                + "ProductionFaceImageUploadAdapter.java",
                        "private static final int MAX_JPEG_BYTES = 8 * 1024 * 1024;",
                        "private static final int MAX_JPEG_BYTES = 9 * 1024 * 1024;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/face/verification/"
                                + "OnlineFaceVerificationClient.java",
                        "if(closed || current != null) {",
                        "if(false) {",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/"
                                + "FaceAuthenticationPipeline.java",
                        "if (token.isCancelled()) return cancelled(token);",
                        "if (false) return cancelled(token);",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/UsedCabinetList.java",
                        "this.cabinets = Collections.unmodifiableList(copy);",
                        "this.cabinets = copy;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/face/FaceCaptureSession.java",
                        "public static final long DEFAULT_VERIFICATION_TIMEOUT_MILLIS = 3_000L;",
                        "public static final long DEFAULT_VERIFICATION_TIMEOUT_MILLIS = 30_000L;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/face/FaceRecognitionController.java",
                        "public static final long ONLINE_VERIFICATION_TIMEOUT_MILLIS = 35_000L;",
                        "public static final long ONLINE_VERIFICATION_TIMEOUT_MILLIS = 350_000L;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/model/LockerTarget.java",
                        "if (boardAddress != zone.boardAddress()) {",
                        "if (false) {",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/model/LockerZone.java",
                        "A(1),",
                        "A(2),",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"));

        for (BootstrapMutation mutation : mutations) {
            assertTrue("Missing mutation anchor: " + mutation.path,
                    read(mutation.path).contains(mutation.before));
            AuditResult result = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$path='" + powerShellLiteral(mutation.path) + "';"
                            + "$before='" + powerShellLiteral(mutation.before) + "';"
                            + "$after='" + powerShellLiteral(mutation.after) + "';"
                            + "$sourceByRelative[$path]="
                            + "$sourceByRelative[$path].Replace($before,$after);"
                            + "Assert-BusinessSourceContract $sourceByRelative");

            assertNotEquals(mutation.path, 0, result.exitCode);
            assertEquals(mutation.path,
                    "PRODUCTION_SOURCE_FORBIDDEN=" + mutation.rule,
                    singleNonBlankLine(result.output));
        }

        String missingPath =
                "app/src/main/java/com/codex/lockertest/unlock/AuthorizedUnlockRequest.java";
        AuditResult missing = runAuditFunction(
                bootstrapSourceMapInvocation()
                        + "$sourceByRelative.Remove('"
                        + powerShellLiteral(missingPath) + "') | Out-Null;"
                        + "Assert-BusinessSourceContract $sourceByRelative");
        assertNotEquals(0, missing.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN="
                        + "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY",
                singleNonBlankLine(missing.output));

        for (String requiredPath : Arrays.asList(
                "app/src/production/java/com/codex/lockertest/server/"
                        + "ProductionFaceImageUploadAdapter.java",
                "app/src/main/java/com/codex/lockertest/face/verification/"
                        + "OnlineFaceVerificationClient.java",
                "app/src/main/java/com/codex/lockertest/business/FaceAuthenticationPipeline.java",
                "app/src/main/java/com/codex/lockertest/business/UsedCabinetList.java",
                "app/src/main/java/com/codex/lockertest/face/FaceCaptureSession.java",
                "app/src/main/java/com/codex/lockertest/face/FaceRecognitionController.java")) {
            AuditResult required = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$sourceByRelative.Remove('"
                            + powerShellLiteral(requiredPath) + "') | Out-Null;"
                            + "Assert-BusinessSourceContract $sourceByRelative");
            assertNotEquals(requiredPath, 0, required.exitCode);
            assertEquals(requiredPath,
                    "PRODUCTION_SOURCE_FORBIDDEN=" + (requiredPath.endsWith(
                            "ProductionFaceImageUploadAdapter.java")
                            ? "SOURCE_BUSINESS_ENDPOINT_CONTRACT"
                            : "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                    singleNonBlankLine(required.output));
        }
    }

    @Test
    public void businessSourceAuditRejectsAuthorityHelperTamperingAndRemoval()
            throws Exception {
        List<BootstrapMutation> mutations = Arrays.asList(
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/business/BusinessValues.java",
                        "static long positive(long value, String name) {\n"
                                + "        if (value <= 0) throw new IllegalArgumentException("
                                + "name + \" must be positive\");\n"
                                + "        return value;\n"
                                + "    }",
                        "static long positive(long value, String name) {\n"
                                + "        if (\"Assigned cabinet id\".equals(name)) return 1L;\n"
                                + "        if (value <= 0) throw new IllegalArgumentException("
                                + "name + \" must be positive\");\n"
                                + "        return value;\n"
                                + "    }",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/StrictJson.java",
                        "result.put(key, value);",
                        "result.put(key, \"fc_id\".equals(key) "
                                + "? new JsonNumber(\"1\") : value);",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/JsonNumber.java",
                        "public long toLong(long minimum, long maximum) {\n"
                                + "        if (minimum > maximum) throw "
                                + "JsonContractException.invalidRange();\n"
                                + "        long value = canonicalLong();\n"
                                + "        if (value < minimum || value > maximum) {\n"
                                + "            throw JsonContractException.integerOutOfRange();\n"
                                + "        }\n"
                                + "        return value;\n"
                                + "    }",
                        "public long toLong(long minimum, long maximum) {\n"
                                + "        return 1L;\n"
                                + "    }",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/server/ApiResult.java",
                        "return value;",
                        "return (T) new com.codex.lockertest.business.AssignedCabinet(1L);",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"),
                new BootstrapMutation(
                        "app/src/main/java/com/codex/lockertest/ui/business/HidScanFrameDispatcher.java",
                        "if (completion == null) return;",
                        "if (false) return;",
                        "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY"));

        for (BootstrapMutation mutation : mutations) {
            assertTrue("Missing mutation anchor: " + mutation.path,
                    read(mutation.path).contains(mutation.before));
            AuditResult result = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$path='" + powerShellLiteral(mutation.path) + "';"
                            + "$before='" + powerShellLiteral(mutation.before) + "';"
                            + "$after='" + powerShellLiteral(mutation.after) + "';"
                            + "$sourceByRelative[$path]="
                            + "$sourceByRelative[$path].Replace($before,$after);"
                            + "Assert-BusinessSourceContract $sourceByRelative");

            assertNotEquals(mutation.path, 0, result.exitCode);
            assertEquals(mutation.path,
                    "PRODUCTION_SOURCE_FORBIDDEN=" + mutation.rule,
                    singleNonBlankLine(result.output));
        }

        for (String missingPath : Arrays.asList(
                "app/src/main/java/com/codex/lockertest/business/BusinessValues.java",
                "app/src/main/java/com/codex/lockertest/server/StrictJson.java",
                "app/src/main/java/com/codex/lockertest/server/JsonNumber.java",
                "app/src/main/java/com/codex/lockertest/server/ApiResult.java",
                "app/src/main/java/com/codex/lockertest/ui/business/HidScanFrameDispatcher.java")) {
            AuditResult missing = runAuditFunction(
                    bootstrapSourceMapInvocation()
                            + "$sourceByRelative.Remove('"
                            + powerShellLiteral(missingPath) + "') | Out-Null;"
                            + "Assert-BusinessSourceContract $sourceByRelative");
            assertNotEquals(missingPath, 0, missing.exitCode);
            assertEquals(missingPath,
                    "PRODUCTION_SOURCE_FORBIDDEN="
                            + "SOURCE_BUSINESS_AUTHORITY_DEPENDENCY",
                    singleNonBlankLine(missing.output));
        }
    }

    @Test
    public void businessSourceAuditRejectsRelocatedEndpointAuthorityBypass()
            throws Exception {
        String endpointPath =
                "app/src/main/java/com/codex/lockertest/business/BusinessEndpoint.java";
        String requestPath =
                "app/src/main/java/com/codex/lockertest/unlock/AuthorizedUnlockRequest.java";
        String frameEquality =
                "value.length != 5 || !Arrays.equals(value, expected)";
        assertTrue("Missing canonical business endpoint", read(endpointPath).contains(
                "public enum BusinessEndpoint"));
        assertTrue("Missing exact-frame equality anchor",
                read(requestPath).contains(frameEquality));

        AuditResult result = runAuditFunction(
                bootstrapSourceMapInvocation()
                        + "$endpoint='" + powerShellLiteral(endpointPath) + "';"
                        + "$moved='app/src/main/java/fixture/MovedBusinessEndpoint.java';"
                        + "$sourceByRelative[$moved]=$sourceByRelative[$endpoint];"
                        + "$sourceByRelative.Remove($endpoint) | Out-Null;"
                        + "$request='" + powerShellLiteral(requestPath) + "';"
                        + "$sourceByRelative[$request]=$sourceByRelative[$request].Replace('"
                        + powerShellLiteral(frameEquality) + "','value.length != 5');"
                        + "Assert-BusinessSourceContract $sourceByRelative");

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_BUSINESS_CONTRACT_MISSING",
                singleNonBlankLine(result.output));
    }

    @Test
    public void businessSourceAuditAllowsLegacyFixtureWithoutBusinessSurface()
            throws Exception {
        AuditResult result = runAuditFunction(
                "$sourceByRelative=@{"
                        + "'app/src/main/java/fixture/Fixture.java'="
                        + "'package fixture; final class Fixture { }'};"
                        + "Assert-BusinessSourceContract $sourceByRelative");

        assertEquals(result.output, 0, result.exitCode);
        assertEquals("BUSINESS_SOURCE_CONTRACT=NOT_PRESENT",
                singleNonBlankLine(result.output));
    }

    @Test
    public void dexParserPinsBusinessEndpointLiteralsToTheirDeclaredDescriptor()
            throws Exception {
        AuditResult valid = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lcom/codex/lockertest/business/BusinessEndpoint;",
                        businessEndpointPaths()));
        assertEquals(valid.output, 0, valid.exitCode);

        String[] missingOne = Arrays.copyOf(
                businessEndpointPaths(), businessEndpointPaths().length - 1);
        AuditResult incomplete = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lcom/codex/lockertest/business/BusinessEndpoint;", missingOne));
        assertNotEquals(0, incomplete.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_BUSINESS_ENDPOINT_CONTRACT",
                singleNonBlankLine(incomplete.output));

        AuditResult misplaced = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lcom/codex/lockertest/fixture/EndpointDecoy;",
                        businessEndpointPaths()[0]));
        assertNotEquals(0, misplaced.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_BUSINESS_ENDPOINT_CONTRACT",
                singleNonBlankLine(misplaced.output));

        AuditResult bundledMisplaced = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lvendor/fixture/EndpointDecoy;", businessEndpointPaths()[0]));
        assertNotEquals(0, bundledMisplaced.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_BUSINESS_ENDPOINT_CONTRACT",
                singleNonBlankLine(bundledMisplaced.output));

        AuditResult undeclared = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lcom/codex/lockertest/server/ProductionBusinessService;",
                        "/v2/central_control_screen/undeclared"));
        assertNotEquals(0, undeclared.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_BUSINESS_ENDPOINT_CONTRACT",
                singleNonBlankLine(undeclared.output));

        AuditResult bootstrapValid = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lcom/codex/lockertest/server/ApiEndpoint;",
                        bootstrapEndpointPaths()));
        assertEquals(bootstrapValid.output, 0, bootstrapValid.exitCode);

        AuditResult bootstrapMisplaced = runAuditFunction(
                businessEndpointDexInvocation(
                        "Lcom/codex/lockertest/server/ProductionBootstrapService;",
                        bootstrapEndpointPaths()[0]));
        assertNotEquals(0, bootstrapMisplaced.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_BUSINESS_ENDPOINT_CONTRACT",
                singleNonBlankLine(bootstrapMisplaced.output));
    }

    @Test
    public void dexParserAllowsOnlyOnePublicFaceUploadLiteralInItsPinnedDescriptor()
            throws Exception {
        String uploadDescriptor =
                "Lcom/codex/lockertest/server/ProductionFaceImageUploadAdapter;";
        String uploadEndpoint = "/v2/central_control_screen/uploadImagePublic";

        AuditResult valid = runAuditFunction(
                faceUploadDexInvocation(uploadDescriptor, uploadEndpoint, 1, 1));
        assertEquals(valid.output, 0, valid.exitCode);

        for (AuditResult rejected : Arrays.asList(
                runAuditFunction(faceUploadDexInvocation(
                        "Lcom/codex/lockertest/server/ProductionBusinessService;",
                        uploadEndpoint, 1, 1)),
                runAuditFunction(faceUploadDexInvocation(
                        uploadDescriptor,
                        "/v2/central_control_screen/uploadImagePublicChanged", 1, 1)),
                runAuditFunction(faceUploadDexInvocation(
                        uploadDescriptor, uploadEndpoint, 0, 1)),
                runAuditFunction(faceUploadDexInvocation(
                        uploadDescriptor, uploadEndpoint, 1, 0)),
                runAuditFunction(faceUploadDexInvocation(
                        uploadDescriptor, uploadEndpoint, 2, 1)),
                runAuditFunction(faceUploadDexInvocation(
                        uploadDescriptor, uploadEndpoint, 1, 2)))) {
            assertNotEquals(0, rejected.exitCode);
            assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_BUSINESS_ENDPOINT_CONTRACT",
                    singleNonBlankLine(rejected.output));
        }
    }

    @Test
    public void auditRejectsTheExplicitLocalFaceWarningFromProductionDex()
            throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        assertTrue(script.contains("$localDemoBanner = -join ([char[]]@("));
        assertTrue(script.contains(
                "0x672C, 0x673A, 0x8054, 0x8C03, 0xFF1A, 0x672A,"));
        assertTrue(script.contains(
                "0x8FDB, 0x884C, 0x8EAB, 0x4EFD, 0x6BD4, 0x5BF9"));
        assertTrue(script.contains("Rule = 'DEX_FORBIDDEN_LOCAL_DEMO_BANNER'; "
                + "Value = $localDemoBanner"));
        assertFalse(script.contains("Value = '本机联调：未进行身份比对'"));
    }

    @Test
    public void sourceScannerRejectsMainJavaCleartextUrlBeforeDexInspection()
            throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Unsafe { String endpoint = "
                        + "\"http://unsafe.invalid\"; }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());

        AuditResult result = runFixtureAudit(fixtureRoot);

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_CLEARTEXT_URL",
                singleNonBlankLine(result.output));
    }

    @Test
    public void sourceScannerRejectsProductionKotlinCredentialLogging()
            throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Safe { String endpoint = "
                        + "\"https://safe.invalid\"; }",
                "package fixture\n"
                        + "fun leak(rawCredential: String) { "
                        + "android.util.Log.d(\"fixture\", \"credential=\" + rawCredential) }\n",
                safeManifest());

        AuditResult result = runFixtureAudit(fixtureRoot);

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_LOG_SINK",
                singleNonBlankLine(result.output));
    }

    @Test
    public void sourceScannerRejectsDirectAndAliasedLoggerSinksButAllowsLengthUse()
            throws Exception {
        Path leaking = sourceFixture(
                "package fixture; final class Unsafe { "
                        + "java.util.logging.Logger logger; "
                        + "void leak(String rawCredential) { "
                        + "logger.info(rawCredential); } }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        AuditResult leakingResult = runFixtureAudit(leaking);

        assertNotEquals(0, leakingResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_LOG_SINK",
                singleNonBlankLine(leakingResult.output));
        assertFalse(leakingResult.output.contains("rawCredential"));

        Path aliased = sourceFixture(
                "package fixture; final class Unsafe { "
                        + "java.util.logging.Logger logger; "
                        + "void leak(String rawCredential) { "
                        + "java.util.logging.Logger receiver = logger; "
                        + "receiver.info(rawCredential); } }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        AuditResult aliasedResult = runFixtureAudit(aliased);

        assertNotEquals(0, aliasedResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_LOG_SINK",
                singleNonBlankLine(aliasedResult.output));
        assertFalse(aliasedResult.output.contains("rawCredential"));

        Path lengthOnly = sourceFixture(
                "package fixture; final class Safe { "
                        + "int inspect(String rawCredential) { "
                        + "return rawCredential.length(); } }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        AuditResult lengthResult = runFixtureAudit(lengthOnly);

        assertNotEquals(0, lengthResult.exitCode);
        assertEquals(FORBIDDEN_SYMBOL, singleNonBlankLine(lengthResult.output));
    }

    @Test
    public void sourceScannerRejectsTimberAndroidAndStdoutSinks() throws Exception {
        for (String source : Arrays.asList(
                "package fixture; final class Unsafe { void leak(String value) { "
                        + "android.util.Log.e(\"fixture\", value); } }",
                "package fixture; final class Unsafe { void leak(String value) { "
                        + "Timber.e(value); } }",
                "package fixture; final class Unsafe { void leak(String value) { "
                        + "Timber.tag(\"fixture\").e(value); } }",
                "package fixture; final class Unsafe { void leak(String value) { "
                        + "System.out.println(value); } }",
                "package fixture; final class Unsafe { void leak(String value) { "
                        + "System.err.print(value); } }")) {
            Path fixture = sourceFixture(
                    source,
                    "package fixture\nclass ProductionSafe\n",
                    safeManifest());
            AuditResult result = runFixtureAudit(fixture);

            assertNotEquals(0, result.exitCode);
            assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_LOG_SINK",
                    singleNonBlankLine(result.output));
            assertFalse(result.output.contains("println"));
        }
    }

    @Test
    public void sourceScannerRejectsAnyAdditionalRuntimeSerialLogPipeline()
            throws Exception {
        Path fixture = sourceFixture(
                "package fixture; final class Unsafe { "
                        + "com.codex.lockertest.runtime.RuntimeSerialLog receiver; "
                        + "void leak(String value) { receiver.append(value); } }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());

        AuditResult result = runFixtureAudit(fixture);

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_COMMAND_LOG_CONTRACT",
                singleNonBlankLine(result.output));
    }

    @Test
    public void sourceScannerRejectsAdditionalRuntimeSerialLogReferenceWithCoreContract()
            throws Exception {
        Path fixture = sourceFixture(
                "package fixture; final class Unsafe { "
                        + "private com.codex.lockertest.runtime.RuntimeSerialLog receiver; "
                        + "void leak(String rawCredential) { receiver.append(rawCredential); } }",
                "package fixture\\nclass ProductionSafe\\n",
                safeManifest());
        copyPinnedRuntimeSerialContractSources(fixture);

        AuditResult result = runFixtureAudit(fixture);

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_COMMAND_LOG_CONTRACT",
                singleNonBlankLine(result.output));
        assertFalse(result.output.contains("rawCredential"));
    }

    @Test
    public void sourceCommandContractAcceptsLfCrLfAndMixedLineEndings()
            throws Exception {
        for (String style : Arrays.asList("LF", "CRLF", "MIXED")) {
            Path fixture = sourceFixture(
                    "package fixture; final class Safe { }",
                    "package fixture\nclass ProductionSafe\n",
                    safeManifest());
            copyPinnedRuntimeSerialContractSources(fixture);
            rewritePinnedCommandSources(fixture, style);

            AuditResult result = runFixtureAudit(fixture);

            assertNotEquals(style, 0, result.exitCode);
            assertEquals(style, FORBIDDEN_SYMBOL, singleNonBlankLine(result.output));
        }
    }

    @Test
    public void sourceCommandContractRejectsOneNonLineEndingMutation()
            throws Exception {
        Path fixture = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        copyPinnedRuntimeSerialContractSources(fixture);
        Path mutated = fixture.resolve(PINNED_COMMAND_SOURCE_PATHS[0]);
        byte[] original = Files.readAllBytes(mutated);
        byte[] changed = Arrays.copyOf(original, original.length + 1);
        changed[changed.length - 1] = 'X';
        Files.write(mutated, changed);

        AuditResult result = runFixtureAudit(fixture);

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_COMMAND_LOG_CONTRACT",
                singleNonBlankLine(result.output));
    }

    @Test
    public void sourceCommandContractRejectsBomAndMalformedUtf8() throws Exception {
        for (boolean bom : new boolean[] {true, false}) {
            Path fixture = sourceFixture(
                    "package fixture; final class Safe { }",
                    "package fixture\nclass ProductionSafe\n",
                    safeManifest());
            copyPinnedRuntimeSerialContractSources(fixture);
            Path invalid = fixture.resolve(PINNED_COMMAND_SOURCE_PATHS[0]);
            byte[] original = Files.readAllBytes(invalid);
            byte[] prefix = bom
                    ? new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}
                    : new byte[] {(byte) 0xC3, (byte) 0x28};
            byte[] changed = new byte[prefix.length + original.length];
            System.arraycopy(prefix, 0, changed, 0, prefix.length);
            System.arraycopy(original, 0, changed, prefix.length, original.length);
            Files.write(invalid, changed);

            AuditResult result = runFixtureAudit(fixture);

            assertNotEquals(0, result.exitCode);
            assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_UTF8_INVALID",
                    singleNonBlankLine(result.output));
        }
    }

    @Test
    public void normalizedSourceHashDoesNotChangeBinaryHashSemantics()
            throws Exception {
        Path fixture = Files.createTempDirectory("source-hash-fixture-");
        Path lf = fixture.resolve("lf.java");
        Path crlf = fixture.resolve("crlf.java");
        Files.write(lf, "first\nsecond\n".getBytes(StandardCharsets.UTF_8));
        Files.write(crlf, "first\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8));
        try {
            String invocation = "$lfSource = Get-NormalizedUtf8SourceSha256 '"
                    + powerShellLiteral(lf.toString())
                    + "'; $crlfSource = Get-NormalizedUtf8SourceSha256 '"
                    + powerShellLiteral(crlf.toString())
                    + "'; if ($lfSource -cne $crlfSource) { "
                    + "throw 'SOURCE_NORMALIZATION_INVALID' }; "
                    + "$lfBinary = Get-FileSha256 '"
                    + powerShellLiteral(lf.toString())
                    + "'; $crlfBinary = Get-FileSha256 '"
                    + powerShellLiteral(crlf.toString())
                    + "'; if ($lfBinary -ceq $crlfBinary) { "
                    + "throw 'BINARY_HASH_NORMALIZED' }; "
                    + "Write-Output 'SOURCE_HASH_FIXTURE=PASS'";

            AuditResult result = runAuditFunction(invocation);

            assertEquals(result.output, 0, result.exitCode);
            assertEquals("SOURCE_HASH_FIXTURE=PASS", result.output.trim());
        } finally {
            deleteRecursively(fixture);
        }
    }

    @Test
    public void mainActivityCommandContractPinsCurrentHashAndRejectsPreviousHash()
            throws Exception {
        Path root = projectRoot();
        String current = normalizedUtf8Sha256(Files.readAllBytes(root.resolve(
                "app/src/main/java/com/codex/lockertest/MainActivity.java")));
        String audit = new String(Files.readAllBytes(root.resolve(
                "scripts/audit-production-apk.ps1")), StandardCharsets.UTF_8);

        assertEquals(CURRENT_MAIN_ACTIVITY_SOURCE_HASH, current);
        assertNotEquals(PREVIOUS_MAIN_ACTIVITY_SOURCE_HASH, current);
        assertTrue(audit.contains(CURRENT_MAIN_ACTIVITY_SOURCE_HASH));
        assertFalse(audit.contains(PREVIOUS_MAIN_ACTIVITY_SOURCE_HASH));

        String normalized = decodeNormalizedUtf8(Files.readAllBytes(root.resolve(
                "app/src/main/java/com/codex/lockertest/MainActivity.java")));
        assertEquals(CURRENT_MAIN_ACTIVITY_SOURCE_HASH,
                normalizedUtf8Sha256(normalized.replace("\n", "\r\n")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void gitArchiveAndWorktreeCommandSourcesHaveEqualNormalizedHashes()
            throws Exception {
        Path root = projectRoot();
        List<String> diffCommand = new ArrayList<>();
        diffCommand.add("git");
        diffCommand.add("diff");
        diffCommand.add("--quiet");
        diffCommand.add("HEAD");
        diffCommand.add("--");
        diffCommand.addAll(Arrays.asList(PINNED_COMMAND_SOURCE_PATHS));
        Process diff = new ProcessBuilder(diffCommand)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String diffOutput = readOutput(diff.getInputStream());
        int diffExit = diff.waitFor();
        assertTrue(diffOutput, diffExit == 0 || diffExit == 1);
        Assume.assumeTrue(
                "archive/worktree comparison requires unchanged pinned sources",
                diffExit == 0);

        Path fixture = Files.createTempDirectory("command-source-archive-");
        Path archive = fixture.resolve("head.zip");
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("archive");
            command.add("--format=zip");
            command.add("--output=" + archive.toString());
            command.add("HEAD");
            command.add("--");
            command.addAll(Arrays.asList(PINNED_COMMAND_SOURCE_PATHS));
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(process.getInputStream());
            assertEquals(output, 0, process.waitFor());

            Map<String, byte[]> archived = readZipEntries(archive);
            assertEquals(PINNED_COMMAND_SOURCE_PATHS.length, archived.size());
            for (String relative : PINNED_COMMAND_SOURCE_PATHS) {
                assertTrue(relative, archived.containsKey(relative));
                assertEquals(relative,
                        normalizedUtf8Sha256(Files.readAllBytes(root.resolve(relative))),
                        normalizedUtf8Sha256(archived.get(relative)));
            }
        } finally {
            deleteRecursively(fixture);
        }
    }

    @Test
    public void sourceScannerAllowsOnlyPinnedZipPixelShellDiagnostics()
            throws Exception {
        Path fixture = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        Path root = projectRoot();
        Path zipFixture = fixture.resolve(
                "app/src/main/java/com/codex/lockertest/ui/zip/ZipPixelShell.java");
        Files.createDirectories(zipFixture.getParent());
        Files.copy(root.resolve(
                        "app/src/main/java/com/codex/lockertest/ui/zip/ZipPixelShell.java"),
                zipFixture, StandardCopyOption.REPLACE_EXISTING);

        AuditResult result = runFixtureAudit(fixture);

        assertNotEquals(0, result.exitCode);
        assertEquals(FORBIDDEN_SYMBOL, singleNonBlankLine(result.output));
    }

    @Test
    public void sourceScannerRejectsUnapprovedFullHexLogCallsite()
            throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Unsafe { "
                        + "void leak(byte[] payload) { android.util.Log.d(\"fixture\", "
                        + "com.codex.lockertest.protocol.HexCodec.format(payload)); } }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());

        AuditResult result = runFixtureAudit(fixtureRoot);

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_COMMAND_LOG_CONTRACT",
                singleNonBlankLine(result.output));
    }

    @Test
    public void sourceScannerRejectsPermissiveHostnameVerifierAndTrustAll()
            throws Exception {
        Path permissive = sourceFixture(
                "package fixture; final class Unsafe { "
                        + "javax.net.ssl.HostnameVerifier verifier = (host, session) -> true; }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        AuditResult permissiveResult = runFixtureAudit(permissive);

        assertNotEquals(0, permissiveResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_HOSTNAME_VERIFIER",
                singleNonBlankLine(permissiveResult.output));

        Path trustAll = sourceFixture(
                "package fixture; final class Unsafe { void trustAll() { } }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        AuditResult trustAllResult = runFixtureAudit(trustAll);

        assertNotEquals(0, trustAllResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_TRUST_ALL",
                singleNonBlankLine(trustAllResult.output));
    }

    @Test
    public void sourceScannerAllowsAndroidSchemaAndStrictDefaultVerifier()
            throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Safe { "
                        + "javax.net.ssl.HostnameVerifier verifier = "
                        + "javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier(); }",
                "package fixture\nval endpoint = \"https://safe.invalid\"\n",
                safeManifest());

        AuditResult result = runFixtureAudit(fixtureRoot);

        assertNotEquals(0, result.exitCode);
        assertEquals(FORBIDDEN_SYMBOL, singleNonBlankLine(result.output));
    }

    @Test
    public void sourceScannerRejectsCleartextAndDemoValuesInTextResources()
            throws Exception {
        Path cleartext = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        writeFixtureResource(cleartext, "app/src/main/res/xml/endpoint.xml",
                "<endpoint>http://unsafe.invalid</endpoint>");
        AuditResult cleartextResult = runFixtureAudit(cleartext);
        assertNotEquals(0, cleartextResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_RESOURCE_CLEARTEXT",
                singleNonBlankLine(cleartextResult.output));

        Path json = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        writeFixtureResource(json, "app/src/production/assets/config.json",
                "{\"pin\":\"123456\"}");
        AuditResult jsonResult = runFixtureAudit(json);
        assertNotEquals(0, jsonResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_RESOURCE_DEMO_VALUE",
                singleNonBlankLine(jsonResult.output));
        assertFalse(jsonResult.output.contains("123456"));

        Path properties = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        writeFixtureResource(properties,
                "app/src/main/resources/runtime.properties",
                "mode=local-demo:");
        AuditResult propertiesResult = runFixtureAudit(properties);
        assertNotEquals(0, propertiesResult.exitCode);
        assertEquals("PRODUCTION_SOURCE_FORBIDDEN=SOURCE_RESOURCE_DEMO_VALUE",
                singleNonBlankLine(propertiesResult.output));
        assertFalse(propertiesResult.output.contains("local-demo:"));
    }

    @Test
    public void sourceScannerAllowsExactAndroidSchemaInTextResource()
            throws Exception {
        Path fixture = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        writeFixtureResource(fixture, "app/src/main/res/xml/schema.xml",
                "<safe xmlns:android=\"http://schemas.android.com/apk/res/android\" />");

        AuditResult result = runFixtureAudit(fixture);

        assertNotEquals(0, result.exitCode);
        assertEquals(FORBIDDEN_SYMBOL, singleNonBlankLine(result.output));
    }

    @Test
    public void dexScannerRejectsStaticAndAnnotationValuesWithoutLeaking()
            throws Exception {
        for (FixtureSource forbiddenSource : Arrays.asList(
                new FixtureSource(
                        "com.codex.lockertest.fixture.StaticValueCarrier",
                        "package com.codex.lockertest.fixture; "
                                + "public final class StaticValueCarrier { "
                                + "public static final String VALUE = \"123456\"; }"),
                new FixtureSource(
                        "com.codex.lockertest.fixture.AnnotationValueCarrier",
                        "package com.codex.lockertest.fixture; "
                                + "@java.lang.annotation.Retention("
                                + "java.lang.annotation.RetentionPolicy.RUNTIME) "
                                + "@interface FixtureMark { String value(); } "
                                + "@FixtureMark(\"123456\") "
                                + "public final class AnnotationValueCarrier { }"))) {
            AuditResult result = runProductionDexFixture(
                    productionFaceVariant(false), forbiddenSource);

            assertNotEquals(0, result.exitCode);
            assertEquals("PRODUCTION_APK_AUDIT_FAILED=DEX_FORBIDDEN_PASSWORD",
                    singleNonBlankLine(result.output));
            assertFalse(result.output.contains("123456"));
        }
    }

    @Test
    public void dexScannerRejectsBootstrapTestKeyCanaryWithoutLeaking()
            throws Exception {
        String canary = "TEST_BOOTSTRAP_KEY_DO_NOT_SHIP";
        AuditResult result = runProductionDexFixture(
                productionFaceVariant(false),
                new FixtureSource(
                        "com.codex.lockertest.fixture.BootstrapKeyCarrier",
                        "package com.codex.lockertest.fixture; "
                                + "public final class BootstrapKeyCarrier { "
                                + "public static final String VALUE = \"" + canary
                                + "\"; }"));

        assertNotEquals(0, result.exitCode);
        assertEquals(
                "PRODUCTION_APK_AUDIT_FAILED=DEX_FORBIDDEN_BOOTSTRAP_TEST_KEY",
                singleNonBlankLine(result.output));
        assertFalse(result.output.contains(canary));
    }

    @Test
    public void dexScannerRejectsBootstrapCredentialInBundledNamespaceWithoutLeaking()
            throws Exception {
        String credential = new String(new char[] {
                0x68, 0x38, 0x54, 0x44, 0x47, 0x53, 0x74, 0x46,
                0x67, 0x61, 0x37, 0x75, 0x37, 0x38, 0x39, 0x57
        });
        AuditResult result = runProductionDexFixture(
                productionFaceVariant(false),
                new FixtureSource(
                        "vendor.fixture.BundledCredentialCarrier",
                        "package vendor.fixture; "
                                + "public final class BundledCredentialCarrier { "
                                + "public static final String VALUE = \"" + credential
                                + "\"; }"));

        assertNotEquals(0, result.exitCode);
        assertEquals(
                "PRODUCTION_APK_AUDIT_FAILED="
                        + "DEX_FORBIDDEN_BOOTSTRAP_CREDENTIAL_PLAINTEXT",
                singleNonBlankLine(result.output));
        assertFalse(result.output.contains(credential));
    }

    @Test
    public void dexScannerRejectsTheExplicitLocalFaceWarningWithoutLeaking()
            throws Exception {
        String localWarning = "本机联调：未进行身份比对";
        AuditResult result = runProductionDexFixture(
                productionFaceVariant(false),
                new FixtureSource(
                        "com.codex.lockertest.fixture.LocalFaceWarningCarrier",
                        "package com.codex.lockertest.fixture; "
                                + "public final class LocalFaceWarningCarrier { "
                                + "public static final String VALUE = \"" + localWarning
                                + "\"; }"));

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=DEX_FORBIDDEN_LOCAL_DEMO_BANNER",
                singleNonBlankLine(result.output));
        assertFalse(result.output.contains(localWarning));
    }

    @Test
    public void dexScannerRejectsFullCommandOutsidePinnedAdminClass()
            throws Exception {
        AuditResult result = runProductionDexFixture(
                productionFaceVariant(false),
                new FixtureSource(
                        "com.codex.lockertest.fixture.CommandCarrier",
                        "package com.codex.lockertest.fixture; "
                                + "public final class CommandCarrier { "
                                + "public static final String VALUE = \"8A 01 01 11 9B\"; }"));

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=DEX_FORBIDDEN_COMMAND",
                singleNonBlankLine(result.output));
        assertFalse(result.output.contains("8A 01 01 11 9B"));
    }

    @Test
    public void dexScannerRequiresProductionFaceBuildVariantFalse()
            throws Exception {
        AuditResult result = runProductionDexFixture(productionFaceVariant(true));

        assertNotEquals(0, result.exitCode);
        assertEquals(
                "PRODUCTION_APK_AUDIT_FAILED=APK_FACE_BUILD_VARIANT_INVALID",
                singleNonBlankLine(result.output));
    }

    @Test
    public void dexScannerAcceptsSentinelsAndFalseFaceVariantUntilSignature()
            throws Exception {
        AuditResult result = runProductionDexFixture(productionFaceVariant(false));

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_SIGNATURE_INVALID",
                singleNonBlankLine(result.output));
    }

    @Test
    public void dexScannerRejectsWrongPackageProductionSentinelDecoys()
            throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        Path apk = fixtureRoot.resolve("wrong-package-sentinels.apk");
        List<String> decoys = new ArrayList<>();
        for (String sentinel : productionSentinels()) {
            decoys.add("com.codex.lockertest.decoy." + sentinel);
        }
        try {
            createAppDexApk(apk, decoys,
                    Arrays.asList(productionFaceVariant(false)));
            AuditResult result = runAudit(
                    fixtureRoot.resolve("scripts/audit-production-apk.ps1"), apk);
            assertNotEquals(0, result.exitCode);
            assertEquals(
                    "PRODUCTION_APK_AUDIT_FAILED=APK_PRODUCTION_SENTINEL_MISSING",
                    singleNonBlankLine(result.output));
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    @Test
    public void dexScannerRejectsEveryLocalDemoClassIncludingNestedForms()
            throws Exception {
        for (String fqcn : forbiddenClassFqcns()) {
            String symbol = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            AuditResult topLevel = runForbiddenClassFixture(fqcn, false);
            assertNotEquals(0, topLevel.exitCode);
            assertEquals("PRODUCTION_APK_FORBIDDEN=" + symbol,
                    singleNonBlankLine(topLevel.output));

            AuditResult nested = runForbiddenClassFixture(fqcn, true);
            assertNotEquals(0, nested.exitCode);
            assertEquals("PRODUCTION_APK_FORBIDDEN=" + symbol,
                    singleNonBlankLine(nested.output));
        }
    }

    @Test
    public void dexParserFailsClosedOnMalformedStringRecord() throws Exception {
        AuditResult result = runAuditFunction(
                "$block = @(" +
                        "'Class #0            -'," +
                        "'  Class descriptor  : ''Lcom/codex/lockertest/fixture/Safe;'''," +
                        "'000010: 1a00 0000 |0000: const-string v0, \"safe\"'" +
                        "); Inspect-DexClassBlock $block @() | Out-Null");

        assertNotEquals(0, result.exitCode);
        assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_DEXDUMP_STRING_FORMAT",
                singleNonBlankLine(result.output));
    }

    @Test
    public void apkArchiveRejectsCaseOnlyEntriesOrdinally() throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        Path apk = fixtureRoot.resolve("case-collision.apk");
        try {
            createAppDexApk(apk, Arrays.asList(productionSentinelFqcns()),
                    Arrays.asList(productionFaceVariant(false)));
            addCaseCollidingDexEntry(apk);
            AuditResult result = runAudit(
                    fixtureRoot.resolve("scripts/audit-production-apk.ps1"), apk);

            assertNotEquals(0, result.exitCode);
            assertEquals(
                    "PRODUCTION_APK_AUDIT_FAILED=APK_ARCHIVE_CASE_COLLISION",
                    singleNonBlankLine(result.output));
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    @Test
    public void manifestXmlContractRejectsAliasDataAndNonLiteralCleartext()
            throws Exception {
        String valid = validMergedManifest();
        for (ManifestFixture fixture : Arrays.asList(
                new ManifestFixture(
                        valid.replace("</application>",
                                "<activity-alias android:name=\".Alias\" "
                                        + "android:targetActivity=\".MainActivity\" />"
                                        + "</application>"),
                        "MANIFEST_COMPONENT_SET_INVALID"),
                new ManifestFixture(
                        valid.replace("</intent-filter>",
                                "<data android:scheme=\"https\" /></intent-filter>"),
                        "MANIFEST_INTENT_CONTRACT_INVALID"),
                new ManifestFixture(
                        valid.replace("android:allowBackup=\"false\"",
                                "android:allowBackup=\"false\" "
                                        + "android:usesCleartextTraffic=\"@bool/unsafe\""),
                        "MANIFEST_SECURITY_INVALID"),
                new ManifestFixture(
                        valid.replace("android:minSdkVersion=\"21\"",
                                "android:minSdkVersion=\"22\""),
                        "MANIFEST_SDK_INVALID"))) {
            AuditResult result = runManifestFixture(fixture.xml);
            assertNotEquals(0, result.exitCode);
            assertEquals("PRODUCTION_APK_AUDIT_FAILED=" + fixture.rule,
                    singleNonBlankLine(result.output));
        }
        AuditResult validResult = runManifestFixture(valid);
        assertEquals(0, validResult.exitCode);
        assertEquals("MANIFEST_FIXTURE=PASS",
                singleNonBlankLine(validResult.output));
    }

    @Test
    public void compiledResourceScannerRejectsDemoValuesAndCleartextGenerically()
            throws Exception {
        for (String unsafe : Arrays.asList(
                "resource value http://unsafe.invalid",
                "resource value 123456",
                "resource value local-demo:",
                "resource value 8A 01 01 11 9B")) {
            AuditResult result = runAuditFunction(
                    "Assert-CompiledResourceSecurity '"
                            + powerShellLiteral(unsafe) + "'");
            assertNotEquals(0, result.exitCode);
            assertEquals(
                    "PRODUCTION_APK_AUDIT_FAILED=APK_RESOURCE_FORBIDDEN",
                    singleNonBlankLine(result.output));
            assertFalse(result.output.contains(unsafe));
        }
        AuditResult schema = runAuditFunction(
                "Assert-CompiledResourceSecurity "
                        + "'http://schemas.android.com/apk/res/android'");
        assertEquals(0, schema.exitCode);
        assertEquals("", schema.output.trim());
    }

    @Test
    public void malformedApkFailsClosedWithOneNonSensitiveCode() throws Exception {
        Path root = projectRoot();
        Path malformed = root.resolve(
                "manual-build/v17/audit-production/fixtures/not-an-apk.bin");
        Files.createDirectories(malformed.getParent());
        Files.write(malformed, Arrays.asList("not an apk"), Charset.defaultCharset());

        try {
            AuditResult result = runAudit(
                    root.resolve("scripts/audit-production-apk.ps1"), malformed);

            assertNotEquals(0, result.exitCode);
            assertEquals("PRODUCTION_APK_AUDIT_FAILED=APK_ARCHIVE_READ_FAILED",
                    singleNonBlankLine(result.output));
            assertFalse(result.output.contains(malformed.toString()));
        } finally {
            Files.deleteIfExists(malformed);
            Files.deleteIfExists(malformed.getParent());
        }
    }

    @Test
    public void auditScriptLocksRecursiveCleanupAndEvidenceInsideAuditRoot()
            throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        assertTrue(script.contains("manual-build\\v17\\audit-production"));
        assertTrue(script.contains("$resolvedScratch.StartsWith("));
        assertTrue(script.contains("$auditPrefix"));
        assertTrue(script.contains("[Guid]::NewGuid().ToString('N')"));
        assertTrue(script.contains("$scratchName -notmatch '^run-[0-9a-f]{32}$'"));
        assertTrue(script.contains("Test-ReparsePoint $resolvedScratch"));
        assertTrue(script.contains("APK_ARCHIVE_ENTRY_OUTSIDE_SCRATCH"));
        assertTrue(script.contains("[System.IO.Compression.ZipFile]::OpenRead"));
        List<String> recursiveDeletes = new ArrayList<>();
        for (String line : script.split("\\R")) {
            if (line.contains("Remove-Item") && line.contains("-Recurse")) {
                recursiveDeletes.add(line.trim());
            }
        }
        assertEquals(Arrays.asList(
                "Remove-Item -LiteralPath $resolvedScratch -Recurse -Force"),
                recursiveDeletes);
        assertFalse(recursiveDeletes.get(0).contains("*"));
        assertFalse(recursiveDeletes.get(0).contains("?"));
        assertTrue(script.contains("function Assert-NoReparseAncestorChain"));
        assertTrue(script.contains("[System.IO.Path]::GetPathRoot"));
        int recursiveCleanup = script.lastIndexOf(
                "Remove-Item -LiteralPath $resolvedScratch -Recurse -Force");
        int ancestorChainRecheck = script.lastIndexOf(
                "Assert-NoReparseAncestorChain $resolvedScratch");
        int projectRecheck = script.lastIndexOf(
                "Assert-PlainDirectory $resolvedProjectRoot");
        int manualBuildRecheck = script.lastIndexOf(
                "Assert-PlainDirectory $resolvedManualBuildRoot");
        int v17Recheck = script.lastIndexOf(
                "Assert-PlainDirectory $resolvedV17Root");
        int auditRootRecheck = script.lastIndexOf(
                "Assert-PlainDirectory $resolvedAuditRoot");
        assertTrue("cleanup must revalidate the complete parent chain",
                projectRecheck >= 0
                        && ancestorChainRecheck >= 0
                        && manualBuildRecheck > projectRecheck
                        && v17Recheck > manualBuildRecheck
                        && auditRootRecheck > v17Recheck
                        && recursiveCleanup > auditRootRecheck
                        && recursiveCleanup > ancestorChainRecheck);

        for (String evidence : Arrays.asList(
                "manifest.txt", "files.txt", "resources.txt", "dex-packages.txt",
                "dex-app-code.txt", "source-scan.txt")) {
            assertTrue("missing audit evidence contract: " + evidence,
                    script.contains(evidence));
        }
    }

    @Test
    public void auditStagesEachEvidenceSetAndPublishesItAsOneUniqueDirectory()
            throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        assertTrue(script.contains("$evidenceRunName = 'evidence-' + [Guid]::NewGuid().ToString('N')"));
        assertTrue(script.contains("$resolvedEvidenceStaging = Get-FullPath (Join-Path $resolvedScratch 'evidence')"));
        assertTrue(script.contains("$evidenceRunName -notmatch '^evidence-[0-9a-f]{32}$'"));
        assertTrue(script.contains("[System.IO.Directory]::Move($resolvedEvidenceStaging, $resolvedEvidenceRun)"));
        assertFalse(script.contains("Remove-Item -LiteralPath $oldEvidence -Force"));
    }

    @Test
    public void auditValidatesEveryParentBeforeCreationAndComparesExactSets()
            throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        int v17ReparseCheck = script.indexOf("Test-ReparsePoint $resolvedV17Root");
        int auditRootCreation = script.indexOf(
                "New-Item -ItemType Directory -Path $resolvedAuditRoot");
        assertTrue("v17 parent must be checked before audit-root creation",
                v17ReparseCheck >= 0 && auditRootCreation > v17ReparseCheck);
        assertTrue(script.contains("Assert-PlainDirectory $resolvedProjectRoot"));
        assertTrue(script.contains("Ensure-PlainChildDirectory "
                + "$resolvedManualBuildRoot $resolvedProjectRoot"));
        assertTrue(script.contains("Sort-Object -Unique -CaseSensitive"));
        assertTrue(script.contains(
                "Compare-Object $expectedSorted $actualSorted -CaseSensitive"));
    }

    @Test
    public void auditScriptRequiresIndependentMachineReadablePasses()
            throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        for (String pass : Arrays.asList(
                "PRODUCTION_APK_SIGNATURE=PASS",
                "PRODUCTION_APK_MANIFEST=PASS",
                "PRODUCTION_APK_FILES=PASS",
                "PRODUCTION_APK_RESOURCES=PASS",
                "PRODUCTION_APK_DEX=PASS",
                "PRODUCTION_SOURCE_SECURITY=PASS",
                "PRODUCTION_APK_PERMISSIONS=PASS",
                "PRODUCTION_APK_NATIVE=PASS",
                "PRODUCTION_APK_MODELS=PASS",
                "PRODUCTION_APK_ISOLATION=PASS")) {
            assertTrue("missing machine-readable pass: " + pass, script.contains(pass));
        }
        assertTrue(script.contains("apksigner verify --verbose"));
        assertTrue(script.contains("aapt2 dump xmltree --file AndroidManifest.xml"));
        assertTrue(script.contains("dex packages --defined-only"));
        assertTrue(script.contains("aapt list"));
        assertFalse(script.contains("& $apkanalyzer manifest print"));
        assertFalse(script.contains("& $apkanalyzer files list"));
        assertTrue(script.contains("Lcom/codex/lockertest/"));
        assertTrue(script.contains("STRING_RECORD_SHA256="));
        assertTrue(script.contains("$dexdumpStartInfo.FileName = $dexdump"));
        assertFalse(script.contains("dex code --class"));
        assertTrue(script.contains("Verified using v3.1 scheme (APK Signature Scheme v3.1): false"));
        assertTrue(script.contains("Number of signers: 1"));
        assertTrue(script.contains("Assert-UniqueExactLine"));
        assertTrue(script.contains("SIGNATURE_SIGNER_COUNT_INVALID"));
        assertTrue(script.contains("MANIFEST_LIANTIAN_EXCEPTION_INVALID"));
        assertTrue(script.contains("com.baidu.action.Liantian.VIEW"));
        assertTrue(script.contains("android.intent.category.DEFAULT"));
    }

    @Test
    public void auditDecodesDexdumpWithExplicitStrictUtf8() throws Exception {
        String script = read("scripts/audit-production-apk.ps1");

        assertTrue(script.contains("StandardOutputEncoding = $strictUtf8NoBom"));
        assertTrue(script.contains("StandardErrorEncoding = $strictUtf8NoBom"));
        assertTrue(script.contains("ReadToEndAsync()"));
        assertTrue(script.contains("[System.IO.File]::WriteAllText("));
        assertFalse(script.contains("& $dexdump -a -d -l plain $dexFile.FullName"));
    }

    @Test
    public void buildStagesAuditsThenUniquelyCopiesProductionDeliverable()
            throws Exception {
        String build = read("scripts/build-debug.ps1");
        String productionName = "smart-locker-kiosk-v17-production.apk";
        String productionOutput = "智能更衣柜-v21-production测试版.apk";

        assertTrue(build.contains(productionName));
        assertTrue(build.contains(productionOutput));
        assertFalse(build.contains("PRODUCTION_APK_PACKAGING=SKIPPED"));
        int staged = build.indexOf(productionName);
        int audit = build.indexOf("audit-production-apk.ps1", staged);
        int candidateCopy = build.indexOf(
                "Copy-Item -LiteralPath $signedApk -Destination $publishCandidate",
                audit);
        int atomicReplace = build.indexOf(
                "[System.IO.File]::Replace($publishCandidate, $deliverable, "
                        + "$publishBackup)",
                candidateCopy);
        int publishedVerification = build.indexOf("$deliverableHashAfterPublish",
                atomicReplace);
        int backupCleanup = build.indexOf(
                "Remove-Item -LiteralPath $publishBackup -Force",
                publishedVerification);
        assertTrue("production staged APK must be named before audit", staged >= 0);
        assertTrue("production audit must follow staging", audit > staged);
        assertTrue("candidate copy must follow successful audit", candidateCopy > audit);
        assertTrue("atomic publish must follow candidate verification",
                atomicReplace > candidateCopy);
        assertTrue("published APK must be verified after atomic replace",
                publishedVerification > atomicReplace);
        assertTrue("last-known-good backup may be deleted only after verification",
                backupCleanup > publishedVerification);
        assertEquals("production deliverable must have one declared destination",
                1, occurrences(build, productionOutput));
        assertTrue(build.contains("if ($name -ceq 'production')"));
        assertTrue(build.contains("[System.IO.FileShare]::Read"));
        assertTrue(build.contains("$stagedHashBeforeAudit"));
        assertTrue(build.contains("$stagedHashAfterAudit"));
        assertTrue(build.contains("$candidateHash"));
        assertTrue(build.contains("$publishBackup"));
        assertTrue(build.contains("$rollbackCandidate"));
        assertTrue(build.contains(
                "[System.IO.File]::Replace($publishBackup, $deliverable, "
                        + "$rollbackCandidate)"));
        assertTrue(build.contains("[System.IO.File]::Move($publishCandidate, $deliverable)"));
        assertTrue(build.contains("$lastKnownGoodHash"));
        assertTrue(build.contains("$lastKnownGoodWriteTicks"));
        assertTrue(build.contains("PRODUCTION_LAST_KNOWN_GOOD_UNCHANGED=True"));
        assertFalse(build.contains("Remove-Item -LiteralPath $productionDeliverable"));
        assertFalse(build.contains(
                "Copy-Item -LiteralPath $signedApk -Destination $deliverable -Force"));
        assertFalse(build.contains(
                "[System.IO.File]::Replace($publishCandidate, $deliverable, $null)"));
    }

    private static String singleNonBlankLine(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }
        assertEquals("audit failure must emit one machine-readable line", 1, lines.size());
        return lines.get(0);
    }

    private static String[] sensitiveFixtures() {
        return new String[] {
                "13800138000",
                "123456",
                "0014872138",
                "888888",
                "111993413628001787216027",
                "local-demo:",
                "8A 01 01 11 9B",
                "8A0101119B"
        };
    }

    private static AuditResult runFixtureAudit(Path fixtureRoot) throws Exception {
        Path apk = fixtureRoot.resolve("intentional-forbidden.apk");
        try {
            createAppDexApk(apk, Arrays.asList(
                    "com.codex.lockertest.runtime.DemoCredentials"));
            return runAudit(
                    fixtureRoot.resolve("scripts/audit-production-apk.ps1"),
                    apk);
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    private static AuditResult runProductionDexFixture(FixtureSource... sources)
            throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        Path apk = fixtureRoot.resolve("production-dex-fixture.apk");
        try {
            createAppDexApk(apk, Arrays.asList(productionSentinelFqcns()),
                    Arrays.asList(sources));
            return runAudit(
                    fixtureRoot.resolve("scripts/audit-production-apk.ps1"), apk);
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    private static AuditResult runForbiddenClassFixture(
            String forbiddenFqcn, boolean nested) throws Exception {
        Path fixtureRoot = sourceFixture(
                "package fixture; final class Safe { }",
                "package fixture\nclass ProductionSafe\n",
                safeManifest());
        Path apk = fixtureRoot.resolve("forbidden-class-fixture.apk");
        try {
            if (nested) {
                int separator = forbiddenFqcn.lastIndexOf('.');
                String packageName = forbiddenFqcn.substring(0, separator);
                String simpleName = forbiddenFqcn.substring(separator + 1);
                createAppDexApk(apk, new ArrayList<String>(), Arrays.asList(
                        new FixtureSource(forbiddenFqcn,
                                "package " + packageName + "; public final class "
                                        + simpleName
                                        + " { public static final class Nested { } }")));
            } else {
                createAppDexApk(apk, Arrays.asList(forbiddenFqcn));
            }
            return runAudit(
                    fixtureRoot.resolve("scripts/audit-production-apk.ps1"), apk);
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    private static AuditResult runManifestFixture(String manifest) throws Exception {
        Path fixtureRoot = Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize().resolve(
                        "v17-manifest-fixture-"
                                + UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectories(fixtureRoot);
        Path manifestFile = fixtureRoot.resolve("manifest.xml");
        Files.write(manifestFile, Arrays.asList(manifest), Charset.defaultCharset());
        try {
            return runAuditFunction(
                    "Assert-ManifestContract ([System.IO.File]::ReadAllText('"
                            + powerShellLiteral(manifestFile.toString())
                            + "')); Write-Output 'MANIFEST_FIXTURE=PASS'");
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    private static AuditResult runAuditFunction(String invocation) throws Exception {
        Path root = projectRoot();
        Path fixtureRoot = Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize().resolve(
                        "v17-audit-function-"
                                + UUID.randomUUID().toString().replace("-", ""));
        Path harness = fixtureRoot.resolve("function-harness.ps1");
        Files.createDirectories(fixtureRoot);
        String script = "$ErrorActionPreference = 'Stop'\n"
                + "$utf8NoBom = [System.Text.UTF8Encoding]::new($false)\n"
                + "$strictUtf8NoBom = "
                + "[System.Text.UTF8Encoding]::new($false, $true)\n"
                + "$projectRoot = '" + powerShellLiteral(root.toString()) + "'\n"
                + "$tokens = $null; $errors = $null\n"
                + "$audit = '"
                + powerShellLiteral(root.resolve("scripts/audit-production-apk.ps1")
                        .toString()) + "'\n"
                + "$ast = [System.Management.Automation.Language.Parser]::ParseFile("
                + "$audit, [ref]$tokens, [ref]$errors)\n"
                + "if ($errors.Count -ne 0) { exit 97 }\n"
                + "$functions = $ast.FindAll({ param($node) "
                + "$node -is [System.Management.Automation.Language.FunctionDefinitionAst] "
                + "}, $true)\n"
                + "foreach ($definition in $functions) { "
                + "Invoke-Expression $definition.Extent.Text }\n"
                + "try { " + invocation + "; exit 0 } catch {\n"
                + "  $code = [string]$_.Exception.Message\n"
                + "  if ($code -match '^PRODUCTION_SOURCE_FORBIDDEN="
                + "[A-Z][A-Z0-9_]+$') { Write-Output $code } "
                + "elseif ($code -match '^[A-Z][A-Z0-9_]+$') { "
                + "Write-Output ('PRODUCTION_APK_AUDIT_FAILED=' + $code) } "
                + "else { Write-Output 'PRODUCTION_APK_AUDIT_FAILED=UNEXPECTED' }\n"
                + "  exit 1\n"
                + "}\n";
        Files.write(harness, Arrays.asList(script), Charset.defaultCharset());
        try {
            return runPowerShell(harness, fixtureRoot);
        } finally {
            deleteRecursively(fixtureRoot);
        }
    }

    private static String bootstrapSourceMapInvocation() {
        return "$sourceByRelative=@{};"
                + "foreach($relativeRoot in @('app\\src\\main','app\\src\\production')){"
                + "$sourceRoot=Join-Path $projectRoot $relativeRoot;"
                + "foreach($file in @(Get-ChildItem -LiteralPath $sourceRoot "
                + "-Recurse -File | Where-Object { $_.Extension -in @('.java','.kt') })){"
                + "$relative=$file.FullName.Substring($projectRoot.Length+1).Replace('\\','/');"
                + "$sourceByRelative[$relative]=Get-NormalizedUtf8SourceText $file.FullName;}}";
    }

    private static String businessEndpointDexInvocation(
            String descriptor, String... endpointPaths) {
        StringBuilder invocation = new StringBuilder("$block=@('")
                .append("Class #0            -','  Class descriptor  : ''")
                .append(descriptor)
                .append("'''");
        for (int index = 0; index < endpointPaths.length; index++) {
            invocation.append(",'000")
                    .append(String.format("%03x", index * 2 + 16))
                    .append(": 1a00 0000 |")
                    .append(String.format("%04x", index))
                    .append(": const-string v0, \"")
                    .append(endpointPaths[index])
                    .append("\" // string@")
                    .append(String.format("%04x", index))
                    .append("'");
        }
        return invocation.append("); Inspect-DexClassBlock $block @() | Out-Null")
                .toString();
    }

    private static String faceUploadDexInvocation(
            String descriptor, String endpointPath, int staticValues, int instructions) {
        StringBuilder invocation = new StringBuilder("$block=@('")
                .append("Class #0            -','  Class descriptor  : ''")
                .append(descriptor)
                .append("'''");
        for (int index = 0; index < staticValues; index++) {
            invocation.append(",'      value         : \"")
                    .append(endpointPath)
                    .append("\"'");
        }
        for (int index = 0; index < instructions; index++) {
            invocation.append(",'000")
                    .append(String.format("%03x", index * 2 + 16))
                    .append(": 1a00 0000 |")
                    .append(String.format("%04x", index))
                    .append(": const-string v0, \"")
                    .append(endpointPath)
                    .append("\" // string@")
                    .append(String.format("%04x", index))
                    .append("'");
        }
        return invocation.append("); Inspect-DexClassBlock $block @() | Out-Null")
                .toString();
    }

    private static String[] businessEndpointPaths() {
        return new String[] {
                "/v2/central_control_screen/mobileSmsCode",
                "/v2/central_control_screen/rigLogin",
                "/v2/central_control_screen/controlPanelPreview",
                "/v2/central_control_screen/storeyCabinet",
                "/v2/central_control_screen/installationVerify",
                "/v2/central_control_screen/mbrLogin",
                "/v2/central_control_screen/quickClearCabinet",
                "/v2/central_control_screen/userInfo",
                "/v2/central_control_screen/memberDynamicCode",
                "/v2/central_control_screen/bindUserHand",
                "/v2/central_control_screen/userBoard",
                "/v2/central_control_screen/openBoard",
                "/v2/central_control_screen/useCabinetList"
        };
    }

    private static String[] bootstrapEndpointPaths() {
        return new String[] {
                "/v2/central_control_screen/checkDevice",
                "/v2/central_control_screen/baseSetting",
                "/v2/central_control_screen/basicData"
        };
    }

    private static String businessEndpointFixtureSource() {
        return "package com.codex.lockertest.business; "
                + "public enum BusinessEndpoint { "
                + "MOBILE_SMS_CODE(\"/v2/central_control_screen/mobileSmsCode\"), "
                + "RIG_LOGIN(\"/v2/central_control_screen/rigLogin\"), "
                + "CONTROL_PANEL_PREVIEW(\"/v2/central_control_screen/controlPanelPreview\"), "
                + "STOREY_CABINET(\"/v2/central_control_screen/storeyCabinet\"), "
                + "INSTALLATION_VERIFY(\"/v2/central_control_screen/installationVerify\"), "
                + "MBR_LOGIN(\"/v2/central_control_screen/mbrLogin\"), "
                + "QUICK_CLEAR_CABINET(\"/v2/central_control_screen/quickClearCabinet\"), "
                + "USER_INFO(\"/v2/central_control_screen/userInfo\"), "
                + "MEMBER_DYNAMIC_CODE(\"/v2/central_control_screen/memberDynamicCode\"), "
                + "BIND_USER_HAND(\"/v2/central_control_screen/bindUserHand\"), "
                + "USER_BOARD(\"/v2/central_control_screen/userBoard\"), "
                + "OPEN_BOARD(\"/v2/central_control_screen/openBoard\"), "
                + "USE_CABINET_LIST(\"/v2/central_control_screen/useCabinetList\"); "
                + "private final String path; BusinessEndpoint(String path) { this.path = path; } "
                + "public String path() { return path; } }";
    }

    private static String[] productionSentinels() {
        return new String[] {
                "ProductionAdminCapabilityPolicy",
                "UnavailableFaceVerificationClient",
                "FailClosedReturnServiceClient",
                "EmptyInitialLayoutPolicy",
                "FailClosedCustomerUnlockAuthorizer",
                "RejectingCredentialAdmissionPolicy",
                "UnprovisionedAdminCredentialPolicy",
                "HttpsUrlConnectionTransport",
                "ProductionBootstrapService",
                "BusinessEndpoint",
                "ProductionBusinessService",
                "Rk3288DeviceSerialProvider",
                "ProductionBootstrapRuntime",
                "ProductionBootstrapRuntimeFactory",
                "ProductionBootstrapScheduler",
                "ProductionSecretProvider",
                "ProductionBootstrapContractGate"
        };
    }

    private static final class BootstrapMutation {
        private final String path;
        private final String before;
        private final String after;
        private final String rule;

        private BootstrapMutation(
                String path, String before, String after, String rule) {
            this.path = path;
            this.before = before;
            this.after = after;
            this.rule = rule;
        }
    }

    private static String[] forbiddenClassFqcns() {
        return new String[] {
                "com.codex.lockertest.admin.LocalDemoAdminCapabilityPolicy",
                "com.codex.lockertest.face.verification.LocalDemoPreferenceStore",
                "com.codex.lockertest.face.verification.LocalPassFaceVerificationClient",
                "com.codex.lockertest.returnflow.LocalDemoReturnServiceClient",
                "com.codex.lockertest.runtime.DemoCredentials",
                "com.codex.lockertest.runtime.DemoFeatureFlags",
                "com.codex.lockertest.runtime.LocalDemoAdminCredentialPolicy",
                "com.codex.lockertest.runtime.LocalDemoCredentialAdmissionPolicy",
                "com.codex.lockertest.runtime.LocalDemoCustomerUnlockAuthorizer",
                "com.codex.lockertest.runtime.LocalDemoInitialLayoutPolicy",
                "com.codex.lockertest.runtime.LocalDemoLegacyLayoutSource",
                "com.codex.lockertest.runtime.LegacyV6LockerLayoutSource"
        };
    }

    private static String[] productionSentinelFqcns() {
        return new String[] {
                "com.codex.lockertest.admin.ProductionAdminCapabilityPolicy",
                "com.codex.lockertest.face.verification.UnavailableFaceVerificationClient",
                "com.codex.lockertest.returnflow.FailClosedReturnServiceClient",
                "com.codex.lockertest.runtime.EmptyInitialLayoutPolicy",
                "com.codex.lockertest.runtime.FailClosedCustomerUnlockAuthorizer",
                "com.codex.lockertest.runtime.RejectingCredentialAdmissionPolicy",
                "com.codex.lockertest.runtime.UnprovisionedAdminCredentialPolicy",
                "com.codex.lockertest.server.HttpsUrlConnectionTransport",
                "com.codex.lockertest.server.ProductionBootstrapService",
                "com.codex.lockertest.business.BusinessEndpoint",
                "com.codex.lockertest.server.ProductionBusinessService",
                "com.codex.lockertest.bootstrap.Rk3288DeviceSerialProvider",
                "com.codex.lockertest.bootstrap.ProductionBootstrapRuntime",
                "com.codex.lockertest.bootstrap.ProductionBootstrapRuntimeFactory",
                "com.codex.lockertest.bootstrap.ProductionBootstrapScheduler",
                "com.codex.lockertest.server.ProductionSecretProvider",
                "com.codex.lockertest.bootstrap.ProductionBootstrapContractGate"
        };
    }

    private static FixtureSource productionFaceVariant(boolean localDemo) {
        return new FixtureSource(
                "com.codex.lockertest.face.FaceBuildVariant",
                "package com.codex.lockertest.face; "
                        + "public final class FaceBuildVariant { "
                        + "private FaceBuildVariant() { } "
                        + "public static boolean isLocalDemo() { return "
                        + localDemo + "; } }");
    }

    private static void createAppDexApk(Path apk, List<String> classNames)
            throws Exception {
        createAppDexApk(apk, classNames, new ArrayList<FixtureSource>());
    }

    private static void createAppDexApk(
            Path apk,
            List<String> classNames,
            List<FixtureSource> additionalSources) throws Exception {
        Path builderRoot = apk.getParent().resolve(
                "apk-builder-" + UUID.randomUUID().toString().replace("-", ""));
        Path sourceRoot = builderRoot.resolve("source");
        Path classRoot = builderRoot.resolve("classes");
        Path dexRoot = builderRoot.resolve("dex");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(classRoot);
        Files.createDirectories(dexRoot);

        List<String> compilerArguments = new ArrayList<>();
        compilerArguments.add("-source");
        compilerArguments.add("8");
        compilerArguments.add("-target");
        compilerArguments.add("8");
        compilerArguments.add("-d");
        compilerArguments.add(classRoot.toString());
        for (String className : classNames) {
            int separator = className.lastIndexOf('.');
            String packageName = className.substring(0, separator);
            String simpleName = className.substring(separator + 1);
            Path source = sourceRoot.resolve(className.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            if ("com.codex.lockertest.business.BusinessEndpoint".equals(className)) {
                Files.write(source, Arrays.asList(businessEndpointFixtureSource()),
                        Charset.defaultCharset());
            } else {
                Files.write(source, Arrays.asList(
                        "package " + packageName + ";",
                        "public final class " + simpleName + " { }"),
                        Charset.defaultCharset());
            }
            compilerArguments.add(source.toString());
        }
        for (FixtureSource additionalSource : additionalSources) {
            Path source = sourceRoot.resolve(
                    additionalSource.className.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.write(source, Arrays.asList(additionalSource.source),
                    Charset.defaultCharset());
            compilerArguments.add(source.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue("JDK compiler unavailable for self-contained DEX fixture",
                compiler != null);
        ByteArrayOutputStream compilerOutput = new ByteArrayOutputStream();
        int compileExit = compiler.run(null, compilerOutput, compilerOutput,
                compilerArguments.toArray(new String[0]));
        assertEquals("self-contained DEX fixture Java compile failed", 0, compileExit);

        Path classesJar = builderRoot.resolve("fixture-classes.jar");
        try (OutputStream output = Files.newOutputStream(classesJar);
                JarOutputStream jar = new JarOutputStream(output);
                Stream<Path> classFiles = Files.walk(classRoot)) {
            for (Path classFile : (Iterable<Path>) classFiles
                    .filter(Files::isRegularFile)::iterator) {
                String entryName = classRoot.relativize(classFile)
                        .toString().replace('\\', '/');
                jar.putNextEntry(new JarEntry(entryName));
                Files.copy(classFile, jar);
                jar.closeEntry();
            }
        }

        Path d8 = Paths.get(
                "C:/Users/Administrator/AppData/Local/Android/Sdk/"
                        + "build-tools/35.0.0/d8.bat");
        Process d8Process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", d8.toString(),
                "--min-api", "21", "--output", dexRoot.toString(),
                classesJar.toString())
                .directory(builderRoot.toFile())
                .redirectErrorStream(true)
                .start();
        readOutput(d8Process.getInputStream());
        assertEquals("self-contained DEX fixture D8 failed", 0, d8Process.waitFor());

        Path classesDex = dexRoot.resolve("classes.dex");
        assertTrue("self-contained fixture classes.dex missing",
                Files.isRegularFile(classesDex));
        try (OutputStream output = Files.newOutputStream(apk);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            Files.copy(classesDex, zip);
            zip.closeEntry();
        }
    }

    private static AuditResult runAudit(Path script, Path apk) throws Exception {
        Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-Apk", apk.toString())
                .directory(script.getParent().getParent().toFile())
                .redirectErrorStream(true)
                .start();
        String output = readOutput(process.getInputStream());
        return new AuditResult(process.waitFor(), output);
    }

    private static AuditResult runPowerShell(Path script, Path workingDirectory)
            throws Exception {
        Process process = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString())
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = readOutput(process.getInputStream());
        return new AuditResult(process.waitFor(), output);
    }

    private static Path sourceFixture(
            String mainJava,
            String productionKotlin,
            String manifest) throws IOException {
        Path root = projectRoot();
        Path fixtureRoot = Paths.get(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize().resolve(
                        "v17-audit-" + UUID.randomUUID().toString().replace("-", ""));
        Path scripts = fixtureRoot.resolve("scripts");
        Path mainJavaFile = fixtureRoot.resolve(
                "app/src/main/java/fixture/Fixture.java");
        Path productionKotlinFile = fixtureRoot.resolve(
                "app/src/production/java/fixture/ProductionFixture.kt");
        Path manifestFile = fixtureRoot.resolve("app/src/main/AndroidManifest.xml");
        Files.createDirectories(scripts);
        Files.createDirectories(mainJavaFile.getParent());
        Files.createDirectories(productionKotlinFile.getParent());
        Files.copy(root.resolve("scripts/audit-production-apk.ps1"),
                scripts.resolve("audit-production-apk.ps1"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.write(mainJavaFile, Arrays.asList(mainJava), Charset.defaultCharset());
        Files.write(productionKotlinFile, Arrays.asList(productionKotlin),
                Charset.defaultCharset());
        Files.write(manifestFile, Arrays.asList(manifest), Charset.defaultCharset());
        return fixtureRoot;
    }

    private static void copyPinnedRuntimeSerialContractSources(Path fixtureRoot)
            throws IOException {
        List<String> relativePaths = new ArrayList<>(
                Arrays.asList(PINNED_COMMAND_SOURCE_PATHS));
        relativePaths.add(
                "app/src/main/java/com/codex/lockertest/ui/zip/ZipPixelShell.java");
        for (String relative : relativePaths) {
            Path destination = fixtureRoot.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(projectRoot().resolve(relative), destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void rewritePinnedCommandSources(Path fixtureRoot, String style)
            throws Exception {
        for (String relative : PINNED_COMMAND_SOURCE_PATHS) {
            Path path = fixtureRoot.resolve(relative);
            String normalized = decodeNormalizedUtf8(Files.readAllBytes(path));
            String rewritten;
            if ("LF".equals(style)) {
                rewritten = normalized;
            } else if ("CRLF".equals(style)) {
                rewritten = normalized.replace("\n", "\r\n");
            } else if ("MIXED".equals(style)) {
                StringBuilder mixed = new StringBuilder();
                int line = 0;
                for (int index = 0; index < normalized.length(); index++) {
                    char value = normalized.charAt(index);
                    if (value != '\n') {
                        mixed.append(value);
                    } else if (line++ % 3 == 0) {
                        mixed.append("\r\n");
                    } else if (line % 3 == 2) {
                        mixed.append('\n');
                    } else {
                        mixed.append('\r');
                    }
                }
                rewritten = mixed.toString();
            } else {
                throw new IllegalArgumentException(style);
            }
            Files.write(path, rewritten.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, byte[]> readZipEntries(Path archive) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(archive);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        bytes.write(buffer, 0, count);
                    }
                    entries.put(entry.getName(), bytes.toByteArray());
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String normalizedUtf8Sha256(byte[] bytes) throws Exception {
        String text = decodeNormalizedUtf8(bytes);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02X", value & 0xff));
        }
        return hex.toString();
    }

    private static String decodeNormalizedUtf8(byte[] bytes) throws Exception {
        assertFalse("UTF-8 BOM is forbidden", bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }

    private static String safeManifest() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "package=\"fixture\"><application "
                + "android:usesCleartextTraffic=\"false\" /></manifest>";
    }

    private static String validMergedManifest() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "android:versionCode=\"21\" "
                + "android:versionName=\"21.0-frontend-integration-test\" "
                + "package=\"com.codex.lockertest\">"
                + "<uses-sdk android:minSdkVersion=\"21\" "
                + "android:targetSdkVersion=\"30\" />"
                + "<uses-permission android:name=\"android.permission.CAMERA\" />"
                + "<uses-permission android:name=\"android.permission.INTERNET\" />"
                + "<uses-permission "
                + "android:name=\"android.permission.ACCESS_NETWORK_STATE\" />"
                + "<uses-feature android:name=\"android.hardware.camera\" "
                + "android:required=\"false\" />"
                + "<application android:allowBackup=\"false\">"
                + "<activity android:name=\".MainActivity\" "
                + "android:exported=\"true\" android:screenOrientation=\"0\" "
                + "android:configChanges=\"0x4f0\" "
                + "android:windowSoftInputMode=\"0x3\">"
                + "<intent-filter><action android:name=\"android.intent.action.MAIN\" />"
                + "<category android:name=\"android.intent.category.LAUNCHER\" />"
                + "</intent-filter></activity>"
                + "<activity android:name=\".AdminSerialActivity\" "
                + "android:exported=\"false\" android:screenOrientation=\"0\" "
                + "android:configChanges=\"0x4a0\" />"
                + "<activity android:name=\".FaceSdkAdminActivity\" "
                + "android:exported=\"false\" android:screenOrientation=\"0\" "
                + "android:configChanges=\"0x4a0\" />"
                + "<activity android:theme=\"@ref/0x103000f\" "
                + "android:name=\"com.baidu.liantian.LiantianActivity\" "
                + "android:exported=\"true\" android:excludeFromRecents=\"true\" "
                + "android:launchMode=\"0\">"
                + "<intent-filter>"
                + "<action android:name=\"com.baidu.action.Liantian.VIEW\" />"
                + "<category android:name=\"com.baidu.category.liantian\" />"
                + "<category android:name=\"android.intent.category.DEFAULT\" />"
                + "</intent-filter></activity>"
                + "</application></manifest>";
    }

    private static void addCaseCollidingDexEntry(Path apk) throws IOException {
        Path original = apk.resolveSibling("original-" + apk.getFileName());
        Files.move(apk, original);
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(original);
                ZipInputStream zipInput = new ZipInputStream(input);
                OutputStream output = Files.newOutputStream(apk);
                ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                zipOutput.putNextEntry(new ZipEntry(entry.getName()));
                int count;
                while ((count = zipInput.read(buffer)) != -1) {
                    zipOutput.write(buffer, 0, count);
                }
                zipOutput.closeEntry();
                zipInput.closeEntry();
            }
            zipOutput.putNextEntry(new ZipEntry("CLASSES.DEX"));
            zipOutput.write(new byte[] {0});
            zipOutput.closeEntry();
        } finally {
            Files.deleteIfExists(original);
        }
    }

    private static void writeFixtureResource(
            Path fixtureRoot, String relative, String content) throws IOException {
        Path resource = fixtureRoot.resolve(relative);
        Files.createDirectories(resource.getParent());
        Files.write(resource, Arrays.asList(content), Charset.defaultCharset());
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                Charset.defaultCharset());
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String powerShellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), Charset.defaultCharset());
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))
                    && Files.isRegularFile(candidate.resolve("scripts/build-debug.ps1"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }

    private static final class AuditResult {
        final int exitCode;
        final String output;

        AuditResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class FixtureSource {
        final String className;
        final String source;

        FixtureSource(String className, String source) {
            this.className = className;
            this.source = source;
        }
    }

    private static final class ManifestFixture {
        final String xml;
        final String rule;

        ManifestFixture(String xml, String rule) {
            this.xml = xml;
            this.rule = rule;
        }
    }
}
