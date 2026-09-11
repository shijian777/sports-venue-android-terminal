# Smart Locker Kiosk UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a signed offline Android APK with a five-method smart-locker customer UI, simulated credential checks, real serial unlock, and a hidden full serial administrator page.

**Architecture:** Keep one native Java `Activity`, but split presentation into focused custom `View` classes. Route all customer unlock attempts through an offline verification service and a session-aware coordinator; keep exactly one serial manager/gateway owner so customer and administrator screens never compete for `/dev/ttyS0`.

**Tech Stack:** Android platform APIs, Java 8, minSdk 19, Canvas/custom Views, existing JNI `libserial_port.so`, JUnit 4, manual Android SDK build pipeline.

## Global Constraints

- Landscape fullscreen, designed at 1280×800 and scaled for other landscape resolutions.
- No AndroidX, third-party UI dependencies, server access, or `INTERNET` permission.
- Customer methods remain face, palm, phone, password, and QR; no bracelet flow.
- Face, palm, and QR pages are implemented but disabled by one central availability map; their gray home cards say “设备暂未接入” and cannot submit or send serial data. No camera or biometric permissions.
- Phone `13800138000` and password `123456` are the only valid customer demo credentials.
- Administrator PIN is `888888`, reached only by holding the top-right clock for 5 seconds.
- Customer serial configuration is `/dev/ttyS0`, `9600`, 8 data bits, 1 stop bit, parity None, flow None.
- Each accepted action sends `8A 01 01 11 9B` exactly once and never retries automatically.
- Received success frame is `8A 01 01 00 8A`; received failure frame is `8A 01 01 11 9B`; acknowledgement timeout is 3 seconds. The locally sent command must never be interpreted as a received failure.
- Entering background safely closes the serial descriptor; stale callbacks cannot update a new page/session.
- Customer and administrator serial Send/Read/Status/Error/Diagnostic events share one bounded in-memory runtime log, but only the authenticated administrator page may render it. Customer UI exposes no HEX, device path, parameters, diagnostics, or low-level errors. Opening admin shows existing history and live updates; process restart clears it.

---

## File Map

- Modify `app/src/main/java/com/codex/lockertest/MainActivity.java`: application composition, navigation, lifecycle, and serial callback routing only.
- Create `app/src/main/java/com/codex/lockertest/model/DemoCredentials.java`: fixed demo phone/password/admin PIN and validation.
- Create `app/src/main/java/com/codex/lockertest/model/UnlockMethod.java`: five supported customer methods.
- Create `app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java`: one-shot session state, timeout, serial connection/send orchestration.
- Create `app/src/main/java/com/codex/lockertest/ui/UiKit.java`: colors, drawables, text/button helpers, and sizing.
- Create `app/src/main/java/com/codex/lockertest/ui/TechBackgroundView.java`: blue technology background.
- Create `app/src/main/java/com/codex/lockertest/ui/HomeView.java`: reference-style five-card home and five-second hidden entry gesture.
- Create `app/src/main/java/com/codex/lockertest/ui/NumericKeypadView.java`: reusable phone/password/admin numeric input.
- Create `app/src/main/java/com/codex/lockertest/ui/UnlockPageView.java`: five unlock method screens and 30-second inactivity return.
- Create `app/src/main/java/com/codex/lockertest/ui/ResultOverlay.java`: validating/opening/success/failure states.
- Create `app/src/main/java/com/codex/lockertest/ui/AdminPinOverlay.java`: hidden admin PIN prompt.
- Create `app/src/main/java/com/codex/lockertest/AdminSerialActivity.java`: exact full-screen preservation of the current universal serial assistant UI; visible layout, labels, proportions, controls, and features cannot be changed or compressed.
- Modify `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`: generation-safe callbacks and consistent disposal/publication.
- Modify `app/src/main/java/com/codex/lockertest/serial/SerialSessionState.java`: state transitions required by safe dispose/open races.
- Create tests under `app/src/test/java/com/codex/lockertest/model`, `unlock`, and `ui`; extend serial state tests.
- Modify `app/src/main/res/values/strings.xml`, `app/build.gradle`, and `scripts/build-debug.ps1`: product metadata, version 3, test list, and final deliverable path.

---

