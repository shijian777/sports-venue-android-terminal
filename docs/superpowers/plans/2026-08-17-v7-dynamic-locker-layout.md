# V7 Dynamic Locker Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the v6 Android kiosk to a v7 dynamic locker selector while preserving every existing customer, serial, discovery, unlock, administrator, and lifecycle behavior.

**Architecture:** Add an immutable logical layout layer above the existing physical `LockerTarget` and protocol path. A v6 compatibility layout source converts the existing A/B/C discovery snapshot into dynamic areas, pages, sparse 4x8 slots, and unchanged physical targets; a later server source can replace that provider without rewriting the selector. Freeze the selected logical label and physical target at confirmation so page changes or future catalog refreshes cannot redirect an active or retried unlock.

**Tech Stack:** Android platform views, Java 8, minSdk 19, targetSdk 30, JUnit 4.13.2, existing manual `javac`/D8/aapt2/zipalign/apksigner pipeline.

## Global Constraints

- Preserve `outputs/智能更衣柜-ABC分区联调版-v6.apk` byte-for-byte; expected SHA-256 is `B26449E570566DBD00AAE7A847A6FE1F3313E8225BA834CD3D6E52EB6FC3B9E0`.
- Produce a separate `智能更衣柜-动态选柜联调版-v7.apk`, versionCode `7`, versionName `7.0-demo`, with the same applicationId and signing key.
- Customer UI is designed and accepted only at 1280x800 landscape.
- A page contains at most 4 rows and 8 columns; sparse positions remain sparse and never compact.
- Logical area names and customer locker labels are independent of physical board addresses.
- v7 does not add server networking, registration, INTERNET permission, or the network-error popup; those belong to the later server phase.
- Current A/B/C discovery and current unlock behavior remain operational through a compatibility layout source.
- Do not change `BoardDiscoveryCoordinator`, `LockerProtocol`, `LockerResponseDetector`, `UnlockCoordinator`, `SerialGateway`, process serial ownership, Admin visible builders, or native libraries unless a failing regression proves an unavoidable defect.
- Preserve phone `13800138000`, password `123456`, administrator PIN `888888`, unavailable face/palm/QR behavior, result timing, and retry semantics.
- During sending, freeze all selector interactions. A failure retry restores the same frozen target but never sends automatically.
- The workspace is not a Git repository. Replace commit steps with file-scope/hash recording and reviewer gates.

---

### Task 1: Capture the v6 Baseline and Frozen-Scope Guard

**Files:**
- Create: `manual-build/v7-baseline/frozen-source.sha256`
- Modify later: `scripts/build-debug.ps1`
- Test: read-only source/APK hash commands

**Interfaces:**
- Consumes: current v6 production tree and delivered APK.
- Produces: exact hash list used by Task 7 to reject accidental serial/admin/protocol drift and v6 overwrite.

- [ ] **Step 1: Hash the delivered v6 APK and all frozen production sources**

Record the v6 APK plus `AdminSerialActivity.java`, `BoardDiscoveryCoordinator.java`, `LockerProtocol.java`, `LockerResponseDetector.java`, `UnlockCoordinator.java`, all `serial/*.java`, and all four `libserial_port.so` files.

- [ ] **Step 2: Verify the v6 deliverable hash**

Run `Get-FileHash -Algorithm SHA256` and require the exact baseline hash from Global Constraints.

- [ ] **Step 3: Run non-packaging baseline verification**

Compile all pure-JVM sources/tests and all Android Java sources without invoking the v6 packaging/delivery step. Expected: existing 228 tests pass and Android compile exits 0.

- [ ] **Step 4: Record scope and baseline result**

Store the deterministic hash list in `manual-build/v7-baseline/frozen-source.sha256`; do not modify any production source in this task.

### Task 2: Add Immutable Dynamic Layout Models

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/layout/LockerLayoutSnapshot.java`
- Create: `app/src/main/java/com/codex/lockertest/layout/LockerArea.java`
- Create: `app/src/main/java/com/codex/lockertest/layout/LockerPage.java`
- Create: `app/src/main/java/com/codex/lockertest/layout/LockerSlot.java`
- Create: `app/src/test/java/com/codex/lockertest/layout/LockerLayoutSnapshotTest.java`

**Interfaces:**
- Produces: immutable `LockerLayoutSnapshot(version, areas)`, `LockerArea(id, displayName, pages)`, `LockerPage(id, pageNumber, rows, columns, slots)`, and `LockerSlot(id, displayLabel, row, column, enabled, LockerTarget)`.
- Validation: area/page/slot IDs nonblank and unique; display names bounded; rows 1..4; columns 1..8; slots within bounds; page coordinates unique; enabled slots require a physical target; all collections defensively copied.

- [ ] **Step 1: Write failing constructor and immutability tests**

Cover 0, 1, 32, and 33 slots; 4x8 valid; row 5/column 9 invalid; duplicate coordinates/IDs invalid; sparse positions retained; caller list mutation cannot alter a snapshot; one logical area can contain targets from boards 1 and 2.

- [ ] **Step 2: Run the focused tests and confirm RED**

Expected: compile failure because the four layout model classes do not exist.

- [ ] **Step 3: Implement the minimal immutable models and validation**

Use unmodifiable defensive copies and constructor validation only; no Android or network imports.

- [ ] **Step 4: Run focused tests and the full pure-JVM suite**

Expected: new tests pass and all existing tests remain green.

- [ ] **Step 5: Request a model-only review**

Reviewer must check sparse layout, uniqueness, defensive copies, boundary values, and absence of server/serial coupling.

### Task 3: Add the v6 Compatibility Layout Source

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/layout/LockerLayoutSource.java`
- Create: `app/src/main/java/com/codex/lockertest/layout/LegacyV6LockerLayoutSource.java`
- Create: `app/src/test/java/com/codex/lockertest/layout/LegacyV6LockerLayoutSourceTest.java`

