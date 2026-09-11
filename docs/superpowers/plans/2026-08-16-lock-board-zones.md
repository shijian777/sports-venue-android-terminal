# Lock Board ACK and A/B/C Zones Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct all 12 passive unlock acknowledgements, safely detect boards at addresses 01–03, and expose only A/B/C customer zones on the existing 1280×800 kiosk UI.

**Architecture:** A pure-Java protocol layer produces and validates XOR-protected frames. A sequential discovery coordinator probes the three fixed technical addresses before selection and publishes one immutable topology snapshot. UI and flow operate on immutable `LockerTarget` values whose customer label hides the physical address; `MainActivity` is the only Android integration point that owns scan and unlock gateway leases.

**Tech Stack:** Java 8, Android platform views (minSdk 19), existing process-owned `SerialGateway`, JUnit 4, PowerShell offline APK builder.

## Global Constraints

- Exact board-01 lock-01..12 send bytes remain `8A0101119B` through `8A010C1196` as supplied by the user and vendor PDF.
- Locked-short polarity is the current default: passive status `00` succeeds and `11` fails; polarity remains explicit in the target.
- While an unlock is pending, `82` active feedback never completes the request.
- Only addresses 01, 02 and 03 are probed and they map permanently to A, B and C; no compaction or renaming when a board is absent.
- Customer UI/accessibility exposes only A/B/C and A1..C12, never technical board labels, serial paths, HEX or checksums.
- No auto retry for discovery or unlock; one outstanding serial request at a time.
- Before customer traffic, the active serial config must exactly equal `/dev/ttyS0`, 9600 baud, parity 0, 8 data bits, 1 stop bit, flags 0; otherwise close and reopen defaults before sending anything.
- Each scan and unlock uses a restartable 300 ms receive-quiet guard before its first request.
- The physical serial gateway remains process-owned and is not closed by screen transitions.
- `AdminSerialActivity` buildScreen/buildHeader/buildControlCard/buildLogCard bodies remain exactly equal to the protected baseline.
- UI design and visual QA target only 1280×800; existing minSdk 19 and four ABI packaging remain unchanged.
- Terminal/server registration is documentation-only in this plan: no network permission, backend client or registration page is added.

---

### Task 1: Board-aware XOR unlock protocol

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/protocol/FeedbackPolarity.java`
- Create: `app/src/main/java/com/codex/lockertest/model/LockerZone.java`
- Create: `app/src/main/java/com/codex/lockertest/model/LockerTarget.java`
- Modify: `app/src/main/java/com/codex/lockertest/protocol/LockerProtocol.java`
- Modify: `app/src/main/java/com/codex/lockertest/protocol/LockerResponseDetector.java`
- Modify: `app/src/test/java/com/codex/lockertest/protocol/LockerProtocolTest.java`
- Modify: `app/src/test/java/com/codex/lockertest/protocol/LockerResponseDetectorTest.java`

**Interfaces:**
- Produces: `LockerTarget(LockerZone,int,int,FeedbackPolarity)`, `customerLabel()`, `LockerProtocol.unlockCommand(LockerTarget)`, `successFrame(LockerTarget)`, `failureFrame(LockerTarget)` and `LockerResponseDetector(LockerTarget)`.
- Compatibility: board-01 integer overloads may delegate during this task, but all production callers migrate by Task 4.

- [ ] **Step 1: Write failing literal-vector tests**

  Assert all twelve board-01 commands as independent literals; assert success literals end in `8A,89,88,8F,8E,8D,8C,83,82,81,80,87`; assert B12 command/success are `8A020C1195`/`8A020C0084`; assert C12 are `8A030C1194`/`8A030C0085`; assert open-short swaps the two outcomes. Add invalid board/lock and defensive-copy cases.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run the project JVM compilation/test stage or focused `javac + JUnitCore` for `LockerProtocolTest` and `LockerResponseDetectorTest`. Expected failure: missing target/polarity APIs and the existing fixed `8A` checksum mismatch.

- [ ] **Step 3: Implement minimal XOR protocol**

  Generate five-byte frames and set byte five to `byte0 ^ byte1 ^ byte2 ^ byte3`. Validate board 1..3 for customer targets and lock 1..12. Store success/failure status in `FeedbackPolarity`; use exact matchers scoped to the immutable target.

- [ ] **Step 4: Add detector edge tests and reach GREEN**

  Cover split/noisy frames, wrong board, wrong lock, stale fixed checksum, bad BCC, earliest terminal frame, and vendor `8201021190` remaining `NONE`. Run focused tests and then the full JVM suite.

### Task 2: Non-actuating A/B/C board discovery

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/protocol/BoardStatusProtocol.java`
- Create: `app/src/main/java/com/codex/lockertest/protocol/BoardStatusResponseDetector.java`
- Create: `app/src/main/java/com/codex/lockertest/serial/BoardDiscoveryCoordinator.java`
- Create: `app/src/test/java/com/codex/lockertest/protocol/BoardStatusProtocolTest.java`
- Create: `app/src/test/java/com/codex/lockertest/protocol/BoardStatusResponseDetectorTest.java`
- Create: `app/src/test/java/com/codex/lockertest/serial/BoardDiscoveryCoordinatorTest.java`

