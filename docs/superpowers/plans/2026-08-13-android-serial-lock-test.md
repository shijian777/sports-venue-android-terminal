# Android Serial Lock Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an offline landscape Android APK that sends `8A 01 01 11 9B` to `/dev/ttyS0` at 9600 8N1 and reports success on `8A 01 01 00 8A`.

**Architecture:** A single Android Activity owns a focused controller. Pure Java protocol helpers validate and encode hex and recognize a success frame. A serial gateway wraps the same `android_serialport_api` JNI interface and native library proven by the supplied serial assistant APK. The UI uses platform Views only so the project builds from the available offline Android/Gradle cache.

**Tech Stack:** Java 8, Android SDK 34, Android Gradle Plugin 8.9.2, Gradle 8.11.1, Android platform Views, JNI `libserial_port.so`, JUnit 4.

## Global Constraints

- Device path is exactly `/dev/ttyS0`.
- Serial parameters are exactly `9600`, 8 data bits, 1 stop bit, no parity.
- The default command is `8A0101119B`, sent as five binary bytes.
- The success response is exactly `8A0101008A` and may arrive in chunks.
- Response timeout is 2 seconds and commands are never automatically retried.
- The app is offline and does not request network permission.
- The app is locked to landscape and targets the 1280×800 terminal layout responsively.
- Run one local test/build pass only; do not start an emulator. Hardware behavior is verified by the user on the nearby terminal.

---

## File Structure

- `settings.gradle`, `build.gradle`, `gradle.properties`: offline Android build configuration.
- `app/build.gradle`: application, ABI, test, packaging, and SDK configuration.
- `app/src/main/AndroidManifest.xml`: landscape Activity and app metadata.
- `app/src/main/java/com/codex/lockertest/protocol/HexCodec.java`: hex normalization, validation, byte conversion, and formatting.
- `app/src/main/java/com/codex/lockertest/protocol/ResponseAccumulator.java`: chunk accumulation and exact success-frame recognition.
- `app/src/main/java/android_serialport_api/SerialPort.java`: JNI binding, permission fallback, and stream ownership.
- `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`: lifecycle-safe background serial connection, send, receive, and timeout callbacks.
- `app/src/main/java/com/codex/lockertest/MainActivity.java`: screen construction, state rendering, logging, and button actions.
- `app/src/main/res/drawable/*.xml`, `values/*.xml`: technology-style screen colors, shapes, theme, and Chinese strings.
- `app/src/main/jniLibs/*/libserial_port.so`: proven native libraries extracted from the supplied serial assistant APK.
- `app/src/test/java/com/codex/lockertest/protocol/*Test.java`: pure JVM protocol tests.
- `README.md`: installation and one-pass hardware test steps.

### Task 1: Offline project and protocol core

**Files:**
- Create: Gradle project files and `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/codex/lockertest/protocol/HexCodec.java`
- Create: `app/src/main/java/com/codex/lockertest/protocol/ResponseAccumulator.java`
- Test: `app/src/test/java/com/codex/lockertest/protocol/HexCodecTest.java`
- Test: `app/src/test/java/com/codex/lockertest/protocol/ResponseAccumulatorTest.java`

**Interfaces:**
- Produces: `HexCodec.decode(String): byte[]`, `HexCodec.format(byte[]): String`, `ResponseAccumulator.append(byte[], int): State`, and `ResponseAccumulator.snapshot(): byte[]`.

- [ ] Write tests covering compact/spaced lowercase hex, invalid odd-length/input, uppercase spaced formatting, complete success response, split success response, and nonmatching five-byte response.
- [ ] Run `gradle --offline :app:testDebugUnitTest` and verify tests fail because the production classes do not exist.
- [ ] Implement the two pure Java protocol classes with exact frame matching.
- [ ] Re-run `gradle --offline :app:testDebugUnitTest` and verify all protocol tests pass.

### Task 2: JNI serial gateway

**Files:**
- Create: `app/src/main/java/android_serialport_api/SerialPort.java`
- Create: `app/src/main/java/com/codex/lockertest/serial/SerialGateway.java`
- Create: `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libserial_port.so`

**Interfaces:**
- Consumes: `HexCodec.format(byte[])` and `ResponseAccumulator`.
- Produces: `SerialGateway.Listener` callbacks `onConnectionChanged`, `onSent`, `onReceived`, and `onResult`; methods `connect()`, `send(byte[])`, and `close()`.

- [ ] Implement the JNI class with the constructor signature `(File, int, int, int, int, int)`, native `open`, native `close`, and stream accessors expected by the supplied library.
- [ ] Add the same permission fallback used by the supplied tool: only when the node is not readable/writable, run `/system/bin/su`, write `chmod 666 /dev/ttyS0`, wait, and re-check access.
- [ ] Implement a single-threaded serial gateway that opens 9600/8N1, starts one blocking reader, rejects concurrent sends, accumulates chunks, times out after 2 seconds, and never retries.
- [ ] Ensure `close()` interrupts workers and releases the descriptor exactly once.

### Task 3: Landscape test interface

**Files:**
- Create: `app/src/main/java/com/codex/lockertest/MainActivity.java`
- Create: `app/src/main/res/values/colors.xml`, `strings.xml`, `styles.xml`
- Create: `app/src/main/res/drawable/bg_*.xml`

**Interfaces:**
- Consumes: `HexCodec.decode` and every `SerialGateway.Listener` callback.

- [ ] Build the responsive landscape screen entirely with platform Views: header, connection chip, fixed parameter panel, editable command, send button, result card, log panel, clear button, and reconnect button.
- [ ] Apply blue technology background, white content cards, green primary action, large Chinese labels, and status-specific green/red/amber colors.
- [ ] On launch call `connect`; on send validate/decode once and disable the button until result; append millisecond timestamps for send/read/error events.
- [ ] Hide navigation/status bars for kiosk-like testing while retaining Activity lifecycle cleanup.

### Task 4: One-pass verification and delivery

**Files:**
- Create: `README.md`
- Deliver: `outputs/智能更衣柜-串口开锁测试-v1.apk`

**Interfaces:**
- Consumes: completed Android project.
- Produces: installable debug APK and field-test instructions.

- [ ] Run one combined verification command: `gradle --offline clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- [ ] Confirm tests passed, lint has no fatal errors, the manifest has no INTERNET permission, and the APK contains all four `libserial_port.so` ABIs.
- [ ] Inspect the built APK with `aapt dump badging` to confirm package, min/target SDK, label, and landscape launch Activity.
- [ ] Copy the APK to `outputs/智能更衣柜-串口开锁测试-v1.apk` and write exact true-device steps in `README.md`.
