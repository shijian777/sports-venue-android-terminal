package com.codex.lockertest.integration;

import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.serial.SerialWriteAttribution;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CustomerSerialAttributionSourceTest {
    @Test
    public void attributionIsAnImmutableStructuredSnapshotWithoutPayloadOrTargetReference() {
        LockerTarget target = new LockerTarget(
                LockerZone.B,
                2,
                7,
                FeedbackPolarity.SHORT_WHEN_OPEN);

        SerialWriteAttribution discovery = SerialWriteAttribution.discovery(3);
        SerialWriteAttribution unlock = SerialWriteAttribution.unlock(target);
        SerialWriteAttribution doorStatus = SerialWriteAttribution.doorStatus(target);
        SerialWriteAttribution returnUnlock = SerialWriteAttribution.returnUnlock(target);

        assertEquals(SerialWriteAttribution.Origin.DISCOVERY, discovery.origin());
        assertEquals(3, discovery.boardAddress());
        assertEquals(0, discovery.localLock());
        assertNull(discovery.feedbackPolarity());
        assertEquals(SerialWriteAttribution.Origin.UNLOCK, unlock.origin());
        assertEquals(2, unlock.boardAddress());
        assertEquals(7, unlock.localLock());
        assertEquals(FeedbackPolarity.SHORT_WHEN_OPEN, unlock.feedbackPolarity());
        assertEquals(SerialWriteAttribution.Origin.DOOR_STATUS, doorStatus.origin());
        assertEquals(2, doorStatus.boardAddress());
        assertEquals(7, doorStatus.localLock());
        assertEquals(FeedbackPolarity.SHORT_WHEN_OPEN,
                doorStatus.feedbackPolarity());
        assertEquals(SerialWriteAttribution.Origin.RETURN_UNLOCK,
                returnUnlock.origin());
        assertEquals(2, returnUnlock.boardAddress());
        assertEquals(7, returnUnlock.localLock());
        assertEquals(FeedbackPolarity.SHORT_WHEN_OPEN,
                returnUnlock.feedbackPolarity());

        assertTrue(Modifier.isFinal(SerialWriteAttribution.class.getModifiers()));
        for (Field field : SerialWriteAttribution.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertTrue(field.getName(), Modifier.isPrivate(field.getModifiers()));
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
            assertFalse(field.getName(), field.getType().isArray());
            assertFalse(field.getName(), field.getType() == String.class);
            assertFalse(field.getName(), field.getType() == LockerTarget.class);
        }
    }

    @Test
    public void newCustomerBoundaryIsPureJavaAndHasNoConcreteGatewayDependency()
            throws Exception {
        String transmitter = read(
                "app/src/main/java/com/codex/lockertest/integration/"
                        + "CustomerSerialTransmitter.java");

        assertFalse(transmitter.contains("import android."));
        assertFalse(transmitter.contains("android."));
        assertFalse(transmitter.contains("SerialGateway"));
        assertFalse(transmitter.contains("RuntimeSerialLog"));
        assertTrue(transmitter.contains("interface GatewayWriter"));
        assertTrue(transmitter.contains("interface AuditSink"));
    }

    @Test
    public void transmitterOwnsItsPolicyWithoutPublicAliasOrInjectionSurface()
            throws Exception {
        Constructor<?>[] constructors = CustomerSerialTransmitter.class.getConstructors();
        assertTrue("transmitter needs a public construction boundary",
                constructors.length > 0);
        for (Constructor<?> constructor : constructors) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(constructor.toString(),
                        parameter == CustomerSerialWritePolicy.class);
            }
        }
        for (Method method : CustomerSerialTransmitter.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
                continue;
            }
            assertFalse(method.toString(),
                    method.getReturnType() == CustomerSerialWritePolicy.class);
        }

        Field policy = CustomerSerialTransmitter.class.getDeclaredField("policy");
        assertEquals(CustomerSerialWritePolicy.class, policy.getType());
        assertTrue(Modifier.isPrivate(policy.getModifiers()));
        assertTrue(Modifier.isFinal(policy.getModifiers()));
    }

    @Test
    public void boundarySourcesContainNoCredentialPhotoActivationOrExceptionLogging()
            throws Exception {
        String source = read(
                "app/src/main/java/com/codex/lockertest/serial/"
                        + "SerialWriteAttribution.java")
                + read("app/src/main/java/com/codex/lockertest/integration/"
                        + "CustomerSerialWritePolicy.java")
                + read("app/src/main/java/com/codex/lockertest/integration/"
                        + "CustomerSerialTransmitter.java");
        Pattern activationShape = Pattern.compile(
                "(?<![A-Z0-9])[A-Z0-9]{4}(?:-[A-Z0-9]{4}){3}(?![A-Z0-9])");
        Pattern prohibited = Pattern.compile(
                "(?i)(credential|photo|image|activation|license|base64|"
                        + "getMessage\\s*\\(|printStackTrace|android\\.util\\.Log|"
                        + "System\\.(?:out|err))");

        assertFalse(activationShape.matcher(source).find());
        assertFalse(prohibited.matcher(source).find());
    }

    private static String read(String relative) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        for (int index = 0; index < 8 && candidate != null; index++) {
            if (Files.isRegularFile(candidate.resolve("app/build.gradle"))) return candidate;
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("project root not found");
    }
}
