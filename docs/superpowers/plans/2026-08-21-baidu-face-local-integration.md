# 智能更衣柜 v13 百度人脸本地联调 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:test-driven-development` for every behavior change, `superpowers:verification-before-completion` before each task handoff, and `superpowers:requesting-code-review` after Tasks 6, 10, and 12.

**Goal:** 在 v12 基线上交付一个独立 v13 APK：管理员可在本应用内在线激活百度 Face SDK 8.5；客户只有点击“人脸识别”后才启动 RK3288 前置摄像头；本地真实检测、质量筛选和完整画面抓拍完成后，由明确标记的本地模拟校验器放行进入现有选柜页；确认柜门之前绝不发送开锁帧。

**Architecture:** 百度授权和模型常驻在进程级 `FaceSubsystem`；每次点击创建独立 `FaceCaptureSession`，通过 Camera1 把 640×480 NV21 帧串行交给百度运行时，三帧稳定合格后编码非镜像正向 JPEG，再交给可替换 `FaceVerificationClient`。v13 的 `localDemo` source set 提供本地模拟校验，`production` source set只提供拒绝实现。`KioskFlowModel` 保存不可变的 `FaceVerificationResult`，现有动态选柜与 `UnlockCoordinator` 继续负责柜门发现和唯一开锁入口。客户串口写入经带来源的策略层审计和拦截。

**Tech Stack:** Android Java 8、平台 Camera1、百度离线 Face SDK Android 8.5 AAR、Android `Handler`/单线程 `ExecutorService`、JUnit 4、现有 PowerShell 离线构建链（javac/D8/AAPT2/zipalign/apksigner）。

**Spec:** `docs/superpowers/specs/2026-08-21-baidu-face-local-integration-design.md`

## Global Constraints

- 基线是 v12；`outputs/智能更衣柜-ID卡二维码8秒恢复联调版-v12.apk` 的 SHA-256 必须始终为 `ED12E785B9DF67D3683D434E438D9AC4938719A7899148BB60B1364D36AEBF17`，大小必须为 `148161` 字节。
- 新构建只允许删除经绝对路径校验的 `manual-build/v13`，不得覆盖 `manual-build/v6` 至 `manual-build/v12` 或历史输出。
- 激活码只允许管理员在真机运行时输入；源码、测试、资源、文档、日志、APK 字符串和构建脚本均不得出现用户提供的完整激活码。
- 不导入百度 Demo Activity、数据库、人脸注册、特征提取、1:N 搜索、活体、NIR、属性等代码或模型。
- 人脸图片仅存在内存，不裁剪、不落盘、不写日志、不在 v13 发送到任何服务器。
- `LOCAL_DEMO` 默认关闭；只有管理员显式开启后才可模拟通过。人脸页和由该凭证进入的选柜页持续显示 `本机联调：未进行身份比对`。
- `MainActivity` 从点击人脸到进入选柜之前，客户串口写入必须为零；选柜发现阶段只允许三条现有查询，各最多一次；用户确认前 `8A` 帧必须为零。
- 管理员串口页面 `buildScreen`、`buildHeader`、`buildControlCard`、`buildLogCard` 四个方法体必须与 Task 7 基线逐字符一致。
- 项目不是 Git 仓库。每个任务不执行虚假的 commit；改为写入 `.superpowers/sdd/2026-08-21-baidu-face-v13/task-N-report.md`，记录 RED、GREEN、验证命令、改动文件 SHA-256 和未解决风险。
- 每个任务先新增失败测试并保存 RED 输出，再写最小生产实现；不得先写生产代码后补测试。
- Android 包装层无法直接纯 JVM 执行的行为，要下沉到无 Android import 的状态机/策略类测试，并增加源码接线测试。最终仍必须在 RK3288 真机完成相机、授权、方向和资源释放验收。

---

### Task 1: 固化百度 AAR 与六个模型的供应链清单

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/face/BaiduFaceArtifactManifest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/BaiduFaceArtifactManifestTest.java`
- Create binary: `app/libs/FaceSDK_8.5_20241220-release.aar`
- Create binary: `app/src/main/assets/face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1`
- Create binary: `app/src/main/assets/face-sdk-models/align/align_rgb-customized-pa-fast.model.float32-0.7.5.5`
- Create binary: `app/src/main/assets/face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4`
- Create binary: `app/src/main/assets/face-sdk-models/blur/blur-customized-pa-addcloud_quant_e19.model.float32-3.0.13.3`
- Create binary: `app/src/main/assets/face-sdk-models/occlusion/occlusion-customized-pa-paddle.model.float32-2.0.7.3`
- Create binary: `app/src/main/assets/face-sdk-models/best_image/best_image-mobilenet-pa-dcqe449_live_e51_relu_128.model.float32-1.0.3.1`
- Create: `tools/baidu-face-api-probe/Baidu85ApiProbe.java`

**Step 1: Write the failing artifact-manifest test**

Test must assert exact relative path, SHA-256, size, uniqueness, and that the list contains exactly one AAR plus six models. The public surface is:

```java
public final class BaiduFaceArtifactManifest {
    public static final class Artifact {
        public String relativePath();
        public String sha256();
        public long sizeBytes();
        public Kind kind();
    }
    public enum Kind { AAR, MODEL }
    public static List<Artifact> requiredArtifacts();
}
```

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

Expected RED: JVM test compilation fails only because `BaiduFaceArtifactManifest` does not exist.

**Step 2: Implement the immutable manifest**

Use these exact records:

| Artifact | SHA-256 | Bytes |
|---|---:|---:|
| `app/libs/FaceSDK_8.5_20241220-release.aar` | `E77439F9DC4F530FF739F423EC80DA5D0AA1F5F055155FED58CC0BD1B43487E5` | `6435103` |
| detect RGB | `080B7123EA0B01AFDB7D972916272D702338C9F41A89F2F7CF402F257EAA32B1` | `948451` |
| align fast | `22205B4AF4D15C7B553481D0B5FCB99B1FA3B964FB813C715DEB7B2B4901D4A7` | `1233870` |
| align accurate | `A6C478F38C40448F0640BA3144DD29A35BAE09E1B6C70983E140B51F266D398F` | `2792512` |
| blur | `16B33D67D648D02284B1B91FB434B7E96D84051CF1A60A29E00C7E07002E1FB2` | `133739` |
| occlusion | `422AF339B14F61E9D505E056B1B3771AE29147813A0B6501A1E99437D9AC4AF7` | `391504` |
| best image | `EE5E69447D0AB603BCB79FAC43FDBB71565F44740734EDCC2DA4662FDF0C6E0A` | `1118807` |

Return an unmodifiable list and reject duplicate paths in static initialization.

**Step 3: Copy only approved binary artifacts**

Read source from `C:\Users\Administrator\Desktop\Baidu_Face_Offline_SDK_Android_8.5(1).zip`, extract into a fresh temporary directory, resolve each source file by exact basename, reject zero or multiple matches, verify source hash/size, copy to the exact destination, then verify destination hash/size. Do not copy the Demo APK, full sample project, licenses, unlisted models, or standalone `.so` files.

**Step 4: Run focused GREEN and an independent hash check**

Before the full script, extract the AAR and compile a nonexecuted API probe against AAR `classes.jar`, `libs/bd_facecollect_unifylicense.jar` and `libs/liantian.jar`. The probe must directly type-check these exact SDK roles, using only the six approved model paths:

```java
BDFaceInstance instance = new BDFaceInstance();
instance.creatInstance();
FaceDetect tracker = new FaceDetect(instance);
FaceDetect detector = new FaceDetect(instance);
tracker.initModel(context, DETECT_MODEL, ALIGN_FAST_MODEL,
        BDFaceSDKCommon.DetectType.DETECT_VIS,
        BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_FAST, callback);
