import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

public final class ZipUiSemanticContractTest {
    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        String source = Files.readString(root.resolve("tools/zip-ui-v16/src/ZipUiAssetTool.java"), StandardCharsets.UTF_8);
        String correctedHomeSource = methodSource(source, "private static void drawCorrectedHome",
                "private static void drawHomeStatus");
        require(correctedHomeSource.contains("\"人脸识别\"")
                        && correctedHomeSource.contains("\"掌纹识别\"")
                        && correctedHomeSource.contains("\"生物信息录入\"")
                        && correctedHomeSource.contains("\"离场还柜\"")
                        && correctedHomeSource.contains("\"ID 卡 / 扫码器\"")
                        && correctedHomeSource.contains("\"管理员入口\""),
                "corrected HOME renderer must expose every planned native interaction surface");
        require(!correctedHomeSource.contains("掌纹录入"),
                "HOME must not leave 掌纹录入 as its lone biometric-enrollment entry");

        BufferedImage homeWaiting = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_01_home_waiting.png"));
        BufferedImage homeReading = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_02_home_reading_credential.png"));
        BufferedImage homeCountdown = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_03_home_unregistered_countdown.png"));
        BufferedImage homeError = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_04_home_credential_error.png"));
        int[][] correctedHomeSamples = {{1068, 275}, {1068, 355}, {1068, 430}, {1068, 520}, {1068, 616}};
        for (int[] point : correctedHomeSamples) {
            int expected = homeWaiting.getRGB(point[0], point[1]);
            require(homeReading.getRGB(point[0], point[1]) == expected,
                    "screen 02 must retain the corrected HOME surface at " + point[0] + "," + point[1]);
            require(homeCountdown.getRGB(point[0], point[1]) == expected,
                    "screen 03 must retain the corrected HOME surface at " + point[0] + "," + point[1]);
            require(homeError.getRGB(point[0], point[1]) == expected,
                    "screen 04 must retain the corrected HOME surface at " + point[0] + "," + point[1]);
        }
        require(isRecoveryGreen(homeCountdown.getRGB(460, 500))
                        && isRecoveryGreen(homeCountdown.getRGB(640, 500))
                        && isRecoveryGreen(homeCountdown.getRGB(820, 500)),
                "screen 03 must expose one contiguous recovery/countdown action, not two HOME buttons");
        String countdownSource = methodSource(source, "private static void drawHomeUnregisteredCountdown",
                "private static void drawBiometricStatus");
        require(countdownSource.contains("\"凭证未登记\"")
                        && countdownSource.contains("\"8s\"")
                        && countdownSource.contains("\"重新识别（8s）\""),
                "screen 03 renderer must expose the title, dynamic countdown seed, and single recovery action");

        require(source.contains("\"人脸识别\""), "generator must draw exact face title 人脸识别");
        require(source.contains("\"Face recognition\""), "generator must draw exact face subtitle Face recognition");
        require(source.contains("\"人脸录入\""), "generator must draw exact enrollment title 人脸录入");
        require(source.contains("\"Face enrollment\""), "generator must draw exact enrollment subtitle Face enrollment");
        require(source.contains("\"掌纹录入\""), "enrollment choice must expose 掌纹录入 as a separate choice");

        int faceStart = source.indexOf("private static void drawFaceTemplate");
        int faceEnd = source.indexOf("private static void drawFaceEnrollmentTemplate", faceStart + 1);
        require(faceStart >= 0 && faceEnd > faceStart, "dedicated face template methods are required");
        String faceSource = source.substring(faceStart, faceEnd);
        require(!faceSource.contains("掌纹") && !faceSource.toLowerCase().contains("palm"),
                "face template source must not contain palm/掌纹 semantics");

