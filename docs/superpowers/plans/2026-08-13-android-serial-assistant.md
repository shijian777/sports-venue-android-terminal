# Android Serial Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the fixed `/dev/ttyS0` lock test into an offline serial assistant that scans device nodes, lets the operator configure and open a port, sends arbitrary HEX or the fixed unlock frame, and reports actionable open failures.

**Architecture:** Keep the proven `android_serialport_api.SerialPort` JNI binding, but place dynamic settings in an immutable `SerialConfig` and make `SerialGateway` explicitly open and close the selected configuration. Keep scanning and success-frame recognition in pure Java units with JVM tests. The Activity remains a platform-Views landscape screen and binds spinners/buttons to the gateway state.

**Tech Stack:** Java 8, Android SDK 34, target SDK 30, platform Android Views, JNI `libserial_port.so` from the supplied working APK, JUnit 4, Android build-tools `javac/d8/aapt2/zipalign/apksigner` fallback.

## Global Constraints

- Default path is `/dev/ttyS0`; default settings are `9600`, 8 data bits, 1 stop bit, parity None, flow control None.
- JNI argument order is exactly `path, baudRate, parity, dataBits, stopBits, flags`; None parity maps to `0`, and None flow control maps to flags `0`.
- Unlock sends exactly one binary frame `8A 01 01 11 9B`; no automatic retry.
- A received stream containing `8A 01 01 00 8A`, including across read boundaries, reports unlock success.
- The app is offline and must not request `INTERNET` or USB Host permissions.
- Serial I/O and scanning must not run on the UI thread.
- The existing native ABIs are `arm64-v8a`, `armeabi-v7a`, `armeabi`, and `x86`; do not claim `x86_64` support because the source APK does not contain it.
- The workspace is not a Git repository, so task checkpoints use test/build evidence instead of commits.
- Do not launch an emulator; the user performs the final serial test on the nearby device.

---

## File Structure

- Create `app/src/main/java/com/codex/lockertest/serial/SerialConfig.java`: immutable selected path and numeric JNI settings.
- Create `app/src/main/java/com/codex/lockertest/serial/SerialDeviceScanner.java`: common device-name filtering, sorting, deduplication, and `/dev/ttyS0` fallback.
- Create `app/src/main/java/com/codex/lockertest/serial/SerialDiagnostics.java`: pre-open device/ABI/access diagnostics and best-effort raw-open errno detail.
- Modify `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`: dynamic open/close/send lifecycle with continuous reads.
- Modify `app/src/main/java/android_serialport_api/SerialPort.java`: preserve the original JNI signature and improve exceptions without changing native linkage.
- Create `app/src/main/java/com/codex/lockertest/protocol/SuccessFrameDetector.java`: rolling success-frame detector for an ongoing receive stream.
- Modify `app/src/main/java/com/codex/lockertest/MainActivity.java`: selectable configuration, scan/open/close, arbitrary HEX and one-click unlock controls.
- Create tests under `app/src/test/java/com/codex/lockertest/{serial,protocol}`.
- Create `scripts/build-debug.ps1`: repeatable one-pass JVM test and APK build/sign/verify command.
- Modify `README.md`: exact field-test and troubleshooting instructions.

### Task 1: Serial configuration and device discovery

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/serial/SerialConfig.java`
- Create: `app/src/main/java/com/codex/lockertest/serial/SerialDeviceScanner.java`
- Test: `app/src/test/java/com/codex/lockertest/serial/SerialConfigTest.java`
- Test: `app/src/test/java/com/codex/lockertest/serial/SerialDeviceScannerTest.java`

**Interfaces:**
- Produces: `SerialConfig(String path, int baudRate, int parity, int dataBits, int stopBits, int flags)`, `SerialConfig.defaults()`, getters, `describe()`.
- Produces: `SerialDeviceScanner.isCandidateName(String): boolean`, `scan(File devDirectory, File preferredDevice): List<String>`.

- [ ] **Step 1: Write failing configuration tests**

```java
@Test public void defaultsMatchManufacturerConfiguration() {
    SerialConfig config = SerialConfig.defaults();
    assertEquals("/dev/ttyS0", config.getPath());
    assertEquals(9600, config.getBaudRate());
    assertEquals(0, config.getParity());
    assertEquals(8, config.getDataBits());
    assertEquals(1, config.getStopBits());
    assertEquals(0, config.getFlags());
    assertEquals("/dev/ttyS0 · 9600 · 8N1 · Flow None", config.describe());
}