### Task 1: Demo credential rules

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/model/DemoCredentials.java`
- Create: `app/src/main/java/com/codex/lockertest/model/UnlockMethod.java`
- Test: `app/src/test/java/com/codex/lockertest/model/DemoCredentialsTest.java`

**Interfaces:**
- Produces: `DemoCredentials.isValidPhone(String)`, `isValidPassword(String)`, `isValidAdminPin(String)`, `FeatureAvailability.isEnabled(UnlockMethod)`, and enum values `FACE`, `PALM`, `PHONE`, `PASSWORD`, `QR`.

- [ ] Write tests asserting only `13800138000`, `123456`, and `888888` validate; null, whitespace variants, wrong values, and wrong lengths fail. Assert PHONE/PASSWORD enabled and FACE/PALM/QR disabled.
- [ ] Run the test compile/JUnit command and verify it fails because `DemoCredentials` does not exist.
- [ ] Implement immutable constants and exact-string validation with no trimming or partial matches.
- [ ] Run `DemoCredentialsTest` and all existing pure JVM tests; verify all pass.

### Task 2: Session-aware unlock coordinator

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java`
- Create: `app/src/test/java/com/codex/lockertest/unlock/UnlockCoordinatorTest.java`

**Interfaces:**
- Consumes: `UnlockMethod`, `HexCodec.decode("8A0101119B")`.
- Produces: `start(UnlockMethod method, String payload)`, `onSerialConnected()`, `onSerialOpenFailed(String)`, `onSerialSent(byte[])`, `onSerialBytes(byte[])`, `cancel()`, and listener states `VERIFYING`, `CONNECTING`, `WAITING_ACK`, `SUCCESS`, `FAILURE`.
- Injects: a scheduler and `SerialActions.ensureDefaultConnected()` / `send(byte[])` interface so it is JVM-testable.

- [ ] Write tests covering invalid phone/password sends zero bytes; FACE/PALM/QR send zero bytes while disabled; duplicate start is ignored; one accepted phone/password request sends once; split/noisy success frame succeeds; split/noisy received `8A0101119B` fails immediately; local `onSerialSent` never counts as failure; three-second timeout reports communication timeout without retry; cancel and stale session callbacks do nothing.
- [ ] Run `UnlockCoordinatorTest` and verify compilation/test failure before implementation.
- [ ] Implement a synchronized session token/state machine and resettable `SuccessFrameDetector` scoped to `WAITING_ACK`.
- [ ] Run the coordinator and existing protocol tests; verify all pass.

### Task 3: Serial lifecycle hardening

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`
- Modify: `app/src/main/java/com/codex/lockertest/serial/SerialSessionState.java`
- Modify: `app/src/test/java/com/codex/lockertest/serial/SerialSessionStateTest.java`

**Interfaces:**
- Keeps the existing `SerialGateway.Listener` contract for Activity routing.
- Produces atomic resource publication, disposal that cannot leak a locally opened port, and callback suppression after disposal.

- [ ] Add state tests for dispose during `OPENING`, rejected resource publication after dispose, and closed/disposed callback gates.
- [ ] Run `SerialSessionStateTest` and verify the new race assertions fail.
- [ ] Make open resource publication and transition one guarded operation; if disposal wins, close the local `SerialPort` immediately; gate reader/start and external callbacks on the active generation.
- [ ] Run all serial/protocol tests and compile the Android sources.

### Task 4: Reusable customer UI primitives

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/UiKit.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/TechBackgroundView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/NumericKeypadModel.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/NumericKeypadView.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/NumericKeypadModelTest.java`

**Interfaces:**
- Produces: reusable colors/drawables/view factories and `NumericKeypadModel(maxLength, masked)` with `pressDigit`, `delete`, `clear`, `rawValue`, `displayValue`.

- [ ] Write model tests for digit limits, deletion, clearing, password dot masking, and ignored non-digits.
- [ ] Run the keypad model test and verify it fails before implementation.
- [ ] Implement the model and a no-soft-keyboard 3×4 large-button view.
- [ ] Implement the background with blue gradient, subtle world dots/grid/light effects and no bitmap dependency.
- [ ] Run pure JVM tests and Android Java compilation.

### Task 5: Reference-style home and hidden administrator entry

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/HoldGestureTracker.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/HomeView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/AdminPinOverlay.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/HoldGestureTrackerTest.java`

**Interfaces:**
- Home listener: `onMethodSelected(UnlockMethod)` and `onAdminRequested()`.
- Admin overlay listener: `onAdminAuthenticated()` and `onDismissed()`.

- [ ] Write hold tests proving less than 5 seconds, movement/cancel, and release do not trigger; 5 seconds triggers exactly once.
- [ ] Run the test and verify failure before implementation.
- [ ] Implement 1280×800 proportional layout with reference-style header, venue/usage/ad panel, two colored enabled cards, three gray disabled cards labeled “设备暂未接入”, tips, brand/version, and updating clock.
- [ ] Implement explicit `ACTION_DOWN` delayed hold logic, cancel on `UP/CANCEL/MOVE`, plus PIN overlay using `NumericKeypadView` and `DemoCredentials.isValidAdminPin`.
- [ ] Run tests and Android Java compilation.

### Task 6: Five unlock screens and result overlay

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/UnlockPageView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ResultOverlay.java`