**Interfaces:**
- Produces: `LockerLayoutSnapshot layoutFor(List<LockerZone> onlineZones)`.
- Compatibility mapping: A1..A12 -> board 1/locks 1..12, B1..B12 -> board 2, C1..C12 -> board 3, with `SHORT_WHEN_LOCKED` and existing labels.
- `LockerSlot.displayLabel` remains the logical customer label, while the existing unmodified `LockerTarget` remains the v6 wire target. This avoids coupling future server labels to physical A/B/C identities in this phase.

- [ ] **Step 1: Write failing compatibility and golden-frame tests**

Assert area ordering follows A/B/C without compacting missing boards; each area has one 2x8 page with 12 occupied positions and four sparse positions; slots for offline boards are disabled; all legacy target fields and all generated unlock frames remain byte-for-byte unchanged.

- [ ] **Step 2: Run focused tests and confirm RED**

Expected: missing source/model APIs.

- [ ] **Step 3: Implement the source and compatible target metadata**

Do not modify protocol classes, discovery, or `LockerTarget`. Logical display labels live in `LockerSlot`; current compatibility labels equal the frozen v6 target labels.

- [ ] **Step 4: Run protocol, layout-source, detector, and full JVM tests**

Expected: all old golden frames and new layout assertions pass.

- [ ] **Step 5: Request a compatibility review**

Reviewer must compare board/local/polarity for A1, A12, B1, B12, C1, C12 and confirm no board renumbering when A is absent.

### Task 4: Replace the Fixed Selector State with Dynamic Area/Page/Slot State

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionModel.java`
- Rewrite tests: `app/src/test/java/com/codex/lockertest/ui/LockerSelectionModelTest.java`

**Interfaces:**
- Consumes: immutable `LockerLayoutSnapshot` plus the discovery-derived compatibility snapshot.
- Produces: current area ID, page index, current sparse slots, selected frozen `LockerTarget`, previous/next availability, confirm state, and sending lock state.
- Keeps compatibility entrypoints `beginDiscovery()`, `applyDiscoverySnapshot(List<LockerZone>)`, `selectedTarget()`, and `restoreTarget(LockerTarget)` for Main/Kiosk migration.

- [ ] **Step 1: Write failing dynamic navigation tests**

Cover multiple areas/pages, source ordering, first/last page navigation, single-page state, selection clearing on area/page change, empty/disabled selection rejection, online partial-board behavior, sending freeze, and exact retry target restoration.

- [ ] **Step 2: Run focused tests and confirm RED**

Expected: old fixed-zone model lacks dynamic APIs/behavior.

- [ ] **Step 3: Implement dynamic state over immutable snapshots**

Never derive the active physical target from display position. Capture the slot target at selection/confirmation; never re-resolve it after a layout change.

- [ ] **Step 4: Run focused, flow, protocol, and full JVM tests**

Expected: selector and all existing flow tests pass.

- [ ] **Step 5: Request a state-machine review**

Reviewer checks stale selection, sending freeze, retry identity, partial board offline behavior, and no automatic send.

### Task 5: Render Dynamic Areas, Pages, and a Sparse 4x8 Grid

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/LockerGridPresentation.java`
- Create: `app/src/test/java/com/codex/lockertest/ui/LockerGridPresentationTest.java`

**Interfaces:**
- Consumes: the shared `LockerSelectionModel`.
- Produces: dynamic area navigation, page navigation, a maximum 4x8 grid, selection callback with frozen `LockerTarget`, cancel callback, and deterministic enabled/disabled content descriptions.

- [ ] **Step 1: Write failing presentation tests**

Cover visible area window of at most four names with left/right navigation; 32 stable coordinates; sparse cells; long labels ellipsized without changing IDs; page counter copy; disabled/selected/empty presentation states.

- [ ] **Step 2: Run focused tests and confirm RED**

Expected: `LockerGridPresentation` missing.

