package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BootstrapContractFixtureEvidenceTest {
    @Test
    public void fixturesAreExactFencedJsonFromPinnedRepositoryEvidence() throws Exception {
        Path root = projectRoot();
        String evidence = readStrictUtf8(
                root.resolve("docs/contracts/bootstrap-api-contract-sanitized.md"));
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("### Request-envelope fixture: checkDevice",
                "check-device-request-provisional.json");
        fixtures.put("### Request-envelope fixture: empty endpoints (provisional)",
                "empty-request-provisional.json");
        fixtures.put("### checkDevice success fixture",
                "check-device-success-official-sanitized.json");
        fixtures.put("### baseSetting success fixture",
                "base-setting-success-official-sanitized.json");
        fixtures.put("### basicData success fixture",
                "basic-data-success-official-sanitized.json");
        fixtures.put("### Non-200 fixture policy", "non-200-synthetic.json");

        for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
            String expected = extractUniqueJsonFence(evidence, fixture.getKey());
            String actual = readStrictUtf8(root.resolve(
                    "app/src/test/fixtures/server/" + fixture.getValue()));
            assertEquals(fixture.getValue(), expected, actual);
        }
    }

    @Test
    public void filenamesAndEvidenceKeepUnknownRequestShapesAndSyntheticFailureExplicit()
            throws Exception {
        String evidence = readStrictUtf8(projectRoot().resolve(
                "docs/contracts/bootstrap-api-contract-sanitized.md"));

        assertTrue(evidence.contains(
                "Request examples are parser fixtures based on public data:Array only"));
        assertTrue(evidence.contains(
                "Use the second fixture only as a provisional parser fixture"));
        assertTrue(evidence.contains("A test may use this **synthetic** minimal failure fixture"));
        assertTrue("check-device-request-provisional.json".contains("provisional"));
        assertTrue("empty-request-provisional.json".contains("provisional"));
        assertTrue("non-200-synthetic.json".contains("synthetic"));
    }

    private static String extractUniqueJsonFence(String evidence, String heading) {
        int headingAt = evidence.indexOf(heading);
        if (headingAt < 0 || evidence.indexOf(heading, headingAt + 1) >= 0) {
            throw new AssertionError("heading must occur exactly once: " + heading);
        }
        int fenceAt = evidence.indexOf("~~~json\n", headingAt + heading.length());
        if (fenceAt < 0) throw new AssertionError("JSON fence missing: " + heading);
        int contentAt = fenceAt + "~~~json\n".length();
        int endAt = evidence.indexOf("\n~~~", contentAt);
        if (endAt < 0) throw new AssertionError("JSON fence end missing: " + heading);
        return evidence.substring(contentAt, endAt) + "\n";
    }

    private static String readStrictUtf8(Path path) throws Exception {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
            return text.replace("\r\n", "\n").replace('\r', '\n');
        } catch (CharacterCodingException invalid) {
            throw new AssertionError("invalid UTF-8: " + path.getFileName());
        }
    }

    private static Path projectRoot() {
        return Paths.get(System.getProperty("codex.projectRoot", "."))
                .toAbsolutePath().normalize();
    }
}
