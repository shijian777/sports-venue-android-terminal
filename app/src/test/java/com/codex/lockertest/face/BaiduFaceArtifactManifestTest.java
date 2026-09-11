package com.codex.lockertest.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BaiduFaceArtifactManifestTest {
    @Test
    public void ordinaryRuntimeModelsExcludeTheOptionalRgbLivenessModel() {
        List<BaiduFaceArtifactManifest.Artifact> coreModels =
                BaiduFaceArtifactManifest.coreRuntimeModels();
        BaiduFaceArtifactManifest.Artifact livenessModel =
                BaiduFaceArtifactManifest.optionalLivenessModel();

        assertEquals(6, coreModels.size());
        for (BaiduFaceArtifactManifest.Artifact artifact : coreModels) {
            assertEquals(BaiduFaceArtifactManifest.Kind.MODEL, artifact.kind());
            assertFalse(artifact.relativePath().contains("silent_live"));
            assertFalse(artifact.relativePath().equals(livenessModel.relativePath()));
        }
        assertEquals(BaiduFaceArtifactManifest.Kind.MODEL, livenessModel.kind());
        assertTrue(livenessModel.relativePath().contains("silent_live"));
    }

    @Test
    public void requiredArtifactsContainsExactlyOneAarAndSevenModelsWithPinnedRecords() {
        List<BaiduFaceArtifactManifest.Artifact> artifacts =
                BaiduFaceArtifactManifest.requiredArtifacts();

        assertEquals(8, artifacts.size());
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.AAR,
                "app/libs/FaceSDK_8.5_20241220-release.aar",
                "E77439F9DC4F530FF739F423EC80DA5D0AA1F5F055155FED58CC0BD1B43487E5", 6435103L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1",
                "080B7123EA0B01AFDB7D972916272D702338C9F41A89F2F7CF402F257EAA32B1", 948451L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5",
                "22205B4AF4D15C7B553481D0B5FCB99B1FA3B964FB813C715DEB7B2B4901D4A7", 1233870L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4",
                "A6C478F38C40448F0640BA3144DD29A35BAE09E1B6C70983E140B51F266D398F", 2792512L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3",
                "16B33D67D648D02284B1B91FB434B7E96D84051CF1A60A29E00C7E07002E1FB2", 133739L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3",
                "422AF339B14F61E9D505E056B1B3771AE29147813A0B6501A1E99437D9AC4AF7", 391504L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1",
                "EE5E69447D0AB603BCB79FAC43FDBB71565F44740734EDCC2DA4662FDF0C6E0A", 1118807L);
        assertArtifact(artifacts, BaiduFaceArtifactManifest.Kind.MODEL,
                "app/src/main/assets/face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1",
                "015A0F9C54338DAF401266FEDFC19FE9FCF57E7F553CAE6CAA4D3E07C8D75A38", 2089103L);
    }

    @Test
    public void requiredArtifactsIsUnmodifiableAndPathsAreUnique() {
        List<BaiduFaceArtifactManifest.Artifact> artifacts =
                BaiduFaceArtifactManifest.requiredArtifacts();
        Set<String> paths = new HashSet<String>();
        for (BaiduFaceArtifactManifest.Artifact artifact : artifacts) {
            assertTrue("duplicate path: " + artifact.relativePath(), paths.add(artifact.relativePath()));
        }
        try {
            artifacts.add(artifacts.get(0));
            fail("requiredArtifacts must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable manifest behavior.
        }
    }

    private static void assertArtifact(List<BaiduFaceArtifactManifest.Artifact> artifacts,
            BaiduFaceArtifactManifest.Kind kind, String relativePath, String sha256, long sizeBytes) {
        for (BaiduFaceArtifactManifest.Artifact artifact : artifacts) {
            if (relativePath.equals(artifact.relativePath())) {
                assertEquals(kind, artifact.kind());
                assertEquals(sha256, artifact.sha256());
                assertEquals(sizeBytes, artifact.sizeBytes());
                return;
            }
        }
        fail("missing artifact: " + relativePath);
    }
}
