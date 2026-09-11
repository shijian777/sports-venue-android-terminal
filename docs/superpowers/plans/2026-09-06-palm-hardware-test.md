# Palm Hardware Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real vendor-backed palm enrollment/1:1 verification test inside the existing localDemo enrollment screen, without claiming server authorization.

**Architecture:** Pure Java session controller owns transient data and invalidates late callbacks. A localDemo-only SDK driver executes off the UI thread; production assembly never loads the vendor. Existing selection view embeds a closeable test panel.

**Tech Stack:** Java 8, Android API21 minimum/API25 device, JXPalm 2.2.17 ARMv7/ARM64, JUnit4, existing manual PowerShell build.

**Spec:** `docs/superpowers/specs/2026-09-06-palm-hardware-test-design.md`

## Global Constraints

- 保持 Android 7.1.2/API25、1280×800、乾卦品牌；不改变锁板指令、串口所有权、扫码/刷卡、原人脸和还柜行为。MainActivity 仅把 ADMIN 来源布尔值传给录入选择页，并在其生命周期失效路径显式清理掌静脉测试页；审计同步更新其精确文件摘要，不放松审计。
- 本阶段厂商 SDK 仅打入 localDemo。production 不包含掌静脉厂商类或原生库，仍保持现有 fail-closed 身份及业务边界。
- 不写盘、不上传、不记录掌静脉图片/特征。只在当前测试页持有临时模板。
- 不执行真实 SDK 激活/硬件采集/开柜/网络业务请求。
- 不覆盖任一已交付 APK，构建必须显式使用 outputs 内新的 DeliveryRoot。
- Preserve dirty baseline. No commits or branch reset; scoped before-copies and review packages provide change evidence. Existing approved in-place work continues on v17-production-safety.

### Task 1: Tested palm hardware session controller

**Files:** Create `app/src/main/java/com/codex/lockertest/palm/PalmTestController.java`; test `app/src/test/java/com/codex/lockertest/palm/PalmTestControllerTest.java`.

**Interfaces:**

```java
public final class PalmTestController {
  public enum State { IDLE, CONNECTING, READY, ENROLLING, ENROLLED,
                      VERIFYING, MATCHED, NOT_MATCHED, FAILED, CLOSED }
  public interface Listener { void onState(State state, int code); }
  public interface Scheduler { Runnable schedule(Runnable task, long delayMillis); }
  // schedule returns a cancellation Runnable. Work Executor is serialized.
  public interface Driver {
    interface Events {
      void onConnected();
      void onFeature(byte[] ownedFeature);
      void onFailure(int code);
      void onDisconnected();
    }
    void connect(Events events);
    void enroll(Events events);
    void capture(Events events);
    int verify(byte[] enrolled, byte[] presented);
    void stop();
    void close();
  }
  public PalmTestController(Driver driver, java.util.concurrent.Executor worker,
      Scheduler scheduler, Listener listener);
  public void connect();
  public void enroll();
  public void verify();
  public void close();
  public State state();
}
```

- [x] Write behavioral tests using controllable worker/scheduler and external driver fake. Example independently-derived flow:

```java
controller.connect(); worker.runAll(); driver.events.onConnected(); worker.runAll();
assertEquals(State.READY, controller.state());
controller.enroll(); worker.runAll();
byte[] feature = new byte[] { 11, 22, 33, 44 };
driver.events.onFeature(feature); worker.runAll();
assertEquals(State.ENROLLED, controller.state());
assertArrayEquals(new byte[4], feature); // ownership consumed, never retained raw
controller.close(); worker.runAll();
assertEquals(State.CLOSED, controller.state());
```

- [x] Watch expected RED before implementation. Verify duplicate events are wiped, late events after cancel/timeout do not change state, no verification before enrollment, disconnected callbacks from initial connection invalidate later capture, and repeated clicks do not overlap.
- [x] Implement state transitions. All driver invocations are worker-only, never under a monitor used by callbacks; immediate public close revokes delivery even while a native operation is still queued. Bounds: feature length 1..65536; match score strictly greater than 70, SDK <=0 error/no pass. Connection/enroll/verify timers 10000/30000/10000 ms; use named negative internal error codes so SDK codes remain distinguishable. Stop capture after terminal result but keep the device connected for same-page retry. Reconnect after FAILED closes previous driver before reopening. Do not add disk/network/auth logic or expose feature bytes to listener.
- [x] Run focused JUnit GREEN and self-review sensitive-buffer lifecycle. Save RED/GREEN commands/results and scoped new-file diff in `.superpowers/sdd/2026-09-06-palm-hardware-test/task-1-report.md` and `task-1-review.diff`. Do not commit mixed tree.

### Task 2: Real localDemo SDK, embedded test UI and reproducible delivery