@Test(expected = IllegalArgumentException.class)
public void rejectsUnsupportedDataBits() {
    new SerialConfig("/dev/ttyS0", 9600, 0, 9, 1, 0);
}
```

- [ ] **Step 2: Write failing scanner tests**

```java
@Test public void recognizesCommonSerialDeviceNames() {
    assertTrue(SerialDeviceScanner.isCandidateName("ttyS0"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttyUSB1"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttyACM0"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttyAMA2"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttyHS3"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttymxc1"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttyMT0"));
    assertTrue(SerialDeviceScanner.isCandidateName("ttyXRUSB0"));
    assertFalse(SerialDeviceScanner.isCandidateName("tty"));
    assertFalse(SerialDeviceScanner.isCandidateName("random"));
}
```

Use a JUnit `TemporaryFolder` with `ttyUSB1`, `ttyS2`, and `random` files to assert sorted full paths and exclusion of `random`. Use a separate existing `preferredDevice` file to assert it is inserted first even when directory listing is unavailable.

- [ ] **Step 3: Run tests and capture the expected failure**

Run the project JVM-test command used by the existing manual build, compiling the four test classes with JUnit 4.13.2. Expected: compilation failure because `SerialConfig` and `SerialDeviceScanner` do not exist.

- [ ] **Step 4: Implement minimal pure-Java units**

Validate baud rate `> 0`; parity in `0..2`; data bits in `5..8`; stop bits in `1..2`; non-empty absolute path. Match candidate names using anchored prefixes followed by at least one letter/digit suffix, deduplicate with `LinkedHashSet`, prioritize `/dev/ttyS0`, then sort remaining paths.

- [ ] **Step 5: Re-run the focused tests**

Expected: all configuration and scanner tests pass without Android runtime classes.

### Task 2: Continuous success-frame detection

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/protocol/SuccessFrameDetector.java`
- Test: `app/src/test/java/com/codex/lockertest/protocol/SuccessFrameDetectorTest.java`

**Interfaces:**
- Produces: `boolean append(byte[] bytes, int length)` and `void reset()`.
- Uses fixed pattern bytes `(byte)0x8A, 0x01, 0x01, 0x00, (byte)0x8A`.

- [ ] **Step 1: Write failing rolling-match tests**

```java
@Test public void findsFrameAcrossReadsAndAfterNoise() {
    SuccessFrameDetector detector = new SuccessFrameDetector();
    assertFalse(detector.append(new byte[]{0x55, (byte) 0x8A, 0x01}, 3));
    assertTrue(detector.append(new byte[]{0x01, 0x00, (byte) 0x8A}, 3));
}

@Test public void mismatchDoesNotEndFutureDetection() {
    SuccessFrameDetector detector = new SuccessFrameDetector();
    assertFalse(detector.append(new byte[]{(byte)0x8A, 0x01, 0x02, 0x00, 0x00}, 5));
    assertTrue(detector.append(new byte[]{(byte)0x8A, 0x01, 0x01, 0x00, (byte)0x8A}, 5));
}
```

Also test invalid `length` and `reset()`.

- [ ] **Step 2: Run the focused test and verify failure**

Expected: compilation failure because `SuccessFrameDetector` does not exist.

- [ ] **Step 3: Implement a five-byte rolling window matcher**

Track the current pattern index and, on mismatch, restart at index `1` only if the current byte equals the first pattern byte. Return `true` when the full pattern is observed, reset the index, and continue accepting later reads.

- [ ] **Step 4: Re-run all protocol tests**

Expected: existing `HexCodec`/`ResponseAccumulator` tests and new detector tests all pass.

