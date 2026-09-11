# Baidu online-license cold-start regression

Run from the project root:

```powershell
./tools/baidu-license-restore-regression/run-tests.ps1 -Phase green
```

This JVM suite compiles the **real production** `BdFaceAuth`, `OfficialFaceAuthOperation`, `CodeDetail`, and `FaceLicenseStateMachine`. Only Android context and the external native/network SDK and its preference I/O use fixtures. The fixtures read/write no user files and perform no network or native calls. They are outside every app source set and are never packaged.

The 11 adapter behaviors cover SDK-owned online identifier recovery when native local info is empty or throws; no authorization before the SDK callback; no alternate-key retry after SDK rejection or network failure; prior local-info fallback only when the online identifier is absent; both sources missing; preference errors; oversized keys; duplicate callbacks; and missing context. Existing policy/state-machine regressions run alongside them (34 tests total).

Output is isolated under `.superpowers/v22-license-restore/`. Historical `-Phase red` expects test failure and is intended only for demonstrating the original behavior, not CI acceptance. These tests do not validate actual license-file persistence, network activation, or process-restart recovery on the terminal; root owns those checks.
