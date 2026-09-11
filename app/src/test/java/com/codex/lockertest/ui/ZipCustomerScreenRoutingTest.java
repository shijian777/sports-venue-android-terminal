package com.codex.lockertest.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.codex.lockertest.ui.zip.ZipCustomerScreenRouter;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import org.junit.Test;

/** Pure-JVM contract for the complete v16 customer screen 1-13 route table. */
public final class ZipCustomerScreenRoutingTest {
    @Test
    public void mapsEveryCustomerStateToItsExactUniqueAsset() {
        assertRoute(ZipCustomerScreenRouter.State.HOME_WAITING,
                ZipScreenAsset.HOME_WAITING, 1);
        assertRoute(ZipCustomerScreenRouter.State.HOME_READING_CREDENTIAL,
                ZipScreenAsset.HOME_READING_CREDENTIAL, 2);
        assertRoute(ZipCustomerScreenRouter.State.HOME_UNREGISTERED_COUNTDOWN,
                ZipScreenAsset.HOME_UNREGISTERED_COUNTDOWN, 3);
        assertRoute(ZipCustomerScreenRouter.State.HOME_CREDENTIAL_ERROR,
                ZipScreenAsset.HOME_CREDENTIAL_ERROR, 4);
        assertRoute(ZipCustomerScreenRouter.State.FACE_PREPARING,
                ZipScreenAsset.FACE_PREPARING, 5);
        assertRoute(ZipCustomerScreenRouter.State.FACE_DETECTING,
                ZipScreenAsset.FACE_DETECTING, 6);
        assertRoute(ZipCustomerScreenRouter.State.FACE_UPLOADING,
                ZipScreenAsset.FACE_UPLOADING, 7);
        assertRoute(ZipCustomerScreenRouter.State.FACE_CAMERA_PERMISSION_TEMPORARY,
                ZipScreenAsset.FACE_CAMERA_PERMISSION_TEMPORARY, 8);
        assertRoute(ZipCustomerScreenRouter.State.FACE_CAMERA_PERMISSION_PERMANENT,
                ZipScreenAsset.FACE_CAMERA_PERMISSION_PERMANENT, 9);
        assertRoute(ZipCustomerScreenRouter.State.FACE_RETRYABLE_FAILURE,
                ZipScreenAsset.FACE_RETRYABLE_FAILURE, 10);
        assertRoute(ZipCustomerScreenRouter.State.FACE_COMPONENT_UNAVAILABLE,
                ZipScreenAsset.FACE_COMPONENT_UNAVAILABLE, 11);
        assertRoute(ZipCustomerScreenRouter.State.PALM_GUIDE,
                ZipScreenAsset.PALM_GUIDE, 12);
        assertRoute(ZipCustomerScreenRouter.State.PALM_DEVICE_UNAVAILABLE,
                ZipScreenAsset.PALM_DEVICE_UNAVAILABLE, 13);

        assertEquals(13, ZipCustomerScreenRouter.State.values().length);
        EnumSet<ZipScreenAsset> assets = EnumSet.noneOf(ZipScreenAsset.class);
        for (ZipCustomerScreenRouter.State state : ZipCustomerScreenRouter.State.values()) {
            assertTrue("duplicate asset for " + state,
                    assets.add(ZipCustomerScreenRouter.assetFor(state)));
        }
    }

    @Test
    public void rejectsMissingStateWithAnExplicitMessage() {
        try {
            ZipCustomerScreenRouter.assetFor(null);
            fail("missing customer state must fail closed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("customer screen state"));
        }
    }

    @Test
    public void routerSourceHasNoAndroidDependency() throws Exception {
        String source = readRouter();
        assertFalse(source.contains("import android."));
        assertFalse(source.contains("android."));
        assertTrue(source.contains("throw new IllegalArgumentException"));
    }

    private static void assertRoute(ZipCustomerScreenRouter.State state,
            ZipScreenAsset expectedAsset, int expectedId) {
        ZipScreenAsset actual = ZipCustomerScreenRouter.assetFor(state);
        assertEquals(expectedAsset, actual);
        assertEquals(expectedId, actual.id());
    }

    private static String readRouter() throws IOException {
        Path root = projectRoot();
        return new String(Files.readAllBytes(root.resolve(
                "app/src/main/java/com/codex/lockertest/ui/zip/ZipCustomerScreenRouter.java")),
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
