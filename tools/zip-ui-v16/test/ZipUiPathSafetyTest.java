import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

public final class ZipUiPathSafetyTest {
    public static void main(String[] args) throws Exception {
        Path root = projectRoot();
        Method validateRoot = ZipUiAssetTool.class.getDeclaredMethod("validateProjectRoot", Path.class);
        Method validateDrawable = ZipUiAssetTool.class.getDeclaredMethod("validateDrawableName", String.class);
        Method safeResolve = ZipUiAssetTool.class.getDeclaredMethod("safeResolve", Path.class, String.class);
        validateRoot.setAccessible(true);
        validateDrawable.setAccessible(true);
        safeResolve.setAccessible(true);

        Path validated = (Path) validateRoot.invoke(null, root);
        require(validated.equals(root), "valid v16 root must be accepted");
        expectRejected(validateRoot, root.getParent(), "non-v16 project root");
        validateDrawable.invoke(null, "zip_screen_05_face_preparing");
        expectRejected(validateDrawable, "../escape", "drawable traversal");
        expectRejected(validateDrawable, "zip_screen_5_bad", "non-two-digit drawable id");
        expectRejected(validateDrawable, "zip_screen_05_BAD", "uppercase drawable");

        Path drawableDir = root.resolve("app/src/main/res/drawable-nodpi").normalize();
        Path safe = (Path) safeResolve.invoke(null, drawableDir, "zip_screen_05_face_preparing.png");
        require(safe.startsWith(drawableDir) && safe.getParent().equals(drawableDir), "safe target must stay in drawable directory");
        expectRejected(safeResolve, new Object[]{drawableDir, "../escape.png"}, "safeResolve traversal");
        System.out.println("PASS ZipUiPathSafetyTest root=bounded drawable=bounded output=bounded");
    }

    private static void expectRejected(Method method, Object argument, String label) throws Exception {
        expectRejected(method, new Object[]{argument}, label);
    }

    private static void expectRejected(Method method, Object[] arguments, String label) throws Exception {
        try {
            method.invoke(null, arguments);
            throw new AssertionError("expected rejection: " + label);
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalStateException || expected.getCause() instanceof IllegalArgumentException,
                    "wrong rejection type for " + label + ": " + expected.getCause());
        }
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
