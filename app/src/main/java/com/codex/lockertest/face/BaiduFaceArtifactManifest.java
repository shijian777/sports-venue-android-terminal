package com.codex.lockertest.face;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable inventory of the Baidu Face SDK artifacts pinned for this application. */
public final class BaiduFaceArtifactManifest {
    private static final String OPTIONAL_LIVENESS_RELATIVE_PATH =
            "app/src/main/assets/face-sdk-models/silent_live/"
                    + "liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1";

    public enum Kind {
        AAR,
        MODEL
    }

    public static final class Artifact {
        private final String relativePath;
        private final String sha256;
        private final long sizeBytes;
        private final Kind kind;

        private Artifact(String relativePath, String sha256, long sizeBytes, Kind kind) {
            this.relativePath = relativePath;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.kind = kind;
        }

        public String relativePath() {
            return relativePath;
        }

        public String sha256() {
            return sha256;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public Kind kind() {
            return kind;
        }
    }

    private static final List<Artifact> REQUIRED_ARTIFACTS = createRequiredArtifacts();
    private static final List<Artifact> CORE_RUNTIME_MODELS = createCoreRuntimeModels();
    private static final Artifact OPTIONAL_LIVENESS_MODEL = findOptionalLivenessModel();

    private BaiduFaceArtifactManifest() {
    }

    public static List<Artifact> requiredArtifacts() {
        return REQUIRED_ARTIFACTS;
    }

    /** Six models required for ordinary detection and quality analysis. */
    public static List<Artifact> coreRuntimeModels() {
        return CORE_RUNTIME_MODELS;
    }

    /** Optional RGB liveness model; its failure must not disable ordinary capture. */
    public static Artifact optionalLivenessModel() {
        return OPTIONAL_LIVENESS_MODEL;
    }

    private static List<Artifact> createCoreRuntimeModels() {
        List<Artifact> models = new ArrayList<Artifact>();
        for (Artifact artifact : REQUIRED_ARTIFACTS) {
            if (artifact.kind() == Kind.MODEL
                    && !OPTIONAL_LIVENESS_RELATIVE_PATH.equals(artifact.relativePath())) {
                models.add(artifact);
            }
        }
        if (models.size() != 6) {
            throw new IllegalStateException("Unexpected Baidu Face core model inventory");
        }
        return Collections.unmodifiableList(models);
    }

    private static Artifact findOptionalLivenessModel() {
        for (Artifact artifact : REQUIRED_ARTIFACTS) {
            if (OPTIONAL_LIVENESS_RELATIVE_PATH.equals(artifact.relativePath())) {
                return artifact;
            }
        }
        throw new IllegalStateException("Optional RGB liveness model is not pinned");
    }

    private static List<Artifact> createRequiredArtifacts() {
        List<Artifact> artifacts = new ArrayList<Artifact>();
        artifacts.add(artifact("app/libs/FaceSDK_8.5_20241220-release.aar",
                "E77439F9DC4F530FF739F423EC80DA5D0AA1F5F055155FED58CC0BD1B43487E5", 6435103L, Kind.AAR));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1",
                "080B7123EA0B01AFDB7D972916272D702338C9F41A89F2F7CF402F257EAA32B1", 948451L, Kind.MODEL));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5",
                "22205B4AF4D15C7B553481D0B5FCB99B1FA3B964FB813C715DEB7B2B4901D4A7", 1233870L, Kind.MODEL));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4",
                "A6C478F38C40448F0640BA3144DD29A35BAE09E1B6C70983E140B51F266D398F", 2792512L, Kind.MODEL));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3",
                "16B33D67D648D02284B1B91FB434B7E96D84051CF1A60A29E00C7E07002E1FB2", 133739L, Kind.MODEL));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3",
                "422AF339B14F61E9D505E056B1B3771AE29147813A0B6501A1E99437D9AC4AF7", 391504L, Kind.MODEL));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1",
                "EE5E69447D0AB603BCB79FAC43FDBB71565F44740734EDCC2DA4662FDF0C6E0A", 1118807L, Kind.MODEL));
        artifacts.add(artifact("app/src/main/assets/face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1",
                "015A0F9C54338DAF401266FEDFC19FE9FCF57E7F553CAE6CAA4D3E07C8D75A38", 2089103L, Kind.MODEL));

        Set<String> paths = new HashSet<String>();
        for (Artifact artifact : artifacts) {
            if (!paths.add(artifact.relativePath())) {
                throw new IllegalStateException("Duplicate Baidu Face artifact path: " + artifact.relativePath());
            }
        }
        return Collections.unmodifiableList(artifacts);
    }

    private static Artifact artifact(String relativePath, String sha256, long sizeBytes, Kind kind) {
        return new Artifact(relativePath, sha256, sizeBytes, kind);
    }
}
