package com.codex.lockertest.face.verification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class FaceVerificationResultTest {
    @Test
    public void passedResultPreservesTheIssuedImmutableSnapshot() {
        FaceVerificationResult result = FaceVerificationResult.passed(
                FaceVerificationSource.LOCAL_DEMO,
                "request-1",
                "opaque-credential",
                "00112233445566778899aabbccddeeff",
                61_000L,
                "device-binding",
                "process-binding",
                7L);

        assertEquals(FaceVerificationStatus.PASSED, result.status());
        assertEquals(FaceVerificationSource.LOCAL_DEMO, result.source());
        assertEquals("request-1", result.requestId());
        assertEquals("opaque-credential", result.credential());
        assertEquals("00112233445566778899aabbccddeeff", result.ticketId());
        assertEquals(61_000L, result.expiresAtEpochMillis());
        assertEquals("device-binding", result.deviceBinding());
        assertEquals("process-binding", result.processBinding());
        assertEquals(7L, result.verificationPolicyEpoch());
        assertTrue(result.isPassed());
        assertNull(result.message());
    }

    @Test
    public void passedIssuerIsNotPublic() {
        for (Method method : FaceVerificationResult.class.getDeclaredMethods()) {
            if (method.getName().equals("passed")) {
                assertFalse("passed issuer must remain package-private",
                        Modifier.isPublic(method.getModifiers()));
                return;
            }
        }
        fail("missing passed issuer");
    }

    @Test
    public void passedRejectsMalformedIdsMissingSecretsAndInvalidExpiry() {
        assertPassedRejected(null, "credential", "00112233445566778899aabbccddeeff", 1L);
        assertPassedRejected("bad request\n", "credential",
                "00112233445566778899aabbccddeeff", 1L);
        assertPassedRejected("request-1", "", "00112233445566778899aabbccddeeff", 1L);
        assertPassedRejected("request-1", "credential", "not-128-bit", 1L);
        assertPassedRejected("request-1", "credential",
                "00112233445566778899aabbccddeeff", 0L);
        try {
            FaceVerificationResult.passed(null, "request-1", "credential",
                    "00112233445566778899aabbccddeeff", 1L,
                    "device-binding", "process-binding", 0L);
            fail("missing source must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
        try {
            FaceVerificationResult.passed(FaceVerificationSource.LOCAL_DEMO,
                    "request-1", "credential", "00112233445566778899aabbccddeeff", 1L,
                    "", "process-binding", 0L);
            fail("missing device binding must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }

    @Test
    public void terminalFailureCannotPretendToBePassed() {
        FaceVerificationResult failure = FaceVerificationResult.terminalFailure(
                FaceVerificationStatus.SERVER_ERROR, "request-2");

        assertEquals(FaceVerificationStatus.SERVER_ERROR, failure.status());
        assertEquals("request-2", failure.requestId());
        assertFalse(failure.isPassed());
        assertNull(failure.source());
        assertNull(failure.credential());
        assertNull(failure.ticketId());
        assertNull(failure.message());
        try {
            FaceVerificationResult.terminalFailure(FaceVerificationStatus.PASSED, "request-2");
            fail("terminal failure cannot use PASSED");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }

    @Test
    public void terminalFailureCanCarryAnExactNonemptyPresentationMessage() {
        FaceVerificationResult failure = FaceVerificationResult.terminalFailure(
                FaceVerificationStatus.SERVER_ERROR,
                "request-message",
                "服务器人脸接口未配置");

        assertEquals(FaceVerificationStatus.SERVER_ERROR, failure.status());
        assertEquals("request-message", failure.requestId());
        assertEquals("服务器人脸接口未配置", failure.message());
        assertFalse(failure.isPassed());
        assertTerminalMessageRejected(null);
        assertTerminalMessageRejected("");
        assertTerminalMessageRejected("   ");
    }

    @Test
    public void terminalFailureRejectsUnicodeBlankMessagesWithoutNormalizingText() {
        assertTerminalMessageRejected(" \t\r\n\f");
        assertTerminalMessageRejected("\u3000");
        assertTerminalMessageRejected("\u00a0");
        assertTerminalMessageRejected("\u2003");

        String original = "\u3000服务器人脸接口未配置\u00a0";
        FaceVerificationResult failure = FaceVerificationResult.terminalFailure(
                FaceVerificationStatus.SERVER_ERROR, "request-unicode-message", original);

        assertEquals(original, failure.message());
    }

    @Test
    public void requestDefensivelyCopiesTheJpegAndRejectsMalformedBindings() throws Exception {
        byte[] jpeg = new byte[] {1, 2, 3};
        FaceVerificationRequest request = new FaceVerificationRequest(
                "request-3", jpeg, 900L, "device-binding", "process-binding");
        jpeg[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, requestBacking(request));
        assertEquals("request-3", request.requestId());
        assertEquals(900L, request.clientEpochMillis());
        assertEquals("device-binding", request.deviceBinding());
        assertEquals("process-binding", request.processBinding());
        try {
            new FaceVerificationRequest("bad request\n", jpeg, 1L,
                    "device-binding", "process-binding");
            fail("malformed request ID must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        } finally {
            request.close();
        }
    }

    @Test
    public void requestIsAutoCloseableAndWipesOnlyJPEGItHasNotTransferred() throws Exception {
        byte[] source = new byte[] {1, 2, 3, 4};
        FaceVerificationRequest closed = new FaceVerificationRequest(
                "request-close", source, 1L, "device-binding", "process-binding");
        byte[] closedBacking = requestBacking(closed);

        assertTrue(AutoCloseable.class.isAssignableFrom(FaceVerificationRequest.class));
        closed.close();
        closed.close();
        assertArrayEquals(new byte[closedBacking.length], closedBacking);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, source);
        assertNull(requestBacking(closed));
        try {
            closed.takeOwnedJpeg();
            fail("closed request cannot transfer JPEG ownership");
        } catch (IllegalStateException expected) {
            // One-shot ownership is terminal after close.
        }

        FaceVerificationRequest rejectedTransfer = new FaceVerificationRequest(
                "request-rejected", source, 2L, "device-binding", "process-binding");
        byte[] rejectedOwned = rejectedTransfer.takeOwnedJpeg();
        rejectedTransfer.close();
        assertArrayEquals(new byte[rejectedOwned.length], rejectedOwned);

        FaceVerificationRequest committedTransfer = new FaceVerificationRequest(
                "request-committed", source, 3L, "device-binding", "process-binding");
        byte[] transferredBacking = requestBacking(committedTransfer);
        byte[] owned = committedTransfer.takeOwnedJpeg();
        assertTrue(transferredBacking == owned);
        committedTransfer.commitOwnedJpegTransfer();
        committedTransfer.close();
        assertArrayEquals(new byte[] {1, 2, 3, 4}, owned);
        assertNull(requestBacking(committedTransfer));
        try {
            committedTransfer.takeOwnedJpeg();
            fail("JPEG ownership can be transferred only once");
        } catch (IllegalStateException expected) {
            // Expected one-shot contract.
        } finally {
            Arrays.fill(owned, (byte) 0);
        }
    }

    @Test
    public void buildRunsAnIndependentFailClosedProductionDexGuard() throws Exception {
        String script = new String(Files.readAllBytes(
                Paths.get("scripts", "build-debug.ps1")), StandardCharsets.UTF_8);

        assertContains(script,
                "$appClassesJar = Join-Path $variantRoot \"app-classes.jar\"");
        assertContains(script,
                "if ($name -ceq 'production') { 'production-dex' } else { 'dex' }");
        assertContains(script,
                "$dexInputs = @($appClassesJar, $aarClassesJar, $licenseJar, $liantianJar)");
        assertContains(script,
                "if ($palmClassesJar) { $dexInputs += $palmClassesJar }");
        assertContains(script,
                "& $d8 --lib $androidJar --min-api 21 --output $dexDirectory @dexInputs");
        // Palm remains a localDemo dependency; production starts without its classes.
        assertContains(script, "$palmClassesJar = $null");
        int palmGate = script.indexOf("if ($name -ceq 'localDemo') {",
                script.indexOf("$palmClassesJar = $null"));
        int palmAssignment = script.indexOf("$palmClassesJar = Join-Path $palmRoot 'classes.jar'");
        assertTrue("Palm classes must be assigned inside the localDemo extraction gate",
                palmGate >= 0 && palmAssignment > palmGate
                        && script.substring(palmGate, palmAssignment).indexOf("\n    }") < 0);
        assertContains(script,
                "Get-ChildItem -LiteralPath $dexDirectory "
                        + "-Filter \"classes*.dex\" -File");
        assertContains(script, "LocalPassFaceVerificationClient");
        assertContains(script, "local-demo:");
        assertContains(script, "PRODUCTION_DEX_FAIL_CLOSED=PASS");
    }

    @Test
    public void productionAssemblyWiresAClientThatClosesTheRequestBeforeItsCallback()
            throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("app", "src", "production",
                "java", "com", "codex", "lockertest", "face", "verification",
                "FaceVerificationAssembly.java")), StandardCharsets.UTF_8);

        assertContains(source, "new UnavailableFaceVerificationClient()");
        assertFalse(source.contains("FaceJpegContract"));
        assertFalse(source.contains("request.takeOwnedJpeg()"));
        assertFalse(source.contains("Arrays.fill"));
        assertFalse(source.contains("LocalPassFaceVerificationClient"));
    }

    @Test
    public void localDemoAssemblyMapsHandlerQueueRejectionToSchedulerFailure()
            throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("app", "src", "localDemo",
                "java", "com", "codex", "lockertest", "face", "verification",
                "FaceVerificationAssembly.java")), StandardCharsets.UTF_8);
        String rejectionCheck = "if (!handler.postDelayed(task, delayMillis))";
        String rejectedResult = "return null;";
        String acceptedHandle = "return () -> handler.removeCallbacks(task);";

        assertContains(source, rejectionCheck);
        assertContains(source, rejectedResult);
        assertContains(source, acceptedHandle);
        assertTrue("queue rejection must be mapped before an accepted cancellation handle",
                source.indexOf(rejectionCheck) < source.indexOf(rejectedResult)
                        && source.indexOf(rejectedResult) < source.indexOf(acceptedHandle));
    }

    private static void assertPassedRejected(
            String requestId, String credential, String ticketId, long expiresAt) {
        try {
            FaceVerificationResult.passed(FaceVerificationSource.LOCAL_DEMO,
                    requestId, credential, ticketId, expiresAt,
                    "device-binding", "process-binding", 0L);
            fail("invalid passed result must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }

    private static void assertTerminalMessageRejected(String message) {
        try {
            FaceVerificationResult.terminalFailure(
                    FaceVerificationStatus.SERVER_ERROR, "request-message", message);
            fail("terminal failure message must be nonempty");
        } catch (IllegalArgumentException expected) {
            // Expected contract rejection.
        }
    }

    private static void assertContains(String source, String token) {
        assertTrue("missing production DEX guard token: " + token, source.contains(token));
    }

    private static byte[] requestBacking(FaceVerificationRequest request) throws Exception {
        Field field = FaceVerificationRequest.class.getDeclaredField("jpeg");
        field.setAccessible(true);
        return (byte[]) field.get(request);
    }
}
