package com.codex.lockertest.ui.zip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public final class ZipScreenCatalogTest {
    private static final Pattern MANIFEST_SCREEN = Pattern.compile(
            "\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+)\\s*,\\s*\\\"enumName\\\"\\s*:\\s*\\\"([A-Z0-9_]+)\\\""
                    + "[^{}]*?\\\"drawableName\\\"\\s*:\\s*\\\"([a-z][a-z0-9_]+)\\\""
                    + "[^{}]*?\\\"template\\\"\\s*:\\s*\\\"([A-Z]+)\\\""
                    + "[^{}]*?\\\"dynamicRegion\\\"\\s*:\\s*\\\"([A-Z_]+)\\\""
                    + "[^{}]*?\\\"referenceImage\\\"\\s*:\\s*\\\"(img-\\d{2}\\.png)\\\""
                    + "[^{}]*?\\\"actions\\\"\\s*:\\s*\\[([^]]*)\\]");
    private static final Pattern ACTION_NAME = Pattern.compile("\\\"([A-Z_]+)\\\"");
    private static final Pattern REFERENCE_ENTRY = Pattern.compile(
            "\\\"referenceImage\\\"\\s*:\\s*\\\"(img-\\d{2}\\.png)\\\"\\s*,\\s*"
                    + "\\\"originalZipFileName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SCREEN_REFERENCE = Pattern.compile(
            "\\\"id\\\"\\s*:\\s*(\\d+)\\s*,\\s*\\\"enumName\\\"\\s*:\\s*\\\"([A-Z0-9_]+)\\\"\\s*,\\s*"
                    + "\\\"referenceImage\\\"\\s*:\\s*\\\"(img-\\d{2}\\.png)\\\"");
    private static final String[] ZIP_REFERENCE_ENTRIES = {
            "img-01.png:21首次存物-不选柜模式.png", "img-02.png:首次存物-名下无业务.png",
            "img-03.png:首次存物-选层提示.png", "img-04.png:30更衣柜管理-确认开柜.png",
            "img-05.png:28离场还柜-还柜成功.png", "img-06.png:24首次存物-手环识别失败.png",
            "img-07.png:23首次存物-无匹配掌纹.png", "img-08.png:22首次存物-已有柜子.png",
            "img-09.png:首次存物-取柜码错误.png", "img-10.png:10掌纹录入-录入成功.png",
            "img-11.png:14掌纹更新-弹窗1.png", "img-12.png:12掌纹录入-弹窗2.png",
            "img-13.png:11掌纹录入-弹窗1.png", "img-14.png:06首页-未使用柜子.png",
            "img-15.png:07首页-开柜失败.png", "img-16.png:08首页-网络异常.png",
            "img-17.png:05首页-取物提示.png", "img-18.png:开柜.png",
            "img-19.png:掌纹录入.png", "img-20.png:用柜详情.png", "img-21.png:离场还柜-验证.png",
            "img-22.png:首页-初始 拷贝 2.png", "img-23.png:掌纹识别.png", "img-24.png:管理员操作.png",
            "img-25.png:选层用柜.png", "img-26.png:用柜.png", "img-27.png:管理员操作-确认开柜.png",
            "img-28.png:安装校验.png", "img-29.png:选柜（多）.png", "img-30.png:识别手环.png",
            "img-31.png:使用多个柜子.png", "img-32.png:使用校验.png", "img-33.png:管理后台.png",
            "img-34.png:配置手环.png", "img-35.png:使用校验-登录.png", "img-36.png:登录.png",
            "img-37.png:安装校验-登录.png"
    };

    @Test
    public void catalogHas57ContinuousUniqueAssetsWithValidNamesAndActions() {
        Set<Integer> ids = new HashSet<Integer>();
        Set<String> drawableNames = new HashSet<String>();
        Set<ZipTemplate> templates = EnumSet.noneOf(ZipTemplate.class);

        assertEquals(57, ZipScreenAsset.values().length);
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            assertTrue("id must be positive", asset.id() > 0);
            assertTrue("duplicate id " + asset.id(), ids.add(asset.id()));
            assertTrue("duplicate drawable " + asset.drawableName(), drawableNames.add(asset.drawableName()));
            assertTrue("invalid drawable " + asset.drawableName(),
                    asset.drawableName().matches("^[a-z][a-z0-9_]+$"));
            assertNotNull(asset.template());
            assertNotNull(asset.dynamicRegion());
            assertFalse("actions must not be empty for " + asset.name(), asset.actions().isEmpty());
            templates.add(asset.template());
        }
        for (int id = 1; id <= 57; id++) {
            assertTrue("missing id " + id, ids.contains(id));
        }
        assertEquals(EnumSet.allOf(ZipTemplate.class), templates);
    }

    @Test
    public void catalogRequiresKnownIdsAndLooksUpDrawablesRoundTrip() {
        expectIllegalArgument(0);
        expectIllegalArgument(58);

        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            assertEquals(asset, ZipScreenCatalog.require(asset.id()));
            assertEquals(asset, ZipScreenCatalog.fromDrawableName(asset.drawableName()));
        }
    }

    @Test
    public void actionsAreNotMutable() {
        Set<ZipActionRole> actions = ZipScreenAsset.HOME_WAITING.actions();
        try {
            actions.add(ZipActionRole.CANCEL);
            fail("actions must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Required immutable catalog contract.
        }
    }

    @Test
    public void homeWaitingDeclaresEveryFlowRolePlannedForItsNativeControls() {
        // OPEN -> credential submit; FACE/PALM -> recognition routes; ENROLL ->
        // enrollment entry; RETURN_LOCKER -> return flow. The visible passive
        // scanner/ID surface is status-only, and 管理员入口 maps directly to
        // ZipHomeView.Listener.onAdminRequested (there is no ADMIN action role).
        assertEquals(EnumSet.of(ZipActionRole.FACE, ZipActionRole.PALM, ZipActionRole.OPEN,
                        ZipActionRole.RETURN_LOCKER, ZipActionRole.ENROLL),
                ZipScreenAsset.HOME_WAITING.actions());
    }

    @Test
    public void homeModalAssetsUseTheHomeFamilyAndOnePlannedNativeAction() throws Exception {
        // CANCEL/RETRY -> credential recovery/reset; CONFIRM -> dismiss validation error.
        assertEquals(EnumSet.of(ZipActionRole.CANCEL),
                ZipScreenAsset.HOME_READING_CREDENTIAL.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY),
                ZipScreenAsset.HOME_UNREGISTERED_COUNTDOWN.actions());
        assertEquals(EnumSet.of(ZipActionRole.CONFIRM),
                ZipScreenAsset.HOME_CREDENTIAL_ERROR.actions());
        assertEquals(ZipTemplate.HOME, ZipScreenAsset.HOME_UNREGISTERED_COUNTDOWN.template());
        assertEquals(ZipDynamicRegion.CREDENTIAL_STATUS,
                ZipScreenAsset.HOME_UNREGISTERED_COUNTDOWN.dynamicRegion());

        Map<Integer, String> references = manifestReferences();
        for (int id = 1; id <= 4; id++) {
            assertEquals("home page " + id + " must use the corrected HOME-family reference",
                    "img-22.png", references.get(id));
        }
    }

    @Test
    public void faceAssetsExposeOnlyRetryActionsThatTaskFiveCanActuallyPerform() {
        // BACK/HOME -> safe return; only pages 08 and 10 have a real camera retry.
        assertEquals(EnumSet.of(ZipActionRole.BACK), ZipScreenAsset.FACE_PREPARING.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK), ZipScreenAsset.FACE_DETECTING.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK), ZipScreenAsset.FACE_UPLOADING.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.BACK),
                ZipScreenAsset.FACE_CAMERA_PERMISSION_TEMPORARY.actions());
        assertEquals(EnumSet.of(ZipActionRole.HOME),
                ZipScreenAsset.FACE_CAMERA_PERMISSION_PERMANENT.actions());
        assertFalse(ZipScreenAsset.FACE_CAMERA_PERMISSION_PERMANENT.actions()
                .contains(ZipActionRole.RETRY));
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.BACK),
                ZipScreenAsset.FACE_RETRYABLE_FAILURE.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK),
                ZipScreenAsset.FACE_COMPONENT_UNAVAILABLE.actions());
    }

    @Test
    public void palmRecognitionAssetsUseRecognitionReferenceAndSafeNativeActions() throws Exception {
        // CONFIRM -> check/start device and transition only to page 13; BACK -> safe return.
        assertEquals(EnumSet.of(ZipActionRole.CONFIRM, ZipActionRole.BACK),
                ZipScreenAsset.PALM_GUIDE.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK),
                ZipScreenAsset.PALM_DEVICE_UNAVAILABLE.actions());
        Map<Integer, String> references = manifestReferences();
        assertEquals("img-23.png", references.get(12));
        assertEquals("img-23.png", references.get(13));
    }

    @Test
    public void lockerAndUnlockCatalogCoversIdsFourteenThroughTwentySixExactlyOnce() {
        Set<Integer> actual = new HashSet<Integer>();
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            if (asset.id() >= 14 && asset.id() <= 26) {
                assertTrue("duplicate 14-26 catalog id " + asset.id(), actual.add(asset.id()));
            }
        }
        Set<Integer> expected = new HashSet<Integer>();
        for (int id = 14; id <= 26; id++) expected.add(id);
        assertEquals(expected, actual);
    }

    @Test
    public void lockerSelectionAssetsExposeOnlyTheTaskSixNativeActionPlan() {
        // 14/15 safe exit only; 16/17 native cells + confirm + safe exit;
        // 18 retry plus safe exit. PNG surfaces are never click handlers.
        assertEquals(EnumSet.of(ZipActionRole.BACK), ZipScreenAsset.LOCKER_DISCOVERING.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK), ZipScreenAsset.LOCKER_NO_AREA.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK, ZipActionRole.SELECT, ZipActionRole.CONFIRM),
                ZipScreenAsset.LOCKER_UNSELECTED.actions());
        assertEquals(EnumSet.of(ZipActionRole.BACK, ZipActionRole.SELECT, ZipActionRole.CONFIRM),
                ZipScreenAsset.LOCKER_SELECTED.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.BACK),
                ZipScreenAsset.LOCKER_SELECTION_ERROR.actions());
    }

    @Test
    public void unlockInFlightAssetsHaveNoActionAndTerminalAssetsHaveExactActions() {
        // NONE on 19-21 is a physical one-shot safety boundary: no back/cancel/retry
        // may be inferred while a lock command can still be in flight.
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.UNLOCK_VALIDATING.actions());
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.UNLOCK_CONNECTING.actions());
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.UNLOCK_WAITING_RESPONSE.actions());
        assertEquals(EnumSet.of(ZipActionRole.HOME), ZipScreenAsset.UNLOCK_SUCCESS.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.UNLOCK_BOARD_REJECTED.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.UNLOCK_DEVICE_CONNECTION_FAILED.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.UNLOCK_SEND_FAILED.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.UNLOCK_COMMUNICATION_TIMEOUT.actions());
    }

    @Test
    public void lockerAndUnlockAssetsUseOneCleanLockerReferenceFamily() throws Exception {
        Map<Integer, String> references = manifestReferences();
        for (int id = 14; id <= 26; id++) {
            assertEquals("screen " + id + " must use the clean locker-family reference",
                    "img-18.png", references.get(id));
        }
    }

    @Test
    public void lockerAndUnlockAssetsDeclareTheWholeLockerFamilyAsDynamic() {
        // The clean family redraws the neutral edge cells as well as the central
        // prompt. RESULT_MESSAGE would leave old hard-coded cell IDs outside its
        // bounds and also measure intentional family cleanup as reference drift.
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            if (asset.id() >= 14 && asset.id() <= 26) {
                assertEquals("screen " + asset.id() + " must own the full locker family",
                        ZipDynamicRegion.LOCKER_GRID, asset.dynamicRegion());
            }
        }
    }

    @Test
    public void returnCatalogCoversIdsTwentySevenThroughThirtyNineExactlyOnce() {
        Set<Integer> actual = new HashSet<Integer>();
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            if (asset.id() >= 27 && asset.id() <= 39) {
                assertTrue("duplicate 27-39 catalog id " + asset.id(), actual.add(asset.id()));
            }
        }
        Set<Integer> expected = new HashSet<Integer>();
        for (int id = 27; id <= 39; id++) expected.add(id);
        assertEquals(expected, actual);
    }

    @Test
    public void returnAssetsExposeOnlyTheTaskSevenNativeActionPlan() {
        // NONE on 28/33/35/37 is a safety boundary. Runtime presentation may
        // narrow these capabilities further, but the static asset must never
        // infer cancel/back/retry while auth, authorization, unlock, or commit
        // work can be in flight.
        assertEquals(EnumSet.of(ZipActionRole.FACE, ZipActionRole.PALM, ZipActionRole.BACK),
                ZipScreenAsset.RETURN_AUTH_READY.actions());
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.RETURN_AUTH_QUERYING.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.RETURN_AUTH_FAILED.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.RETURN_AUTH_NETWORK_ERROR.actions());
        assertEquals(EnumSet.of(ZipActionRole.SELECT, ZipActionRole.BACK),
                ZipScreenAsset.RETURN_LOCKER_UNSELECTED.actions());
        assertEquals(EnumSet.of(ZipActionRole.SELECT, ZipActionRole.RETURN_LOCKER, ZipActionRole.BACK),
                ZipScreenAsset.RETURN_LOCKER_SELECTED.actions());
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.RETURN_LOCKER_PROCESSING.actions());
        assertEquals(EnumSet.of(ZipActionRole.HOME), ZipScreenAsset.RETURN_LOCKER_EMPTY.actions());
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.RETURN_OPENING.actions());
        assertEquals(EnumSet.of(ZipActionRole.CONFIRM), ZipScreenAsset.RETURN_WAITING_FOR_CLOSE.actions());
        assertEquals(EnumSet.of(ZipActionRole.NONE), ZipScreenAsset.RETURN_COMMITTING.actions());
        assertEquals(EnumSet.of(ZipActionRole.HOME), ZipScreenAsset.RETURN_SUCCESS.actions());
        assertEquals(EnumSet.of(ZipActionRole.RETRY, ZipActionRole.HOME),
                ZipScreenAsset.RETURN_FAILED.actions());
    }

    @Test
    public void returnAssetsUseThreeConsistentReferenceFamilies() throws Exception {
        Map<Integer, String> references = manifestReferences();
        for (int id = 27; id <= 30; id++) {
            assertEquals("return-auth page " + id + " must use the ZIP return-auth shell",
                    "img-21.png", references.get(id));
        }
        for (int id = 31; id <= 34; id++) {
            assertEquals("return-list page " + id + " must use the ZIP locker-list shell",
                    "img-31.png", references.get(id));
        }
        for (int id = 35; id <= 39; id++) {
            assertEquals("return-progress page " + id + " must use the ZIP return shell",
                    "img-21.png", references.get(id));
        }
    }

    @Test
    public void returnAssetsDeclareTheirWholeCleanFamilyAsDynamic() {
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            if (asset.id() >= 27 && asset.id() <= 30) {
                assertEquals(ZipDynamicRegion.RETURN_AUTH, asset.dynamicRegion());
            } else if (asset.id() >= 31 && asset.id() <= 34) {
                assertEquals(ZipDynamicRegion.RETURN_LIST, asset.dynamicRegion());
            } else if (asset.id() >= 35 && asset.id() <= 39) {
                assertEquals(ZipDynamicRegion.RETURN_PROGRESS, asset.dynamicRegion());
            }
        }
    }

    @Test
    public void adminSdkAndEnrollmentSemanticNamesAreDeclared() {
        Set<String> actionNames = new HashSet<String>();
        for (ZipActionRole role : ZipActionRole.values()) actionNames.add(role.name());
        assertTrue("SERIAL_ADMIN navigation role is required", actionNames.contains("SERIAL_ADMIN"));
        assertTrue("FACE_SDK_ADMIN navigation role is required", actionNames.contains("FACE_SDK_ADMIN"));
        assertTrue("SERIAL_TEST operation role is required", actionNames.contains("SERIAL_TEST"));

        Set<String> regionNames = new HashSet<String>();
        for (ZipDynamicRegion region : ZipDynamicRegion.values()) regionNames.add(region.name());
        assertTrue("ADMIN_PANEL dynamic region is required", regionNames.contains("ADMIN_PANEL"));
    }

    @Test
    public void adminSdkAndEnrollmentCatalogMatchesExactTaskThreeEContract() {
        assertContract(40, "ADMIN_PIN_ENTRY", ZipTemplate.AUTH, "ADMIN_PIN", "CONFIRM", "BACK");
        assertContract(41, "ADMIN_PIN_ERROR", ZipTemplate.AUTH, "ADMIN_PIN", "CONFIRM", "BACK");
        assertContract(42, "ADMIN_FUNCTIONS", ZipTemplate.ADMIN, "ADMIN_PANEL",
                "SERIAL_ADMIN", "FACE_SDK_ADMIN", "ENROLL", "HOME");
        assertContract(43, "ADMIN_SERIAL_DISCONNECTED", ZipTemplate.ADMIN, "SERIAL_PANEL",
                "SERIAL_OPEN", "BACK");
        assertContract(44, "ADMIN_SERIAL_OPENING", ZipTemplate.ADMIN, "SERIAL_PANEL", "NONE");
        assertContract(45, "ADMIN_SERIAL_CONNECTED", ZipTemplate.ADMIN, "SERIAL_PANEL",
                "SERIAL_CLOSE", "SERIAL_TEST", "BACK");
        assertContract(46, "ADMIN_SERIAL_SUCCESS", ZipTemplate.PROMPT, "SERIAL_PANEL", "CONFIRM");
        assertContract(47, "ADMIN_SERIAL_FAILURE", ZipTemplate.PROMPT, "SERIAL_PANEL", "CONFIRM", "BACK");
        assertContract(48, "FACE_SDK_CHECKING_LICENSE", ZipTemplate.ADMIN, "SDK_PANEL", "BACK");
        assertContract(49, "FACE_SDK_AWAITING_ACTIVATION", ZipTemplate.AUTH, "SDK_PANEL",
                "SDK_ACTIVATE", "BACK");
        assertContract(50, "FACE_SDK_ACTIVATING", ZipTemplate.PROMPT, "SDK_PANEL", "NONE");
        assertContract(51, "FACE_SDK_LICENSED", ZipTemplate.PROMPT, "SDK_PANEL", "NONE");
        assertContract(52, "FACE_SDK_INITIALIZING", ZipTemplate.PROMPT, "SDK_PANEL", "NONE");
        assertContract(53, "FACE_SDK_READY", ZipTemplate.ADMIN, "SDK_PANEL", "LIVENESS_TOGGLE", "BACK");
        assertContract(54, "FACE_SDK_ERROR", ZipTemplate.PROMPT, "SDK_PANEL", "RETRY", "BACK");
        assertContract(55, "ENROLLMENT_CHOICE", ZipTemplate.ADMIN, "ENROLLMENT_PANEL",
                "FACE", "PALM", "BACK");
        assertContract(56, "FACE_ENROLLMENT_CAPTURING", ZipTemplate.BIOMETRIC, "FACE_PREVIEW", "BACK");
        assertContract(57, "PALM_ENROLLMENT_UNAVAILABLE", ZipTemplate.PROMPT,
                "ENROLLMENT_PANEL", "BACK");
    }

    @Test
    public void adminSdkAndEnrollmentReferencesUseOnlyTheDeclaredCleanShellFamilies() throws Exception {
        Map<Integer, String> references = manifestReferences();
        assertEquals("img-36.png", references.get(40));
        assertEquals("img-36.png", references.get(41));
        assertEquals("img-24.png", references.get(42));
        for (int id = 43; id <= 47; id++) assertEquals("img-28.png", references.get(id));
        for (int id = 48; id <= 54; id++) assertEquals("img-33.png", references.get(id));
        assertEquals("img-24.png", references.get(55));
        assertEquals("img-23.png", references.get(56));
        assertEquals("img-23.png", references.get(57));
    }

    @Test
    public void adminSdkAndEnrollmentSafetyBoundariesAreExplicit() throws Exception {
        for (ZipScreenAsset asset : new ZipScreenAsset[]{
                ZipScreenAsset.ADMIN_SERIAL_OPENING,
                ZipScreenAsset.FACE_SDK_ACTIVATING,
                ZipScreenAsset.FACE_SDK_LICENSED,
                ZipScreenAsset.FACE_SDK_INITIALIZING}) {
            assertEquals(EnumSet.of(ZipActionRole.NONE), asset.actions());
        }
        assertFalse(ZipScreenAsset.ADMIN_FUNCTIONS.actions().contains(ZipActionRole.SERIAL_OPEN));
        assertFalse(ZipScreenAsset.ADMIN_FUNCTIONS.actions().contains(ZipActionRole.SDK_ACTIVATE));
        assertFalse(ZipScreenAsset.ADMIN_SERIAL_FAILURE.actions().contains(ZipActionRole.RETRY));

        String manifest = new String(Files.readAllBytes(
                new File("tools/zip-ui-v16/manifest.json").toPath()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("管理员－人脸 SDK 授权或核心模型异常"));
        assertFalse(manifest.contains("管理员－人脸 SDK 模型或活体异常"));
    }

    private static Map<Integer, String> manifestReferences() throws Exception {
        String json = new String(Files.readAllBytes(
                new File("tools/zip-ui-v16/manifest.json").toPath()), StandardCharsets.UTF_8);
        Map<Integer, String> references = new HashMap<Integer, String>();
        Matcher matcher = MANIFEST_SCREEN.matcher(json);
        while (matcher.find()) {
            references.put(Integer.parseInt(matcher.group(1)), matcher.group(6));
        }
        return references;
    }

    private static void assertContract(int id, String enumName, ZipTemplate template,
            String dynamicRegion, String... actionNames) {
        ZipScreenAsset asset = ZipScreenCatalog.require(id);
        assertEquals(enumName, asset.name());
        assertEquals(template, asset.template());
        assertEquals(dynamicRegion, asset.dynamicRegion().name());

        Set<String> expected = new HashSet<String>();
        for (String actionName : actionNames) expected.add(actionName);
        Set<String> actual = new HashSet<String>();
        for (ZipActionRole action : asset.actions()) actual.add(action.name());
        assertEquals("exact action roles for " + enumName, expected, actual);
    }

    @Test
    public void manifestEntriesMatchEveryJavaCatalogField() throws Exception {
        File manifest = new File("tools/zip-ui-v16/manifest.json");
        assertTrue("manifest must exist", manifest.isFile());
        String json = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"designWidth\": 1280"));
        assertTrue(json.contains("\"designHeight\": 800"));
        assertTrue(json.contains("乾卦智能柜自助终端"));
        assertTrue(json.contains("乾卦SaaS管理系统(gmtfit.com)"));

        Map<Integer, ZipScreenAsset> catalogById = new HashMap<Integer, ZipScreenAsset>();
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            catalogById.put(asset.id(), asset);
        }
        Set<Integer> manifestIds = new HashSet<Integer>();
        Matcher matcher = MANIFEST_SCREEN.matcher(json);
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            ZipScreenAsset asset = catalogById.get(id);
            assertNotNull("unexpected manifest id " + id, asset);
            assertTrue("duplicate manifest id " + id, manifestIds.add(id));
            assertEquals(asset.name(), matcher.group(2));
            assertEquals(asset.drawableName(), matcher.group(3));
            assertEquals(asset.template().name(), matcher.group(4));
            assertEquals(asset.dynamicRegion().name(), matcher.group(5));
            assertEquals(asset.actions(), parseActions(matcher.group(7)));
        }
        assertEquals(57, manifestIds.size());
    }

    @Test
    public void referenceMapUsesOriginalZipNamesAndCompleteDeclaredScreenReferences() throws Exception {
        File referenceMap = new File("tools/zip-ui-v16/reference-map.json");
        assertTrue("reference map must exist", referenceMap.isFile());
        String json = new String(Files.readAllBytes(referenceMap.toPath()), StandardCharsets.UTF_8);
        File manifest = new File("tools/zip-ui-v16/manifest.json");
        assertTrue("manifest must exist", manifest.isFile());
        String manifestJson = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        Map<Integer, String> manifestReferences = new HashMap<Integer, String>();
        Matcher manifestMatcher = MANIFEST_SCREEN.matcher(manifestJson);
        while (manifestMatcher.find()) {
            int id = Integer.parseInt(manifestMatcher.group(1));
            assertTrue("duplicate manifest reference id " + id,
                    manifestReferences.put(id, manifestMatcher.group(6)) == null);
        }
        assertEquals(57, manifestReferences.size());

        Set<String> actualReferences = new HashSet<String>();
        Matcher referenceMatcher = REFERENCE_ENTRY.matcher(json);
        while (referenceMatcher.find()) {
            actualReferences.add(referenceMatcher.group(1) + ":" + referenceMatcher.group(2));
        }
        Set<String> expectedReferences = new HashSet<String>();
        for (String entry : ZIP_REFERENCE_ENTRIES) {
            expectedReferences.add(entry);
        }
        assertEquals(expectedReferences, actualReferences);

        Set<Integer> ids = new HashSet<Integer>();
        Set<String> enumNames = new HashSet<String>();
        Matcher screenMatcher = SCREEN_REFERENCE.matcher(json);
        while (screenMatcher.find()) {
            int id = Integer.parseInt(screenMatcher.group(1));
            ZipScreenAsset asset = ZipScreenCatalog.require(id);
            assertTrue("duplicate reference id " + id, ids.add(id));
            assertTrue("duplicate reference enum " + screenMatcher.group(2),
                    enumNames.add(screenMatcher.group(2)));
            assertEquals(asset.name(), screenMatcher.group(2));
            assertTrue("undeclared reference " + screenMatcher.group(3),
                    actualReferencesContainsImage(actualReferences, screenMatcher.group(3)));
            assertEquals("manifest reference mismatch for " + asset.name(),
                    manifestReferences.get(id), screenMatcher.group(3));
        }
        assertEquals(57, ids.size());
        assertEquals(57, enumNames.size());
    }

    private static Set<ZipActionRole> parseActions(String actionJson) {
        Set<ZipActionRole> actions = EnumSet.noneOf(ZipActionRole.class);
        Matcher matcher = ACTION_NAME.matcher(actionJson);
        while (matcher.find()) {
            actions.add(ZipActionRole.valueOf(matcher.group(1)));
        }
        return actions;
    }

    private static boolean actualReferencesContainsImage(Set<String> references, String imageName) {
        for (String reference : references) {
            if (reference.startsWith(imageName + ":")) {
                return true;
            }
        }
        return false;
    }

    private static void expectIllegalArgument(int id) {
        try {
            ZipScreenCatalog.require(id);
            fail("require(" + id + ") must reject an unknown id");
        } catch (IllegalArgumentException expected) {
            // Expected public lookup contract.
        }
    }
}
