# Final Home Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align active home actions with the final ZIP without changing customer authorization.

**Architecture:** A pure, testable consecutive-tap gate handles the hidden admin entry. A pure home-action projection distinguishes missing bootstrap configuration from explicit server capability disablement. Native controls retain pixel-shell alignment and existing listener compatibility.

**Tech Stack:** Java 8, Android native views, JUnit4, existing ZipPixelShell.

**Spec:** Final package `智能更衣柜页面交互说明文档.pdf`, PDF pages 1–4 and 29–31; current user final-ZIP authorization.

## Global Constraints

- 1280×800, 乾卦 brand and current pixel shell scaling preserved.
- Home right side has only 掌纹录入, 人脸识别, 离场还柜; no separate palm recognition or visible admin button.
- Five consecutive clock taps open existing admin callback; not a long press. Taps separated by more than 1500 ms reset the sequence, negative/decreasing clock values reset, trigger only once per five taps. Accessibility description explains five taps without making it a one-tap bypass.
- Server recognition_type controls face/palm entry visibility (5 face, 3 palm). With no server configuration, use injected FeatureAvailability for localDemo; do not enable production customer actions.
- No new network, activations, hardware writes, commits or APK publication. Preserve earlier dirty changes.

### Task 1: Home action projection and clock entry

**Files:** modify ZipHomeView.java, BootstrapHomePresentation.java; create ClockAdminTapGate.java and FinalHomeActions.java in ui; corresponding common behavior tests and focused Android test if required.

**Interfaces:** keep ZipHomeView.Listener unchanged. BootstrapHomePresentation exposes whether server capabilities are known and an immutable list of recognition types. ClockAdminTapGate exposes `boolean tap(long nowElapsedMillis)` and `void reset()`. FinalHomeActions consumes known flag, server types and FeatureAvailability; getters for face/palm visibility. These are rendering permissions only, never admission authorization.

- [ ] Write failing behavior tests: taps at 0/100/200/300 do not trigger, 400 triggers once, next starts fresh; a 1501ms gap/decreasing time resets. FinalHomeActions with known ["3"] hides face/shows palm even when local availability says all; unknown uses fallback; null/invalid capability input cannot enable anything.

```java
ClockAdminTapGate gate = new ClockAdminTapGate();
assertFalse(gate.tap(0)); assertFalse(gate.tap(100));
assertFalse(gate.tap(200)); assertFalse(gate.tap(300));
assertTrue(gate.tap(400)); assertFalse(gate.tap(500));
```

- [ ] Compile focused tests and observe RED before implementing.
- [ ] Add pure classes with explicit bounded state. Bind clock click to monotonic Android SystemClock.elapsedRealtime; reset on detach/lost interaction context. Invoke existing onAdminRequested only on fifth tap; remove visible admin button.
- [ ] Replace right actions with final three controls. Only show allowed palm/face controls, dynamically distribute remaining actions without overlapping. Preserve labels/styles from supplied PNG layouts. Palm label exactly 掌纹录入; callback still onEnrollmentRequested until actual server enrollment journey is integrated. No fake enrolled success.
- [ ] Add server capability fields to presentation from READY_READ_ONLY snapshot; local fallback only when not server-configured. Keep current customerActionsEnabled gate unchanged, including modal interception.
- [ ] Test capability projection and button action semantics; no source-string-only tests. Run relevant existing UI/model tests and record pending Android runtime checks. Do not alter older inactive HomeView unless needed for a concrete active callsite.
- [ ] Review exact owned diff against before copies; no changes to MainActivity, ZipPixelShell, SDK, layout/protocol or runtime authentication gates.
- [ ] Save full report in .superpowers/sdd/2026-09-06-final-home-controls/task-1-report.md, including RED/GREEN evidence. Parent performs independent review and emulator verification.