        int[] faceIds = {5, 6, 7, 8, 9, 10, 11, 56};
        String[] names = {
                "zip_screen_05_face_preparing.png", "zip_screen_06_face_detecting.png",
                "zip_screen_07_face_uploading.png", "zip_screen_08_face_camera_permission_temporary.png",
                "zip_screen_09_face_camera_permission_permanent.png", "zip_screen_10_face_retryable_failure.png",
                "zip_screen_11_face_component_unavailable.png", "zip_screen_56_face_enrollment_capturing.png"
        };
        String[] references = {"img-10.png", "img-10.png", "img-10.png", "img-10.png", "img-10.png", "img-10.png", "img-10.png", "img-23.png"};
        for (int i = 0; i < faceIds.length; i++) {
            BufferedImage target = read(root.resolve("app/src/main/res/drawable-nodpi").resolve(names[i]));
            BufferedImage forbiddenPalm = read(root.resolve("tools/zip-ui-v16/reference").resolve(references[i]));
            double semanticSimilarity = regionSimilarity(target, forbiddenPalm, 20, 65, 1260, 730);
            require(semanticSimilarity < 0.90,
                    String.format("face asset %02d still exposes palm reference semantics (center similarity %.6f)",
                            faceIds[i], semanticSimilarity));
            require(isBlueCameraPixel(target.getRGB(300, 230)) && isBlueCameraPixel(target.getRGB(980, 230)),
                    "face asset must contain a clean blue camera preview at id " + faceIds[i]);
        }

        BufferedImage temporaryPermission = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_08_face_camera_permission_temporary.png"));
        BufferedImage permanentPermission = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_09_face_camera_permission_permanent.png"));
        require(isRetryGreen(temporaryPermission.getRGB(540, 500)),
                "screen 08 must retain the left green reauthorization action");
        require(isCleanWhite(permanentPermission.getRGB(540, 500)),
                "screen 09 must not reuse screen 08's left retry-button layout in any status color");
        require(isSafeGray(permanentPermission.getRGB(640, 500)),
                "screen 09 must expose one centered safe return-home action");
        Method actionLabel;
        try {
            actionLabel = ZipUiAssetTool.class.getDeclaredMethod("faceErrorActionLabel", int.class);
        } catch (NoSuchMethodException missingRoute) {
            throw new AssertionError("generator must expose an explicit 08/09 face-error action route", missingRoute);
        }
        actionLabel.setAccessible(true);
        require("重新授权".equals(actionLabel.invoke(null, 8)), "screen 08 action route must be 重新授权");
        require("返回首页".equals(actionLabel.invoke(null, 9)), "screen 09 action route must be 返回首页");
        require("重新尝试".equals(actionLabel.invoke(null, 10)),
                "screen 10 action route must be 重新尝试, never 重新授权");

        String palmSource = methodSource(source, "private static void drawPalmRecognitionShell",
                "private static void drawEnrollmentChoice");
        require(palmSource.contains("\"掌纹识别\"")
                        && palmSource.contains("\"Palm recognition\"")
                        && palmSource.contains("\"开始识别\"")
                        && palmSource.contains("\"设备未接入\""),
                "screens 12/13 must use the clean palm-recognition renderer and its real actions");
        require(!palmSource.contains("录入") && !palmSource.contains("更新")
                        && !palmSource.contains("返回(10s)") && !palmSource.contains("首页(5s)"),
                "screens 12/13 palm renderer must contain no enrollment/update/countdown remnants");
        BufferedImage palmGuide = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_12_palm_guide.png"));
        BufferedImage palmUnavailable = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_13_palm_device_unavailable.png"));
        require(isCleanWhite(palmGuide.getRGB(100, 250)) && isCleanWhite(palmGuide.getRGB(1180, 250)),
                "screen 12 must cover the old enrollment/input composite with a clean recognition shell");
        require(isPalmPreviewPixel(palmGuide.getRGB(640, 300)),
                "screen 12 must expose a palm/device recognition illustration region");
        require(isRecoveryGreen(palmGuide.getRGB(640, 650)),
                "screen 12 must expose one visible start-recognition action");
        require(isSafeGray(palmUnavailable.getRGB(640, 500))
                        && isCleanPanelPixel(palmUnavailable.getRGB(480, 500))
                        && isCleanPanelPixel(palmUnavailable.getRGB(800, 500)),
                "screen 13 must expose exactly one centered safe return action");

        BufferedImage choice = read(root.resolve("app/src/main/res/drawable-nodpi/zip_screen_55_enrollment_choice.png"));
        require(isFaceChoicePixel(choice.getRGB(450, 430)), "screen 55 must contain a distinct face-enrollment choice");
        require(isPalmChoicePixel(choice.getRGB(830, 430)), "screen 55 must contain a distinct palm-enrollment choice");
        require(isCleanWhite(choice.getRGB(390, 675)) && isCleanWhite(choice.getRGB(640, 675))
                        && isCleanWhite(choice.getRGB(890, 675)),
                "screen 55 must hide legacy cabinet actions below the two enrollment choices");
        require(isCleanWhite(choice.getRGB(100, 250)) && isCleanWhite(choice.getRGB(1180, 250)),
                "screen 55 must hide legacy cabinet-level labels around the enrollment choices");

