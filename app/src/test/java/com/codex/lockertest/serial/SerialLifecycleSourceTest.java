package com.codex.lockertest.serial;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SerialLifecycleSourceTest {
    @Test
    public void pageAndActivityLifecycleOnlyDetachFromTheProcessSerialOwner()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String admin = read("app/src/main/java/com/codex/lockertest/AdminSerialActivity.java");

        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(main, "onCreate"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(main, "onStart"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(main, "onStop"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(main, "onDestroy"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(main, "returnHome"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(main, "finishPresentedReturnJourneyToHome"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(main, "finishReturnJourneyToHome"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(main, "showAdminPin"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(main, "launchAdminActivity"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(main, "launchFaceSdkAdminActivity"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(main, "invalidateCustomerWork"));
        String detachCustomer = javaMethodBody(
                main, "detachCustomerGatewayAfterInvalidation");
        assertLifecycleDoesNotReleasePhysicalGateway(detachCustomer);
        assertTrue(detachCustomer.contains("subscription.unsubscribe()"));
        assertTrue(detachCustomer.contains("SERIAL_GATEWAY_OWNER.relinquish(lease)"));

        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(admin, "onCreate"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(admin, "onStart"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(admin, "onStop"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(admin, "onDestroy"));
        assertLifecycleDoesNotReleasePhysicalGateway(javaMethodBody(admin, "buildScreen"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(admin, "buildSerialPrompt"));
        String detachAdmin = javaMethodBody(admin, "detachAdminGateway");
        assertLifecycleDoesNotReleasePhysicalGateway(detachAdmin);
        assertTrue(detachAdmin.contains("subscription.unsubscribe()"));
        assertTrue(detachAdmin.contains("SERIAL_GATEWAY_OWNER.relinquish(lease)"));
    }

    @Test
    public void manualCloseAndExplicitDefaultReconfigurationRemainTheOnlyActivityCloses()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String admin = read("app/src/main/java/com/codex/lockertest/AdminSerialActivity.java");

        String manualToggle = javaMethodBody(admin, "toggleConnection");
        assertTrue(manualToggle.contains("current.closePort()"));
        assertTrue(manualToggle.contains("AdminCapability.MANAGE_SERIAL_CONNECTION"));
        assertTrue(manualToggle.contains("onManualCloseAccepted()"));

        String customerReconfiguration =
                javaMethodBody(main, "applyCustomerConnectionAction");
        assertOrdered(customerReconfiguration,
                "case CLOSE_FOR_DEFAULTS:",
                "SerialGateway::closePort",
                "case OPEN_DEFAULTS:");
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(main, "handleCustomerGatewayWakeup"));
        assertLifecycleDoesNotReleasePhysicalGateway(
                javaMethodBody(admin, "onConnectionChanged"));
    }

    @Test
    public void allActivityPhysicalReleaseCallsStayInsideTheTwoApprovedMethods()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String admin = read("app/src/main/java/com/codex/lockertest/AdminSerialActivity.java");
        String customerReconfiguration =
                javaMethodBody(main, "applyCustomerConnectionAction");
        String manualToggle = javaMethodBody(admin, "toggleConnection");

        assertEquals(1, physicalReleaseCallCount(customerReconfiguration));
        assertEquals(physicalReleaseCallCount(customerReconfiguration),
                physicalReleaseCallCount(main));
        assertEquals(1, physicalReleaseCallCount(manualToggle));
        assertEquals(physicalReleaseCallCount(manualToggle),
                physicalReleaseCallCount(admin));
    }

    @Test
    public void relinquishingAnActivityLeaseKeepsTheProcessGatewayOwned()
            throws Exception {
        String owner = read("app/src/main/java/com/codex/lockertest/integration/"
                + "ProcessSerialGatewayOwner.java");
        String relinquish = javaMethodBody(owner, "relinquish");
        String acquire = javaMethodBody(owner, "acquire");

        assertTrue(owner.contains("private static final ProcessSerialGatewayOwner<Object> SHARED"));
        assertTrue(owner.contains("new ProcessSerialGatewayOwner<>()"));
        assertTrue(owner.contains("private ProcessSerialGatewayOwner()"));
        assertTrue(acquire.contains("if (owner == null)"));
        assertTrue(relinquish.contains("activeLease = null"));
        assertTrue(relinquish.contains("activeRole = null"));
        assertFalse(relinquish.contains("owner = null"));
        assertLifecycleDoesNotReleasePhysicalGateway(relinquish);
    }

    @Test
    public void activitiesUseTheSharedOwnerInsteadOfConstructingAnotherSerialOwner()
            throws Exception {
        String main = read("app/src/main/java/com/codex/lockertest/MainActivity.java");
        String admin = read("app/src/main/java/com/codex/lockertest/AdminSerialActivity.java");

        assertTrue(main.contains("ProcessSerialGatewayOwner.shared()"));
        assertTrue(admin.contains("ProcessSerialGatewayOwner.shared()"));
        assertFalse(main.contains("new ProcessSerialGatewayOwner"));
        assertFalse(admin.contains("new ProcessSerialGatewayOwner"));
        assertFalse(main.contains("new SerialGateway("));
        assertFalse(admin.contains("new SerialGateway("));
    }

    private static void assertLifecycleDoesNotReleasePhysicalGateway(String source) {
        assertEquals(0, physicalReleaseCallCount(source));
    }

    private static int physicalReleaseCallCount(String source) {
        Pattern call = Pattern.compile(
                "(?:\\b(?:closePort|dispose)\\s*\\("
                        + "|::\\s*(?:closePort|dispose)\\b)");
        java.util.regex.Matcher matcher = call.matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String javaMethodBody(String source, String methodName) {
        Pattern declaration = Pattern.compile(
                "(?m)^\\s*(?:public|protected|private)\\s+"
                        + "(?:(?:static|final|synchronized)\\s+)*"
                        + "[A-Za-z0-9_$.<>?,\\[\\] ]+\\s+"
                        + Pattern.quote(methodName) + "\\s*\\(");
        java.util.regex.Matcher matcher = declaration.matcher(source);
        if (!matcher.find()) fail("missing method: " + methodName);
        int open = source.indexOf('{', matcher.end());
        if (open < 0) fail("missing method body: " + methodName);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new AssertionError("unterminated method: " + methodName);
    }

    private static void assertOrdered(String source, String... tokens) {
        int position = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, position + 1);
            assertTrue("missing/out-of-order token: " + token, found >= 0);
            position = found;
        }
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
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