detector.initModel(context, DETECT_MODEL, ALIGN_ACCURATE_MODEL,
        BDFaceSDKCommon.DetectType.DETECT_VIS,
        BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE, callback);
detector.initQuality(context, BLUR_MODEL, OCCLUSION_MODEL, callback);
detector.initBestImage(context, BEST_IMAGE_MODEL, callback);
BDFaceImageInstance image = new BDFaceImageInstance(
        nv21, 480, 640,
        BDFaceSDKCommon.BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,
        0, 1);
try {
    tracker.track(BDFaceSDKCommon.DetectType.DETECT_VIS,
            BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_FAST, image);
    detector.detect(BDFaceSDKCommon.DetectType.DETECT_VIS, image);
} finally {
    image.destory();
}
```

The probe also references `FaceAuth`, `LH` and `AndroidLicenser` class symbols so missing nested authorization JARs fail now. `DETECT_VIS` above is an SDK enum, not an asset; the only model-string arguments are the six artifact-manifest paths. Any compile mismatch is a Task 1 blocker and must be resolved by `javap` against the pinned AAR, without adding a seventh model.

Do **not** run the current full v12 build after adding these production assets: it would rebuild and overwrite the protected v12 deliverable. Instead, compile and run only the focused test into a fresh temporary directory:

```powershell
$root = (Resolve-Path '.').Path
$tmp = Join-Path $env:TEMP ('codex-face-task1-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
& 'C:\Users\Administrator\.jdks\jbr-21.0.11\bin\javac.exe' `
  -encoding UTF-8 -source 8 -target 8 -Xlint:none -nowarn `
  -cp "$root\manual-build\tooling\junit-4.13.2.jar;$root\manual-build\tooling\hamcrest-core-1.3.jar" `
  -d $tmp `
  "$root\app\src\main\java\com\codex\lockertest\face\BaiduFaceArtifactManifest.java" `
  "$root\app\src\test\java\com\codex\lockertest\face\BaiduFaceArtifactManifestTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Task 1 focused compile failed' }
& 'C:\Users\Administrator\.jdks\jbr-21.0.11\bin\java.exe' `
  -cp "$tmp;$root\manual-build\tooling\junit-4.13.2.jar;$root\manual-build\tooling\hamcrest-core-1.3.jar" `
  org.junit.runner.JUnitCore com.codex.lockertest.face.BaiduFaceArtifactManifestTest
if ($LASTEXITCODE -ne 0) { throw 'Task 1 focused test failed' }
```

Run `Get-FileHash` and `Get-Item` on all seven destination files and compare against the table. Task 2 must convert the build script to v13 before the next successful full build.

**Step 5: Record the checkpoint**

Write Task 1 report with the ZIP source path, seven source/destination hashes, test output, and a statement that no activation code or license file was copied.

---

### Task 2: 建立 v13 两种 source set 与可审计离线构建链

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `scripts/build-debug.ps1`
- Modify: `app/src/main/java/com/codex/lockertest/ui/ZipKioskShell.java`
- Modify: `README.md`
- Create: `app/src/localDemo/java/com/codex/lockertest/face/FaceBuildVariant.java`
- Create: `app/src/production/java/com/codex/lockertest/face/FaceBuildVariant.java`
- Test: `app/src/localDemoTest/java/com/codex/lockertest/face/FaceBuildVariantTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/BaiduFaceBuildContractSourceTest.java`

**Step 1: Write the failing build-contract source test**

Assert all of the following in source:

- Gradle minSdk 21, ABI filters only `armeabi-v7a` and `arm64-v8a`, local AAR dependency, `localDemo` and `production` flavors.
- Manifest declares exactly `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`; camera feature is `required=false`; Liantian Activity matches the vendor sample; no storage/phone/settings permissions.
- Script uses only `manual-build/v13`, version 13/13.0-demo, protects v12 hash/size before and after, extracts the AAR into v13 build space, includes AAR `classes.jar`, `bd_facecollect_unifylicense.jar`, `liantian.jar`, packages all `classes*.dex`, includes assets, and checks only two ABIs.
- Script contains seven artifact hash/size assertions and rejects any additional `face-sdk-models` file.
- Script searches source and DEX for the activation-code pattern and fails on a 4×4 uppercase/digit group separated by hyphens.

Do not invoke the old v12 script for this RED because it owns the protected v12 build directory. Compile and run only the source-contract test in a new GUID-named temporary directory, using the same JDK/JUnit/Hamcrest absolute paths shown in Task 1:

```powershell
$root = (Resolve-Path '.').Path
$tmp = Join-Path $env:TEMP ('codex-face-task2-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
& 'C:\Users\Administrator\.jdks\jbr-21.0.11\bin\javac.exe' `
  -encoding UTF-8 -source 8 -target 8 -Xlint:none -nowarn `
  -cp "$root\manual-build\tooling\junit-4.13.2.jar;$root\manual-build\tooling\hamcrest-core-1.3.jar" `
  -d $tmp `
  "$root\app\src\test\java\com\codex\lockertest\face\BaiduFaceBuildContractSourceTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Task 2 RED test compile failed unexpectedly' }
& 'C:\Users\Administrator\.jdks\jbr-21.0.11\bin\java.exe' `
  -cp "$tmp;$root\manual-build\tooling\junit-4.13.2.jar;$root\manual-build\tooling\hamcrest-core-1.3.jar" `
  org.junit.runner.JUnitCore com.codex.lockertest.face.BaiduFaceBuildContractSourceTest
```

Expected RED: JUnit fails its v13 source assertions while the v12 APK and `manual-build/v12` remain untouched. After this proof, patch all Task 2 production/build files and run only the new v13 script.

**Step 2: Configure Gradle without importing the Demo app**

Apply this shape:

```groovy
defaultConfig {
    minSdk 21
    targetSdk 30
    versionCode 13
    versionName '13.0-demo'
    ndk { abiFilters 'armeabi-v7a', 'arm64-v8a' }
}
flavorDimensions 'faceVerification'
productFlavors {
    localDemo { dimension 'faceVerification' }
    production { dimension 'faceVerification' }
}
dependencies {
    implementation files('libs/FaceSDK_8.5_20241220-release.aar')
    testImplementation 'junit:junit:4.13.2'
}
```

Keep Java 8, `minifyEnabled false`, existing legacy JNI packaging, package name `com.codex.lockertest`, compileSdk 34, and targetSdk 30.

**Step 3: Add the minimum Manifest contract**

Add the three permissions and optional camera feature. Add this exact vendor fingerprint Activity, while keeping existing activities unchanged:

```xml
<activity
    android:name="com.baidu.liantian.LiantianActivity"
    android:excludeFromRecents="true"
    android:exported="true"
    android:launchMode="standard"
    android:theme="@android:style/Theme.Translucent">
    <intent-filter>
        <action android:name="com.baidu.action.Liantian.VIEW" />
        <category android:name="com.baidu.category.liantian" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Do not add storage, phone, autofocus-as-permission, `largeHeap`, or legacy external storage flags.

**Step 4: Upgrade the manual builder**

The script must:

1. Validate and remove only the exact resolved `manual-build/v13` directory.
2. Protect v6–v12; add exact v12 SHA/size PRE and POST checks.
3. Extract the AAR below `manual-build/v13/third-party/FaceSDK_8.5_20241220-release`.
4. Compile Android sources with classpath `android.jar;classes.jar;bd_facecollect_unifylicense.jar;liantian.jar`.
5. Use three explicit source/test lists: common JVM (`main` pure + `src/test`), local-demo JVM (`main` pure + `localDemo` pure + `src/localDemoTest`), and production Android safety (`main + production`). Missing source roots are treated as empty rather than shell errors.
6. Compile `app/src/main/java` plus `app/src/localDemo/java` for the v13 APK; separately compile `main + production` as a fail-closed safety gate.
7. Feed app classes and all three vendor JARs to D8 with `--min-api 21`.
8. Link assets with `-A app/src/main/assets`, link minSdk 21 and version 13.
9. Package every `classes*.dex`.
10. Package all AAR native libraries plus `libserial_port.so` for only armv7 and arm64; compare the exact archive entry set.
11. Verify the exact three permissions, six model assets, two ABIs, minSdk21, target30, package/label/version, v1/v2/v3 signature, frozen certificate, Admin builders, frozen 24-file baseline, and v12 immutability.
12. Deliver only `outputs/智能更衣柜-百度人脸本地联调版-v13.apk`.

The two `FaceBuildVariant` classes have the same FQCN and only this API:

```java
public final class FaceBuildVariant {
    public static boolean isLocalDemo();
}
```

The localDemo implementation returns true; production returns false. This is a real variant boundary used later by the ticket validator, not a temporary stub. Its test runs only in the local-demo test compilation.

**Step 5: Update visible version copy**

Set the footer to `版本：v13.0 Demo` and README final output path to the v13 APK. Keep v12 documentation/history intact.

**Step 6: Run GREEN**

Run the script once. At this stage the APK contains the SDK assets but no customer face flow. The package must pass all build/package assertions and v12 must remain unchanged.

**Step 7: Record the checkpoint**

Record build output, APK hash/size, exact native entries, asset entries, permissions, badging, and protected-version hashes.

---

### Task 3: 定义校验结果、demo 开关与本地模拟校验器

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationStatus.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationSource.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationRequest.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationResult.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationClient.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationTicketValidator.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceJpegContract.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationEnvironment.java`
- Create: `app/src/main/java/com/codex/lockertest/face/verification/FaceVerificationTicketRegistry.java`
- Create: `app/src/localDemo/java/com/codex/lockertest/face/verification/FaceVerificationAssembly.java`
- Create: `app/src/localDemo/java/com/codex/lockertest/face/verification/LocalPassFaceVerificationClient.java`
- Create: `app/src/localDemo/java/com/codex/lockertest/face/verification/LocalDemoPreferenceStore.java`
- Create: `app/src/production/java/com/codex/lockertest/face/verification/FaceVerificationAssembly.java`
- Test: `app/src/test/java/com/codex/lockertest/face/verification/FaceVerificationResultTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/verification/FaceVerificationTicketValidatorTest.java`
- Test: `app/src/localDemoTest/java/com/codex/lockertest/face/verification/LocalPassFaceVerificationClientTest.java`

**Step 1: Write RED tests for the immutable result and ticket rules**

Required API:

```java
public final class FaceVerificationResult {
    static FaceVerificationResult passed(
            FaceVerificationSource source,
            String requestId,
            String credential,
            String ticketId,
            long expiresAtEpochMillis,
            String deviceBinding,
            String processBinding,
            long verificationPolicyEpoch);
    public static FaceVerificationResult terminalFailure(
            FaceVerificationStatus status, String requestId);
    // passed(...) is package-private; public API exposes immutable getters only
}

public interface FaceVerificationClient {
    interface Callback { void onCompleted(FaceVerificationResult result); }
    interface Cancellable { void cancel(); }
    Cancellable verify(FaceVerificationRequest request, Callback callback);
}
```

Test malformed request/ticket IDs, missing credentials, nonfuture expiry, wrong device/process/request, cancel, wrong source, missing registry entry, revoked ticket, policy-epoch mismatch and expiry at the exact boundary.

**Step 2: Write RED tests for local demo policy**

Use a fake scheduler and fake JPEG decoder. Assert:

- default disabled rejects immediately;
- enabled valid JPEG completes once after exactly 800 ms;
- SOI/EOI, decodable width/height, nonzero dimensions, and `<= 1_048_576` bytes are mandatory;
- cancel before 800 ms suppresses success and zeroes the owned JPEG copy;
- credential expires exactly 60 seconds after issuance and binds request/device/process;
- disabling the environment increments a monotonic policy epoch, clears the ticket registry, invalidates all outstanding demo requests, and makes every ticket issued under the prior epoch fail validation;
- logs receive only request ID, stage, elapsed time and byte count.

**Step 3: Implement main-domain types and validation**

`FaceVerificationTicketValidator.validate(...)` must return a typed reason:

```java
enum TicketVerdict {
    VALID,
    NOT_PASSED,
    DISABLED,
    WRONG_SOURCE,
    REQUEST_MISMATCH,
    DEVICE_MISMATCH,
    PROCESS_MISMATCH,
    TICKET_NOT_REGISTERED,
    TICKET_REVOKED,
    POLICY_EPOCH_MISMATCH,
    EXPIRED
}
```

No component may infer validity from a nonempty credential string alone. `FaceVerificationTicketRegistry` distinguishes `ACTIVE`, `REVOKED` and unknown ticket IDs; revoked tombstones are retained only through the ticket's original 60-second expiry and then purged, preventing unbounded growth. Adapter tests must assert every verdict. In particular, closing the demo switch after selection returns `DISABLED`, `TICKET_REVOKED` or `POLICY_EPOCH_MISMATCH`, never a generic credential failure.

**Step 4: Implement flavor-separated assemblies**

- `localDemo` assembly owns the private preference key, monotonic in-process policy epoch and local client. Every enable/disable transition increments the epoch; a result stores the epoch at issue time.
- `production` assembly always reports demo disabled, refuses enable operations, and returns a client that produces `SERVER_ERROR` without inspecting JPEG contents.
- Main code references only the common `FaceVerificationEnvironment` contract and the flavor-specific `FaceVerificationAssembly` factory.
- Neither production class nor common code contains `LOCAL_DEMO` pass-generation logic.
- `FaceVerificationEnvironment` exposes `currentPolicyEpoch()`; ticket validation requires the result epoch to equal the current epoch, so turning the switch off immediately invalidates an already displayed selection ticket.
- `FaceVerificationEnvironment.validateTicket(...)` also checks `isEnabled()` and a random 128-bit `ticketId` in `FaceVerificationTicketRegistry`. Only the package-private result issuer used by the verifier can register a passed ticket; UI and Flow cannot manufacture one from a string.

**Step 5: Run GREEN and formal-source guard**

Run the full build. Additionally compile `main + production`, DEX it in a temporary directory, and assert its string table contains neither `LocalPassFaceVerificationClient` nor a demo pass credential prefix. Save this assertion in the build script for every later task.

---

### Task 4: 实现纯 JVM 人脸质量门与三帧稳定规则

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/face/FaceObservation.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceQualityConfig.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceFrameQualityGate.java`
- Test: `app/src/test/java/com/codex/lockertest/face/FaceFrameQualityGateTest.java`

**Step 1: Write exhaustive RED tests**

The immutable observation must carry frame size, face count, detection confidence, center/size, yaw/pitch/roll, blur, `illum`, seven occlusion scores, completeness, and best-image score. The gate returns:

```java
public final class FaceFrameQualityGate {
    public enum Outcome {
        NO_FACE, MULTIPLE_FACES, TOO_SMALL, OFF_CENTER, LOW_CONFIDENCE,
        BAD_POSE, TOO_BLURRY, TOO_DARK, OCCLUDED, INCOMPLETE,
        NOT_BEST_IMAGE, STABILIZING, CAPTURE
    }
    public Decision evaluate(FaceObservation observation);
    public void reset();
}
```

Test every boundary both at and just outside the threshold. Exact config:

- confidence `>= 0.5`;
- face width and height `>= 60 px`;
- center X within `[0.20, 0.80] × frameWidth`, center Y within `[0.15, 0.85] × frameHeight`;
- absolute yaw/pitch/roll `<= 30`;
- blur `<= 0.8`;
- illum `>= 0.8`;
- each of leftEye/rightEye/nose/mouth/leftCheek/rightCheek/chin `<= 0.8`;
- completeness `== 1.0`;
- best image `> 0.5`;
- exactly three consecutive qualified processed frames produce one `CAPTURE`; any rejection resets the count.

**Step 2: Implement immutable config/DTO and ordered rejection**

Return the first actionable customer hint in this fixed priority: face count → size → position → confidence → pose → blur → light → occlusion → completeness → best image → stabilization. Never expose numeric SDK values to customers.

**Step 3: Run GREEN and mutation tests**

Ensure callers cannot mutate arrays/occlusion values after construction. Run the full JVM suite.

---

### Task 5: 实现有界帧队列、会话状态机、超时与清零

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/face/FaceFrame.java`
- Create: `app/src/main/java/com/codex/lockertest/face/LatestFaceFrameMailbox.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceCapture.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceCaptureSession.java`
- Test: `app/src/test/java/com/codex/lockertest/face/LatestFaceFrameMailboxTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/FaceCaptureSessionTest.java`

**Step 1: Write mailbox RED tests**

The mailbox owns at most one pending NV21 frame. Offering a newer frame while one is pending must zero the dropped frame; taking transfers ownership; closing zeroes the pending frame and rejects future offers.

**Step 2: Write session RED tests**

Public state contract:

```java
public final class FaceCaptureSession {
    public enum State {
        PREPARING, DETECTING, CAPTURING, VERIFYING,
        SUCCESS, FAILURE, CANCELLED, TIMEOUT
    }
    public long start();
    public void onRuntimeReady(long sessionId);
    public void onCameraReady(long sessionId);
    public void onPreviewFrame(long sessionId, FaceFrame frame);
    public void onObservation(long sessionId, long frameId, FaceObservation observation);
    public void onJpegReady(long sessionId, long frameId, byte[] jpeg);
    public void onVerification(long sessionId, FaceVerificationResult result);
    public void cancel();
}
```

Actions are injected and must be idempotent. Test:

- only one active session and one in-flight SDK frame;
- 100 ms analysis rate limit;
- latest-frame replacement without queue growth;
- three qualified frames trigger exactly one JPEG encode and one verification;
- license/model 15s, camera first-frame 5s, detection 20s, JPEG 3s, verifier 3s;
- first terminal event wins;
- stale session/frame/verifier callbacks are ignored and their byte arrays zeroed;
- Back/onStop/cancel stops camera, cancels verifier/timers, clears mailbox and JPEG exactly once;
- success stops camera and clears JPEG before notifying UI.

**Step 3: Implement the state machine without Android imports**

Inject `Clock`, `Scheduler`, `Actions`, `FaceFrameQualityGate`, and `Listener`. Do not let the state machine own Activity, Camera, SDK or Handler objects.

**Step 4: Run GREEN with forced interleavings**

Use latch/fake scheduler tests for cancel-vs-success, timeout-vs-success, and replacement-vs-analysis completion. Full JVM suite must remain green.

---

### Task 6: 接入百度在线授权和六模型单线程 Runtime

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/face/baidu/license/BdFaceAuth.java`
- Create: `app/src/main/java/com/codex/lockertest/face/baidu/license/CodeDetail.java`
- Create: `app/src/main/java/com/codex/lockertest/face/baidu/license/CodeLicenseDetail.java`
- Create: `app/src/main/java/com/codex/lockertest/face/baidu/license/data/SituationData.java`
- Create: `app/src/main/java/com/codex/lockertest/face/baidu/license/util/BdFileUtils.java`
- Create: `app/src/main/java/com/codex/lockertest/face/baidu/license/util/BdHttpUtils.java`
- Create: `app/src/main/java/com/codex/lockertest/face/BaiduFaceLicenseManager.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceLicenseStateMachine.java`
- Create: `app/src/main/java/com/codex/lockertest/face/BaiduFaceRuntime.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceRuntimeStateMachine.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceDeviceBindingProvider.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceProcessBinding.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceSubsystem.java`
- Test: `app/src/test/java/com/codex/lockertest/face/FaceLicenseStateMachineTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/FaceRuntimeStateMachineTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/BaiduFaceRuntimeSourceTest.java`

**Step 1: Write RED state-machine tests**

License states: `UNKNOWN / CHECKING_LOCAL / ACTIVATING_ONLINE / READY / INVALID / FAILED`. Runtime states: `UNINITIALIZED / INITIALIZING / READY / FAILED / RELEASED`. Test single-flight initialization, activation retry, stale callbacks, timeout, and idempotent release.

**Step 2: Vendor only the six auth helper sources**

Start from the SDK sample under `AuthLibrary`, change package names, and keep the official `initLicenseOnLine` contract. Mandatory hardening:

- no full activation response, license ID, device fingerprint, license bytes or token in log;
- do not enable SDK `ALL` activation logging;
- use per-connection `setConnectTimeout(8000)` and `setReadTimeout(8000)`;
- close input/output streams in `finally` and always `disconnect()`;
- local license files stay under `Context.getFilesDir()`;
- activation input is held only until submission completes, then cleared from the `EditText` and controller reference.

**Step 3: Implement the license wrapper**

```java
public interface BaiduFaceLicenseManager {
    interface Listener {
        void onReady(String baiduDeviceId);
        void onFailure(int code, String safeMessage);
    }
    void checkLocal(Context context, Listener listener);
    void activateOnline(Context context, char[] licenseId, Listener listener);
    void cancelUiWait();
    FaceLicenseStateMachine.State state();
}
```

Use `char[]` at the application boundary, overwrite it after calling the vendor helper, never expose it in RuntimeSerialLog, and map raw response text to a finite administrator-safe error code/message.

**Step 4: Implement the SDK runtime on one executor**

Use one shared `BDFaceInstance`, one fast tracker and one accurate detector. Exact initialization sequence:

```java
BDFaceInstance instance = new BDFaceInstance();
instance.creatInstance();
FaceDetect tracker = new FaceDetect(instance);
FaceDetect detector = new FaceDetect(instance);
tracker.loadConfig(new BDFaceSDKConfig());
detector.loadConfig(new BDFaceSDKConfig());
tracker.initModel(context, DETECT_MODEL_PATH, ALIGN_FAST_MODEL_PATH,
        BDFaceSDKCommon.DetectType.DETECT_VIS,
        BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_FAST, callback);
detector.initModel(context, DETECT_MODEL_PATH, ALIGN_ACCURATE_MODEL_PATH,
        BDFaceSDKCommon.DetectType.DETECT_VIS,
        BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE, callback);
detector.initQuality(context, BLUR_MODEL_PATH, OCCLUSION_MODEL_PATH, callback);
detector.initBestImage(context, BEST_IMAGE_MODEL_PATH, callback);
```

Every callback must succeed before `READY`. Analyze a frame by constructing `BDFaceImageInstance` with NV21 and current rotation/mirror; use fast `track` for preview box and accurate `detect` with quality/best-image enabled for `FaceObservation`. Always call the vendor spelling `image.destory()` in `finally`. Read `FaceInfo.illum`, not a nonexistent illumination field. Copy scalar values into the app DTO; never retain `FaceInfo`, `BDFaceOcclusion`, SDK image objects, or landmarks after the executor task ends.

**Step 5: Build the process singleton**

`FaceSubsystem.shared(Context)` owns license manager, runtime, verification environment and no Activity. It may retain loaded models for the process lifetime; it must never own a camera or customer page. Listener subscription must be identity-based so an old Activity cannot receive new state.

`FaceDeviceBindingProvider` computes an opaque SHA-256 from the application ID and `Settings.Secure.ANDROID_ID`; it never exposes or logs the raw Android ID. `FaceProcessBinding` generates one 128-bit random nonce when the process singleton is created. These two injected bindings plus the verification policy epoch are required to validate every demo ticket.

**Step 6: Run GREEN, Android compile, and independent code review**

Run full JVM/build. Review specifically: secret logging, SDK object lifetime, callback thread, duplicate init, all six model callbacks, `destory()` paths, and release ordering. Do not proceed while any Critical/Important finding remains.

---

### Task 7: 自写 RK3288 Camera1 控制器与完整画面 JPEG 编码

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/face/CameraOrientationPolicy.java`
- Create: `app/src/main/java/com/codex/lockertest/face/Camera1FaceCameraController.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceJpegEncoder.java`
- Create: `app/src/main/java/com/codex/lockertest/face/AndroidFaceJpegEncoder.java`
- Test: `app/src/test/java/com/codex/lockertest/face/CameraOrientationPolicyTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/Camera1FaceCameraControllerSourceTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/AndroidFaceJpegEncoderSourceTest.java`

**Step 1: Write orientation RED tests**

Pure output:

```java
public final class CameraOrientationPolicy {
    public static Transform frontCamera(int sensorOrientation, int displayRotationDegrees);
    public static final class Transform {
        public int previewDisplayOrientation();
        public int sdkRotationDegrees();
        public int sdkMirror();
        public int jpegRotationDegrees();
        public boolean jpegMirror();
    }
}
```

For front camera:

- upright sensor/JPEG rotation uses `(sensorOrientation - displayRotationDegrees + 360) % 360`;
- preview display orientation uses `(360 - ((sensorOrientation + displayRotationDegrees) % 360)) % 360`, matching the Camera1 front-camera mirror-compensation formula;
- SDK preview analysis mirror is `1`;
- final JPEG applies the measured upright rotation and `jpegMirror=false`.

Test all sensor/display combinations of 0/90/180/270 and reject other values. RK3288 sign/order must be verified against a photographed orientation card before final acceptance; once measured, encode the result as a device test fixture rather than a runtime hardcode.

**Step 2: Write Camera1 source-contract RED tests**

Assert front camera only, exact 640×480 and NV21, supported-size/format validation, `setPreviewCallbackWithBuffer`, at least three callback buffers, legal FPS-range selection from supported values, single release path, and no fallback to back camera.

**Step 3: Implement the controller**

```java
public interface Listener {
    void onCameraReady(CameraDescriptor descriptor);
    void onPreviewFrame(byte[] nv21, int width, int height, Transform transform);
    void onCameraError(String safeMessage, String diagnostic);
}
void start(SurfaceHolder holder, Listener listener);
void stop();
boolean isRunning();
```

Return buffers only after the consumer releases ownership. Stop must remove callbacks before `stopPreview/release`, be idempotent, and never call UI while holding the camera lock.

**Step 4: Implement full-frame in-memory JPEG**

`AndroidFaceJpegEncoder` performs `YuvImage.compressToJpeg` at quality 85, decodes in memory, rotates upright, applies no mirror to the final output, re-encodes quality 85, recycles all Bitmaps in `finally`, and validates SOI/EOI, dimensions, decodability and 1 MiB cap. Every temporary byte array is zeroed on failure/cancel; no file APIs are imported.

**Step 5: Run GREEN and Android compile**

Run the full build and source scans for external storage/file writes. Record that desktop tests do not replace RK3288 camera validation.

---

### Task 8: 建立人脸页、联调横幅和管理员激活/开关层

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/FaceRecognitionView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/FaceDemoBanner.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/FaceSdkAdminOverlay.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/AdminFunctionOverlay.java`
- Create: `app/src/main/java/com/codex/lockertest/FaceSdkAdminActivity.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/codex/lockertest/ui/FaceUiSourceTest.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/FaceAdminIntegrationSourceTest.java`

**Step 1: Write RED source/UI model tests**

Assert:

- `ZipHomeView.Listener` has a distinct `onFaceRequested()`; FACE does not call `onUnavailableSelected` while PALM still does.
- `FaceRecognitionView` contains a real `SurfaceView`, return control, status, retry control and persistent banner.
- `LockerSelectionView` can display the same banner without changing selection model data.
- customer text contains no SDK code, device ID, activation code, token, URL or photo byte count.
- `AdminSerialActivity.java` remains byte-identical to the frozen 24-file manifest, including its four protected builder methods.

**Step 2: Implement the 1280×800 face page**

Use `ZipKioskShell`, a large centered preview with rounded overlay frame, one status line, a real return button, and the orange `FaceDemoBanner`. Public methods:

```java
SurfaceHolder previewHolder();
void showPreparing();
void showDetecting(String hint, FaceBox box);
void showVerifying();
void showPermissionDenied(boolean permanentlyDenied);
void showFailure(String message, boolean retryable);
void setListener(Listener listener);
```

Do not place SDK/business logic in the View.

**Step 3: Add selection-page banner**

Add `LockerSelectionView.setSecurityBanner(CharSequence text, boolean visible)`. It must occupy a fixed header strip, preserve the 4×8 grid geometry, area/page navigation and send freeze, and be impossible to dismiss. Hidden state restores the exact v12 layout.

**Step 4: Add an Admin-only settings Activity without touching the serial assistant**

After the existing administrator PIN succeeds, Task 11 will show `AdminFunctionOverlay` with three actions: `串口调试`, `百度人脸 SDK`, `返回首页`. `串口调试` launches the existing `AdminSerialActivity` unchanged; `百度人脸 SDK` launches the new non-exported landscape `FaceSdkAdminActivity`. The face settings Activity hosts `FaceSdkAdminOverlay`, which contains:

- current license/runtime state;
- masked activation-code `EditText` with uppercase text input and paste support;
- `在线激活` button with one-request guard and 15-second UI timeout;
- `本机模拟校验` switch, default off;
- clear explanation that it does not perform identity verification;
- close button.

Do not store activation text in fields after submission. Do not modify `AdminSerialActivity` at all. Closing the demo switch cancels active demo verification and invalidates issued demo credentials. Register `FaceSdkAdminActivity` with `exported=false`; it has no launcher intent filter.

**Step 5: Run GREEN and exact builder hashes**

Full build must report the complete frozen 24-file manifest exact and four Admin builder `EXACT=True`; independently verify `AdminSerialActivity.java` full SHA-256 remains `AF8CA7F50620900CDA0FCBC42470682BA972235C9E5342A407B50ED6ADAA43BD`.

---

### Task 9: 扩展 Flow 与凭证适配器，冻结人脸验证快照并处理过期

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/KioskFlowModel.java`
- Modify: `app/src/main/java/com/codex/lockertest/unlock/UnlockCredentialAdapter.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/KioskFlowModelTest.java`
- Test: `app/src/test/java/com/codex/lockertest/unlock/UnlockCredentialAdapterTest.java`

**Step 1: Write Flow RED tests**

Add `FACE_RECOGNITION` and exact APIs:

```java
public boolean beginFaceRecognition();
public boolean acceptFaceVerification(FaceVerificationResult result, long nowEpochMillis);
public FaceVerificationResult pendingFaceVerification();
public ConfirmResult confirmLockerAt(LockerTarget target, long nowEpochMillis);

public enum ConfirmResult {
    ACCEPTED,
    INVALID_SELECTION,
    FACE_CREDENTIAL_EXPIRED
}
```

Test:

- HOME → FACE only through explicit click;
- failed/cancelled/expired result stays on FACE;
- valid result freezes the same immutable object, method FACE and opaque credential, then begins discovery;
- back from FACE returns HOME and clears result;
- FACE confirmation at `expiresAt-1` succeeds; at `expiresAt` clears selected locker, area/page and credential, then returns FACE with an expiry flag;
- no overload extends expiry or reconstructs a ticket;
- PHONE/PASSWORD/ID existing tests remain unchanged.

**Step 2: Implement adapter RED tests**

Replace the two-argument mapping for FACE with structured resolution:

```java
public static ResolvedCredential resolve(
        UnlockMethod customerMethod,
        String rawCredential,
        FaceVerificationResult faceResult,
        long nowEpochMillis,
        String expectedDeviceBinding,
        String expectedProcessBinding,
        FaceVerificationEnvironment verificationEnvironment);
```

FACE resolves to coordinator method FACE only if `verificationEnvironment.validateTicket(...)` succeeds against current enabled state, ticket registry, policy epoch, request, device/process bindings and expiry. Production rejects `LOCAL_DEMO`; localDemo accepts it only while the exact registered ticket remains valid. Direct `UnlockMethod.FACE + arbitraryString` must fail. Add a regression that disables demo after entering selection and proves confirm cannot resolve the old result. Preserve ID_CARD→frozen QR bridge and PHONE/PASSWORD behavior.

**Step 3: Implement minimal Flow/adapter changes**

Keep `UnlockCoordinator` protocol behavior unchanged. Add only `FACE` to `coordinatorFeatureEnabled` after the structured adapter gate. Ensure failure retry for a face-authenticated unlock never refreshes the ticket; if it expires before reconfirm, return to face page.

**Step 4: Run GREEN and legacy regression**

Run Flow, adapter, coordinator, discovery, locker protocol and full JVM suites. Assert all 12 historical open commands remain byte-identical.

---

### Task 10: 为客户串口写入增加来源、阶段和白名单审计

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/serial/SerialWriteAttribution.java`
- Create: `app/src/main/java/com/codex/lockertest/integration/CustomerSerialWritePolicy.java`
- Create: `app/src/main/java/com/codex/lockertest/integration/CustomerSerialTransmitter.java`
- Test: `app/src/test/java/com/codex/lockertest/integration/CustomerSerialWritePolicyTest.java`
- Test: `app/src/test/java/com/codex/lockertest/integration/CustomerSerialTransmitterTest.java`
- Test: `app/src/test/java/com/codex/lockertest/integration/CustomerSerialAttributionSourceTest.java`

**Step 1: Write policy RED tests**

```java
public enum CustomerSerialPhase {
    FACE_PRE_SELECTION,
    LOCKER_DISCOVERY,
    LOCKER_CONFIRMED,
    TERMINAL
}

public final class CustomerSerialWritePolicy {
    public Decision authorize(
            CustomerSerialPhase phase,
            long operationId,
            SerialWriteAttribution attribution,
            byte[] payload);
    public void endOperation(long operationId);
}
```

Test:

- `FACE_PRE_SELECTION` rejects every payload;
- discovery accepts exactly `80 01 01 33 B3`, `80 02 01 33 B0`, `80 03 01 33 B1`, each once per operation, and rejects duplicates/other frames;
- discovery rejects every `8A` prefix;
- confirmed unlock accepts only the exact payload supplied by `LockerProtocol.open(target)` for the same operation/target and only through UNLOCK attribution;
- stale/ended operations reject;
- payload arrays are defensively copied.

**Step 2: Add an attributed customer boundary without changing frozen SerialGateway**

`CustomerSerialTransmitter` is the only customer-side boundary allowed to call existing `SerialGateway.send(byte[])`. It receives an injected `GatewayWriter` and `RuntimeSerialLog` adapter, validates the `SerialWriteAttribution`, authorizes through the policy, appends safe origin/phase/operation ID plus existing HEX to the administrator log, then calls the writer with a defensive copy. A rejection emits typed send failure and performs no Gateway call. `SerialGateway.java` and all other frozen serial files remain byte-identical.

**Step 3: Implement the customer transmitter**

All Main customer coordinator actions call `CustomerSerialTransmitter`, which checks policy before resolving the exact current Gateway lease and calling the existing send through its injected writer. Rejection produces a typed send failure and never falls back from the transmitter to another path.

**Step 4: Run GREEN and independent protocol review**

Source scan must prove `MainActivity` and its customer action adapters have no direct `gateway.send(byte[])` call; only `CustomerSerialTransmitter` may call it for customers. `AdminSerialActivity` remains the existing direct user and byte-identical. Full protocol/coordinator tests, frozen 24 files and Admin builder hashes must pass.

---

### Task 11: 在 MainActivity 完成人脸权限、会话、相机和选柜编排

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Create: `app/src/main/java/com/codex/lockertest/face/FaceRecognitionController.java`
- Test: `app/src/test/java/com/codex/lockertest/face/FaceRecognitionControllerTest.java`
- Test: `app/src/test/java/com/codex/lockertest/face/FaceMainIntegrationSourceTest.java`

**Step 1: Write controller RED tests**

The controller owns the Activity-local `FaceCaptureSession`, camera, preview, encoder and verifier, while the process singleton owns license/runtime. Test the exact sequence:

1. start session;
2. license READY;
3. runtime READY;
4. camera permission granted;
5. Surface ready/open camera;
6. analyze bounded frames;
7. encode once;
8. verify once;
9. stop/clear resources;
10. post structured success.

Test permission denial, camera/runtimes errors, every timeout, retry creates a new session ID, and stale callback suppression.

**Step 2: Write Main source-boundary RED tests**

Assert:

- FACE button invokes `beginFaceRecognition`, not unavailable;
- camera starts only while screen is FACE and permission is granted;
- every controller callback uses unconditional `handler.post` then checks `active`, UI generation, source View identity and session ID;
- Back, return home, Admin launch, `onStop` and `onDestroy` invalidate the face generation before canceling resources;
- success cancels/releases face work before `acceptFaceVerification`, render selection and `startDiscoveryOperation`;
- no face path calls `submitLocker`, `UnlockCoordinator.start` or any serial send before user confirmation;
- `onRequestPermissionsResult` rechecks permission and never loops the system dialog;
- user-visible errors are safe text; diagnostics contain no photo/activation/token.

**Step 3: Implement Main wiring**

Add FACE case to `renderScreen`, create `FaceRecognitionView`, and use `FaceRecognitionController`. On valid result:

```java
cancelFaceWorkAndInvalidate();
if (!flow.acceptFaceVerification(result, clock.nowEpochMillis())) {
    return;
}
renderScreen();
startDiscoveryOperation();
```

At locker confirm, call `confirmLockerAt`. If `FACE_CREDENTIAL_EXPIRED`, do not render RESULT and do not start unlock; clear active discovery work, render FACE, and show `验证已过期，请重新识别`.

On administrator authentication, render the new `AdminFunctionOverlay`. Its serial choice runs the unchanged existing handoff into `AdminSerialActivity`; its face-SDK choice starts `FaceSdkAdminActivity` without acquiring or releasing the serial Gateway. Back/cancel returns home safely.

**Step 4: Replace customer serial writes with transmitter**

- Face page sets phase `FACE_PRE_SELECTION`.
- Discovery reserves a new operation and phase `LOCKER_DISCOVERY`.
- Confirmed selection registers the exact `LockerProtocol.open(target)` payload and phase `LOCKER_CONFIRMED`.
- Terminal/cancel ends the operation.

Do not close or recreate the process-owned serial Gateway during face navigation.

**Step 5: Run full GREEN and request code review**

Run all tests/build. Independent review must cover camera lifecycle, license/runtime ownership, secret/privacy, face credential expiry, stale callbacks, serial zero-send/whitelist, and all existing credential flows. Resolve every Critical/Important finding before Task 12.

---

### Task 12: 真机构建守卫、v13 打包与 RK3288 验收

**Files:**
- Modify: `scripts/build-debug.ps1`
- Modify: `README.md`
- Create: `.superpowers/sdd/2026-08-21-baidu-face-v13/rk3288-acceptance.md`
- Create: `.superpowers/sdd/2026-08-21-baidu-face-v13/task-12-report.md`

**Step 1: Add final fail-closed build assertions**

The script must fail unless:

- all JVM tests pass;
- `main + localDemo` and `main + production` compile;
- Android compile/D8/AAPT2/zipalign/signing pass;
- v13 contains exact AAR native entries and the six exact model hashes;
- v13 contains only armv7/arm64 plus serial `.so` in each;
- only the three approved permissions exist;
- minSdk21/version13/13.0-demo/label/package/certificate/signatures match;
- `LOCAL_DEMO` banner text exists in localDemo DEX and `LocalPassFaceVerificationClient` does not exist in production DEX;
- activation-code regex finds zero matches in Java/resources/scripts/docs selected for packaging and in DEX strings;
- privacy scanning uses two exact scopes: `face/baidu/license/**` may write only the literal private files `license.key` and `license.ini` below `Context.getFilesDir()` and may use HTTP connection streams; capture/runtime/camera/JPEG/verification/UI code must not import or reference `File`, `FileOutputStream`, `FileWriter`, `RandomAccessFile`, `MediaStore`, `Environment`, `openFileOutput`, `getCacheDir`, or any external-storage path. `ByteArrayOutputStream` is allowed only for in-memory JPEG encoding. The RK3288 acceptance additionally compares application-private files/cache listings before and after capture to confirm that no JPEG is persisted;
- Admin four builder bodies, frozen 24-file list and v6–v12 artifacts remain exact;
- final APK timestamp is newer than every production input and output hash equals signed source hash.

**Step 2: Run a single fresh full package build**

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

Capture the full output. Do not rerun only to obtain prettier logs; rerun only after a deterministic failure is fixed.

**Step 3: Independently inspect the APK**

Outside the build script, use the ASCII `manual-build/v13` signed source for `aapt`, `zipalign`, `apksigner`, ABI, asset, permission and DEX inspection. Compare SHA-256/size with the Chinese output path.

**Step 4: Execute RK3288 acceptance**

Record each item with pass/fail and evidence:

1. app installs on the RK3288 armv7 terminal;
2. admin enters the activation code at runtime; source/log screenshot proves it is masked and not logged;
3. online activation succeeds or the exact safe vendor error is recorded; app restart reuses local authorization;
4. `initQuality` and best-image model both report success, proving the license includes required features;
5. camera permission allow/deny/permanent deny flows;
6. camera remains off until FACE is clicked and turns off immediately on Back/home/background/success;
7. 640×480 NV21 is supported; preview is user-correct mirrored; JPEG orientation card is upright and non-mirrored;
8. no face/multiple faces/too far/off-center/pose/blur/dark/occlusion hints;
9. three good processed frames cause one capture and, with demo enabled, one pass after about 800 ms;
10. banner remains visible on FACE and selection pages;
11. demo disabled never enters selection; disabling during verification cancels it; 60-second expiry returns to FACE without serial unlock;
12. serial audit: zero writes before selection, exactly the permitted discovery queries, zero `8A` until confirm, one exact open command after confirm;
13. 50 enter/exit cycles: no camera-in-use failure; post-GC heap growth ≤8 MiB; camera reopens immediately;
14. PHONE/PASSWORD/ID/QR/PALM-unavailable/Admin serial/dynamic locker pages regressions pass.

**Step 5: Final review and handoff**

Request a final independent review against the approved spec. Deliver only after verdict READY with no Critical/Important findings. Final response must state:

- exact v13 APK absolute path, SHA-256 and size;
- v12 unchanged hash/size;
- Baidu activation and model status observed on RK3288;
- what is real (camera/detection/quality/JPEG) and what remains simulated (server identity verification);
- that formal server upload is not implemented and cannot be claimed until its endpoint contract arrives.
