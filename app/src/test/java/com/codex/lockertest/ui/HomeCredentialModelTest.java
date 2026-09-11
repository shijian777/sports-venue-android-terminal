package com.codex.lockertest.ui;

import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HomeCredentialModelTest {
    @Test
    public void validateAdmitsOnceAndStoresOnlyThePolicyMessage() {
        final int[] calls = {0};
        final UnlockMethod[] requestedMethod = {null};
        final String[] requestedValue = {null};
        CredentialAdmissionPolicy policy = (method, value) -> {
            calls[0]++;
            requestedMethod[0] = method;
            requestedValue[0] = value;
            return CredentialAdmission.rejected("服务器认证尚未配置");
        };
        HomeCredentialModel model = new HomeCredentialModel(policy);
        model.select(UnlockMethod.PASSWORD);
        enter(model, "654321");

        assertFalse(model.validate());

        assertEquals(1, calls[0]);
        assertEquals(UnlockMethod.PASSWORD, requestedMethod[0]);
        assertEquals("654321", requestedValue[0]);
        assertEquals("服务器认证尚未配置", model.validationError());
    }

    @Test
    public void oneKeypadEditsOnlyTheSelectedFieldAndPasswordKeepsRawDigits() {
        HomeCredentialModel model = demoModel();
        model.select(UnlockMethod.PHONE);
        enter(model, "13800138000");
        model.select(UnlockMethod.PASSWORD);
        enter(model, "123456");

        assertEquals("13800138000", model.rawValue(UnlockMethod.PHONE));
        assertEquals("123456", model.rawValue(UnlockMethod.PASSWORD));
        assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022",
                model.displayValue(UnlockMethod.PASSWORD));
        assertEquals(UnlockMethod.PASSWORD, model.activeMethod());
        assertNull(model.validationError());
    }

    @Test
    public void phoneAndPasswordStopAtExactlyElevenAndSixDigits() {
        HomeCredentialModel model = demoModel();

        model.select(UnlockMethod.PHONE);
        enter(model, "13800138000");
        assertFalse(model.pressDigit('9'));
        assertEquals(11, model.rawValue(UnlockMethod.PHONE).length());

        model.select(UnlockMethod.PASSWORD);
        enter(model, "123456");
        assertFalse(model.pressDigit('7'));
        assertEquals(6, model.rawValue(UnlockMethod.PASSWORD).length());
    }

    @Test
    public void deleteAndClearAffectOnlyTheActiveField() {
        HomeCredentialModel model = demoModel();
        model.select(UnlockMethod.PHONE);
        enter(model, "1380");
        model.select(UnlockMethod.PASSWORD);
        enter(model, "123");

        assertTrue(model.delete());
        assertEquals("12", model.rawValue(UnlockMethod.PASSWORD));
        assertEquals("1380", model.rawValue(UnlockMethod.PHONE));

        model.select(UnlockMethod.PHONE);
        model.clear();
        assertEquals("", model.rawValue(UnlockMethod.PHONE));
        assertEquals("12", model.rawValue(UnlockMethod.PASSWORD));
    }

    @Test
    public void invalidPhoneUsesTheRequiredCopyAndExactDemoPhonePasses() {
        HomeCredentialModel model = demoModel();
        model.select(UnlockMethod.PHONE);
        enter(model, "13800138001");

        assertFalse(model.validate());
        assertEquals("手机号不正确", model.validationError());

        model.clear();
        enter(model, "13800138000");
        assertTrue(model.validate());
        assertNull(model.validationError());
    }

    @Test
    public void invalidPasswordUsesTheRequiredCopyAndExactRawPasswordPasses() {
        HomeCredentialModel model = demoModel();
        model.select(UnlockMethod.PASSWORD);
        enter(model, "123457");

        assertFalse(model.validate());
        assertEquals("密码不正确", model.validationError());

        model.clear();
        enter(model, "123456");
        assertTrue(model.validate());
        assertEquals("123456", model.rawValue(UnlockMethod.PASSWORD));
        assertNull(model.validationError());
    }

    @Test
    public void onlyPhoneAndPasswordCanBecomeTheActiveKeypadField() {
        HomeCredentialModel model = demoModel();

        assertEquals(UnlockMethod.PHONE, model.activeMethod());
        assertFalse(model.select(UnlockMethod.FACE));
        assertFalse(model.select(UnlockMethod.PALM));
        assertFalse(model.select(UnlockMethod.QR));
        assertFalse(model.select(null));
        assertEquals(UnlockMethod.PHONE, model.activeMethod());
    }

    private static void enter(HomeCredentialModel model, String digits) {
        for (char digit : digits.toCharArray()) {
            assertTrue(model.pressDigit(digit));
        }
    }

    private static HomeCredentialModel demoModel() {
        return new HomeCredentialModel(RuntimePolicyFixtures.demoCredentialPolicy());
    }
}
