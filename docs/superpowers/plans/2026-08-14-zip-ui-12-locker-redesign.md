# ZIP UI Restore and 12-Locker Implementation Plan

> **协议勘误（2026-08-16）：** 本文保留为历史实施记录；其中把所有成功帧校验字节固定为 `8A` 的规则已被厂家锁控板 PDF 对应的新设计取代。现行规则为前四字节逐字节 XOR，板 01 锁 01–12 的成功校验依次是 `8A,89,88,8F,8E,8D,8C,83,82,81,80,87`，以 `docs/superpowers/specs/2026-08-16-lock-board-zones-design.md` 为准。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current customer card menu with the confirmed ZIP-style interactive home, unavailable-method pages, 12-locker selector, and exact per-locker serial response handling while preserving the universal admin serial assistant.

**Architecture:** Pure Java models own credentials, navigation state, locker selection, command tables, and streaming response detection. Android Views render the confirmed 1280×800 ZIP design and send typed events to `MainActivity`; `UnlockCoordinator` remains the only customer component allowed to open/send serial data. The existing process-wide gateway owner, bounded admin log, and unchanged admin builder methods stay in place.

**Tech Stack:** Android platform Views/Canvas (minSdk 19), Java 8, JUnit 4.13.2, existing JNI serial library, manual Android SDK build pipeline.

## Global Constraints

- Customer serial configuration remains `/dev/ttyS0`, 9600 baud, 8 data bits, 1 stop bit, parity None, flow control None.
- No server and no `INTERNET` permission.
- Test phone is exactly `13800138000`; test password is exactly `123456`; admin PIN is exactly `888888`.
- FACE, PALM, and QR navigate to complete ZIP-style pages but remain unavailable and perform zero serial opens and zero sends.
- Credentials are verified before the 12-locker page; no serial action occurs until a selected locker is confirmed.
- Locker commands and success/failure frames must match the confirmed table in `docs/superpowers/specs/2026-08-14-zip-ui-12-locker-redesign.md` verbatim.
- A successful locker frame is `8A 01 <locker> 00 8A`; a failure frame is the exact unlock command sent for that locker.
- Each confirmation sends exactly once; there is no automatic retry; ACK timeout is 3,000 ms.
- Only received bytes decide success/failure. A sent callback is diagnostic and never an ACK.
- Customer pages never expose HEX, tty paths, JNI, permissions, or other low-level errors.
- `AdminSerialActivity.buildScreen`, `buildHeader`, `buildControlCard`, and `buildLogCard` must remain byte-for-byte method-body equivalent to the Task 7 baseline.
- The project is not a Git repository. Replace per-task commits with ledger entries and fresh hashes/test evidence; do not invent commit IDs.
- Do not launch an emulator. The final verification is build/static only; the user performs real-device testing.

---

