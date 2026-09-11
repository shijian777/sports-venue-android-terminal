import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ZipUiManifestTest {
    private static final Pattern SCREEN = Pattern.compile(
            "\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+).*?\\\"enumName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                    + "\\\"drawableName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"referenceImage\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
            Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        String json = Files.readString(root.resolve("tools/zip-ui-v16/manifest.json"), StandardCharsets.UTF_8);
        require(json.contains("\"designWidth\": 1280"), "manifest design width must be 1280");
        require(json.contains("\"designHeight\": 800"), "manifest design height must be 800");
        require(json.contains("\"protectedBrandTitle\": \"乾卦智能柜自助终端\""), "protected title changed");
        require(json.contains("\"protectedFooterBrand\": \"乾卦SaaS管理系统(gmtfit.com)\""), "protected footer changed");

        Matcher matcher = SCREEN.matcher(json);
        Set<Integer> ids = new HashSet<>();
        Set<String> enums = new HashSet<>();
        Set<String> drawables = new HashSet<>();
        Set<String> references = new HashSet<>();
        int count = 0;
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            String enumName = matcher.group(2);
            String drawable = matcher.group(3);
            String reference = matcher.group(4);
            require(ids.add(id), "duplicate id: " + id);
            require(enums.add(enumName), "duplicate enumName: " + enumName);
            require(drawables.add(drawable), "duplicate drawableName: " + drawable);
            require(reference.matches("img-(0[1-9]|[12][0-9]|3[0-7])\\.png"), "invalid reference: " + reference);
            references.add(reference);
            require(id == count + 1, "ids must be contiguous at " + id);
            require(drawable.startsWith(String.format("zip_screen_%02d_", id)), "drawable id prefix mismatch: " + drawable);
            count++;
        }
        require(count == 57, "manifest must contain 57 screens, got " + count);
        require(drawables.size() == 57, "manifest must map 57 unique drawables");
        require(references.size() >= 1 && references.size() <= 37, "reference mapping count out of range");
        System.out.println("PASS ZipUiManifestTest screens=57 uniqueDrawables=57 protectedBrand=OK");
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