**Interfaces:**
- Produces: `BoardStatusProtocol.query(int boardAddress)`, response detector for expected address, and a token-aware coordinator returning one immutable online-zone snapshot after A/B/C completes.
- Coordinator serial actions mirror the unlock coordinator: ensure connected, send, onSent, onBytes, send failure, connection failure, cancel.

- [ ] **Step 1: Write protocol and parser tests first**

  Use literals `80010133B3`, `80020133B0`, `80030133B1`; accept `8001010080` and `8001011191`; reject query echo, wrong address, bad BCC, `82` and `8A`; cover split and concatenated reads.

- [ ] **Step 2: Verify focused RED**

  Expected failure: discovery protocol/detector classes do not exist.

- [ ] **Step 3: Implement query generation and exact response parsing**

  Use XOR generation, defensive copies and a bounded byte parser. Do not add a broadcast query or an unlock-based probe.

- [ ] **Step 4: Write coordinator behavior tests first**

  Assert a restartable 300 ms pre-scan quiet guard, order A→B→C, one outstanding query, buffering of a valid response that races ahead of `onSent`, a one-second queue-to-sent watchdog, 300 ms response timeout starting only after matching `onSent`, 20 ms quiet gap, no retry, defensive immutable final snapshot, 0/1/2/3 responder cases, cancellation, stale scan tokens and asynchronous send failure.

- [ ] **Step 5: Verify RED, implement minimal coordinator, verify GREEN**

  The coordinator must not call serial actions while holding a callback/registry lock; Android listeners will post callbacks before invoking it. Run its focused tests and the full JVM suite.

### Task 3: A/B/C selection and 1280×800 layout

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionModel.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/ZipPromptModel.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/ResultOverlay.java`
- Modify: `app/src/test/java/com/codex/lockertest/ui/LockerSelectionModelTest.java`
- Modify: `app/src/test/java/com/codex/lockertest/ui/ZipPromptModelTest.java`

**Interfaces:**
- Consumes: immutable online zones and `LockerTarget` from Tasks 1–2.
- Produces: `onConfirmLocker(LockerTarget)`, zone tabs, target-aware result methods and customer labels only.

- [ ] **Step 1: Write model/prompt RED tests**

  Assert labels A1..A12/B1..B12/C1..C12, offline-zone rejection, first-online default, zone switch clears selection, retry restores exact target, and success/failure copy contains the logical label but no address/HEX tokens.

- [ ] **Step 2: Implement the model and target-aware prompt API**

  Keep one 3×4 grid. Before the complete snapshot arrives, disable tabs/grid/confirm and show `正在检测可用柜区，请稍候`. Expose three fixed zone tabs after completion; render offline tabs disabled/grey, online tabs green, and never render `板01` or a technical address. When no zone responds, keep selection disabled and show `暂无可用柜区，请联系管理员`.

- [ ] **Step 3: Adjust only the 1280×800 composition**

  Change home columns from 31/37/32 to 28/44/28. Convert selection geometry to the existing ZIP design-unit helper, preserving touch size and existing visual language. Do not claim or add QA work for other resolutions.

- [ ] **Step 4: Compile Android sources and visually inspect 1280×800**

  Render or exercise the view at 1280×800, checking tab/grid/action bounds, no overlap, full Chinese labels and disabled-state clarity. Run focused model tests and Android `javac`.

### Task 4: Integrate discovery, flow and board-aware unlock

Execution is split into four independently reviewed green checkpoints: **4A** exact-default connection policy, **4B** target-aware unlock quiet/send lifecycle, **4C** typed transport callbacks and Admin-local A1 compatibility, then **4D** Activity discovery→selector→unlock wiring. The detailed SDD briefs under `.superpowers/sdd/2026-08-16-lock-board-zones/` are authoritative for those checkpoints.

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/KioskFlowModel.java`
- Modify: `app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java`
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Modify: `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`
- Modify: `app/src/main/java/com/codex/lockertest/AdminSerialActivity.java`
- Modify: `app/src/main/java/com/codex/lockertest/integration/PersistentSerialConnectionPolicy.java`
- Modify: `app/src/main/java/com/codex/lockertest/serial/SerialConfig.java`
- Modify: `app/src/test/java/com/codex/lockertest/integration/PersistentSerialConnectionPolicyTest.java`
- Modify: `app/src/test/java/com/codex/lockertest/serial/SerialConfigTest.java`
- Delete: `app/src/main/java/com/codex/lockertest/protocol/SuccessFrameDetector.java`
- Delete: `app/src/main/java/com/codex/lockertest/protocol/ResponseAccumulator.java`
- Delete: `app/src/test/java/com/codex/lockertest/protocol/SuccessFrameDetectorTest.java`
- Delete: `app/src/test/java/com/codex/lockertest/protocol/ResponseAccumulatorTest.java`
- Modify: matching tests under `app/src/test/java/com/codex/lockertest/`

