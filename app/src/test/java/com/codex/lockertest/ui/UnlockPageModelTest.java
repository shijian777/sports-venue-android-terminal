package com.codex.lockertest.ui;

import com.codex.lockertest.model.UnlockMethod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UnlockPageModelTest {
    @Test
    public void defaultUnavailableMethodsCanNeverSubmit() {
        for (UnlockMethod method : new UnlockMethod[]{
                UnlockMethod.FACE, UnlockMethod.PALM, UnlockMethod.QR}) {
            UnlockPageModel model = new UnlockPageModel(method, defaultAvailability());

            assertFalse(model.isAvailable());
            assertFalse(model.canSubmit("simulated"));
            assertEquals("设备暂未接入", model.unavailableMessage());
        }
    }

    @Test
    public void phoneRequiresExactlyElevenDigitsButNotTheDemoCredential() {
        UnlockPageModel model = new UnlockPageModel(
                UnlockMethod.PHONE, defaultAvailability());

        assertTrue(model.isAvailable());
        assertTrue(model.usesNumericKeypad());
        assertEquals(11, model.maxInputLength());
        assertFalse(model.isMasked());
        assertFalse(model.canSubmit("1380013800"));
        assertFalse(model.canSubmit("138001380000"));
        assertFalse(model.canSubmit("1380013800A"));
        assertTrue(model.canSubmit("13800138000"));
        assertTrue(model.canSubmit("13999999999"));
    }

    @Test
    public void passwordRequiresExactlySixDigitsAndIsMasked() {
        UnlockPageModel model = new UnlockPageModel(
                UnlockMethod.PASSWORD, defaultAvailability());

        assertTrue(model.isAvailable());
        assertTrue(model.usesNumericKeypad());
        assertEquals(6, model.maxInputLength());
        assertTrue(model.isMasked());
        assertFalse(model.canSubmit("12345"));
        assertFalse(model.canSubmit("1234567"));
        assertFalse(model.canSubmit("12345A"));
        assertTrue(model.canSubmit("123456"));
        assertTrue(model.canSubmit("999999"));
    }

    @Test
    public void dormantRecognitionPagesCanSubmitWhenFutureFlagsEnableThem() {
        for (UnlockMethod method : new UnlockMethod[]{
                UnlockMethod.FACE, UnlockMethod.PALM, UnlockMethod.QR}) {
            UnlockPageModel model = new UnlockPageModel(method, ignored -> true);

            assertTrue(model.isAvailable());
            assertFalse(model.usesNumericKeypad());
            assertEquals(0, model.maxInputLength());
            assertFalse(model.isMasked());
            assertTrue(model.canSubmit("simulated"));
        }
    }

    @Test
    public void qrUsesScanCopyWhileBiometricsKeepRecognitionCopy() {
        UnlockPageModel qr = new UnlockPageModel(UnlockMethod.QR, ignored -> true);
        UnlockPageModel face = new UnlockPageModel(UnlockMethod.FACE, ignored -> true);
        UnlockPageModel palm = new UnlockPageModel(UnlockMethod.PALM, ignored -> true);

        assertEquals("开始扫码", qr.recognitionActionLabel());
        assertEquals("正在扫码...", qr.recognitionProgressLabel());
        assertEquals("开始识别", face.recognitionActionLabel());
        assertEquals("开始识别", palm.recognitionActionLabel());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsANullMethod() {
        new UnlockPageModel(null, defaultAvailability());
    }

    private static com.codex.lockertest.model.FeatureAvailability defaultAvailability() {
        return method -> method == UnlockMethod.PHONE
                || method == UnlockMethod.PASSWORD
                || method == UnlockMethod.ID_CARD;
    }
}