- [ ] **Step 3: Implement the pure presentation helper**

Keep layout calculation Android-free and deterministic for 1280x800.

- [ ] **Step 4: Rebuild `LockerSelectionView` using ZIP-style controls**

Reuse `ZipKioskShell`/`UiKit`; keep blue technology background and green title; render current compatibility page as 8 lockers on row 1 and 4 lockers on row 2; add previous/page/next controls; preserve target/cancel listener behavior.

- [ ] **Step 5: Compile all Android sources**

Expected: Java 8/android-34 compile exit 0 and no unsupported minSdk APIs.

- [ ] **Step 6: Request a UI/static review**

Reviewer checks real buttons, 1280x800 dimensions, no overlap, no `EditText`, no low-level serial copy, correct disabled touch behavior, and handler/listener cleanup.

### Task 6: Integrate the Dynamic Selector into the Existing Kiosk Flow

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/ui/KioskFlowModel.java`
- Modify: `app/src/test/java/com/codex/lockertest/ui/KioskFlowModelTest.java`
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`

**Interfaces:**
- Main continues to receive `List<LockerZone>` from the unchanged discovery coordinator and passes it through `LegacyV6LockerLayoutSource`.
- Kiosk flow and View share one selector model/snapshot.
- Main continues to pass the exact frozen `LockerTarget` to the unchanged `UnlockCoordinator`.

- [ ] **Step 1: Write failing flow tests for dynamic layout and frozen confirmation**

Cover multi-area/page selection; refresh/relabel after confirmation cannot alter pending label/board/lock; failure returns to the original area/page/slot; success returns home; Back and Admin handoff cancel safely; confirm remains exactly one send.

- [ ] **Step 2: Run focused tests and confirm RED**

Expected: flow lacks dynamic layout session behavior.

- [ ] **Step 3: Integrate KioskFlowModel with the shared selector model**

Preserve result contexts, credential state, retry, success-home terminal, and operation-token cancellation.

- [ ] **Step 4: Integrate MainActivity at the selector boundary only**

Do not alter physical gateway ownership, discovery protocol, connection policy, serial callbacks, admin handoff, or result timing. Customer logs may include logical label plus existing technical data only in `RuntimeSerialLog`.

- [ ] **Step 5: Run full JVM and Android compile verification**

Expected: all old and new tests pass; Android compile and aapt2 link succeed.

- [ ] **Step 6: Request integration review**

Reviewer checks post-first callback gates, attempt identity, retry without resend, exact target freeze, and no frozen-source drift.

### Task 7: Package and Independently Verify v7

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/java/com/codex/lockertest/ui/ZipKioskShell.java` only for visible v7 version copy if needed
- Modify: `scripts/build-debug.ps1`
- Produce: `outputs/智能更衣柜-动态选柜联调版-v7.apk`

**Interfaces:**
- Produces: signed installable v7 APK using the existing package name and debug key.
- Build script must use `manual-build/v7`, v7 filenames, automatic pure-JVM test discovery, frozen-source guards, Admin builder guards, and a v6 deliverable hash guard before and after packaging.

- [ ] **Step 1: Change version metadata and isolated output paths**

Set versionCode 7/versionName 7.0-demo; never write `manual-build/v6` or the v6 output path.

- [ ] **Step 2: Add build guards**

Require the recorded frozen-source hashes, Admin builder equality, no customer technical leakage, no permissions, exactly four native ABIs, same application ID/min/target, and unchanged v6 hash.

- [ ] **Step 3: Run the complete v7 build once**

Expected: full JVM suite, Android javac, D8, aapt2, four-ABI packaging, zipalign, v1/v2/v3 signing, metadata checks, and output/source hash equality all pass.

- [ ] **Step 4: Independently inspect the APK**

Verify package, version, label, minSdk/targetSdk, permissions, ABI list, signatures, alignment, file timestamp, source/output SHA-256, and v6 immutability.

- [ ] **Step 5: Run final whole-app review**

Reject any Critical/Important finding. Re-run the entire build after the last source change.

- [ ] **Step 6: Hand off for 1280x800 true-device testing**

Test A/B detection, A1/B1 physical unlock, multi-area UI, sparse positions, pagination, selection locking, Back, retry, administrator entry, persistent serial ownership, and rollback installation using the preserved v6 APK.

## Plan Self-Review

- Spec coverage: v6 preservation, dynamic areas/pages/4x8, sparse/disabled states, current local compatibility, future provider seam, 1280x800 UI, retry identity, and isolated v7 packaging are each assigned to a task.
- Placeholder scan: no TBD/TODO or unspecified implementation step remains.
- Type consistency: layout models feed the compatibility source, selector model, selector view, kiosk flow, and Main in that order; physical `LockerTarget` remains the single immutable input to the unchanged unlock path.
- Scope: server HTTP, registration, online-only command retrieval, network popup, and detection protocol changes are explicitly excluded from v7.