**Files:** Create localDemo `palm/JxPalmTestDriver.java`, `palm/PalmHardwareTestPanel.java`, `palm/PalmHardwareTestAssembly.java`; production `palm/PalmHardwareTestAssembly.java`; main `palm/PalmHardwareTestPanelHandle.java`; modify `ui/BiometricEnrollmentChoiceView.java`, `app/build.gradle`, `scripts/build-debug.ps1`; add pinned `app/libs/JXPalm-release-2.2.17_RK356x_build3.aar`; relevant localDemo and Android tests.

**Integration refinements:** LocalDemo pure `PalmUsbDeviceFilter` and `PalmDeviceFeaturePolicy` with tests isolate verified USB IDs and feature/error rules. Minimal `MainActivity` ADMIN-origin/lifecycle changes require an exact hash update in `scripts/audit-production-apk.ps1`. `PalmPanelRegression` is invoked by `PixelLayoutRegression`; `scripts/test-pixel-layout.ps1` compiles both. `FaceVerificationResultTest` preserves the build-contract check for the D8 argument array plus the localDemo-only palm input gate. Factory/view/close exceptions are isolated at the enrollment view boundary; tests must exercise real View behavior, not only compile.

**Interfaces:** Consumes Task1 controller. `PalmHardwareTestPanelHandle` returns `android.view.View view()` and `void close()`. Flavor assembly static `create(android.content.Context context, Runnable onBack)` returns handle in localDemo and null in production. Production must not reference localDemo vendor/driver classes.

- [x] Before implementation add lifecycle test fixture for embedded panel opening/closing via test factory and no vendor loading in production assembly. Use real controller for state tests, not source-string assertions.
- [x] Vendor selection must match actual AAR signatures, not stale PDF overloads. Root read source + javap. Use actual `JXPalmApi` from Standalone, `ANDROID_960_OTG_DEVICE`, `PalmCaptureListener.onCapture(int,JXImage,JXImage)`, `setEnrollListener`/`startEnroll`, `setMatchListener`/`startCapture`, `cancelEnroll`/`stopCapture`/listeners-null/`unInitializeDevice`. Every native call serialized; callbacks only copy required template bytes and recycle image aliases, then enqueue controller event. No bitmap rendering or application raw logs. `setShowPrint` controls palm-print image capture, NOT logging; do not claim it disables logs. No liveness-disabled fallback masquerading as success.
- [x] Actual Standalone emits 560-byte device features. Public `verify` skips conversion whereas `addPalm`/`palmMatch` both convert lengths below 1024. Implement Driver.verify as a one-template temporary group, `initGroup` + clear + `addPalm(group, testUid, 0, enrolled)` + `palmMatch(group, presented, ids)` + finally clear; verify expected uid/index and group count. Use one constant diagnostic group, never an accumulating group per sample. Check return codes fail closed; cleanup failure poisons driver ownership. Controller's score semantics remain unchanged. Do not copy/decompile private conversion routines.
- [x] Device discovery uses Android UsbManager directly with vendor VID 42921 / PID 1559..1568, verified from JXPalmUSBManager bytecode. Do not call vendor initUSBPermission: it creates an unbounded discovery thread and permission latch. Request permission via app-scoped random PendingIntent action, verify actual UsbManager.hasPermission and selected still-attached device before initializing. Revoke receiver and PendingIntent on close. Disconnect only the selected palm device. App background/detached view closes handle; repeat entry cannot overlap a prior still-cleaning SDK singleton. Cleanup failure keeps new operation blocked and readable, not silently reused.
- [x] Embed localDemo panel on existing palm click. Existing production onPalmRequested flow and face listener stay unchanged. Test panel uses 1280×800 native coordinates inside existing branded shell, no new Activity/manifest permissions. UI labels distinguish hardware test from business enrollment. Back and detachment clear retained panel; localDemo no-device state is retryable. Do not change DemoFeatureFlags.
- [x] Gradle uses `localDemoImplementation files('libs/JXPalm-release-2.2.17_RK356x_build3.aar')`. Manual build verifies exact pinned AAR hash, extracts localDemo only, adds classes/assets/JNI without overwriting Baidu libs or loading unused AAR resources. Production build and audit remain unchanged. Bound extraction to intended output path; verify collisions instead of pickFirst.
- [x] Build both variants into a new DeliveryRoot, run full inherited tests and API25 audit. Run API25 emulator UI test without hardware, verify test panel error/retry/back/background plus old layout regression. Record limits and protected APK hashes. Independent scoped review before handing off.

## Preflight decisions

User explicitly requested feasible implementation before blocker questions, so this is a deliverable hardware-test subproject, not a claimed production palm flow. No commits in mixed dirty checkout. Existing SDD shell helpers are unavailable on this Windows setup; use apply_patch ledger/brief and safe before-file comparisons. Final review reads these deltas only. Keep evidence rather than deleting uncommitted review records.