### Task 3: Dynamic gateway and actionable diagnostics

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/serial/SerialDiagnostics.java`
- Modify: `app/src/main/java/android_serialport_api/SerialPort.java`
- Modify: `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`
- Test: `app/src/test/java/com/codex/lockertest/serial/SerialDiagnosticsTest.java`

**Interfaces:**
- Produces: `SerialDiagnostics.basic(File, String[] supportedAbis): String` containing path, exists/read/write, and ABI facts.
- `SerialGateway.open(SerialConfig)`, `closePort()`, `send(byte[])`, `isConnected()`, `getActiveConfig()`, `dispose()`.
- Listener callbacks: `onConnectionChanged(boolean, String)`, `onSent(byte[])`, `onReceived(byte[])`, `onUnlockSuccess()`.

- [ ] **Step 1: Write failing pure diagnostic tests**

```java
@Test public void basicDiagnosticIncludesAccessAndAbi() throws Exception {
    File node = temporaryFolder.newFile("ttyS0");
    String value = SerialDiagnostics.basic(node, new String[]{"arm64-v8a", "armeabi-v7a"});
    assertTrue(value.contains(node.getAbsolutePath()));
    assertTrue(value.contains("exists=true"));
    assertTrue(value.contains("read="));
    assertTrue(value.contains("write="));
    assertTrue(value.contains("arm64-v8a"));
}
```

- [ ] **Step 2: Run and verify the diagnostic test fails**

Expected: compilation failure because `SerialDiagnostics` does not exist.

- [ ] **Step 3: Implement diagnostic facts and Android raw-open probe**

Keep `basic` pure Java. Add `probeRawOpen(File)` in Android production code: on API 21+, call `android.system.Os.open(path, O_RDWR | O_NOCTTY, 0)`, immediately call `Os.close(fd)`, and return `raw-open=ok`; catch `ErrnoException` and return `raw-open=<function>: errno=<errno>`. On API 19/20, open and immediately close `RandomAccessFile(path, "rw")`, returning its exception text on failure. Never leave the probe descriptor open.

- [ ] **Step 4: Refactor the gateway around explicit configuration**

`open(config)` closes any current descriptor on its single connection executor, emits `正在打开 <description>`, logs `SerialDiagnostics`, constructs `SerialPort(file, baudRate, parity, dataBits, stopBits, flags)`, and starts exactly one reader thread. `closePort()` closes without destroying executors; `dispose()` closes then shuts them down. `send` writes once and does not impose a two-second terminal timeout on arbitrary serial-assistant traffic. Feed every read into `SuccessFrameDetector`; emit `onUnlockSuccess()` whenever it matches.

- [ ] **Step 5: Preserve native compatibility**

Keep Java package `android_serialport_api`, library name `serial_port`, constructor descriptor `(Ljava/io/File;IIIII)V`, and native methods unchanged. If JNI returns null, throw an `IOException` that appends the diagnostic summary and the instruction `请彻底退出其他串口应用后重试`.

- [ ] **Step 6: Compile production sources against Android SDK**

Expected: no missing Android symbols and no JNI method signature changes. Verify source text contains the parameter call in exact order: `baudRate, parity, dataBits, stopBits, flags`.

### Task 4: Serial-assistant landscape interface

**Files:**
- Modify: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes `SerialDeviceScanner.scan`, `SerialConfig`, `SerialGateway.open/closePort/send`, and gateway listener callbacks.

- [ ] **Step 1: Replace the fixed parameter panel**

Build platform `Spinner` controls for port, baud (`1200,2400,4800,9600,19200,38400,57600,115200`), data bits (`5,6,7,8`), stop bits (`1,2`), parity (`None,Odd,Even`), plus a disabled Flow `None` field. Add Refresh, Open/Close, Send HEX, and One-click Unlock buttons.

- [ ] **Step 2: Bind scanner and defaults**

On start, scan on a background executor and populate the port spinner on the UI thread. Select `/dev/ttyS0` when present and default the other controls to `9600/8/1/None`. Do not auto-open; log the number of detected ports.

- [ ] **Step 3: Bind connection state safely**

Open constructs one `SerialConfig` from spinner values. While opening, disable configuration and refresh controls. When connected, change the same button to “关闭串口”, enable both send buttons, and show `activeConfig.describe()`. Closing re-enables configuration. Lifecycle `onStop` calls `dispose()` so the original assistant can reclaim the device.

- [ ] **Step 4: Bind HEX and unlock sends**

“发送 HEX” decodes the input with `HexCodec.decode` and sends once. “一键开锁” always decodes the constant `8A0101119B`, regardless of input text, and sends once. Both actions log the exact binary bytes from `onSent`.

- [ ] **Step 5: Render receive and diagnostic output**

Append timestamped `[Read]` for every read, retain all non-success data, and show green “开锁成功” from `onUnlockSuccess`. Render opening failures in red with the full diagnostic message; keep it selectable in the log. Include a visible hint: `测试本应用前请彻底退出原串口调试助手`.

- [ ] **Step 6: Compile UI sources**

Expected: `MainActivity` compiles on min SDK 19 and has no external UI dependencies.

### Task 5: Repeatable build, APK verification, and delivery

**Files:**
- Create: `scripts/build-debug.ps1`
- Modify: `README.md`
- Deliver: `outputs/智能更衣柜-串口调试版-v2.apk`

**Interfaces:**
- Produces a signed, aligned installable APK and one console verification summary.

- [ ] **Step 1: Create the one-pass build script**

The script locates the installed Android SDK build-tools and JBR, compiles all JVM tests with the existing local JUnit/Hamcrest jars, runs `org.junit.runner.JUnitCore`, compiles Android Java sources against `android.jar`, generates `classes.dex` with `d8`, compiles/links resources with `aapt2`, adds only the four proven ABI libraries, aligns with `zipalign`, and signs with the existing debug keystore via `apksigner`.

- [ ] **Step 2: Update field instructions**

Document: fully exit the original assistant; refresh and select `/dev/ttyS0`; select `9600/8/1/None/Flow None`; open; send unlock; expect `8A 01 01 00 8A`. Explain that `EBUSY` means another process likely owns the device and `EACCES` means device-node access is denied.

- [ ] **Step 3: Run one full clean verification**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

Expected summary: all JUnit tests pass; Java and DEX compilation succeed; the APK contains `lib/arm64-v8a`, `lib/armeabi-v7a`, `lib/armeabi`, and `lib/x86`; `zipalign -c` succeeds; `apksigner verify --verbose` succeeds.

- [ ] **Step 4: Inspect final APK metadata**

Use `aapt dump badging` on an ASCII-path copy and verify package `com.codex.lockertest`, target SDK 30, landscape launcher Activity, and no `android.permission.INTERNET` in `aapt dump permissions`.

- [ ] **Step 5: Copy only the verified artifact to outputs**

Copy the signed artifact to `C:/Users/Administrator/Documents/Codex/2026-08-13/to/outputs/智能更衣柜-串口调试版-v2.apk`, calculate SHA-256, and report that local verification covers software packaging while the user’s true-device run is the hardware acceptance test.
