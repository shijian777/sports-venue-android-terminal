import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public final class ZipUiAssetTest {
    private static final Pattern SCREEN = Pattern.compile(
            "\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+).*?\\\"enumName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                    + "\\\"drawableName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"template\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                    + "\\\"dynamicRegion\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"referenceImage\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                    + "\\\"directReference\\\"\\s*:\\s*(true|false)", Pattern.DOTALL);
    private static final Map<String, int[]> REGIONS = regions();

    private record Screen(int id, String enumName, String drawable, String template,
                          String dynamicRegion, String referenceImage, boolean directReference) {}

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        List<Screen> screens = parseScreens(root.resolve("tools/zip-ui-v16/manifest.json"));
        require(screens.size() == 57, "manifest screen count is not 57");
        Path targetDir = root.resolve("app/src/main/res/drawable-nodpi");
        Set<String> expected = new HashSet<>();
        for (Screen screen : screens) expected.add(screen.drawable + ".png");

        List<String> missing = new ArrayList<>();
        for (String name : expected) if (!Files.isRegularFile(targetDir.resolve(name))) missing.add(name);
        if (!missing.isEmpty()) {
            throw new AssertionError("Missing target resources: " + missing.size() + " of 57; first=" + missing.get(0));
        }

        List<Path> actualPaths;
        try (var stream = Files.list(targetDir)) {
            actualPaths = stream.filter(p -> p.getFileName().toString().matches("zip_screen_.*\\.png"))
                    .sorted().toList();
        }
        require(actualPaths.size() == 57, "target directory must contain exactly 57 zip_screen PNGs, got " + actualPaths.size());
        for (Path path : actualPaths) require(expected.contains(path.getFileName().toString()), "extra target resource: " + path.getFileName());

        Path referenceDir = root.resolve("tools/zip-ui-v16/reference");
        double minimumSimilarity = 1.0;
        String minimumName = "";
        for (Screen screen : screens) {
            Path targetPath = targetDir.resolve(screen.drawable + ".png");
            BufferedImage target = ImageIO.read(targetPath.toFile());
            require(target != null, "PNG decode failed: " + targetPath);
            require(target.getWidth() == 1280 && target.getHeight() == 800,
                    "wrong dimensions: " + targetPath + " = " + target.getWidth() + "x" + target.getHeight());
            require(isRgbOrArgb(target), "PNG must decode with RGB or ARGB color model: " + targetPath
                    + " type=" + target.getType());
            require(distinctRgb(target, 256), "image has fewer than 256 distinct RGB values: " + targetPath);
            require(((target.getRGB(0, 0) >>> 24) & 0xff) != 0, "top-left is transparent: " + targetPath);
            require(((target.getRGB(1279, 0) >>> 24) & 0xff) != 0, "top-right is transparent: " + targetPath);
            require(((target.getRGB(0, 799) >>> 24) & 0xff) != 0, "bottom-left is transparent: " + targetPath);
            require(((target.getRGB(1279, 799) >>> 24) & 0xff) != 0, "bottom-right is transparent: " + targetPath);
            require(footerOpaque(target), "footer contains fully transparent pixels: " + targetPath);

            BufferedImage reference = ImageIO.read(referenceDir.resolve(screen.referenceImage).toFile());
            require(reference != null, "reference PNG decode failed: " + screen.referenceImage);
            require(reference.getWidth() == 1280 && reference.getHeight() == 800, "reference dimensions invalid: " + screen.referenceImage);
            double similarity = stableSimilarity(target, reference, REGIONS.get(screen.dynamicRegion));
            double threshold = screen.directReference ? 0.995 : 0.95;
            require(similarity >= threshold, String.format("similarity %.6f below %.3f for %s", similarity, threshold, screen.enumName));
            if (similarity < minimumSimilarity) {
                minimumSimilarity = similarity;
                minimumName = screen.enumName;
            }
        }
        System.out.printf("PASS ZipUiAssetTest assets=57 dimensions=1280x800 minSimilarity=%.6f screen=%s%n",
                minimumSimilarity, minimumName);
    }

    private static List<Screen> parseScreens(Path manifest) throws Exception {
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        Matcher matcher = SCREEN.matcher(json);
        List<Screen> screens = new ArrayList<>();
        while (matcher.find()) {
            screens.add(new Screen(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.group(3),
                    matcher.group(4), matcher.group(5), matcher.group(6), Boolean.parseBoolean(matcher.group(7))));
        }
        return screens;
    }

    private static boolean distinctRgb(BufferedImage image, int minimum) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colors.add(image.getRGB(x, y) & 0xffffff);
                if (colors.size() >= minimum) return true;
            }
        }
        return false;
    }

    private static boolean isRgbOrArgb(BufferedImage image) {
        var model = image.getColorModel();
        return model.getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_RGB
                && model.getNumColorComponents() == 3
                && (model.getNumComponents() == 3 || model.getNumComponents() == 4);
    }

    private static boolean footerOpaque(BufferedImage image) {
        for (int y = 730; y < 800; y += 4) {
            for (int x = 0; x < 1280; x += 4) {
                if (((image.getRGB(x, y) >>> 24) & 0xff) == 0) return false;
            }
        }
        return true;
    }

    private static double stableSimilarity(BufferedImage actual, BufferedImage reference, int[] excluded) {
        long error = 0;
        long channels = 0;
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 1280; x++) {
                if (excluded != null && x >= excluded[0] && y >= excluded[1] && x < excluded[2] && y < excluded[3]) continue;
                int a = actual.getRGB(x, y);
                int b = reference.getRGB(x, y);
                error += Math.abs(((a >>> 16) & 0xff) - ((b >>> 16) & 0xff));
                error += Math.abs(((a >>> 8) & 0xff) - ((b >>> 8) & 0xff));
                error += Math.abs((a & 0xff) - (b & 0xff));
                channels += 3;
            }
        }
        return 1.0 - ((double) error / channels) / 255.0;
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

    private static Path projectRoot() {
        String value = System.getProperty("zip.ui.projectRoot");
        require(value != null && !value.isBlank(), "zip.ui.projectRoot is required");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