**Interfaces:**
- Consumes: `BoardDiscoveryCoordinator`, online-zone snapshot, `LockerTarget`.
- Produces: valid credential → scan → enabled A/B/C selector → one board-aware unlock request; result/retry/back paths remain token- and lease-safe.

- [ ] **Step 1: Write flow/coordinator RED tests**

  Assert C12 sends exactly `8A030C1194`, ignores `82030C119C`, accepts `8A030C0085`, rejects wrong board/lock, reports only `C12` to the customer, and preserves the same target on retry. Assert zero unlock sends before successful discovery and zero sends for offline zones.

- [ ] **Step 2: Migrate coordinator and flow to immutable targets**

  Replace integer pending locker state with `LockerTarget`. Customer copy and overlays use `customerLabel()`; admin log records zone plus technical address and local lock.

- [ ] **Step 3: Add scan lifecycle integration**

  After credential validation, acquire the customer discovery lease/subscription, ensure default serial connection, and scan addresses 01–03. Unconditionally use `Handler.post` for every gateway callback before taking the process owner lock; do not use `runOnUiThread`, because it can execute inline while a listener-registry lock is held. On completion, detach discovery access without closing the physical gateway and publish the complete snapshot to the selector.

  Compare the complete active config before `REUSE`. Add an explicit close-for-defaults policy action. If an OPEN gateway is not exactly defaults, close it safely, wait for close completion, open defaults exactly once, and send zero query/unlock bytes until the default OPEN callback.

- [ ] **Step 4: Guard every exit and stale callback**

  Back, home, Admin launch and `onStop` cancel discovery/coordinator timers, unsubscribe, invalidate generation and relinquish only the logical lease. Scan and unlock never overlap. Late reads cannot alter a replacement screen or send a later query.

- [ ] **Step 5: Make SerialGateway transport-only**

  Add a typed `onSendFailed(byte[] payload, String detail)` callback for executor rejection and asynchronous write failure while preserving raw diagnostics. Remove the global lock-1-only semantic success callback/detector so every raw read is logged and delivered exactly once; customer protocol code alone decides an attempt. Preserve Admin's existing visible success behavior with an Admin-local A1 matcher fed from raw reads. Keep the administrator page’s four visible builder bodies unchanged.

- [ ] **Step 6: Run targeted and full integration tests**

  Require all pure-Java tests green, Android source compile green, and the Admin builder baseline guard exact.

### Task 5: Documentation, packaging and final verification

**Files:**
- Modify: `README.md`
- Modify: `app/build.gradle`
- Modify: `scripts/build-debug.ps1`
- Modify if needed: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: a new versioned APK whose filename does not overwrite v5.

- [ ] **Step 1: Document the corrected protocol and fixed A/B/C mapping**

  Record XOR response vectors, polarity rule, ignored `82` behavior, status-query detection, DIP addresses 01/02/03, logical labels and the deferred registration boundary. Mark the old fixed-`8A` statement as superseded.

- [ ] **Step 2: Add build-time behavioral guards**

  Keep automatic JVM test discovery; retain Admin builder exact checks; add a narrowly scoped check over customer-rendered strings/content descriptions for prohibited technical text without scanning legitimate Admin runtime-log statements or replacing behavior tests with source-grep tests.

- [ ] **Step 3: Package as a new APK**

  Increment version metadata and deliver a distinct output such as `智能更衣柜-ABC分区联调版-v6.apk`; preserve package name, minSdk, targetSdk and four ABIs.

- [ ] **Step 4: Run fresh completion verification**

  Run `powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1`. Require zero failed JVM tests, Android compile/DEX/resources/package success, zipalign success, v1/v2/v3 signature success, no new permissions, four expected ABIs, exact Admin builder hashes and a SHA-256 for the final APK.

- [ ] **Step 5: Independent final review**

  Review protocol literals, wrong-board isolation, scan/unlock serialization, Activity lifecycle races, customer technical-data leakage, 1280×800 layout and APK metadata. Fix Critical/Important findings once and re-review the fix scope.