**Interfaces:**
- Unlock page listener: `onSubmit(UnlockMethod, String)` and `onReturnHome()`.
- Coordinator listener drives overlay states and detail text.

- [ ] Implement a shared header/back shell and separate content for face silhouette, palm scan, phone keypad, password keypad, and QR scan frame.
- [ ] Make face/palm/QR buttons submit their method once after an 800 ms visual verification delay; phone/password submit raw keypad data.
- [ ] Add 30-second inactivity return, reset on every touch, and invalidate callbacks after the view is detached.
- [ ] Implement modal overlay states: verifying, connecting/opening, success with three-second home return, and failure with retry/home buttons.
- [ ] Compile Android Java and manually inspect view construction paths for all five enum values.

### Task 7: Exact administrator serial assistant preservation

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/AdminSerialActivity.java`
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`

**Interfaces:**
- Admin Activity retains the current scan, selected configuration open/close, HEX send, one-click unlock, log clear, status and diagnostic interfaces exactly as visible today.
- Runtime serial/log ownership may be shared internally, but no visible admin control may change.

- [ ] Copy the existing entire `MainActivity` serial assistant into `AdminSerialActivity`, preserving exact layout, two-column proportions, every spinner, connection control, HEX input, one-click unlock, result panel, diagnostics, bounded log, receive batching, visible string and supported parameter.
- [ ] Use Android system Back to return to customer home; do not add, remove, rename, resize, or compress any visible control. Closing admin mode safely releases any active configuration so customer mode can reopen defaults.
- [ ] Bind to the Activity-owned 48,000-character/600-line `BoundedLogBuffer`: render pre-existing customer/admin history immediately, keep live batched receive updates, support clear, and do not persist across process restarts.
- [ ] Compile Android Java and verify scan falls back to `/dev/ttyS0`.

### Task 8: Activity composition and real serial flow

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Composes Home, Unlock page, Result overlay, Admin PIN, Admin serial page, `UnlockCoordinator`, and one `SerialGateway` instance.

- [ ] Replace the serial-assistant-only root with page navigation while keeping immersive landscape and screen-on flags.
- [ ] Wire customer submissions through `UnlockCoordinator`; on accepted request open `/dev/ttyS0` with `9600/8N1/None`, then send exactly one frame and route bytes back to the active session.
- [ ] Route admin actions directly through the same gateway and never treat admin receive traffic as customer success unless the coordinator is waiting for an ACK.
- [ ] In `onStop`, cancel timers/sessions, dispose the gateway once, stop scan executors, and invalidate UI generations; recreate safely in `onStart`.
- [ ] Compile the complete Android source tree.

### Task 9: Product metadata, build, and verification

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/build.gradle`
- Modify: `scripts/build-debug.ps1`
- Modify: `README.md`

**Interfaces:**
- Produces: `outputs/智能更衣柜-前端初版-v1.apk`.

- [ ] Set app label to “智能更衣柜”, versionCode `3`, versionName `3.0-demo`, and update manual build/package checks consistently.
- [ ] Add all new JVM test classes to the build script and update the deliverable filename.
- [ ] Update README with demo phone `13800138000`, customer password `123456`, administrator PIN `888888`, hidden-entry instructions, serial protocol, and true limitations.
- [ ] Run `powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1`; require all JUnit, Java, DEX, resources, four ABI, zipalign, signature, package and no-INTERNET checks to pass.
- [ ] Inspect `aapt dump badging`, `aapt dump permissions`, APK archive ABI entries, SHA-256, and deliverable existence.
- [ ] Perform one code review focused on specification compliance, one review focused on lifecycle/concurrency, then rerun the complete build after all accepted fixes.

---

## Plan Self-Review

- Spec coverage: home, five methods, exact demo credentials, hidden administrator page, real one-shot serial command, split-frame success, timeout, lifecycle safety, build/sign/delivery are each mapped to a task.
- Placeholder scan: no implementation placeholders or deferred production requirements remain.
- Interface consistency: UI produces `UnlockMethod` and payload; coordinator alone owns customer session semantics; Activity owns the single gateway and routes all callbacks.