        int[] lockerIds = {14, 16, 17};
        for (int id : lockerIds) {
            String suffix = id == 14 ? "locker_discovering" : id == 16 ? "locker_unselected" : "locker_selected";
            BufferedImage locker = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(String.format("zip_screen_%02d_%s.png", id, suffix)));
            require(isCleanWhite(locker.getRGB(165, 250)) && isCleanWhite(locker.getRGB(1215, 250))
                            && isCleanWhite(locker.getRGB(165, 575)) && isCleanWhite(locker.getRGB(1215, 575)),
                    "locker screen " + id + " leaks old grid outside the new 4x8 panel");
        }

        int[] cellX = {222, 329, 436, 543, 650, 757, 864, 971};
        int[] cellY = {185, 261, 337, 413};
        for (int id : lockerIds) {
            String suffix = id == 14 ? "locker_discovering" : id == 16 ? "locker_unselected" : "locker_selected";
            BufferedImage locker = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(String.format("zip_screen_%02d_%s.png", id, suffix)));
            for (int y : cellY) {
                for (int x : cellX) {
                    require(isSolidRegion(locker, x + 16, y + 17, x + 84, y + 48),
                            "locker screen " + id + " cell contains a pre-baked ID/state glyph at " + x + "," + y);
                }
            }
        }
        BufferedImage noArea = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_15_locker_no_area.png"));
        BufferedImage selectionError = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_18_locker_selection_error.png"));
        for (BufferedImage prompt : new BufferedImage[]{noArea, selectionError}) {
            for (int y : cellY) {
                require(isSolidRegion(prompt, cellX[0] + 16, y + 17, cellX[0] + 84, y + 48)
                                && isSolidRegion(prompt, cellX[7] + 16, y + 17, cellX[7] + 84, y + 48),
                        "locker prompt leaks old edge-cell example IDs outside its modal");
            }
        }
        require(isSafeGray(noArea.getRGB(640, 503))
                        && isCleanPanelPixel(noArea.getRGB(500, 503))
                        && isCleanPanelPixel(noArea.getRGB(780, 503)),
                "screen 15 must contain exactly one centered safe action");
        require(isColoredAction(selectionError.getRGB(545, 503))
                        && isSafeGray(selectionError.getRGB(735, 503))
                        && isCleanPanelPixel(selectionError.getRGB(640, 503)),
                "screen 18 must contain only retry plus safe-exit actions");

        String lockerSource = methodSource(source, "private static void drawLockerSelectionFamily",
                "private static void drawUnlockResultFamily");
        require(!lockerSource.contains("A10") && !lockerSource.contains("\"A\" +")
                        && !lockerSource.contains("number == 8") && !lockerSource.contains("number == 24")
                        && !lockerSource.contains("随机开柜") && !lockerSource.contains("001")
                        && !lockerSource.contains("4层"),
                "clean locker renderer must not encode example IDs, states, floors, or fake random-open routes");
        require(!source.contains("已选择 A10 柜门"),
                "asset generator must not retain the old hard-coded A10 selection copy");

        int[] inFlightIds = {19, 20, 21};
        String[] inFlightNames = {
                "zip_screen_19_unlock_validating.png", "zip_screen_20_unlock_connecting.png",
                "zip_screen_21_unlock_waiting_response.png"
        };
        for (int i = 0; i < inFlightIds.length; i++) {
            BufferedImage progress = read(root.resolve("app/src/main/res/drawable-nodpi").resolve(inFlightNames[i]));
            require(isCleanPanelPixel(progress.getRGB(545, 503))
                            && isCleanPanelPixel(progress.getRGB(640, 503))
                            && isCleanPanelPixel(progress.getRGB(735, 503)),
                    "in-flight unlock screen " + inFlightIds[i] + " must have no visible action row");
        }

        BufferedImage unlockSuccess = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_22_unlock_success.png"));
        require(isRecoveryGreen(unlockSuccess.getRGB(640, 503))
                        && isCleanPanelPixel(unlockSuccess.getRGB(500, 503))
                        && isCleanPanelPixel(unlockSuccess.getRGB(780, 503)),
                "screen 22 must contain exactly one centered HOME action");

        String[] failureNames = {
                "zip_screen_23_unlock_board_rejected.png",
                "zip_screen_24_unlock_device_connection_failed.png",
                "zip_screen_25_unlock_send_failed.png",
                "zip_screen_26_unlock_communication_timeout.png"
        };
        for (int i = 0; i < failureNames.length; i++) {
            BufferedImage failure = read(root.resolve("app/src/main/res/drawable-nodpi").resolve(failureNames[i]));
            require(isColoredAction(failure.getRGB(545, 503))
                            && isSafeGray(failure.getRGB(735, 503))
                            && isCleanPanelPixel(failure.getRGB(640, 503)),
                    "terminal unlock screen " + (23 + i) + " must contain only RETRY + HOME action regions");
        }

        String unlockSource = methodSource(source, "private static void drawUnlockResultFamily",
                "private static void drawReturnAuthFamily");
        require(!unlockSource.contains("首次存物") && !unlockSource.contains("取柜码")
                        && !unlockSource.contains("掌纹录入") && !unlockSource.contains("随机开柜"),
                "unlock-result renderer must not route through registration/enrollment composites");
        require(unlockSource.contains("drawVectorStatusIcon"),
                "unlock-result renderer must use deterministic vector status icons, not tofu glyphs");

        require("91960bd6e19b785c4f848911b5c89b8f027865938d391fbe2d1eb42e75add6e4"
                        .equals(protectedAssetManifestDigest(root, 1, 26)),
                "Task 3D must preserve the exact generated hashes for pages 01-26");
        require("4c0771e0cee16ad58b288974772359cb80df52b4715a0a5cc15b81f191f9be53"
                        .equals(protectedAssetManifestDigest(root, 1, 39)),
                "Task 3E must preserve the exact generated hashes for pages 01-39");

        String drawStateSource = methodSource(source, "private static void drawState",
                "private static void drawCorrectedHome");
        require(drawStateSource.contains("drawReturnAuthFamily")
                        && drawStateSource.contains("drawReturnLockerFamily")
                        && drawStateSource.contains("drawReturnProgressFamily"),
                "pages 27-39 must route through three dedicated clean return renderers");

        BufferedImage returnAuthReady = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_27_return_auth_ready.png"));
        require(isFaceChoicePixel(returnAuthReady.getRGB(380, 544)),
                "screen 27 must expose a distinct native face-verification surface");
        require(isPalmChoicePixel(returnAuthReady.getRGB(640, 544)),
                "screen 27 must expose a distinct native palm-verification surface");
        require(isPassiveStatusPixel(returnAuthReady.getRGB(900, 544)),
                "screen 27 must expose a passive ID/QR status surface");
        require(isCleanPanelPixel(returnAuthReady.getRGB(640, 356)),
                "screen 27 credential field must be a neutral native-overlay substrate");
        require(isRecoveryGreen(returnAuthReady.getRGB(640, 419)),
                "screen 27 must expose the native identity verification action region");
        require(isSafeGray(returnAuthReady.getRGB(1075, 129)),
                "screen 27 must expose the Task 7 safe-return region");

        String[] noActionReturnNames = {
                "zip_screen_28_return_auth_querying.png",
                "zip_screen_33_return_locker_processing.png",
                "zip_screen_35_return_opening.png",
                "zip_screen_37_return_committing.png"
        };
        int[] noActionReturnIds = {28, 33, 35, 37};
        for (int i = 0; i < noActionReturnNames.length; i++) {
            BufferedImage progress = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(noActionReturnNames[i]));
            require(isCleanPanelPixel(progress.getRGB(545, 503))
                            && isCleanPanelPixel(progress.getRGB(640, 503))
                            && isCleanPanelPixel(progress.getRGB(735, 503)),
                    "return screen " + noActionReturnIds[i] + " must have no visible action row");
        }

        String[] twoActionReturnNames = {
                "zip_screen_29_return_auth_failed.png",
                "zip_screen_30_return_auth_network_error.png",
                "zip_screen_39_return_failed.png"
        };
        int[] twoActionReturnIds = {29, 30, 39};
        for (int i = 0; i < twoActionReturnNames.length; i++) {
            BufferedImage failure = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(twoActionReturnNames[i]));
            require(isColoredAction(failure.getRGB(545, 503))
                            && isSafeGray(failure.getRGB(735, 503))
                            && isCleanPanelPixel(failure.getRGB(640, 503)),
                    "return screen " + twoActionReturnIds[i] + " must contain only RETRY + HOME regions");
        }

        String[] centeredHomeNames = {
                "zip_screen_34_return_locker_empty.png",
                "zip_screen_38_return_success.png"
        };
        int[] centeredHomeIds = {34, 38};
        for (int i = 0; i < centeredHomeNames.length; i++) {
            BufferedImage oneAction = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(centeredHomeNames[i]));
            require(isRecoveryGreen(oneAction.getRGB(640, 503))
                            && isCleanPanelPixel(oneAction.getRGB(500, 503))
                            && isCleanPanelPixel(oneAction.getRGB(780, 503)),
                    "return screen " + centeredHomeIds[i] + " must contain exactly one centered HOME region");
        }

        BufferedImage waitingForClose = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_36_return_waiting_for_close.png"));
        require(isRecoveryGreen(waitingForClose.getRGB(585, 503))
                        && isCleanPanelPixel(waitingForClose.getRGB(500, 503))
                        && isCleanPanelPixel(waitingForClose.getRGB(780, 503)),
                "screen 36 must contain exactly one centered door-closed confirmation region");

        BufferedImage returnUnselected = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_31_return_locker_unselected.png"));
        BufferedImage returnSelected = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_32_return_locker_selected.png"));
        int[] returnRowY = {272, 327, 382, 437};
        for (int y : returnRowY) {
            require(isSolidRegion(returnUnselected, 341, y + 12, 939, y + 32),
                    "screen 31 neutral return row contains a baked locker ID at y=" + y);
            require(isSolidRegion(returnSelected, 341, y + 12, 939, y + 32),
                    "screen 32 neutral return row contains a baked locker ID/state at y=" + y);
            require(returnSelected.getRGB(640, y + 22) == returnSelected.getRGB(640, returnRowY[0] + 22),
                    "screen 32 must not bake a fixed selected row");
        }
        require(isSafeGray(returnUnselected.getRGB(575, 516)),
                "screen 31 confirm surface must be visibly disabled");
        require(isRecoveryGreen(returnSelected.getRGB(575, 516)),
                "screen 32 confirm-return surface must be visibly enabled");
        require(isSafeGray(returnUnselected.getRGB(1075, 129))
                        && isSafeGray(returnSelected.getRGB(1075, 129)),
                "screens 31/32 must expose the Task 7 safe-return region");

        BufferedImage returnProcessing = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_33_return_locker_processing.png"));
        BufferedImage returnEmpty = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_34_return_locker_empty.png"));
        require(isLightRegion(returnProcessing, 500, 210, 780, 224)
                        && isLightRegion(returnEmpty, 500, 210, 780, 224),
                "screens 33/34 must not reveal a duplicate locker-list heading behind the prompt");

        String returnAuthSource = methodSource(source, "private static void drawReturnAuthFamily",
                "private static void drawReturnLockerFamily");
        require(!returnAuthSource.contains("掌纹录入") && !returnAuthSource.contains("掌静脉录入")
                        && !returnAuthSource.contains("配置手环") && !returnAuthSource.contains("登录管理后台")
                        && !returnAuthSource.contains("安装校验") && !returnAuthSource.contains("首次存物"),
                "return-auth renderer must contain no enrollment/admin/config/login/install/storage remnants");

        String returnListSource = methodSource(source, "private static void drawReturnLockerFamily",
                "private static void drawReturnProgressFamily");
        require(!returnListSource.contains("A区") && !returnListSource.contains("\"A\" +")
                        && !returnListSource.contains("i == 1") && !returnListSource.contains("一键还柜")
                        && !returnListSource.contains("清柜") && !returnListSource.contains("配置手环"),
                "return-list renderer must contain no fixed locker records or legacy management actions");

        String returnProgressSource = methodSource(source, "private static void drawReturnProgressFamily",
                "private static void drawReturnPrompt");
        require(!returnProgressSource.contains("登录管理后台")
                        && !returnProgressSource.contains("安装校验")
                        && !returnProgressSource.contains("首次存物")
                        && !returnProgressSource.contains("一键还柜"),
                "return-progress renderer must contain no login/install/storage/one-key-return remnants");
        require(returnProgressSource.contains("drawVectorStatusIcon"),
                "return-progress renderer must use deterministic vector status icons");

        verifyTaskThreeEAdminAndEnrollment(root, source, drawStateSource);

        System.out.println("PASS ZipUiSemanticContractTest home=4 face=8 palm=2 choice=2 locker=5 unlock=8 return=13 admin=18");
    }

    private static void verifyTaskThreeEAdminAndEnrollment(
            Path root, String source, String drawStateSource) throws Exception {
        List<String> failures = new ArrayList<>();
        check(drawStateSource.contains("drawAdminPinFamily"), failures,
                "40-41 are not routed through a dedicated clean admin PIN renderer");
        check(drawStateSource.contains("drawAdminFunctionsFamily"), failures,
                "42 is not routed through a dedicated admin-functions renderer");
        check(drawStateSource.contains("drawSerialAdminFamily"), failures,
                "43-47 are not routed through a dedicated serial-admin renderer");
        check(drawStateSource.contains("drawFaceSdkAdminFamily"), failures,
                "48-54 are not routed through a dedicated Face SDK admin renderer");
        check(drawStateSource.contains("drawEnrollmentFamily"), failures,
                "55-57 are not routed through one dedicated enrollment renderer");
        check(!drawStateSource.contains("if (screen.id == 42) return"), failures,
                "42 still uses the early-return that exposes the old locker grid");
        check(source.contains("map.put(\"ADMIN_PANEL\", new int[]{20, 105, 1260, 710})"), failures,
                "ADMIN_PANEL must opaque-cover the full administrator business area");

        String pinSource = methodSourceOrEmpty(source, "private static void drawAdminPinFamily",
                "private static void drawAdminFunctionsFamily");
        String functionsSource = methodSourceOrEmpty(source, "private static void drawAdminFunctionsFamily",
                "private static void drawSerialAdminFamily");
        String serialSource = methodSourceOrEmpty(source, "private static void drawSerialAdminFamily",
                "private static void drawFaceSdkAdminFamily");
        String sdkSource = methodSourceOrEmpty(source, "private static void drawFaceSdkAdminFamily",
                "private static void drawEnrollmentFamily");
        String enrollmentSource = methodSourceOrEmpty(source, "private static void drawEnrollmentFamily",
                "private static void drawCorrectedHome");
        check(!pinSource.contains("●") && !pinSource.contains("手机号")
                        && !pinSource.contains("取柜码") && !pinSource.contains("数字键盘"),
                failures, "40-41 renderer bakes PIN/input or old credential semantics");
        check(!functionsSource.contains("一键开柜") && !functionsSource.contains("随机开柜")
                        && !functionsSource.contains("A区") && !functionsSource.contains("B区"),
                failures, "42 renderer retains locker-management semantics");
        check(!serialSource.contains("/dev/") && !serialSource.contains("9600")
                        && !serialSource.contains("重新发送") && !serialSource.contains("重试开锁"),
                failures, "43-47 renderer bakes device data or unsafe resend semantics");
        check(!sdkSource.contains("配置手环") && !sdkSource.contains("登录管理后台")
                        && !sdkSource.contains("活体异常"),
                failures, "48-54 renderer retains management/bracelet or liveness-error semantics");
        check(!enrollmentSource.contains("取柜码") && !enrollmentSource.contains("再次尝试")
                        && !enrollmentSource.contains("首页（5s）") && !enrollmentSource.contains("录入成功"),
                failures, "55-57 renderer retains old keypad/countdown/success semantics");

        BufferedImage pinEntry = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_40_admin_pin_entry.png"));
        BufferedImage pinError = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_41_admin_pin_error.png"));
        check(isSolidRegion(pinEntry, 501, 342, 781, 370)
                        && isSolidRegion(pinError, 501, 342, 781, 370), failures,
                "40-41 PIN input substrate still contains baked glyphs/dots");

        BufferedImage functions = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_42_admin_functions.png"));
        check(isColoredAction(functions.getRGB(420, 330))
                        && isColoredAction(functions.getRGB(700, 330))
                        && isColoredAction(functions.getRGB(420, 420))
                        && isSafeGray(functions.getRGB(700, 420)), failures,
                "42 does not expose the four exact 2x2 native action substrates");
        check(isCleanWhite(functions.getRGB(100, 250))
                        && isCleanWhite(functions.getRGB(1180, 250)), failures,
                "42 still exposes locker-grid samples outside its admin card");

        String[] noActionNames = {
                "zip_screen_44_admin_serial_opening.png",
                "zip_screen_50_face_sdk_activating.png",
                "zip_screen_51_face_sdk_licensed.png",
                "zip_screen_52_face_sdk_initializing.png"
        };
        int[] noActionIds = {44, 50, 51, 52};
        for (int i = 0; i < noActionNames.length; i++) {
            BufferedImage progress = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(noActionNames[i]));
            check(isCleanPanelPixel(progress.getRGB(545, 503))
                            && isCleanPanelPixel(progress.getRGB(640, 503))
                            && isCleanPanelPixel(progress.getRGB(735, 503)), failures,
                    "admin progress screen " + noActionIds[i]
                            + " still displays a fake cancel/continue/close/init action");
        }

        BufferedImage serialSuccess = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_46_admin_serial_success.png"));
        check(isColoredAction(serialSuccess.getRGB(640, 503))
                        && isCleanPanelPixel(serialSuccess.getRGB(500, 503))
                        && isCleanPanelPixel(serialSuccess.getRGB(780, 503)), failures,
                "46 must expose exactly one confirmation action");
        for (String twoActionName : new String[]{
                "zip_screen_47_admin_serial_failure.png", "zip_screen_54_face_sdk_error.png"}) {
            BufferedImage twoAction = read(root.resolve("app/src/main/res/drawable-nodpi")
                    .resolve(twoActionName));
            check(isColoredAction(twoAction.getRGB(545, 503))
                            && isSafeGray(twoAction.getRGB(735, 503))
                            && isCleanPanelPixel(twoAction.getRGB(640, 503)), failures,
                    twoActionName + " must expose exactly two declared actions");
        }

        BufferedImage activation = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_49_face_sdk_awaiting_activation.png"));
        check(isSolidRegion(activation, 501, 342, 781, 370)
                        && isCleanWhite(activation.getRGB(640, 620)), failures,
                "49 still exposes old account/password keypad or baked activation input");

        BufferedImage sdkReady = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_53_face_sdk_ready.png"));
        check(isCleanPanelPixel(sdkReady.getRGB(587, 365))
                        && isCleanPanelPixel(sdkReady.getRGB(833, 368)), failures,
                "53 capability text/switch areas are not clean neutral native substrates");

        BufferedImage enrollmentChoice = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_55_enrollment_choice.png"));
        check(isFaceChoicePixel(enrollmentChoice.getRGB(450, 430))
                        && isPalmChoicePixel(enrollmentChoice.getRGB(830, 430))
                        && isSafeGray(enrollmentChoice.getRGB(640, 574)), failures,
                "55 must expose two enrollment actions plus one safe back action");
        check(isSolidRegion(enrollmentChoice, 330, 402, 570, 405)
                        && isSolidRegion(enrollmentChoice, 710, 402, 950, 405), failures,
                "55 enrollment card subtitles overlap the action buttons");

        BufferedImage faceEnrollment = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_56_face_enrollment_capturing.png"));
        check(isSolidRegion(faceEnrollment, 320, 235, 960, 455)
                        && isSolidRegion(faceEnrollment, 425, 520, 855, 558), failures,
                "56 preview/status substrate contains a fake face frame, image, or progress");

        BufferedImage palmUnavailable = read(root.resolve(
                "app/src/main/res/drawable-nodpi/zip_screen_57_palm_enrollment_unavailable.png"));
        check(isSafeGray(palmUnavailable.getRGB(640, 503))
                        && isCleanPanelPixel(palmUnavailable.getRGB(500, 503))
                        && isCleanPanelPixel(palmUnavailable.getRGB(780, 503)), failures,
                "57 must contain one centered back action with old dual actions cleared");

        if (!failures.isEmpty()) {
            throw new AssertionError("Task 3E admin/enrollment violations (" + failures.size() + "):\n - "
                    + String.join("\n - ", failures));
        }
    }

    private static BufferedImage read(Path path) throws Exception {
        BufferedImage image = ImageIO.read(path.toFile());
        require(image != null, "cannot decode " + path);
        return image;
    }

    private static double regionSimilarity(BufferedImage a, BufferedImage b, int x1, int y1, int x2, int y2) {
        long error = 0, channels = 0;
        for (int y = y1; y < y2; y += 2) {
            for (int x = x1; x < x2; x += 2) {
                int aa = a.getRGB(x, y), bb = b.getRGB(x, y);
                error += Math.abs(((aa >>> 16) & 255) - ((bb >>> 16) & 255));
                error += Math.abs(((aa >>> 8) & 255) - ((bb >>> 8) & 255));
                error += Math.abs((aa & 255) - (bb & 255));
                channels += 3;
            }
        }
        return 1.0 - ((double) error / channels) / 255.0;
    }

    private static boolean isBlueCameraPixel(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return b >= 90 && b > r * 1.25 && b >= g;
    }

    private static boolean isFaceChoicePixel(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return b > 150 && g > 90 && b > r * 1.25;
    }

    private static boolean isPalmChoicePixel(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return g > 130 && g > r * 1.25 && g > b * 1.05;
    }

    private static boolean isRetryGreen(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return g > 145 && g > r * 1.25 && g > b * 1.05;
    }

    private static boolean isRecoveryGreen(int rgb) {
        return isRetryGreen(rgb);
    }

    private static boolean isPalmPreviewPixel(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return b >= 80 && b > r * 1.25 && b >= g;
    }

    private static boolean isSafeGray(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return r >= 105 && r <= 170 && Math.abs(r - g) <= 16 && Math.abs(g - b) <= 16;
    }

    private static boolean isCleanWhite(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return r >= 245 && g >= 245 && b >= 245;
    }

    private static boolean isCleanPanelPixel(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return r >= 238 && g >= 241 && b >= 242;
    }

    private static boolean isColoredAction(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        int maximum = Math.max(r, Math.max(g, b));
        int minimum = Math.min(r, Math.min(g, b));
        return maximum >= 145 && maximum - minimum >= 45;
    }

    private static boolean isSolidRegion(BufferedImage image, int x1, int y1, int x2, int y2) {
        int expected = image.getRGB(x1, y1);
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                if (image.getRGB(x, y) != expected) return false;
            }
        }
        return true;
    }

    private static boolean isLightRegion(BufferedImage image, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
                if (r < 225 || g < 225 || b < 225) return false;
            }
        }
        return true;
    }

    private static boolean isPassiveStatusPixel(int rgb) {
        int r = (rgb >>> 16) & 255, g = (rgb >>> 8) & 255, b = rgb & 255;
        return r >= 210 && g >= 235 && b >= 225 && g > r;
    }

    private static String protectedAssetManifestDigest(Path root, int first, int last) throws Exception {
        Path directory = root.resolve("app/src/main/res/drawable-nodpi");
        List<Path> assets;
        try (var stream = Files.list(directory)) {
            assets = stream.filter(path -> path.getFileName().toString().matches("zip_screen_[0-9]{2}_.*\\.png"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        int id = Integer.parseInt(name.substring(11, 13));
                        return id >= first && id <= last;
                    })
                    .sorted()
                    .toList();
        }
        require(assets.size() == last - first + 1,
                "protected asset set has wrong size: " + assets.size());
        MessageDigest aggregate = MessageDigest.getInstance("SHA-256");
        for (Path asset : assets) {
            String line = sha256(asset) + "  " + asset.getFileName() + "\n";
            aggregate.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(aggregate.digest());
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String methodSource(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + 1);
        require(start >= 0 && end > start,
                "required production method slice missing: " + startToken + " -> " + endToken);
        return source.substring(start, end);
    }

    private static String methodSourceOrEmpty(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + 1);
        return start >= 0 && end > start ? source.substring(start, end) : "";
    }

    private static void check(boolean condition, List<String> failures, String message) {
        if (!condition) failures.add(message);
    }

    private static Path projectRoot() {
        String value = System.getProperty("zip.ui.projectRoot");
        require(value != null && !value.isBlank(), "zip.ui.projectRoot is required");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
