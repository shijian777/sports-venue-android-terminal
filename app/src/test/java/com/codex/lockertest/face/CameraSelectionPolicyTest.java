package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CameraSelectionPolicyTest {
    @Test
    public void selectsTheOnlyCameraWhenTerminalHalReportsItAsBackFacing() {
        assertEquals(0, select(false));
    }

    @Test
    public void prefersFrontFacingCameraOverEarlierBackFacingCamera() {
        assertEquals(1, select(false, true));
    }

    @Test
    public void rejectsMultipleCamerasWhenNoneIsFrontFacing() {
        assertEquals(-1, select(false, false));
    }

    @Test
    public void selectsTheOnlyFrontFacingCamera() {
        assertEquals(0, select(true));
    }

    @Test
    public void rejectsEmptyCameraInventory() {
        assertEquals(-1, select());
    }

    @Test
    public void keepsFirstFrontFacingCameraWithoutReadingLaterCameras() {
        int selected = CameraSelectionPolicy.selectCameraId(3,
                new CameraSelectionPolicy.FacingReader() {
                    @Override public boolean isFrontFacing(int cameraId) {
                        if (cameraId > 1) {
                            throw new AssertionError("must not inspect past selected front camera");
                        }
                        return cameraId == 1;
                    }
                });
        assertEquals(1, selected);
    }

    private static int select(final boolean... frontFacing) {
        return CameraSelectionPolicy.selectCameraId(frontFacing.length,
                new CameraSelectionPolicy.FacingReader() {
                    @Override public boolean isFrontFacing(int cameraId) {
                        return frontFacing[cameraId];
                    }
                });
    }
}
