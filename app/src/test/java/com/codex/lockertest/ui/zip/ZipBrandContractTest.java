package com.codex.lockertest.ui.zip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Exact protected copy and design-canvas contract for the v16 ZIP shell. */
public final class ZipBrandContractTest {
    @Test
    public void protectedBrandAndDesignConstantsRemainExact() {
        assertEquals("乾卦智能柜自助终端", ZipBrand.HOME_TITLE);
        assertEquals("乾卦SaaS管理系统(gmtfit.com)", ZipBrand.FOOTER);
        assertEquals("版本：v21 测试版", ZipBrand.VERSION);
        assertEquals(1280, ZipBrand.DESIGN_WIDTH);
        assertEquals(800, ZipBrand.DESIGN_HEIGHT);
    }
}