### Task 1: Exact 12-Locker Protocol and Streaming Detector

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/protocol/LockerProtocol.java`
- Create: `app/src/main/java/com/codex/lockertest/protocol/LockerResponseDetector.java`
- Create: `app/src/test/java/com/codex/lockertest/protocol/LockerProtocolTest.java`
- Create: `app/src/test/java/com/codex/lockertest/protocol/LockerResponseDetectorTest.java`

**Interfaces:**
- Produces: `LockerProtocol.unlockCommand(int)`, `successFrame(int)`, `failureFrame(int)`, `displayNumber(int)`, and `isValidLocker(int)`.
- Produces: `LockerResponseDetector(int lockerNumber)`, `append(byte[] bytes, int length)`, `reset()`, and `Result { NONE, SUCCESS, FAILURE }`.

- [ ] **Step 1: Write the failing protocol table test**

```java
@Test
public void allTwelveCommandsAndResponsesMatchTheConfirmedTable() {
    String[] commands = {
        "8A0101119B", "8A01021198", "8A01031199", "8A0104119E",
        "8A0105119F", "8A0106119C", "8A0107119D", "8A01081192",
        "8A01091193", "8A010A1190", "8A010B1191", "8A010C1196"
    };
    for (int locker = 1; locker <= 12; locker++) {
        assertArrayEquals(HexCodec.decode(commands[locker - 1]),
                LockerProtocol.unlockCommand(locker));
        assertArrayEquals(HexCodec.decode(String.format(
                "8A01%02X008A", locker)), LockerProtocol.successFrame(locker));
        assertArrayEquals(LockerProtocol.unlockCommand(locker),
                LockerProtocol.failureFrame(locker));
        assertEquals(String.format("%03d", locker),
                LockerProtocol.displayNumber(locker));
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1`

Expected: test compilation fails because `LockerProtocol` does not exist.

- [ ] **Step 3: Implement the explicit command table**

Use a private `String[] COMMAND_HEX` containing the 12 confirmed strings verbatim. Validate `1 <= lockerNumber <= 12`, decode/copy defensively, and build the confirmed success frame as five bytes `{0x8A, 0x01, locker, 0x00, 0x8A}`. Do not calculate or replace the supplied command checksums.

- [ ] **Step 4: Add detector RED tests**

Test all of these observable behaviors:

```java
@Test public void splitSelectedLockerSuccessIsDetected() { /* 8A 01 | 0C 00 8A */ }
@Test public void exactSelectedLockerCommandReceivedIsFailure() { /* locker 12 */ }
@Test public void wrongLockerFramesAndNoiseAreIgnored() { /* locker 1 while waiting 2 */ }
@Test public void earliestCompleteSelectedFrameWinsWhenChunkContainsBoth() { /* success then failure */ }
@Test public void resetDropsPartialFrameState() { /* partial, reset, remainder */ }
```

- [ ] **Step 5: Implement the minimal streaming detector and verify GREEN**

The detector owns two byte-pattern matchers for the selected locker. It consumes only the supplied `length`, supports split and concatenated reads, and returns immediately on the earliest full selected-locker frame.

Run the full script. Expected: all existing tests plus the new protocol tests pass and the APK build remains green.

- [ ] **Step 6: Record Task 1 evidence in the redesign ledger**

Record the RED reason, targeted test count, full test count, and source hashes in `.superpowers/sdd/2026-08-14-zip-ui-12-locker-redesign/progress.md`.

---

### Task 2: Credential Input, Navigation, and Locker Selection Models

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/HomeCredentialModel.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionModel.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/KioskFlowModel.java`
- Create: `app/src/test/java/com/codex/lockertest/ui/HomeCredentialModelTest.java`
- Create: `app/src/test/java/com/codex/lockertest/ui/LockerSelectionModelTest.java`
- Create: `app/src/test/java/com/codex/lockertest/ui/KioskFlowModelTest.java`

**Interfaces:**
- Produces: `HomeCredentialModel.select(UnlockMethod)`, `pressDigit(char)`, `delete()`, `clear()`, `rawValue(UnlockMethod)`, `displayValue(UnlockMethod)`, `activeMethod()`, and `validationError()`.
- Produces: `LockerSelectionModel.select(int)`, `selectedLocker()`, `canConfirm()`, `beginSending()`, `finishSending()`, and `isInteractionEnabled()`.
- Produces: `KioskFlowModel.Screen { HOME, UNAVAILABLE, LOCKER_SELECTION, RESULT, ADMIN_PIN }`, typed transitions, and pending credential/locker accessors.

- [ ] **Step 1: Write failing home-input tests**

```java
@Test
public void oneKeypadEditsOnlyTheSelectedFieldAndPasswordKeepsRawDigits() {
    HomeCredentialModel model = new HomeCredentialModel();
    model.select(UnlockMethod.PHONE);
    for (char c : "13800138000".toCharArray()) model.pressDigit(c);
    model.select(UnlockMethod.PASSWORD);
    for (char c : "123456".toCharArray()) model.pressDigit(c);
    assertEquals("13800138000", model.rawValue(UnlockMethod.PHONE));
    assertEquals("123456", model.rawValue(UnlockMethod.PASSWORD));
    assertEquals("••••••", model.displayValue(UnlockMethod.PASSWORD));
    assertNull(model.validationError());
}
```

Also test max 11/6 digits, delete/clear affecting only the active field, invalid phone copy, and invalid password copy.

- [ ] **Step 2: Verify RED, then implement `HomeCredentialModel` minimally**

The model must use `DemoCredentials` for exact validation and must never replace raw password digits with display bullets.

- [ ] **Step 3: Write failing selection model tests**

```java
@Test
public void confirmRequiresExactlyOneLockerAndSendingLocksEveryControl() {
    LockerSelectionModel model = new LockerSelectionModel();
    assertFalse(model.canConfirm());
    assertTrue(model.select(12));
    assertEquals(12, model.selectedLocker());
    assertTrue(model.canConfirm());
    assertTrue(model.beginSending());
    assertFalse(model.select(1));
    assertFalse(model.canConfirm());
    model.finishSending();
    assertEquals(12, model.selectedLocker());
    assertTrue(model.canConfirm());
}
```

Test rejection of locker 0/13 and single-selection replacement before sending.

- [ ] **Step 4: Implement the minimal selection model and verify GREEN**

- [ ] **Step 5: Write and implement flow-model transition tests**

Cover the complete button matrix:

```java
@Test public void correctCredentialMovesHomeToLockerSelectionWithoutSerialState() { }
@Test public void facePalmAndQrMoveToUnavailableWithTheSelectedMethod() { }
@Test public void cancelFromSelectionReturnsHomeAndClearsSensitivePassword() { }
@Test public void confirmedLockerMovesToResultForThatLocker() { }
@Test public void retryReturnsToSelectionKeepingLockerButNeverAutoConfirms() { }
@Test public void adminButtonMovesToPinAndBackReturnsHome() { }
```

`KioskFlowModel` must store the raw credential only until the request finishes or returns home, then clear it.

- [ ] **Step 6: Run the complete JVM/build suite and record Task 2 evidence**

---

### Task 3: Generalize `UnlockCoordinator` for the Selected Locker

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/unlock/UnlockCoordinator.java`
- Modify: `app/src/test/java/com/codex/lockertest/unlock/UnlockCoordinatorTest.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/CustomerFailurePresentation.java`
- Modify: `app/src/test/java/com/codex/lockertest/ui/CustomerFailurePresentationTest.java`

**Interfaces:**
- Changes accepted start API to `long start(UnlockMethod method, String payload, int lockerNumber)`.
- Listener continues `onStateChanged(long attemptId, State state, String detail)`; terminal detail includes the three-digit cabinet display number but never low-level customer details.
- Serial actions remain token-aware and unchanged.

- [ ] **Step 1: Replace the lock-1-only tests with a 12-lock table RED**

For each locker, start a fresh fixture with correct password, connect it, and assert exactly one sent byte array equals `LockerProtocol.unlockCommand(locker)`.

- [ ] **Step 2: Add selected-locker receive RED tests**

Test that while waiting for locker 12, complete success/failure frames for locker 1 do nothing; locker 12 success or failure terminates exactly once. Test split reads and both-frame chunks.

- [ ] **Step 3: Implement using `LockerProtocol` and `LockerResponseDetector`**

Remove fixed `UNLOCK_FRAME`, `FAILURE_FRAME`, `SuccessFrameDetector`, and the private fixed-frame matcher from the coordinator. The active attempt captures the selected locker and creates/resets its detector. Timeout remains exactly 3,000 ms and no path retries automatically.

- [ ] **Step 4: Add customer-safe category tests and implementation**

Credential errors stay method-specific. Device open errors map to `设备连接失败，请联系管理员。`; send errors map to `开柜指令发送失败，请再次尝试。`; selected-locker failure maps to `00X号柜门开启失败，请再次尝试。`; timeout maps to `设备无响应，请再次尝试。`. The detailed reason still goes only to `RuntimeSerialLog` from `MainActivity`.

- [ ] **Step 5: Run full tests/build and record Task 3 evidence**

---

### Task 4: ZIP-Style Home, Common Shell, and Unavailable Pages

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/ZipKioskShell.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ZipPromptOverlay.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ZipHomeView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/UnavailableMethodView.java`
- Create: `app/src/main/java/com/codex/lockertest/ui/ZipPromptModel.java`
- Create: `app/src/test/java/com/codex/lockertest/ui/ZipPromptModelTest.java`
- Read-only reference: `C:/Users/Administrator/Documents/Codex/2026-08-13/to/work/smart-locker-v2-review/*.png`

**Interfaces:**
- `ZipHomeView.Listener`: `onCredentialSubmit(UnlockMethod, String)`, `onUnavailableSelected(UnlockMethod)`, `onAdminRequested()`.
- `UnavailableMethodView.Listener`: `onReturnHome()` and `onShowUnavailableAgain()`.
- `ZipPromptOverlay` consumes a tested `ZipPromptModel` and two callbacks.

- [ ] **Step 1: Write failing prompt-model tests**

Assert exact titles/messages/button roles for unavailable, credential error, selected-locker success, selected-locker failure, device connection failure, send failure, and timeout. The model exposes no HEX or tty detail.

- [ ] **Step 2: Implement the pure prompt model and verify GREEN**

- [ ] **Step 3: Implement the common ZIP visual shell**

`ZipKioskShell` composes the existing `TechBackgroundView` with a white framed panel, green trapezoid header drawn with a `Path`, centered Chinese title, English subtitle, optional upper-right return pill, and bottom brand/version. Cache paths/paints on size change and avoid per-frame allocations.

- [ ] **Step 4: Implement `ZipHomeView` using real controls**

Match the confirmed proportions: left 31%, center 37%, right 32%; left venue/`0/12`/ad, center two selectable fields/3×4 keypad/confirm, right unavailable method cards and bottom-right admin button. Use `HomeCredentialModel`; do not use a screenshot background or Android soft keyboard.

Every clickable View gets a content description and exactly one listener path. Confirm passes the active method and raw value, never bullets.

- [ ] **Step 5: Implement three unavailable pages**

Use method-specific title/subtitle/Canvas illustration. Entry or primary action displays `设备暂未接入，请联系管理员。`. Return and retry/close stay within the defined navigation. No serial type is imported by these views.

- [ ] **Step 6: Compile Android sources, run all tests, and statically scan**

Run `rg -n -i "8A01|ttyS|/dev/|9600|JNI" app/src/main/java/com/codex/lockertest/ui/Zip*.java app/src/main/java/com/codex/lockertest/ui/UnavailableMethodView.java`.

Expected: no customer-visible low-level strings.

- [ ] **Step 7: Record Task 4 evidence**

---

### Task 5: ZIP-Style 12-Locker Selection and Results

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/ui/LockerSelectionView.java`
- Replace or adapt: `app/src/main/java/com/codex/lockertest/ui/ResultOverlay.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/LockerSelectionModelTest.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/ZipPromptModelTest.java`

**Interfaces:**
- `LockerSelectionView.Listener`: `onConfirmLocker(int lockerNumber)` and `onCancel()`.
- `setSending(boolean)`, `showSelectionError(String)`, and `resetAfterFailure(int lockerNumber)` are UI-thread-only.

- [ ] **Step 1: Extend model tests for all 12 visible labels and retry behavior**

Assert `001` through `012`, one selected locker, disabled controls during sending, and preserved selection with no auto-confirm after retry.

- [ ] **Step 2: Implement the 3×4 selection grid**

Follow ZIP《请选择您要使用的柜子》: green trapezoid header, white panel, `001–012` outlined cells, green selected cell, gray cancel, green confirm. There is no occupancy state in this offline phase, so all 12 start available.

- [ ] **Step 3: Adapt the result overlay to ZIP prompts**

Success, failure, connection, send, and timeout copy comes from `ZipPromptModel`. Success auto-returns home after 3 seconds. Failure/timeout exposes “返回首页” and “再次尝试”; retry returns to the selection page with the same locker highlighted and performs zero sends until confirm is pressed again.

- [ ] **Step 4: Run full tests/build and record Task 5 evidence**

---

### Task 6: Main Activity Integration and Button Navigation

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Modify: `app/src/main/java/com/codex/lockertest/ui/AdminPinOverlay.java`
- Modify only lifecycle/internal wiring if required: `app/src/main/java/com/codex/lockertest/AdminSerialActivity.java`
- Test: `app/src/test/java/com/codex/lockertest/ui/KioskFlowModelTest.java`
- Test: existing integration/serial test classes.

**Interfaces:**
- `MainActivity` renders exclusively from `KioskFlowModel.Screen` and owns one `UnlockCoordinator`.
- Pending credential remains inside the flow model and is supplied again only when the user confirms a selected locker.

- [ ] **Step 1: Add RED flow tests for every button and back action**

Cover home phone/password confirmation, three unavailable cards, unavailable return/retry, locker 1–12 selection, selection cancel, confirm, result retry/home, admin entry, PIN cancel/error/success, and system Back from each customer screen.

- [ ] **Step 2: Replace current customer navigation with the ZIP flow**

Correct credential confirmation calls no serial API and renders `LockerSelectionView`. Locker confirmation calls `coordinator.start(method, rawCredential, selectedLocker)` once. `onSerialSent` remains diagnostic. Attempt ID and gateway generation checks remain on every callback.

- [ ] **Step 3: Make the administrator button explicit**

Remove dependence on the hidden long-press path from the active home. Clicking the bottom-right admin button opens the PIN overlay; only `888888` starts `AdminSerialActivity` after the existing safe ownership handoff.

- [ ] **Step 4: Preserve logging and lifecycle safety**

Customer credential acceptance/rejection logs method and length only, never raw credentials. Record selected locker, sent/received frames, connection state, and result in `RuntimeSerialLog`. Keep process-wide role/identity ownership, poisoned release behavior, reader generation, and admin receive batching intact.

- [ ] **Step 5: Verify admin visible builders against the Task 7 baseline**

Extract and compare normalized method bodies for `buildScreen`, `buildHeader`, `buildControlCard`, and `buildLogCard`; all four must report `EXACT=True`.

- [ ] **Step 6: Run full tests, Android compile/aapt link, and record Task 6 evidence**

---

### Task 7: Versioned APK, Documentation, and Final Verification

**Files:**
- Modify: `app/build.gradle`
- Modify: `scripts/build-debug.ps1`
- Modify: `README.md`
- Modify: `app/src/main/res/values/strings.xml` only if customer-facing version copy requires it.
- Deliver: `C:/Users/Administrator/Documents/Codex/2026-08-13/to/outputs/智能更衣柜-12柜联调版-v4.apk`

**Interfaces:**
- APK package remains `com.codex.lockertest`.
- Set `versionCode 4` and `versionName 4.0-demo` so the user's device updates the installed version 3 build.

- [ ] **Step 1: Update version and build assertions**

The script must still auto-discover all pure JVM production/test classes, compile all Android sources, build DEX/resources, package all four ABIs (`arm64-v8a`, `armeabi-v7a`, `armeabi`, `x86`), align, sign, and reject `INTERNET` permission.

- [ ] **Step 2: Update README with the exact real-device test sequence**

Document credentials, 12 locker commands, confirmed success/failed-frame rules, admin entry/PIN, unavailable features, default serial config, and the fact that no server is used.

- [ ] **Step 3: Run the final build from a clean `manual-build/v4` directory**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1`

Expected: all JVM tests pass; Android Java, D8, aapt2, four ABI packaging, zipalign, and v1/v2/v3 signing pass; the v4 output is produced.

- [ ] **Step 4: Independently inspect final APK metadata**

Verify package, label, version 4/4.0-demo, minSdk 19, targetSdk 30, no permissions other than the package line, four native libraries, alignment, signature, size, and SHA-256. Compare the ASCII build-path APK hash with the Chinese output-path hash.

- [ ] **Step 5: Run a final whole-project review**

Review protocol table, wrong-locker response isolation, every button route, zero-serial unavailable flows, customer-detail isolation, lifecycle ownership, bounded logs, admin builder identity, and APK metadata. One fix wave and one scoped re-review are allowed before delivery.

- [ ] **Step 6: Hand off for real-device testing**

Provide the v4 APK and a short checklist: password `123456` → choose locker → confirm → observe one frame → inject the selected success/failure frame; admin button → PIN `888888` → inspect logs. Do not claim hardware success until the user reports it.
