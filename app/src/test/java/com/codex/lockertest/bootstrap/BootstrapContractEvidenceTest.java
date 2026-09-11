package com.codex.lockertest.bootstrap;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;

/** Verifies the repository-local sanitized bootstrap contract evidence. */
public final class BootstrapContractEvidenceTest {
    private static final String CONTRACT_PATH =
            "docs/contracts/bootstrap-api-contract-sanitized.md";
    private static final String PIN_PATH =
            ".superpowers/contracts/bootstrap-api-contract-sanitized.sha256";
    private static final String EXPECTED_SHA256 =
            "03140013D8AD4FB94B3D62F866E945FC11F3132164989ACABA5441DADF28A0B3";

    @Test
    public void sanitizedContractMatchesItsNormalizedLfSha256Pin() throws Exception {
        Path projectRoot = Paths.get(
                System.getProperty("codex.projectRoot", ".")).toAbsolutePath().normalize();
        String evidence = readStrictUtf8(projectRoot.resolve(CONTRACT_PATH));
        String pin = normalizeLf(readStrictUtf8(projectRoot.resolve(PIN_PATH)));

        assertEquals(EXPECTED_SHA256 + "  " + CONTRACT_PATH + "\n", pin);
        assertEquals(EXPECTED_SHA256, sha256(normalizeLf(evidence)));
    }

    private static String readStrictUtf8(Path path) throws Exception {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new AssertionError("not strict UTF-8: " + path, error);
        }
    }

    private static String normalizeLf(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            result.append(String.format("%02X", item & 0xff));
        }
        return result.toString();
    }
}
