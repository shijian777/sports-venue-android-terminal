package com.codex.lockertest.face;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FaceBuildVariantTest {
    @Test
    public void localDemoVariantEnablesLocalFaceFlow() {
        assertTrue(FaceBuildVariant.isLocalDemo());
    }
}
