package com.codex.lockertest;

import com.codex.lockertest.face.verification.FaceVerificationClient;
import com.codex.lockertest.face.verification.FaceVerificationRequest;
import com.codex.lockertest.face.verification.FaceVerificationResult;
import com.codex.lockertest.face.verification.FaceVerificationStatus;
import com.codex.lockertest.face.verification.UnavailableFaceVerificationClient;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ProductionVariantWiringSourceTest {
    private static final String FACE_UNAVAILABLE = "服务器人脸接口未配置";

    @Test
    public void productionAssembliesContainNoLocalPassClient() throws Exception {
        String production = allJavaUnder("app/src/production/java");

        assertFalse(production.contains("LocalPassFaceVerificationClient"));
        assertFalse(production.contains("LocalDemoReturnServiceClient"));
        assertTrue(production.contains(FACE_UNAVAILABLE));
        assertTrue(production.contains("服务器还柜协议未配置"));
        assertTrue(read("app/src/production/java/com/codex/lockertest/face/verification/"
                + "FaceVerificationAssembly.java")
                .contains("new UnavailableFaceVerificationClient()"));
    }

    @Test
    public void localDemoAssembliesKeepTheirExistingLocalSimulation() throws Exception {
        String face = read("app/src/localDemo/java/com/codex/lockertest/face/verification/"
                + "FaceVerificationAssembly.java");
        String returns = read("app/src/localDemo/java/com/codex/lockertest/returnflow/"
                + "ReturnServiceAssembly.java");

        assertTrue(face.contains("new LocalPassFaceVerificationClient("));
        assertTrue(returns.contains("new LocalDemoReturnServiceClient()"));
    }

    @Test
    public void productionFaceFailureClosesAndZeroesItsRequestBeforeCallback()
            throws Exception {
        FaceVerificationRequest request = request("face-normal", new byte[] {7, 6, 5, 4});
        byte[] backing = requestBacking(request);
        AtomicReference<FaceVerificationResult> callbackResult = new AtomicReference<>();

        FaceVerificationClient.Cancellable cancellable =
                new UnavailableFaceVerificationClient().verify(request, result -> {
                    assertClosedAndCleared(request, backing);
                    callbackResult.set(result);
                });

        assertNotNull(cancellable);
        assertUnavailable(callbackResult.get(), "face-normal");
        assertClosedAndCleared(request, backing);
        request.close();
        request.close();
        cancellable.cancel();
        assertClosedAndCleared(request, backing);
    }

    @Test
    public void productionFaceFailureRemainsClosedWhenCallbackThrows() throws Exception {
        FaceVerificationRequest request = request("face-throwing", new byte[] {3, 1, 4});
        byte[] backing = requestBacking(request);
        AtomicReference<FaceVerificationResult> callbackResult = new AtomicReference<>();

        FaceVerificationClient.Cancellable cancellable =
                new UnavailableFaceVerificationClient().verify(request, result -> {
                    assertClosedAndCleared(request, backing);
                    callbackResult.set(result);
                    throw new IllegalStateException("consumer failure");
                });

        assertNotNull(cancellable);
        assertUnavailable(callbackResult.get(), "face-throwing");
        assertClosedAndCleared(request, backing);
        request.close();
        assertClosedAndCleared(request, backing);
    }

    @Test
    public void preclosedProductionFaceRequestStillReportsOneFailClosedResult()
            throws Exception {
        FaceVerificationRequest request = request("face-preclosed", new byte[] {2, 7, 1, 8});
        byte[] backing = requestBacking(request);
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<FaceVerificationResult> callbackResult = new AtomicReference<>();
        request.close();

        FaceVerificationClient.Cancellable cancellable =
                new UnavailableFaceVerificationClient().verify(request, result -> {
                    callbackCount.incrementAndGet();
                    callbackResult.set(result);
                });

        assertNotNull(cancellable);
        assertEquals(1, callbackCount.get());
        assertUnavailable(callbackResult.get(), "face-preclosed");
        assertClosedAndCleared(request, backing);
    }

    @Test
    public void cancellingCompletedFaceHandleNeverReplaysItsSynchronousCallback()
            throws Exception {
        FaceVerificationRequest request = request("face-cancelled", new byte[] {1, 6, 1, 8});
        byte[] backing = requestBacking(request);
        AtomicInteger callbackCount = new AtomicInteger();
        AtomicReference<FaceVerificationResult> callbackResult = new AtomicReference<>();

        FaceVerificationClient.Cancellable cancellable =
                new UnavailableFaceVerificationClient().verify(request, result -> {
                    callbackCount.incrementAndGet();
                    callbackResult.set(result);
                });
        assertNotNull(cancellable);
        cancellable.cancel();
        cancellable.cancel();
        cancellable.cancel();

        assertEquals(1, callbackCount.get());
        assertUnavailable(callbackResult.get(), "face-cancelled");
        assertClosedAndCleared(request, backing);
    }

    @Test
    public void nullCallbackDoesNotTakeRequestOwnership() throws Exception {
        FaceVerificationRequest request = request("face-no-callback", new byte[] {9, 8, 7});
        byte[] backing = requestBacking(request);

        try {
            new UnavailableFaceVerificationClient().verify(request, null);
            fail("missing callback must be rejected");
        } catch (IllegalArgumentException expected) {
            // Validation happens before the production client takes request ownership.
        }

        assertSame(backing, requestBacking(request));
        assertArrayEquals(new byte[] {9, 8, 7}, backing);
        assertEquals("AVAILABLE", ownershipState(request));
        request.close();
        assertClosedAndCleared(request, backing);
    }

    @Test
    public void nullRequestIsRejected() {
        try {
            new UnavailableFaceVerificationClient().verify(null, result -> { });
            fail("missing request must be rejected");
        } catch (IllegalArgumentException expected) {
            // No request exists for the client to own.
        }
    }

    private static FaceVerificationRequest request(String requestId, byte[] jpeg) {
        return new FaceVerificationRequest(
                requestId, jpeg, 1L, "device-binding", "process-binding");
    }

    private static void assertUnavailable(FaceVerificationResult result, String requestId) {
        assertNotNull(result);
        assertEquals(FaceVerificationStatus.SERVER_ERROR, result.status());
        assertEquals(requestId, result.requestId());
        assertEquals(FACE_UNAVAILABLE, result.message());
        assertFalse(result.isPassed());
    }

    private static void assertClosedAndCleared(
            FaceVerificationRequest request, byte[] backing) {
        assertArrayEquals(new byte[backing.length], backing);
        assertNull(requestBacking(request));
        assertEquals("CLOSED", ownershipState(request));
    }

    private static byte[] requestBacking(FaceVerificationRequest request) {
        try {
            Field jpeg = FaceVerificationRequest.class.getDeclaredField("jpeg");
            jpeg.setAccessible(true);
            return (byte[]) jpeg.get(request);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String ownershipState(FaceVerificationRequest request) {
        try {
            Field ownership = FaceVerificationRequest.class.getDeclaredField("jpegOwnership");
            ownership.setAccessible(true);
            return String.valueOf(ownership.get(request));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
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
