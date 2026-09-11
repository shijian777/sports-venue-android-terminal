# Final Package Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make existing production bootstrap accept the final supplied contract without weakening authentication or issuing external operations.

**Architecture:** Extend the existing immutable snapshots and strict parsers. Compatibility is limited to representations documented by the final package. SDK activation remains an explicit separate operation.

**Tech Stack:** Java 8, Android native, existing StrictJson parser and JUnit 4; offline manual build tooling.

**Spec:** docs/superpowers/specs/2026-09-06-final-package-alignment.md

## Global Constraints

- Final user ZIP is authoritative; no live server mutations, activations, photo uploads or hardware operations in tests.
- Keep existing user changes, original APKs, exact lock command mapping, model files and persistent serial lifecycle.
- Work in the current non-main branch v17-production-safety. Do not commit accumulated user changes or create a worktree that omits them.
- Use apply_patch, TDD and focused JVM tests. Do not change MainActivity or SDK runtime in Task 1.
- No activation values, passwords, signatures or biometric fixtures in logs or reports.

### Task 1: Accept final bootstrap response representations

**Files:**
- Modify: app/src/main/java/com/codex/lockertest/bootstrap/BaseSettingSnapshot.java
- Modify: app/src/main/java/com/codex/lockertest/bootstrap/BaseSettingResponseParser.java
- Modify: app/src/main/java/com/codex/lockertest/bootstrap/BasicDataResponseParser.java
- Modify: app/src/main/java/com/codex/lockertest/bootstrap/CheckDeviceResponseParser.java
- Modify if needed: app/src/main/java/com/codex/lockertest/bootstrap/CentralControlResponseParser.java
- Test: app/src/test/java/com/codex/lockertest/bootstrap/FinalPackageBootstrapContractTest.java
- Update conflicting old assertions only: app/src/test/java/com/codex/lockertest/bootstrap/BootstrapResponseParserTest.java

**Interfaces:**
- Existing parse(String) and all existing snapshot accessors remain compatible.
- Add `String BaseSettingSnapshot.activationCode()` and `Map<String,String> BaseSettingSnapshot.voiceFiles()`; returned map immutable. Preserve old constructor callers using a delegating overload where necessary.

- [ ] Write behavior tests that parse a complete sanitized final baseSetting response and assert numeric status becomes "2", multiline notices retain line breaks, activation value available only through explicit accessor, and voice map contains expected URL. Use no real activation code. Test old response without new fields still works.

```java
assertEquals("2", result.value().lockerCheckStatus());
assertEquals("第一行\n第二行", result.value().returnNotice());
assertEquals("https://example.invalid/open.mp3", result.value().voiceFiles().get("柜门已开"));
```

- [ ] Compile and run the focused class against current sources. Confirm failure is due to the missing final contract support, not a malformed test fixture. First tests can target existing status/notice behavior before new accessor tests are introduced.
- [ ] Implement numeric flag normalization for canonical integer or single-character string. Accept 0..2 for locker status, 0..1 for dynamic verification. Reject decimals, exponent numbers, negative zero, other values and malformed strings.

```java
if (value instanceof String) {
    String flag = text(value, 1, false);
    if (flag.charAt(0) < '0' || flag.charAt(0) > ('0' + maximum)) throw contract();
    return flag;
}
return Integer.toString(canonicalInt(value, 0, maximum));
```

- [ ] Add documented optional keys without accepting arbitrary unknown fields. Decode voice as bounded map (at most 32 entries, label up to 128 chars, URL up to 2048 chars, no controls); empty list tolerated, nonempty list rejected. Only notices permit newline/carriage-return/tab. Activation string max 256 chars, empty allowed, controls forbidden. Store bounded strings, do not log, persist, auto-activate or fetch URLs.
- [ ] Accept device_no string or canonical nonnegative integer (no decimal or exponent); preserve original string values and leading zeros. Merchant code stays bounded nonempty string.
- [ ] Add rejection cases for invalid enums, controls, wrong map/list structures, numeric device edge cases and immutable voice configuration. Adapt superseded old tests and run full common JVM suite once.
- [ ] Review diff and report red/green commands, counts and any unchanged failing baseline. Do not commit because the checkout contains pre-existing user changes. Save full report to this plan's SDD workspace task-1-report.md.

### Task 2: Independent review and regression integration

**Files:** scope is the exact Task 1 diff; no new production files unless correcting a specific review finding.

- [ ] Capture a diff against the pre-task file copies, excluding unrelated dirty files.
- [ ] Independent reviewer evaluates final document conformity and implementation quality; check both verdicts.
- [ ] Route findings to the original implementer, rerun affected tests and scoped review.
- [ ] Run full production compile/tests and APK audit using non-overwriting publication mode; if build audit pins intentionally superseded contracts, update only the corresponding documented gates with behavior evidence.
- [ ] Record completion and remaining independently scoped final requirements in the execution ledger; continue with the next subsystem plan.
