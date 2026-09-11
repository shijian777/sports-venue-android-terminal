package com.codex.lockertest.runtime;

import com.codex.lockertest.unlock.AuthorizedUnlockRequest;
import com.codex.lockertest.unlock.UnlockCoordinator;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AuthorizedOnlyUnlockSourceTest {
    @Test
    public void coordinatorExposesOnlyTheExactAuthorizedStartAndThreeDependencyConstructor()
            throws Exception {
        Method authorized = UnlockCoordinator.class.getDeclaredMethod(
                "startAuthorized", AuthorizedUnlockRequest.class);
        assertTrue(Modifier.isPublic(authorized.getModifiers()));
        assertTrue(Modifier.isSynchronized(authorized.getModifiers()));
        for (Method method : UnlockCoordinator.class.getDeclaredMethods()) {
            assertFalse("credential start remains: " + method, method.getName().equals("start"));
        }

        Constructor<?>[] constructors = UnlockCoordinator.class.getConstructors();
        assertEquals(1, constructors.length);
        assertArrayEquals(new Class<?>[] {
                        UnlockCoordinator.SerialActions.class,
                        UnlockCoordinator.Scheduler.class,
                        UnlockCoordinator.Listener.class
                }, constructors[0].getParameterTypes());
    }

    @Test
    public void commonSourceContainsNoDemoLegacyOrLocalPinAuthority() throws Exception {
        String main = allJavaUnder("app/src/main/java");

        assertFalse(main.contains("DemoCredentials"));
        assertFalse(main.contains("DemoFeatureFlags"));
        assertFalse(main.contains("LegacyV6LockerLayoutSource"));
        assertFalse(main.contains("123456"));
        assertFalse(main.contains("UnlockCredentialAdapter"));
    }

    @Test
    public void customerConfirmationAuthorizesOnceBeforeAnySerialOrOperationMutation()
            throws Exception {
        String submit = javaMethodBody(read("app/src/main/java/com/codex/lockertest/MainActivity.java"),
                "submitLocker");

        assertEquals(1, occurrences(submit, "customerUnlockAuthorizer.authorize("));
        assertEquals(1, occurrences(submit, "authorization.request()"));
        assertEquals(1, occurrences(submit, "System.currentTimeMillis()"));
        assertOrdered(submit,
                "source != lockerSelectionView",
                "long confirmationEpochMillis = System.currentTimeMillis()",
                "flow.checkLockerConfirmationAt(target, confirmationEpochMillis)",
                "KioskFlowModel.ConfirmResult.FACE_CREDENTIAL_EXPIRED",
                "customerUnlockAuthorizer.authorize(",
                "if (authorization == null)",
                "if (!authorization.authorized())",
                "authorization.message()",
                "authorization.request()",
                "samePhysicalTarget(selectedTarget, request.target())",
                "pendingFaceSuccess = null",
                "flow.confirmLockerAt(target, confirmationEpochMillis)",
                "invalidateCustomerWork(true)",
                "customerSerialTransmitter.beginOperation(",
                "reserveCustomerOperation(",
                "unlockCoordinator.startAuthorized(request)");
        assertContains(submit, "catch (RuntimeException | LinkageError");
        assertContains(submit, "source.showSelectionError(authorization.message())");
        assertContains(submit, "source.showSelectionError(\"开柜授权失败，请重试\")");
        assertFalse(submit.contains("LockerProtocol"));
        assertFalse(submit.contains("unlockCoordinator.start("));

        String beforeAuthorization = sourceSlice(submit,
                "LockerTarget selectedTarget = target",
                "customerUnlockAuthorizer.authorize(");
        assertFalse(beforeAuthorization.contains("invalidateCustomerWork(true)"));
        assertFalse(beforeAuthorization.contains("customerSerialTransmitter.beginOperation("));
        assertFalse(beforeAuthorization.contains("reserveCustomerOperation("));
        assertFalse(beforeAuthorization.contains("unlockCoordinator.startAuthorized("));

        String denied = sourceSlice(submit,
                "if (!authorization.authorized())", "request = authorization.request()");
        assertFalse(denied.contains("pendingFaceSuccess = null"));
        assertZeroCustomerMutation(denied);

        String nullAuthorization = sourceSlice(submit,
                "if (authorization == null)", "if (!authorization.authorized())");
        assertContains(nullAuthorization, "pendingFaceSuccess = null");
        assertZeroCustomerMutation(nullAuthorization);

        String mismatch = sourceSlice(submit,
                "if (request == null || !samePhysicalTarget", "} catch");
        assertContains(mismatch, "pendingFaceSuccess = null");
        assertZeroCustomerMutation(mismatch);

        String exceptional = sourceSlice(submit,
                "catch (RuntimeException | LinkageError", "KioskFlowModel.ConfirmResult commit");
        assertContains(exceptional, "pendingFaceSuccess = null");
        assertZeroCustomerMutation(exceptional);

        String expired = sourceSlice(submit,
                "if (preflight", "if (preflight != KioskFlowModel.ConfirmResult.ACCEPTED)");
        assertContains(expired, "flow.confirmLockerAt(target, confirmationEpochMillis)");
        assertFalse(expired.contains("customerUnlockAuthorizer.authorize("));
        assertEquals(2, occurrences(submit,
                "flow.confirmLockerAt(target, confirmationEpochMillis)"));

        String commitFailure = sourceSlice(submit,
                "if (commit != KioskFlowModel.ConfirmResult.ACCEPTED)",
                "invalidateCustomerWork(true)");
        assertZeroCustomerMutation(commitFailure);
    }

    @Test
    public void lockerSelectionViewConfirmsWithoutStartingTheSendingState() throws Exception {
        String confirm = javaMethodBody(
                read("app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java"),
                "confirm");

        assertContains(confirm, "model.canConfirm()");
        assertContains(confirm, "onConfirmLocker(target)");
        assertFalse(confirm.contains("model.beginSending()"));
        assertOrdered(confirm, "model.selectedTarget()", "model.canConfirm()",
                "onConfirmLocker(target)");
    }

    @Test
    public void returnFaceUsesIssuedTicketValidatorWithoutGrantingSerialAuthority()
            throws Exception {
        String complete = javaMethodBody(
                read("app/src/main/java/com/codex/lockertest/MainActivity.java"),
                "completeReturnFaceRecognition");

        assertContains(complete, "verificationEnvironment.validateTicket(");
        assertContains(complete, "success.expectedRequestId()");
        assertContains(complete, "faceSubsystem.deviceBinding()");
        assertContains(complete, "faceSubsystem.processBinding()");
        assertContains(complete, "FaceVerificationTicketValidator.TicketVerdict.VALID");
        assertContains(complete, "new ReturnIdentity(");
        assertOrdered(complete, "long now = System.currentTimeMillis()",
                "if (now < 0L)", "return;", "verificationEnvironment.validateTicket(",
                "new ReturnIdentity(");
        assertFalse(complete.contains("customerUnlockAuthorizer"));
        assertFalse(complete.contains("startAuthorized("));
        assertFalse(complete.contains("unlockCoordinator"));
    }

    @Test
    public void frozenGuardAllowsOnlyExactTask8PathsAndReviewedOnlineSerialAddition() throws Exception {
        String script = read("scripts/build-debug.ps1");
        int functionStart = script.indexOf("function Assert-FrozenBaseline");
        int functionEnd = script.indexOf("function Get-JavaMethodBody", functionStart);
        assertTrue(functionStart >= 0 && functionEnd > functionStart);
        String guard = script.substring(functionStart, functionEnd);

        assertEquals(3, occurrences(guard, "$relativePath -ceq"));
        assertEquals(1, occurrences(guard,
                "$relativePath -ceq 'app/src/main/java/com/codex/lockertest/AdminSerialActivity.java'"));
        assertEquals(1, occurrences(guard,
                "$relativePath -ceq 'app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java'"));
        assertEquals(1, occurrences(guard,
                "$relativePath -ceq 'app/src/main/java/com/codex/lockertest/serial/SerialGateway.java'"));
        assertContains(guard, "$actualHash -ceq 'B881BD5858E9B8B410F41968E047139FC3AD5BD60D378ADF43BB7B88B92FD44B'");
        assertContains(guard, "Get-TextSha256 (Get-JavaMethodBody $resolvedPath 'send')");
        assertContains(guard, "5D187CCD45D50F6AADF8FDF70E68D3CEEFF584809A1A83A091CFF094DBB814F8");
        assertContains(guard, "$matches -ne 21");
        assertContains(guard, "$task8UiOverrides -ne 2");
        assertContains(guard, "$onlineSerialOverrides -ne 1");
        assertFalse(guard.contains("$relativePath -like"));
        assertFalse(guard.contains("$relativePath -match"));
    }

    private static String allJavaUnder(String relative) throws IOException {
        StringBuilder combined = new StringBuilder();
        try (java.util.stream.Stream<Path> files = Files.walk(projectRoot().resolve(relative))) {
            files.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            combined.append(new String(Files.readAllBytes(path),
                                    StandardCharsets.UTF_8));
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        }
        return combined.toString();
    }

    private static String read(String relative) throws IOException {
        Path path = projectRoot().resolve(relative);
        assertNotNull(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String javaMethodBody(String source, String methodName) {
        int name = source.indexOf(methodName + "(");
        while (name >= 0) {
            int lineStart = source.lastIndexOf('\n', name) + 1;
            String prefix = source.substring(lineStart, name).trim();
            if (prefix.startsWith("private ") || prefix.startsWith("public ")
                    || prefix.startsWith("protected ")) {
                break;
            }
            name = source.indexOf(methodName + "(", name + methodName.length());
        }
        if (name < 0) throw new AssertionError("missing method: " + methodName);
        int open = source.indexOf('{', name);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new AssertionError("unterminated method: " + methodName);
    }

    private static void assertOrdered(String source, String... markers) {
        int offset = -1;
        for (String marker : markers) {
            offset = source.indexOf(marker, offset + 1);
            assertTrue("missing or out of order: " + marker, offset >= 0);
        }
    }

    private static int occurrences(String source, String marker) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    private static void assertContains(String source, String marker) {
        assertTrue("missing: " + marker, source.contains(marker));
    }

    private static String sourceSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing: " + startMarker, start >= 0);
        assertTrue("missing after " + startMarker + ": " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static void assertZeroCustomerMutation(String source) {
        assertFalse(source.contains("flow.confirmLockerAt("));
        assertFalse(source.contains("flow.finishUnlockFailure("));
        assertFalse(source.contains("invalidateCustomerWork("));
        assertFalse(source.contains("customerSerialTransmitter.beginOperation("));
        assertFalse(source.contains("reserveCustomerOperation("));
        assertFalse(source.contains("unlockCoordinator.startAuthorized("));
        assertFalse(source.contains("customerConfirmedTarget ="));
        assertFalse(source.contains("customerWriteAttribution ="));
        assertFalse(source.contains("customerSerialPhase ="));
        assertFalse(source.contains("customerProtocolId ="));
        assertFalse(source.contains("finishActiveCustomerOperation("));
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int index = 0; index < 8 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
