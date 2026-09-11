package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CustomerFailurePresentationTest {
    @Test
    public void preservesTheTwoSafeCredentialValidationCategories() {
        assertPresentation("手机号不正确", "手机号不正确", "请重新输入手机号");
        assertPresentation("密码不正确", "密码不正确", "请重新输入密码");
    }

    @Test
    public void presentsEverySafeDeviceCategoryWithoutLowLevelCopy() {
        assertPresentation(
                "设备连接失败，请联系管理员。",
                "设备连接失败",
                "请联系管理员");
        assertPresentation(
                "开柜指令发送失败，请再次尝试。",
                "开柜指令发送失败",
                "请再次尝试");
        assertPresentation(
                "设备无响应，请再次尝试。",
                "设备无响应",
                "请再次尝试");
        assertPresentation(
                "012号柜门开启失败，请再次尝试。",
                "012号柜门开启失败",
                "请再次尝试");
    }

    @Test
    public void replacesUnknownLowLevelDetailsWithGenericCustomerCopy() {
        assertPresentation(
                "打开失败 /dev/ttyS0: EACCES permission denied HEX 8A JNI",
                "开柜失败",
                "请再次尝试或联系管理员");
        assertPresentation(null, "开柜失败", "请再次尝试或联系管理员");
    }

    private static void assertPresentation(
            String coordinatorDetail,
            String expectedTitle,
            String expectedDetail) {
        CustomerFailurePresentation presentation =
                CustomerFailurePresentation.fromCoordinatorDetail(coordinatorDetail);
        assertEquals(expectedTitle, presentation.title());
        assertEquals(expectedDetail, presentation.detail());
    }
}
