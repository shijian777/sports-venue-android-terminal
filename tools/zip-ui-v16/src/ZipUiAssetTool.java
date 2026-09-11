import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public final class ZipUiAssetTool {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;
    private static final Color GREEN = new Color(29, 190, 146);
    private static final Color DARK = new Color(65, 80, 82);
    private static final Color RED = new Color(239, 78, 83);
    private static final Color AMBER = new Color(244, 142, 54);
    private static final Color BLUE = new Color(34, 133, 218);
    private static final Pattern SCREEN_LINE = Pattern.compile(
            "\\{\\\"id\\\":(\\d+),\\\"enumName\\\":\\\"([^\\\"]+)\\\",\\\"displayName\\\":\\\"([^\\\"]+)\\\","
                    + "\\\"drawableName\\\":\\\"([^\\\"]+)\\\",\\\"template\\\":\\\"([^\\\"]+)\\\","
                    + "\\\"dynamicRegion\\\":\\\"([^\\\"]+)\\\",\\\"referenceImage\\\":\\\"([^\\\"]+)\\\","
                    + "\\\"actions\\\":\\[([^]]*)],\\\"directReference\\\":(true|false)\\}");
    private static final Map<String, int[]> REGIONS = regions();
    private static Font regularFont;
    private static Font boldFont;

    private record Screen(int id, String enumName, String displayName, String drawable,
                          String template, String dynamicRegion, String referenceImage,
                          String actions, boolean directReference) {}

    private record Verification(double minimumSimilarity, String minimumScreen, int assetCount) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: ZipUiAssetTool render|verify|contact-sheet");
        Path root = projectRoot();
        List<Screen> screens = parseScreens(root.resolve("tools/zip-ui-v16/manifest.json"));
        require(screens.size() == 57, "manifest must contain 57 screens");
        loadFonts();
        switch (args[0]) {
            case "render" -> render(root, screens);
            case "verify" -> {
                Verification result = verify(root, screens);
                System.out.printf("VERIFY PASS assets=%d dimensions=1280x800 minSimilarity=%.6f screen=%s%n",
                        result.assetCount, result.minimumSimilarity, result.minimumScreen);
            }
            case "contact-sheet" -> contactSheets(root, screens);
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        }
    }

    private static void render(Path root, List<Screen> screens) throws Exception {
        Path workspace = workspaceRoot(root);
        Path sourceDir = fixedDirectory(workspace, "analysis/ui-zip-audit-20260824");
        Path referenceDir = fixedDirectory(root, "tools/zip-ui-v16/reference");
        Path targetDir = fixedDirectory(root, "app/src/main/res/drawable-nodpi");
        Files.createDirectories(referenceDir);
        Files.createDirectories(targetDir);

        List<String> shaLines = new ArrayList<>();
        for (int i = 1; i <= 37; i++) {
            String name = String.format("img-%02d.png", i);
            Path source = safeResolve(sourceDir, name);
            Path copy = safeResolve(referenceDir, name);
            require(Files.isRegularFile(source), "missing source reference: " + source);
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            String sourceHash = sha256(source);
            String copyHash = sha256(copy);
            require(sourceHash.equals(copyHash), "reference SHA mismatch after copy: " + name);
            shaLines.add(name + "  source=" + sourceHash + "  copy=" + copyHash + "  MATCH");
        }
        Files.write(safeResolve(referenceDir, "reference-sha256.txt"), shaLines, StandardCharsets.UTF_8);

        Set<String> expected = new HashSet<>();
        for (Screen screen : screens) {
            validateDrawableName(screen.drawable);
            require(screen.drawable.startsWith(String.format("zip_screen_%02d_", screen.id)),
                    "drawable id prefix mismatch: " + screen.drawable);
            expected.add(screen.drawable + ".png");
        }
        try (var stream = Files.list(targetDir)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().matches("zip_screen_.*\\.png")).toList()) {
                require(expected.contains(path.getFileName().toString()), "unexpected existing target refuses overwrite scope: " + path.getFileName());
            }
        }

        for (Screen screen : screens) {
            validateReferenceName(screen.referenceImage);
            BufferedImage reference = ImageIO.read(safeResolve(referenceDir, screen.referenceImage).toFile());
            require(reference != null && reference.getWidth() == WIDTH && reference.getHeight() == HEIGHT,
                    "invalid reference image: " + screen.referenceImage);
            BufferedImage output = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = output.createGraphics();
            configure(g);
            g.drawImage(reference, 0, 0, null);
            if (!screen.directReference) drawState(g, screen);
            drawVersion(g);
            g.dispose();
            Path target = safeResolve(targetDir, screen.drawable + ".png");
            require(ImageIO.write(output, "PNG", target.toFile()), "no PNG writer for " + target);
        }
        System.out.println("RENDER PASS references=37 shaMatch=37/37 assets=57");
    }

    private static void drawState(Graphics2D g, Screen screen) {
        if (screen.id >= 1 && screen.id <= 4) {
            drawCorrectedHome(g);
            if (screen.id == 3) {
                drawHomeUnregisteredCountdown(g);
            } else if (screen.id != 1) {
                drawHomeStatus(g, screen);
            }
            return;
        }
        if (screen.id >= 40 && screen.id <= 41) {
            drawAdminPinFamily(g, screen);
            return;
        }
        if (screen.id == 42) {
            drawAdminFunctionsFamily(g);
            return;
        }
        if (screen.id >= 43 && screen.id <= 47) {
            drawSerialAdminFamily(g, screen);
            return;
        }
        if (screen.id >= 48 && screen.id <= 54) {
            drawFaceSdkAdminFamily(g, screen);
            return;
        }
        if (screen.id >= 55 && screen.id <= 57) {
            drawEnrollmentFamily(g, screen);
            return;
        }
        if (screen.id >= 5 && screen.id <= 11) {
            drawFaceTemplate(g, screen);
            return;
        }
        if (screen.id == 12 || screen.id == 13) {
            drawPalmRecognitionShell(g, screen);
            return;
        }
        if (screen.id >= 14 && screen.id <= 18) {
            drawLockerSelectionFamily(g, screen);
            return;
        }
        if (screen.id >= 19 && screen.id <= 26) {
            drawUnlockResultFamily(g, screen);
            return;
        }
        if (screen.id >= 27 && screen.id <= 30) {
            drawReturnAuthFamily(g, screen);
            return;
        }
        if (screen.id >= 31 && screen.id <= 34) {
            drawReturnLockerFamily(g, screen);
            return;
        }
        if (screen.id >= 35 && screen.id <= 39) {
            drawReturnProgressFamily(g, screen);
            return;
        }
        if (screen.template.equals("HOME")) return;
        if (screen.template.equals("BIOMETRIC")) {
            drawBiometricStatus(g, screen);
            return;
        }
        if (screen.template.equals("AUTH")) {
            drawAuthStatus(g, screen);
            return;
        }
        if (screen.template.equals("ADMIN")) {
            drawAdminStatus(g, screen);
            return;
        }
        drawPrompt(g, screen);
    }

    private static void drawAdminPinFamily(Graphics2D g, Screen screen) {
        drawAdminBusinessShell(g, "管理员验证", "Administrator verification");
        button(g, 1050, 110, 176, 38, "返回", new Color(126, 139, 140));

        panel(g, 386, 212, 510, 250, new Color(250, 253, 255), GREEN);
        Color accent = screen.id == 41 ? RED : GREEN;
        drawCentered(g, screen.id == 41 ? "密码错误，请重新输入" : "请输入6位管理员密码",
                641, 278, font(true, 27), accent);
        drawCentered(g, "密码由安全原生控件输入", 641, 310,
                font(false, 16), new Color(99, 117, 120));
        drawNeutralSubstrate(g, 481, 332, 320, 48);
        button(g, 551, 398, 180, 42, "确认", accent);
    }

    private static void drawAdminFunctionsFamily(Graphics2D g) {
        drawAdminBusinessShell(g, "管理员功能", "Administrator functions");
        panel(g, 322, 185, 640, 350, new Color(250, 253, 255), GREEN);
        drawNeutralSubstrate(g, 382, 225, 520, 52);
        drawCentered(g, "请选择管理功能", 642, 258, font(true, 20), DARK);

        button(g, 382, 300, 240, 60, "串口调试", BLUE);
        button(g, 662, 300, 240, 60, "百度人脸 SDK", GREEN);
        button(g, 382, 390, 240, 60, "人脸/掌纹录入", new Color(35, 155, 183));
        button(g, 662, 390, 240, 60, "返回首页", new Color(126, 139, 140));
    }

    private static void drawSerialAdminFamily(Graphics2D g, Screen screen) {
        drawAdminBusinessShell(g, "串口调试", "Serial port diagnostics");
        if (screen.id == 43 || screen.id == 45) {
            button(g, 1050, 110, 176, 38, "返回", new Color(126, 139, 140));
        }
        if (screen.id >= 46) {
            Color accent = screen.id == 46 ? GREEN : RED;
            panel(g, 355, 225, 570, 330, new Color(250, 253, 255), accent);
            drawVectorStatusIcon(g, 640, 300, screen.id == 46 ? "SUCCESS" : "ERROR", accent);
            drawCentered(g, screen.id == 46 ? "串口操作成功" : "串口操作失败",
                    640, 370, font(true, 29), accent);
            drawCentered(g, screen.id == 46 ? "设备已返回可确认的操作结果" : "本次操作未完成，请检查设备状态",
                    640, 412, font(false, 19), DARK);
            drawCentered(g, screen.id == 46 ? "请确认结果后继续" : "确认后可安全返回串口管理",
                    640, 445, font(false, 16), new Color(105, 119, 121));
            if (screen.id == 46) {
                button(g, 565, 480, 150, 46, "确认", GREEN);
            } else {
                button(g, 470, 480, 150, 46, "确认", RED);
                button(g, 660, 480, 150, 46, "返回", new Color(126, 139, 140));
            }
            return;
        }

        panel(g, 322, 185, 640, 350, new Color(250, 253, 255), GREEN);
        Color accent = screen.id == 43 ? RED : screen.id == 44 ? AMBER : GREEN;
        String heading = screen.id == 43 ? "串口未连接" : screen.id == 44 ? "正在打开串口" : "串口已连接";
        String detail = screen.id == 43 ? "请确认设备接线后打开串口"
                : screen.id == 44 ? "正在建立稳定的进程级串口连接" : "串口连接保持中，可执行明确的测试操作";
        drawVectorStatusIcon(g, 640, 252, screen.id == 44 ? "PROGRESS" : screen.id == 45 ? "SUCCESS" : "ERROR", accent);
        drawCentered(g, heading, 640, 292, font(true, 27), accent);
        drawCentered(g, detail, 640, 318, font(false, 16), DARK);
        drawNeutralSubstrate(g, 382, 325, 520, 95);
        if (screen.id == 43) {
            button(g, 555, 455, 170, 46, "打开串口", GREEN);
        } else if (screen.id == 45) {
            button(g, 452, 455, 170, 46, "关闭串口", new Color(126, 139, 140));
            button(g, 662, 455, 170, 46, "测试发送", BLUE);
        }
    }

    private static void drawFaceSdkAdminFamily(Graphics2D g, Screen screen) {
        drawAdminBusinessShell(g, "人脸 SDK 管理", "Face SDK management");
        if (screen.id == 48 || screen.id == 49 || screen.id == 53) {
            button(g, 1050, 110, 176, 38, "返回", new Color(126, 139, 140));
        }
        if (screen.id == 49) {
            panel(g, 386, 212, 510, 250, new Color(250, 253, 255), GREEN);
            drawCentered(g, "等待在线激活", 641, 278, font(true, 27), GREEN);
            drawCentered(g, "请输入正式授权信息", 641, 310,
                    font(false, 16), new Color(99, 117, 120));
            drawNeutralSubstrate(g, 481, 332, 320, 48);
            button(g, 551, 398, 180, 42, "在线激活", GREEN);
            return;
        }
        if (screen.id == 54) {
            panel(g, 355, 225, 570, 330, new Color(250, 253, 255), RED);
            drawVectorStatusIcon(g, 640, 300, "ERROR", RED);
            drawCentered(g, "授权或核心模型异常", 640, 370, font(true, 29), RED);
            drawCentered(g, "授权或核心模型初始化异常，请重试", 640, 412, font(false, 19), DARK);
            drawCentered(g, "重试不会伪造授权或模型状态", 640, 445,
                    font(false, 16), new Color(105, 119, 121));
            button(g, 470, 480, 150, 46, "重新尝试", RED);
            button(g, 660, 480, 150, 46, "返回", new Color(126, 139, 140));
            return;
        }
        if (screen.id >= 50 && screen.id <= 52) {
            Color accent = screen.id == 51 ? GREEN : AMBER;
            panel(g, 355, 225, 570, 330, new Color(250, 253, 255), accent);
            drawVectorStatusIcon(g, 640, 300, screen.id == 51 ? "SUCCESS" : "PROGRESS", accent);
            String heading = screen.id == 50 ? "正在在线激活"
                    : screen.id == 51 ? "授权成功，正在准备模型" : "正在初始化模型";
            String detail = screen.id == 50 ? "请保持网络连接并稍候"
                    : screen.id == 51 ? "系统将自动进入核心模型初始化" : "正在加载人脸检测所需核心模型";
            drawCentered(g, heading, 640, 370, font(true, 29), accent);
            drawCentered(g, detail, 640, 412, font(false, 19), DARK);
            drawCentered(g, "当前阶段无需重复操作", 640, 445,
                    font(false, 16), new Color(105, 119, 121));
            return;
        }

        panel(g, 322, 185, 640, 350, new Color(250, 253, 255), GREEN);
        if (screen.id == 48) {
            drawVectorStatusIcon(g, 640, 252, "PROGRESS", AMBER);
            drawCentered(g, "检查本地授权", 640, 292, font(true, 27), AMBER);
            drawCentered(g, "正在读取本机授权状态", 640, 318, font(false, 16), DARK);
            drawNeutralSubstrate(g, 382, 325, 520, 95);
            return;
        }

        drawVectorStatusIcon(g, 640, 252, "SUCCESS", GREEN);
        drawCentered(g, "人脸 SDK 已就绪", 640, 292, font(true, 27), GREEN);
        drawCentered(g, "普通人脸检测已可用；活体能力由设备动态呈现",
                640, 318, font(false, 16), DARK);
        drawNeutralSubstrate(g, 402, 342, 370, 60);
        drawNeutralSubstrate(g, 797, 347, 72, 42);
    }

    private static void drawEnrollmentFamily(Graphics2D g, Screen screen) {
        if (screen.id == 55) {
            drawAdminBusinessShell(g, "人脸/掌纹录入", "Biometric enrollment");
            panel(g, 20, 105, 1240, 605, Color.WHITE, GREEN);
            drawCentered(g, "Biometric enrollment", 640, 140,
                    font(false, 17), new Color(23, 174, 130));
            drawCentered(g, "请选择录入方式", 640, 205,
                    font(true, 31), new Color(23, 174, 130));
            drawChoiceCard(g, 285, 260, 330, 240, BLUE, "人脸录入", "◎");
            drawChoiceCard(g, 665, 260, 330, 240, GREEN, "掌纹录入", "◇");
            button(g, 550, 550, 180, 48, "返回", new Color(126, 139, 140));
            return;
        }
        if (screen.id == 56) {
            drawAdminBusinessShell(g, "人脸录入", "Face enrollment");
            button(g, 1050, 110, 176, 38, "返回", new Color(126, 139, 140));
            panel(g, 270, 185, 740, 410, new Color(31, 87, 125), new Color(55, 155, 188));
            drawNeutralSubstrate(g, 405, 500, 470, 78);
            return;
        }

        drawAdminBusinessShell(g, "掌纹录入", "Palm enrollment");
        panel(g, 355, 225, 570, 330, new Color(250, 253, 255), RED);
        drawVectorStatusIcon(g, 640, 305, "ERROR", RED);
        drawCentered(g, "掌纹设备未接入", 640, 385, font(true, 29), RED);
        drawCentered(g, "请返回并联系管理员检查设备", 640, 425, font(false, 18), DARK);
        button(g, 565, 480, 150, 46, "返回", new Color(126, 139, 140));
    }

    private static void drawNeutralSubstrate(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(246, 249, 250));
        g.fillRoundRect(x, y, w, h, 14, 14);
        g.setColor(new Color(188, 207, 211));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 14, 14);
    }

    private static void drawAdminBusinessShell(Graphics2D g, String title, String subtitle) {
        // Preserve the ZIP header, outer neon frame, blue edge treatment, brand, and footer.
        // Only the old business content is replaced by one opaque clean interior.
        g.setColor(Color.WHITE);
        g.fillRect(31, 105, 1218, 613);

        Polygon titleBand = new Polygon(
                new int[]{343, 940, 964, 941, 343, 318},
                new int[]{29, 29, 67, 104, 104, 67}, 6);
        g.setColor(GREEN);
        g.fillPolygon(titleBand);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawPolygon(titleBand);
        drawCentered(g, title, 641, 83, font(true, 38), Color.WHITE);
        drawCentered(g, subtitle, 641, 130, font(false, 17), GREEN);
    }

    private static void drawCorrectedHome(Graphics2D g) {
        int x = 900, y = 99, w = 336, h = 621;
        panel(g, x, y, w, h, new Color(250, 253, 255), new Color(65, 188, 216));
        drawCentered(g, "其他识别方式", x + w / 2, 137, font(true, 21), new Color(31, 100, 151));

        button(g, 912, 150, 312, 72, "人脸识别", BLUE);
        button(g, 912, 239, 312, 72, "掌纹识别", new Color(76, 95, 218));
        button(g, 912, 327, 312, 56, "生物信息录入", new Color(139, 88, 210));
        button(g, 912, 402, 312, 56, "离场还柜", GREEN);

        g.setColor(new Color(235, 250, 248));
        g.fillRoundRect(912, 468, 312, 106, 18, 18);
        g.setColor(new Color(157, 222, 207));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(912, 468, 312, 106, 18, 18);
        drawCentered(g, "ID 卡 / 扫码器", 1068, 507, font(true, 19), new Color(27, 151, 118));
        drawCentered(g, "设备就绪 · 无需点击自动识别", 1068, 542, font(false, 15), new Color(79, 111, 111));

        button(g, 912, 590, 312, 52, "管理员入口", new Color(39, 76, 114));
    }

    private static void drawHomeUnregisteredCountdown(Graphics2D g) {
        int x = 355, y = 225, w = 570, h = 330;
        panel(g, x, y, w, h, new Color(250, 253, 255, 252), RED);
        g.setColor(new Color(239, 78, 83, 28));
        g.fillOval(600, 245, 80, 80);
        drawCentered(g, "!", 640, 305, font(true, 44), RED);
        drawCentered(g, "凭证未登记", 640, 350, font(true, 29), RED);
        drawCentered(g, "该凭证尚未登记，请重新识别", 640, 382, font(false, 18), DARK);
        drawCentered(g, "8s", 640, 442, font(true, 48), new Color(31, 146, 198));
        button(g, 450, 470, 380, 66, "重新识别（8s）", GREEN);
    }

    private static void drawHomeStatus(Graphics2D g, Screen screen) {
        int x = 455, y = 278, w = 390, h = 180;
        panel(g, x, y, w, h, new Color(250, 253, 255, 246), GREEN);
        drawCentered(g, headline(screen), x + w / 2, y + 58, font(true, 28), statusColor(screen));
        drawCentered(g, detail(screen), x + w / 2, y + 105, font(false, 20), DARK);
        button(g, x + 105, y + 128, 180, 40, primaryAction(screen), statusColor(screen));
    }

    private static void drawBiometricStatus(Graphics2D g, Screen screen) {
        int x = 392, y = 448, w = 500, h = 155;
        panel(g, x, y, w, h, new Color(250, 253, 255, 242), GREEN);
        drawCentered(g, headline(screen), x + w / 2, y + 48, font(true, 28), statusColor(screen));
        drawCentered(g, detail(screen), x + w / 2, y + 88, font(false, 20), DARK);
        drawCentered(g, "请保持正对识别区", x + w / 2, y + 123, font(false, 18), new Color(100, 118, 120));
    }

    private static void drawFaceTemplate(Graphics2D g, Screen screen) {
        drawCleanFaceShell(g, "人脸识别", "Face recognition");
        if (screen.id <= 7) {
            Color color = statusColor(screen);
            g.setColor(new Color(4, 29, 61, 205));
            g.fillRoundRect(405, 500, 470, 74, 18, 18);
            drawCentered(g, headline(screen), 640, 532, font(true, 25), Color.WHITE);
            drawCentered(g, detail(screen), 640, 558, font(false, 16), new Color(204, 232, 245));
            g.setColor(color);
            g.fillRoundRect(405, 570, 470, 5, 5, 5);
        } else {
            drawFaceError(g, screen);
        }
    }

    private static void drawFaceEnrollmentTemplate(Graphics2D g) {
        drawCleanFaceShell(g, "人脸录入", "Face enrollment");
        g.setColor(new Color(4, 29, 61, 205));
        g.fillRoundRect(405, 500, 470, 78, 18, 18);
        drawCentered(g, "采集中", 640, 535, font(true, 27), Color.WHITE);
        drawCentered(g, "请正对摄像头并保持自然表情", 640, 562, font(false, 17), new Color(204, 232, 245));
        g.setColor(GREEN);
        g.fillRoundRect(405, 574, 470, 5, 5, 5);
    }

    private static void drawCleanFaceShell(Graphics2D g, String title, String subtitle) {
        g.setColor(Color.WHITE);
        g.fillRoundRect(20, 68, 1240, 662, 12, 12);
        g.setColor(new Color(87, 207, 239));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(20, 68, 1240, 662, 12, 12);

        g.setPaint(new GradientPaint(320, 28, new Color(22, 178, 133), 960, 102, new Color(30, 199, 157)));
        g.fillRoundRect(320, 28, 640, 76, 8, 8);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(320, 28, 640, 76, 8, 8);
        drawCentered(g, title, 640, 80, font(true, 36), Color.WHITE);
        drawCentered(g, subtitle, 640, 132, font(false, 18), new Color(17, 177, 130));

        g.setColor(GREEN);
        g.fillRoundRect(1050, 110, 176, 38, 22, 22);
        drawCentered(g, "返回", 1138, 136, font(true, 18), Color.WHITE);

        g.setPaint(new GradientPaint(270, 185, new Color(16, 89, 159), 1010, 595, new Color(1, 36, 82)));
        g.fillRoundRect(270, 185, 740, 410, 18, 18);
        g.setColor(new Color(77, 203, 240));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(270, 185, 740, 410, 18, 18);

        g.setColor(new Color(132, 226, 242, 220));
        g.setStroke(new BasicStroke(5f));
        g.drawOval(562, 258, 156, 174);
        g.drawArc(485, 382, 310, 190, 20, 140);
        g.setColor(new Color(112, 243, 198, 235));
        g.setStroke(new BasicStroke(4f));
        drawFrameCorner(g, 405, 235, 1, 1);
        drawFrameCorner(g, 875, 235, -1, 1);
        drawFrameCorner(g, 405, 510, 1, -1);
        drawFrameCorner(g, 875, 510, -1, -1);

        g.setColor(new Color(102, 222, 255, 90));
        for (int y = 220; y < 570; y += 28) g.drawLine(295, y, 985, y);
        drawCentered(g, "CAMERA PREVIEW", 640, 620, font(false, 15), new Color(92, 120, 128));
    }

    private static void drawFrameCorner(Graphics2D g, int x, int y, int dx, int dy) {
        g.drawLine(x, y, x + dx * 42, y);
        g.drawLine(x, y, x, y + dy * 42);
    }

    private static void drawFaceError(Graphics2D g, Screen screen) {
        int x = 390, y = 250, w = 500, h = 300;
        panel(g, x, y, w, h, new Color(250, 253, 255, 252), statusColor(screen));
        drawCentered(g, "!", 640, y + 72, font(true, 45), statusColor(screen));
        drawCentered(g, headline(screen), 640, y + 125, font(true, 27), statusColor(screen));
        drawCentered(g, detail(screen), 640, y + 166, font(false, 19), DARK);
        drawCentered(g, supporting(screen), 640, y + 198, font(false, 16), new Color(100, 116, 120));
        String actionLabel = faceErrorActionLabel(screen.id);
        if (screen.id == 9) {
            button(g, 565, y + 230, 150, 44, actionLabel, new Color(126, 139, 140));
        } else {
            if (screen.actions.contains("RETRY")) button(g, 465, y + 230, 150, 44, actionLabel, statusColor(screen));
            button(g, screen.actions.contains("RETRY") ? 665 : 565, y + 230, 150, 44, "返回", new Color(126, 139, 140));
        }
    }

    private static String faceErrorActionLabel(int screenId) {
        if (screenId == 8) return "重新授权";
        if (screenId == 9) return "返回首页";
        if (screenId == 10) return "重新尝试";
        return "返回";
    }

    private static void drawPalmRecognitionShell(Graphics2D g, Screen screen) {
        g.setColor(Color.WHITE);
        g.fillRoundRect(20, 68, 1240, 662, 12, 12);
        g.setColor(new Color(87, 207, 239));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(20, 68, 1240, 662, 12, 12);

        g.setPaint(new GradientPaint(320, 28, new Color(22, 178, 133), 960, 102,
                new Color(30, 199, 157)));
        g.fillRoundRect(320, 28, 640, 76, 8, 8);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(320, 28, 640, 76, 8, 8);
        drawCentered(g, "掌纹识别", 640, 80, font(true, 36), Color.WHITE);
        drawCentered(g, "Palm recognition", 640, 132, font(false, 18), new Color(17, 177, 130));

        g.setPaint(new GradientPaint(270, 185, new Color(20, 116, 174), 1010, 515,
                new Color(3, 39, 88)));
        g.fillRoundRect(270, 185, 740, 330, 18, 18);
        g.setColor(new Color(77, 203, 240));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(270, 185, 740, 330, 18, 18);

        g.setColor(new Color(77, 228, 191, 210));
        g.fillRoundRect(420, 305, 175, 145, 70, 70);
        g.fillRoundRect(423, 230, 29, 130, 28, 28);
        g.fillRoundRect(458, 210, 30, 145, 28, 28);
        g.fillRoundRect(495, 220, 30, 140, 28, 28);
        g.fillRoundRect(532, 240, 30, 125, 28, 28);
        g.fillOval(382, 335, 110, 72);
        g.setColor(new Color(219, 255, 247, 210));
        g.setStroke(new BasicStroke(3f));
        g.drawArc(448, 320, 116, 90, 20, 145);
        g.drawArc(448, 345, 116, 75, 20, 145);
        g.drawLine(495, 315, 495, 430);
        g.drawLine(530, 330, 505, 430);
        g.drawLine(460, 335, 488, 430);

        g.setColor(new Color(5, 28, 61, 225));
        g.fillRoundRect(746, 225, 184, 240, 22, 22);
        g.setColor(new Color(85, 232, 195));
        g.setStroke(new BasicStroke(4f));
        g.drawRoundRect(746, 225, 184, 240, 22, 22);
        g.drawRoundRect(779, 270, 118, 118, 15, 15);
        g.drawOval(811, 302, 54, 54);
        g.setColor(new Color(99, 230, 255, 125));
        for (int yy = 245; yy < 450; yy += 24) g.drawLine(762, yy, 914, yy);
        g.setColor(new Color(101, 255, 205, 185));
        g.setStroke(new BasicStroke(5f));
        g.drawLine(305, 360, 975, 360);

        if (screen.id == 12) {
            button(g, 1050, 110, 176, 38, "返回", new Color(126, 139, 140));
            drawCentered(g, "请将手掌平放在设备上方 8–12 厘米", 640, 558,
                    font(true, 22), new Color(36, 92, 105));
            drawCentered(g, "保持手掌稳定，等待识别完成", 640, 588,
                    font(false, 17), new Color(91, 112, 116));
            button(g, 520, 620, 240, 60, "开始识别", GREEN);
        } else {
            int x = 390, y = 250, w = 500, h = 300;
            panel(g, x, y, w, h, new Color(250, 253, 255, 252), RED);
            drawCentered(g, "!", 640, y + 72, font(true, 45), RED);
            drawCentered(g, "设备未接入", 640, y + 125, font(true, 28), RED);
            drawCentered(g, "未检测到掌纹识别设备", 640, y + 166, font(false, 19), DARK);
            drawCentered(g, "请返回首页并联系管理员检查设备", 640, y + 198,
                    font(false, 16), new Color(100, 116, 120));
            button(g, 565, y + 228, 150, 44, "返回首页", new Color(126, 139, 140));
        }
    }

    private static void drawEnrollmentChoice(Graphics2D g) {
        int x = 20, y = 105, w = 1240, h = 605;
        panel(g, x, y, w, h, Color.WHITE, GREEN);
        drawCentered(g, "Enrollment management", 640, 136, font(false, 17), new Color(23, 174, 130));
        drawCentered(g, "请选择录入方式", 640, 205, font(true, 31), new Color(23, 174, 130));
        drawCentered(g, "Enrollment method", 640, 236, font(false, 17), new Color(82, 111, 116));

        drawChoiceCard(g, 285, 260, 330, 240, new Color(37, 126, 218), "人脸录入", "◎");
        drawChoiceCard(g, 665, 260, 330, 240, new Color(25, 182, 133), "掌纹录入", "◇");
        button(g, 550, 550, 180, 48, "返回", new Color(126, 139, 140));
    }

    private static void drawChoiceCard(Graphics2D g, int x, int y, int w, int h, Color color,
                                       String title, String icon) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 24));
        g.fillRoundRect(x, y, w, h, 18, 18);
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 18, 18);
        drawCentered(g, icon, x + w / 2, y + 78, font(true, 44), color);
        drawCentered(g, title, x + w / 2, y + 125, font(true, 26), color);
        g.setColor(color);
        g.fillRoundRect(x + 50, y + 145, w - 100, 55, 28, 28);
        drawCentered(g, "选择", x + w / 2, y + 180, font(true, 19), Color.WHITE);
    }

    private static void drawAuthStatus(Graphics2D g, Screen screen) {
        int x = 386, y = 212, w = 510, h = 250;
        panel(g, x, y, w, h, new Color(250, 253, 255, 247), GREEN);
        drawCentered(g, headline(screen), x + w / 2, y + 55, font(true, 29), statusColor(screen));
        drawCentered(g, detail(screen), x + w / 2, y + 97, font(false, 20), DARK);
        g.setColor(new Color(236, 243, 243));
        g.fillRoundRect(x + 95, y + 120, 320, 48, 10, 10);
        drawCentered(g, "请输入身份凭证", x + w / 2,
                y + 152, font(false, 22), new Color(105, 120, 122));
        button(g, x + 165, y + 186, 180, 42, primaryAction(screen), statusColor(screen));
    }

    private static void drawAdminStatus(Graphics2D g, Screen screen) {
        int x = 322, y = 185, w = 640, h = 350;
        panel(g, x, y, w, h, new Color(250, 253, 255, 246), GREEN);
        drawCentered(g, headline(screen), x + w / 2, y + 60, font(true, 30), statusColor(screen));
        drawCentered(g, detail(screen), x + w / 2, y + 105, font(false, 20), DARK);
        g.setColor(new Color(233, 244, 243));
        g.fillRoundRect(x + 60, y + 140, w - 120, 95, 14, 14);
        drawCentered(g, adminDiagnostic(screen), x + w / 2, y + 180, font(false, 19), new Color(68, 91, 93));
        drawCentered(g, "系统状态可在此页检查并操作", x + w / 2, y + 214, font(false, 17), new Color(115, 130, 132));
        button(g, x + 130, y + 270, 170, 46, primaryAction(screen), statusColor(screen));
        button(g, x + 340, y + 270, 170, 46, "返回", new Color(126, 139, 140));
    }

    private static void drawPrompt(Graphics2D g, Screen screen) {
        int x = 355, y = 225, w = 570, h = 330;
        g.setComposite(AlphaComposite.SrcOver);
        panel(g, x, y, w, h, new Color(250, 253, 255, 249), statusColor(screen));
        g.setColor(new Color(statusColor(screen).getRed(), statusColor(screen).getGreen(), statusColor(screen).getBlue(), 35));
        g.fillOval(x + 245, y + 28, 80, 80);
        drawCentered(g, symbol(screen), x + w / 2, y + 88, font(true, 46), statusColor(screen));
        drawCentered(g, headline(screen), x + w / 2, y + 145, font(true, 29), statusColor(screen));
        drawCentered(g, detail(screen), x + w / 2, y + 188, font(false, 20), DARK);
        drawCentered(g, supporting(screen), x + w / 2, y + 220, font(false, 17), new Color(105, 119, 121));
        String action = primaryAction(screen);
        button(g, x + 115, y + 255, 150, 46, action, statusColor(screen));
        button(g, x + 305, y + 255, 150, 46, secondaryAction(screen), new Color(132, 143, 144));
    }

    private static void drawLockerSelectionFamily(Graphics2D g, Screen screen) {
        drawCleanLockerShell(g, "选择柜门", "Locker selection");
        boolean detecting = screen.id == 14;
        drawNeutralLockerCells(g, detecting);

        String title = switch (screen.id) {
            case 14 -> "正在检测可用柜区";
            case 15 -> "无可用柜区";
            case 16 -> "请选择柜门";
            case 17 -> "已选择柜门";
            default -> "选择操作错误";
        };
        drawCentered(g, title, 640, 165, font(true, 25),
                screen.id == 18 ? RED : detecting ? AMBER : new Color(28, 157, 120));

        if (screen.id == 15) {
            drawLockerPrompt(g, "EMPTY", "无可用柜区", "未发现可选择的柜区",
                    "请返回首页稍后重试", false);
            return;
        }
        if (screen.id == 18) {
            drawLockerPrompt(g, "ERROR", "选择操作错误", "当前选择未能完成",
                    "柜区数据已保留，可重新尝试", true);
            return;
        }

        Color statusFill = screen.id == 17 ? new Color(222, 248, 240) : new Color(239, 245, 246);
        neutralSurface(g, 222, 493, 500, 34, statusFill,
                screen.id == 17 ? GREEN : new Color(174, 194, 198));
        drawCentered(g, screen.id == 14 ? "正在检测可用柜区"
                        : screen.id == 16 ? "请选择一个可用柜门" : "已选择柜门，请确认",
                472, 516, font(false, 16), screen.id == 17 ? new Color(24, 145, 109) : new Color(94, 112, 116));

        neutralSurface(g, 770, 493, 44, 34, new Color(239, 244, 245), new Color(181, 197, 200));
        neutralSurface(g, 822, 493, 176, 34, new Color(246, 249, 249), new Color(181, 197, 200));
        neutralSurface(g, 1006, 493, 44, 34, new Color(239, 244, 245), new Color(181, 197, 200));

        int[] areaX = {274, 452, 630, 808};
        neutralSurface(g, 222, 582, 44, 34, new Color(239, 244, 245), new Color(181, 197, 200));
        for (int x : areaX) {
            neutralSurface(g, x, 582, 170, 34, new Color(245, 250, 249), new Color(159, 211, 196));
        }
        neutralSurface(g, 986, 582, 44, 34, new Color(239, 244, 245), new Color(181, 197, 200));

        button(g, 550, 535, 180, 42, screen.id == 14 ? "检测中" : "确认开柜",
                screen.id == 17 ? GREEN : new Color(184, 194, 196));
        button(g, 304, 655, 192, 47, screen.id == 14 ? "取消" : "返回首页",
                new Color(126, 139, 140));
    }

    private static void drawCleanLockerShell(Graphics2D g, String title, String subtitle) {
        g.setColor(Color.WHITE);
        g.fillRoundRect(20, 68, 1240, 662, 12, 12);
        g.setColor(new Color(87, 207, 239));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(20, 68, 1240, 662, 12, 12);

        g.setPaint(new GradientPaint(320, 28, new Color(22, 178, 133), 960, 102,
                new Color(30, 199, 157)));
        g.fillRoundRect(320, 28, 640, 76, 8, 8);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(320, 28, 640, 76, 8, 8);
        drawCentered(g, title, 640, 80, font(true, 36), Color.WHITE);
        drawCentered(g, subtitle, 640, 132, font(false, 18), new Color(17, 177, 130));
    }

    private static void drawNeutralLockerCells(Graphics2D g, boolean disabled) {
        int[] xs = {222, 329, 436, 543, 650, 757, 864, 971};
        int[] ys = {185, 261, 337, 413};
        Color fill = disabled ? new Color(234, 241, 243) : new Color(247, 252, 251);
        Color border = disabled ? new Color(176, 193, 197) : new Color(111, 207, 180);
        for (int y : ys) {
            for (int x : xs) {
                g.setColor(fill);
                g.fillRoundRect(x, y, 100, 64, 10, 10);
                g.setColor(border);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(x, y, 100, 64, 10, 10);
            }
        }
    }

    private static void neutralSurface(Graphics2D g, int x, int y, int w, int h,
                                       Color fill, Color border) {
        g.setColor(fill);
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(border);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 10, 10);
    }

    private static void drawLockerPrompt(Graphics2D g, String icon, String title,
                                         String detail, String supporting, boolean retry) {
        int x = 355, y = 225, w = 570, h = 330;
        Color accent = retry ? RED : new Color(126, 139, 140);
        panel(g, x, y, w, h, new Color(250, 253, 255, 252), accent);
        drawVectorStatusIcon(g, 640, 292, icon, accent);
        drawCentered(g, title, 640, 370, font(true, 29), accent);
        drawCentered(g, detail, 640, 410, font(false, 20), DARK);
        drawCentered(g, supporting, 640, 445, font(false, 17), new Color(105, 119, 121));
        if (retry) {
            button(g, 470, 480, 150, 46, "重新尝试", RED);
            button(g, 660, 480, 150, 46, "返回首页", new Color(126, 139, 140));
        } else {
            button(g, 565, 480, 150, 46, "返回首页", new Color(126, 139, 140));
        }
    }

    private static void drawVectorStatusIcon(Graphics2D g, int centerX, int centerY,
                                             String kind, Color color) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
        g.fillOval(centerX - 34, centerY - 34, 68, 68);
        g.setColor(color);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (kind.equals("SUCCESS")) {
            g.drawLine(centerX - 17, centerY, centerX - 4, centerY + 14);
            g.drawLine(centerX - 4, centerY + 14, centerX + 22, centerY - 17);
        } else if (kind.equals("PROGRESS")) {
            g.drawArc(centerX - 22, centerY - 22, 44, 44, 35, 255);
            g.fillOval(centerX + 13, centerY - 18, 8, 8);
        } else if (kind.equals("EMPTY")) {
            g.drawOval(centerX - 22, centerY - 22, 44, 44);
            g.drawLine(centerX - 13, centerY, centerX + 13, centerY);
        } else {
            g.drawLine(centerX, centerY - 19, centerX, centerY + 7);
            g.fillOval(centerX - 3, centerY + 15, 7, 7);
        }
    }

    private static void drawUnlockResultFamily(Graphics2D g, Screen screen) {
        drawCleanLockerShell(g, "开柜处理", "Unlock process");
        drawNeutralLockerCells(g, true);

        int x = 355, y = 225, w = 570, h = 330;
        Color accent = unlockResultColor(screen.id);
        panel(g, x, y, w, h, new Color(250, 253, 255, 252), accent);
        drawVectorStatusIcon(g, 640, 292,
                screen.id <= 21 ? "PROGRESS" : screen.id == 22 ? "SUCCESS" : "ERROR", accent);
        drawCentered(g, headline(screen), 640, 370, font(true, 29), accent);
        drawCentered(g, unlockResultDetail(screen.id), 640, 410, font(false, 20), DARK);
        drawCentered(g, unlockResultSupporting(screen.id), 640, 445,
                font(false, 17), new Color(105, 119, 121));

        if (screen.id == 22) {
            button(g, 565, 480, 150, 46, "返回首页", GREEN);
        } else if (screen.id >= 23) {
            button(g, 470, 480, 150, 46, "重新尝试", accent);
            button(g, 660, 480, 150, 46, "返回首页", new Color(126, 139, 140));
        }
    }

    private static Color unlockResultColor(int screenId) {
        if (screenId == 22) return GREEN;
        if (screenId >= 23 && screenId <= 25) return RED;
        if (screenId == 26) return AMBER;
        return new Color(31, 146, 198);
    }

    private static String unlockResultDetail(int screenId) {
        return switch (screenId) {
            case 19 -> "正在核验开柜权限";
            case 20 -> "正在连接锁控设备";
            case 21 -> "开柜指令已发送，等待设备响应";
            case 22 -> "柜门已成功打开";
            case 23 -> "锁板明确拒绝了本次开柜";
            case 24 -> "未能连接锁控设备";
            case 25 -> "开柜指令未能发送";
            default -> "等待设备响应超时";
        };
    }

    private static String unlockResultSupporting(int screenId) {
        if (screenId <= 21) return "处理中，请勿重复操作";
        if (screenId == 22) return "请取放物品并及时关闭柜门";
        if (screenId == 23) return "柜门状态未改变，可安全重试";
        if (screenId == 24) return "请检查设备连接后重新尝试";
        if (screenId == 25) return "本次指令未发出，可安全重试";
        return "未收到有效回包，可安全重试";
    }

    private static void drawReturnAuthFamily(Graphics2D g, Screen screen) {
        drawCleanReturnShell(g, "离场还柜", "Return locker");
        if (screen.id != 27) {
            Color accent = screen.id == 28 ? BLUE : screen.id == 29 ? RED : AMBER;
            String icon = screen.id == 28 ? "PROGRESS" : "ERROR";
            String message = screen.id == 28 ? "正在核验离场身份"
                    : screen.id == 29 ? "身份验证未通过" : "暂时无法完成身份核验";
            String note = screen.id == 28 ? "正在查询授权信息，请稍候"
                    : screen.id == 29 ? "请确认身份信息后重新尝试" : "请检查网络连接后重新尝试";
            drawReturnPrompt(g, message, note, icon, accent,
                    screen.id == 28 ? "NONE" : "RETRY_HOME");
            return;
        }

        panel(g, 70, 180, 260, 286, new Color(248, 252, 252), new Color(167, 220, 211));
        drawCentered(g, "身份凭证", 200, 226, font(true, 23), DARK);
        drawCentered(g, "刷卡与扫码结果由设备实时呈现", 200, 263,
                font(false, 15), new Color(105, 119, 121));
        g.setColor(new Color(220, 246, 234));
        g.fillRoundRect(105, 292, 190, 82, 14, 14);
        drawCentered(g, "ID / QR", 200, 340, font(true, 22), new Color(55, 134, 111));
        drawCentered(g, "等待识别", 200, 405, font(false, 17), new Color(105, 119, 121));

        panel(g, 386, 212, 510, 250, new Color(250, 253, 255), GREEN);
        drawCentered(g, "身份验证", 641, 251, font(true, 25), DARK);
        g.setColor(new Color(246, 249, 250));
        g.fillRoundRect(481, 278, 320, 42, 9, 9);
        g.setFont(font(false, 14));
        g.setColor(new Color(126, 139, 140));
        g.drawString("手机尾号", 410, 305);
        g.setColor(new Color(246, 249, 250));
        g.fillRoundRect(481, 332, 320, 48, 9, 9);
        g.setFont(font(false, 14));
        g.setColor(new Color(126, 139, 140));
        g.drawString("取柜码", 418, 363);
        button(g, 551, 398, 180, 42, "验证身份", GREEN);

        panel(g, 950, 180, 260, 286, new Color(248, 252, 252), new Color(167, 220, 211));
        drawCentered(g, "数字键盘", 1080, 226, font(true, 23), DARK);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 3; column++) {
                g.setColor(new Color(235, 242, 243));
                g.fillRoundRect(981 + column * 68, 252 + row * 47, 56, 35, 7, 7);
            }
        }

        button(g, 270, 520, 220, 48, "人脸验证", BLUE);
        button(g, 530, 520, 220, 48, "掌纹验证", GREEN);
        g.setColor(new Color(220, 246, 234));
        g.fillRoundRect(790, 520, 220, 48, 48, 48);
        drawCentered(g, "ID 卡 / 扫码自动识别", 900, 539, font(true, 16), new Color(55, 134, 111));
        button(g, 1050, 110, 176, 38, "安全返回", new Color(126, 139, 140));
    }

    private static void drawReturnLockerFamily(Graphics2D g, Screen screen) {
        drawCleanReturnShell(g, "选择归还柜门", "Select return locker");
        if (screen.id == 31 || screen.id == 32) {
            panel(g, 270, 190, 740, 360, new Color(250, 253, 255), GREEN);
            drawCentered(g, screen.id == 32 ? "已选择本人柜门" : "请选择本人柜门",
                    640, 244, font(true, 26), DARK);
            int[] rowY = {272, 327, 382, 437};
            for (int y : rowY) {
                g.setColor(new Color(238, 245, 245));
                g.fillRoundRect(325, y, 630, 44, 9, 9);
            }
            button(g, 550, 495, 180, 42, screen.id == 32 ? "确认还柜" : "等待选择",
                    screen.id == 32 ? GREEN : new Color(126, 139, 140));
            button(g, 1050, 110, 176, 38, "安全返回", new Color(126, 139, 140));
            return;
        }

        String message = screen.id == 33 ? "正在查询可归还柜门" : "暂无可归还柜门";
        String note = screen.id == 33 ? "正在同步授权范围，请稍候" : "当前身份没有可用的归还柜门";
        drawReturnPrompt(g, message, note, screen.id == 33 ? "PROGRESS" : "EMPTY",
                screen.id == 33 ? BLUE : AMBER, screen.id == 33 ? "NONE" : "HOME");
    }

    private static void drawReturnProgressFamily(Graphics2D g, Screen screen) {
        drawCleanReturnShell(g, "离场还柜", "Return locker");
        panel(g, 120, 205, 230, 270, new Color(248, 252, 252), new Color(167, 220, 211));
        drawCentered(g, "离场还柜", 235, 250, font(true, 24), DARK);
        drawCentered(g, "身份已核验", 235, 304, font(false, 18), new Color(55, 134, 111));
        drawCentered(g, "柜门状态由设备实时确认", 235, 350,
                font(false, 15), new Color(105, 119, 121));

        int x = 355, y = 225, w = 570, h = 330;
        Color accent = screen.id == 38 ? GREEN : screen.id == 39 ? RED
                : screen.id == 36 ? AMBER : BLUE;
        panel(g, x, y, w, h, new Color(250, 253, 255), accent);
        drawVectorStatusIcon(g, 640, 292,
                screen.id == 38 ? "SUCCESS" : screen.id == 39 ? "ERROR" : "PROGRESS", accent);
        String message = switch (screen.id) {
            case 35 -> "正在打开归还柜门";
            case 36 -> "请关闭柜门";
            case 37 -> "正在确认还柜结果";
            case 38 -> "还柜成功";
            default -> "还柜未完成";
        };
        String note = switch (screen.id) {
            case 35 -> "正在准备锁控设备并打开柜门";
            case 36 -> "确认后仍会再次校验门状态";
            case 37 -> "正在提交归还记录，请勿重复操作";
            case 38 -> "柜门与归还记录均已确认";
            default -> "请检查页面提示后重试或返回首页";
        };
        drawCentered(g, message, 640, 370, font(true, 29), accent);
        drawCentered(g, note, 640, 416, font(false, 18), DARK);
        drawCentered(g, screen.id == 36 ? "关闭柜门后再确认" : "离场流程将保留当前业务记录",
                640, 447, font(false, 16), new Color(105, 119, 121));

        if (screen.id == 36) {
            button(g, 565, 480, 150, 46, "柜门已关闭", GREEN);
        } else if (screen.id == 38) {
            button(g, 565, 480, 150, 46, "返回首页", GREEN);
        } else if (screen.id == 39) {
            button(g, 470, 480, 150, 46, "重新尝试", RED);
            button(g, 660, 480, 150, 46, "返回首页", new Color(126, 139, 140));
        }
    }

    private static void drawReturnPrompt(Graphics2D g, String message, String note,
                                         String icon, Color accent, String actionMode) {
        int x = 355, y = 225, w = 570, h = 330;
        panel(g, x, y, w, h, new Color(250, 253, 255), accent);
        drawVectorStatusIcon(g, 640, 292, icon, accent);
        drawCentered(g, message, 640, 370, font(true, 29), accent);
        drawCentered(g, note, 640, 416, font(false, 18), DARK);
        drawCentered(g, "离场流程将保留当前业务记录", 640, 447,
                font(false, 16), new Color(105, 119, 121));
        if (actionMode.equals("RETRY_HOME")) {
            button(g, 470, 480, 150, 46, "重新尝试", accent);
            button(g, 660, 480, 150, 46, "返回首页", new Color(126, 139, 140));
        } else if (actionMode.equals("HOME")) {
            button(g, 565, 480, 150, 46, "返回首页", GREEN);
        }
    }

    private static void drawCleanReturnShell(Graphics2D g, String title, String subtitle) {
        g.setColor(new Color(247, 252, 253));
        g.fillRect(20, 65, 1240, 665);
        g.setColor(Color.WHITE);
        g.fillRoundRect(30, 82, 1220, 636, 18, 18);
        g.setColor(new Color(77, 201, 220));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(30, 82, 1219, 635, 18, 18);

        Polygon titleBand = new Polygon(
                new int[]{343, 940, 964, 941, 343, 318},
                new int[]{29, 29, 67, 104, 104, 67}, 6);
        g.setColor(GREEN);
        g.fillPolygon(titleBand);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f));
        g.drawPolygon(titleBand);
        drawCentered(g, title, 641, 83, font(true, 38), Color.WHITE);
        drawCentered(g, subtitle, 641, 130, font(false, 17), GREEN);
    }

    private static void drawVersion(Graphics2D g) {
        int x = 1082, y = 750, w = 166, h = 31;
        g.setColor(new Color(25, 188, 140, 238));
        g.fillRoundRect(x, y, w, h, 18, 18);
        drawCentered(g, "版本：v16.0 Demo", x + w / 2, y + 22, font(true, 14), Color.WHITE);
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h, Color fill, Color border) {
        g.setColor(new Color(9, 33, 52, 35));
        g.fillRoundRect(x + 7, y + 9, w, h, 24, 24);
        g.setColor(fill);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 24, 24));
        g.setColor(new Color(border.getRed(), border.getGreen(), border.getBlue(), 190));
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, w, h, 24, 24);
    }

    private static void button(Graphics2D g, int x, int y, int w, int h, String text, Color color) {
        g.setColor(color);
        g.fillRoundRect(x, y, w, h, h, h);
        drawCentered(g, text, x + w / 2, y + (h + 16) / 2, font(true, 18), Color.WHITE);
    }

    private static void drawCentered(Graphics2D g, String text, int centerX, int baselineY, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, centerX - width / 2, baselineY);
    }

    private static String headline(Screen s) {
        String name = s.displayName;
        int dash = Math.max(name.indexOf('－'), name.indexOf('-'));
        return dash >= 0 ? name.substring(dash + 1) : name;
    }

    private static String detail(Screen s) {
        return switch (s.enumName) {
            case "HOME_WAITING" -> "请刷卡、扫码或选择识别方式";
            case "HOME_READING_CREDENTIAL" -> "正在读取并核验您的凭证";
            case "HOME_CREDENTIAL_ERROR" -> "手机号或取柜码校验失败";
            case "FACE_PREPARING" -> "摄像头和识别组件正在准备";
            case "FACE_DETECTING" -> "已启动检测，请面向摄像头";
            case "FACE_UPLOADING" -> "抓拍完成，正在上传验证";
            case "PALM_GUIDE" -> "请将手掌对准识别区域";
            case "LOCKER_DISCOVERING" -> "正在检测可用柜区和柜门";
            case "LOCKER_UNSELECTED" -> "请选择一个可用柜门";
            case "LOCKER_SELECTED" -> "已选择柜门，请确认";
            case "RETURN_AUTH_READY" -> "请刷卡、扫码或输入身份信息";
            case "ADMIN_PIN_ENTRY" -> "请输入六位管理员密码";
            case "ADMIN_PIN_ERROR" -> "密码验证失败，请重新输入";
            default -> stateSentence(s);
        };
    }

    private static String stateSentence(Screen s) {
        String e = s.enumName;
        if (e.contains("SUCCESS") || e.contains("READY") || e.contains("LICENSED") || e.contains("CONNECTED")) return "状态已确认，可继续下一步操作";
        if (e.contains("FAILED") || e.contains("ERROR") || e.contains("UNAVAILABLE") || e.contains("REJECTED")) return "当前操作未完成，请检查后重试";
        if (e.contains("TIMEOUT")) return "设备响应超时，请重新尝试";
        if (e.contains("WAITING") || e.contains("QUERYING") || e.contains("OPENING") || e.contains("INITIALIZING") || e.contains("ACTIVATING")) return "系统处理中，请稍候";
        return "请按页面提示完成当前操作";
    }

    private static String supporting(Screen s) {
        if (s.enumName.contains("NETWORK")) return "请检查网络连接后重新尝试";
        if (s.enumName.contains("CAMERA_PERMISSION")) return "请在系统设置中允许摄像头权限";
        if (s.enumName.contains("SERIAL")) return "串口与锁控板状态已记录";
        if (s.enumName.contains("SDK")) return "人脸 SDK 授权与模型状态可安全重试";
        if (s.enumName.contains("RETURN")) return "还柜流程将保留当前业务记录";
        return "操作过程不会改变既有业务数据";
    }

    private static String symbol(Screen s) {
        Color c = statusColor(s);
        if (c.equals(RED)) return "!";
        if (c.equals(AMBER)) return "…";
        return "✓";
    }

    private static Color statusColor(Screen s) {
        String e = s.enumName;
        if (e.contains("FAILED") || e.contains("ERROR") || e.contains("UNAVAILABLE") || e.contains("REJECTED")
                || e.contains("PERMANENT") || e.contains("EMPTY")) return RED;
        if (e.contains("WAITING") || e.contains("READING") || e.contains("PREPARING") || e.contains("DETECTING")
                || e.contains("UPLOADING") || e.contains("QUERYING") || e.contains("OPENING") || e.contains("PROCESSING")
                || e.contains("INITIALIZING") || e.contains("ACTIVATING") || e.contains("CHECKING") || e.contains("TIMEOUT")) return AMBER;
        if (e.contains("GUIDE") || e.contains("FUNCTIONS") || e.contains("CHOICE") || e.contains("CAPTURING")) return BLUE;
        return GREEN;
    }

    private static String primaryAction(Screen s) {
        if (s.actions.contains("RETRY")) return "重新尝试";
        if (s.actions.contains("CONFIRM")) return "确认";
        if (s.actions.contains("HOME")) return "返回首页";
        if (s.actions.contains("CANCEL")) return "取消";
        if (s.actions.contains("SELECT")) return "选择柜门";
        if (s.actions.contains("FACE")) return "人脸识别";
        if (s.actions.contains("BACK")) return "返回";
        return "继续";
    }

    private static String secondaryAction(Screen s) {
        if (s.actions.contains("BACK")) return "返回";
        if (s.actions.contains("HOME")) return "首页";
        if (s.actions.contains("CANCEL")) return "取消";
        return "关闭";
    }

    private static String adminDiagnostic(Screen s) {
        if (s.enumName.contains("SERIAL")) return "串口：COM  ·  波特率：9600  ·  锁控板：实时检测";
        if (s.enumName.contains("SDK")) return "授权文件  ·  模型初始化  ·  活体检测  ·  摄像头";
        if (s.enumName.contains("ENROLLMENT")) return "人脸录入  ·  掌纹录入  ·  设备状态";
        return "管理员功能  ·  设备校验  ·  系统配置";
    }

    private static Verification verify(Path root, List<Screen> screens) throws Exception {
        Path targetDir = fixedDirectory(root, "app/src/main/res/drawable-nodpi");
        Path referenceDir = fixedDirectory(root, "tools/zip-ui-v16/reference");
        Set<String> expected = new HashSet<>();
        for (Screen screen : screens) {
            validateDrawableName(screen.drawable);
            expected.add(screen.drawable + ".png");
        }
        List<Path> actual;
        try (var stream = Files.list(targetDir)) {
            actual = stream.filter(p -> p.getFileName().toString().matches("zip_screen_.*\\.png")).sorted().toList();
        }
        require(actual.size() == 57, "target directory must contain exactly 57 assets, got " + actual.size());
        for (Path path : actual) require(expected.contains(path.getFileName().toString()), "extra target: " + path.getFileName());
        verifyReferenceHashes(referenceDir);

        double minimum = 1.0;
        String minimumScreen = "";
        List<String> similarityFailures = new ArrayList<>();
        for (Screen screen : screens) {
            Path targetPath = targetDir.resolve(screen.drawable + ".png");
            require(Files.isRegularFile(targetPath), "missing target: " + targetPath);
            BufferedImage target = ImageIO.read(targetPath.toFile());
            BufferedImage reference = ImageIO.read(referenceDir.resolve(screen.referenceImage).toFile());
            require(target != null, "PNG decode failed: " + targetPath);
            require(target.getWidth() == WIDTH && target.getHeight() == HEIGHT, "wrong target dimensions: " + targetPath);
            require(isRgbOrArgb(target), "target must decode with RGB/ARGB color model: " + targetPath
                    + " type=" + target.getType());
            require(distinctRgb(target) >= 256, "target has fewer than 256 distinct colors: " + targetPath);
            require(cornersAndFooterOpaque(target), "transparent corner/footer: " + targetPath);
            require(reference != null && reference.getWidth() == WIDTH && reference.getHeight() == HEIGHT, "bad reference: " + screen.referenceImage);
            require(brandRegionEqual(target, reference), "protected footer brand pixels changed: " + screen.enumName);
            double similarity = similarity(target, reference, REGIONS.get(screen.dynamicRegion));
            double threshold = screen.directReference ? 0.995 : 0.95;
            if (similarity < threshold) {
                similarityFailures.add(String.format("%.6f below %.3f: %s",
                        similarity, threshold, screen.enumName));
            }
            if (similarity < minimum) {
                minimum = similarity;
                minimumScreen = screen.enumName;
            }
        }
        require(similarityFailures.isEmpty(), "similarity failures: " + String.join("; ", similarityFailures));
        return new Verification(minimum, minimumScreen, actual.size());
    }

    private static void verifyReferenceHashes(Path referenceDir) throws Exception {
        Path report = safeResolve(referenceDir, "reference-sha256.txt");
        require(Files.isRegularFile(report), "missing reference-sha256.txt");
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        require(lines.size() == 37, "reference SHA report must contain 37 lines");
        for (int i = 1; i <= 37; i++) {
            String name = String.format("img-%02d.png", i);
            String hash = sha256(safeResolve(referenceDir, name));
            String line = lines.get(i - 1);
            require(line.startsWith(name + "  source=" + hash + "  copy=" + hash + "  MATCH"), "reference SHA report mismatch: " + name);
        }
    }

    private static int distinctRgb(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < HEIGHT; y += 2) {
            for (int x = 0; x < WIDTH; x += 2) {
                colors.add(image.getRGB(x, y) & 0xffffff);
                if (colors.size() >= 256) return colors.size();
            }
        }
        return colors.size();
    }

    private static boolean isRgbOrArgb(BufferedImage image) {
        var model = image.getColorModel();
        return model.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_RGB
                && model.getNumColorComponents() == 3
                && (model.getNumComponents() == 3 || model.getNumComponents() == 4);
    }

    private static boolean cornersAndFooterOpaque(BufferedImage image) {
        int[] corners = {image.getRGB(0, 0), image.getRGB(WIDTH - 1, 0), image.getRGB(0, HEIGHT - 1), image.getRGB(WIDTH - 1, HEIGHT - 1)};
        for (int pixel : corners) if (((pixel >>> 24) & 0xff) == 0) return false;
        for (int y = 730; y < HEIGHT; y += 4) for (int x = 0; x < WIDTH; x += 4)
            if (((image.getRGB(x, y) >>> 24) & 0xff) == 0) return false;
        return true;
    }

    private static boolean brandRegionEqual(BufferedImage target, BufferedImage reference) {
        for (int y = 742; y < 790; y += 2) {
            for (int x = 38; x < 690; x += 2) {
                if ((target.getRGB(x, y) & 0xffffff) != (reference.getRGB(x, y) & 0xffffff)) return false;
            }
        }
        return true;
    }

    private static double similarity(BufferedImage actual, BufferedImage reference, int[] excluded) {
        long error = 0;
        long channels = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (excluded != null && x >= excluded[0] && y >= excluded[1] && x < excluded[2] && y < excluded[3]) continue;
                int a = actual.getRGB(x, y), b = reference.getRGB(x, y);
                error += Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255));
                error += Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255));
                error += Math.abs((a & 255) - (b & 255));
                channels += 3;
            }
        }
        return 1.0 - ((double) error / channels) / 255.0;
    }

    private static void contactSheets(Path root, List<Screen> screens) throws Exception {
        Path outputs = fixedDirectory(workspaceRoot(root), "outputs");
        Files.createDirectories(outputs);
        writeContactSheet(root, screens, safeResolve(outputs, "v16-ui-contact-sheet.png"), "v16 UI · 全部 57 个状态", 6);
        writeContactSheet(root, select(screens, 1, 13), safeResolve(outputs, "v16-ui-contact-home-face.png"), "首页 · 人脸 · 掌纹", 4);
        writeContactSheet(root, select(screens, 14, 26), safeResolve(outputs, "v16-ui-contact-locker-unlock.png"), "选柜 · 开柜", 4);
        writeContactSheet(root, select(screens, 27, 39), safeResolve(outputs, "v16-ui-contact-return.png"), "离场还柜", 4);
        writeContactSheet(root, select(screens, 40, 57), safeResolve(outputs, "v16-ui-contact-admin-enrollment.png"), "管理员 · SDK · 录入", 4);
        System.out.println("CONTACT-SHEET PASS files=5 output=" + outputs);
    }

    private static List<Screen> select(List<Screen> screens, int first, int last) {
        return screens.stream().filter(s -> s.id >= first && s.id <= last).toList();
    }

    private static void writeContactSheet(Path root, List<Screen> screens, Path output, String title, int columns) throws Exception {
        int margin = 20, gap = 12, thumbW = columns == 6 ? 192 : 288, thumbH = thumbW * 5 / 8;
        int labelH = columns == 6 ? 36 : 42, headerH = 74;
        int rows = (screens.size() + columns - 1) / columns;
        int sheetW = margin * 2 + columns * thumbW + (columns - 1) * gap;
        int sheetH = headerH + margin + rows * (thumbH + labelH + gap) + margin;
        BufferedImage sheet = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        configure(g);
        g.setPaint(new GradientPaint(0, 0, new Color(7, 37, 69), sheetW, sheetH, new Color(18, 91, 133)));
        g.fillRect(0, 0, sheetW, sheetH);
        g.setColor(new Color(255, 255, 255, 30));
        for (int x = 0; x < sheetW; x += 32) g.drawLine(x, 0, x, sheetH);
        drawCentered(g, title, sheetW / 2, 46, font(true, columns == 6 ? 28 : 32), Color.WHITE);
        Path targetDir = fixedDirectory(root, "app/src/main/res/drawable-nodpi");
        for (int i = 0; i < screens.size(); i++) {
            Screen screen = screens.get(i);
            int col = i % columns, row = i / columns;
            int x = margin + col * (thumbW + gap);
            int y = headerH + row * (thumbH + labelH + gap);
            validateDrawableName(screen.drawable);
            BufferedImage image = ImageIO.read(safeResolve(targetDir, screen.drawable + ".png").toFile());
            require(image != null, "cannot decode contact-sheet source: " + screen.drawable);
            g.setColor(new Color(255, 255, 255, 210));
            g.fillRoundRect(x - 2, y - 2, thumbW + 4, thumbH + 4, 8, 8);
            g.drawImage(image, x, y, thumbW, thumbH, null);
            g.setColor(new Color(4, 27, 48, 225));
            g.fillRoundRect(x, y + thumbH, thumbW, labelH, 0, 0);
            g.setFont(font(true, columns == 6 ? 13 : 16));
            g.setColor(Color.WHITE);
            String id = String.format("%02d", screen.id);
            g.drawString(id + "  " + headline(screen), x + 8, y + thumbH + (columns == 6 ? 23 : 27));
        }
        g.dispose();
        require(ImageIO.write(sheet, "PNG", output.toFile()), "cannot write contact sheet: " + output);
    }

    private static List<Screen> parseScreens(Path manifest) throws Exception {
        List<Screen> screens = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            Matcher matcher = SCREEN_LINE.matcher(line.trim());
            if (matcher.find()) {
                screens.add(new Screen(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.group(3),
                        matcher.group(4), matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8),
                        Boolean.parseBoolean(matcher.group(9))));
            }
        }
        screens.sort(Comparator.comparingInt(Screen::id));
        return screens;
    }

    private static Map<String, int[]> regions() {
        Map<String, int[]> map = new HashMap<>();
        map.put("HOME_INPUTS", new int[]{320, 150, 1240, 700});
        map.put("CREDENTIAL_STATUS", new int[]{300, 120, 1240, 700});
        map.put("RESULT_MESSAGE", new int[]{250, 150, 1030, 650});
        map.put("FACE_PREVIEW", new int[]{20, 65, 1260, 730});
        map.put("LOCKER_GRID", new int[]{145, 110, 1235, 700});
        map.put("RETURN_AUTH", new int[]{20, 65, 1260, 730});
        map.put("RETURN_LIST", new int[]{20, 65, 1260, 730});
        map.put("RETURN_PROGRESS", new int[]{20, 65, 1260, 730});
        map.put("ADMIN_PIN", new int[]{386, 212, 896, 462});
        map.put("ADMIN_PANEL", new int[]{20, 105, 1260, 710});
        map.put("SERIAL_PANEL", new int[]{220, 110, 1090, 700});
        map.put("SDK_PANEL", new int[]{322, 185, 962, 555});
        map.put("ENROLLMENT_PANEL", new int[]{20, 105, 1260, 710});
        map.put("NONE", new int[]{0, 0, 0, 0});
        return map;
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static void loadFonts() {
        regularFont = loadFont(Path.of("C:/Windows/Fonts/NotoSansSC-VF.ttf"));
        if (regularFont == null) regularFont = loadFont(Path.of("C:/Windows/Fonts/msyh.ttc"));
        if (regularFont == null) regularFont = new Font("Microsoft YaHei", Font.PLAIN, 20);
        boldFont = loadFont(Path.of("C:/Windows/Fonts/msyhbd.ttc"));
        if (boldFont == null) boldFont = regularFont.deriveFont(Font.BOLD);
    }

    private static Font loadFont(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            return Font.createFont(Font.TRUETYPE_FONT, path.toFile());
        } catch (FontFormatException | IOException ignored) {
            return null;
        }
    }

    private static Font font(boolean bold, float size) {
        return (bold ? boldFont : regularFont).deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path projectRoot() {
        String value = System.getProperty("zip.ui.projectRoot");
        require(value != null && !value.isBlank(), "zip.ui.projectRoot is required");
        return validateProjectRoot(Path.of(value));
    }

    private static Path validateProjectRoot(Path candidate) {
        Path root = candidate.toAbsolutePath().normalize();
        require(root.getFileName() != null && root.getFileName().toString().equals("smart-locker-serial-test-v16"),
                "project root must be smart-locker-serial-test-v16: " + root);
        require(Files.isDirectory(root), "project root is not a directory: " + root);
        require(Files.isRegularFile(fixedDirectory(root, "tools/zip-ui-v16/manifest.json")),
                "project root is missing the v16 manifest: " + root);
        require(Files.isDirectory(fixedDirectory(root, "app/src/main/res")),
                "project root is missing the Android resource directory: " + root);
        return root;
    }

    private static void validateDrawableName(String name) {
        require(name != null && name.matches("zip_screen_[0-9]{2}_[a-z0-9_]+"),
                "invalid drawable name: " + name);
    }

    private static void validateReferenceName(String name) {
        require(name != null && name.matches("img-(0[1-9]|[12][0-9]|3[0-7])\\.png"),
                "invalid reference name: " + name);
    }

    private static Path fixedDirectory(Path root, String relative) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(relative).normalize();
        require(candidate.startsWith(normalizedRoot), "fixed path escapes root: " + relative);
        return candidate;
    }

    private static Path safeResolve(Path directory, String fileName) {
        require(fileName != null && !fileName.isBlank(), "file name is required");
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path target = normalizedDirectory.resolve(fileName).normalize();
        require(target.startsWith(normalizedDirectory) && target.getParent() != null
                        && target.getParent().equals(normalizedDirectory),
                "target escapes fixed directory: " + fileName);
        return target;
    }

    private static Path workspaceRoot(Path projectRoot) {
        Path work = projectRoot.getParent();
        require(work != null && work.getParent() != null, "cannot derive workspace root from " + projectRoot);
        Path workspace = work.getParent().toAbsolutePath().normalize();
        require(work.getFileName() != null && work.getFileName().toString().equals("work"),
                "v16 project must be inside the workspace work directory: " + projectRoot);
        return workspace;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
