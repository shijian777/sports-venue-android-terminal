# Existing-contract business integration

User approval: implement what final package specifies; missing contracts remain explicit internal adapters for later connection. No further design approval required. Authority is the final `智能更衣柜app(2).zip` and extracted DOCX, not earlier inferred missing-document claims.

## Design / scope

Preserve legacy localDemo behavior and current visual layout. Keep production bootstrap separate from customer business. Add typed signed REST clients, a cancellable single-journey coordinator, then route production home credentials to it. Successful server identity permits reading server-owned cabinet layout or the authenticated user's occupied cabinets. It does not manufacture physical authorization. Implement documented mutation requests as service methods, but do not bind them to physical execution until a fresh command/transaction contract is confirmed. Face upload gets an explicit replaceable upload boundary and a tested upload->userInfo type 5 pipeline. Palm association and MQTT have explicit unavailable capabilities with reasons, not sockets or simulated success.

## Global Constraints

- Work in existing dirty v17 checkout on v17-production-safety; preserve all earlier work and APKs. No commits, resets or branch changes.
- Keep 1280x800 layout and 乾卦 branding. Do not alter lock command bytes, lock-board numbering, long-lived serial ownership, or palm diagnostic behavior.
- Production never trusts local whitelist/mock authorization. No live mutation, biometrics upload, SDK activation, SMS, lock command, or device installation during tests.
- JSON POST uses fixed HTTPS test host, existing system TLS, current signing/envelope, token and bound device/merchant headers. No arbitrary URL, redirect following, TLS bypass, payload/secret logging, or automatic mutation retry.
- Missing contract means unavailable with an explicit reason. Do not invent multipart signing, palm ID encoding, MQTT settings, or physical open/return transaction semantics.
- Network work off main thread; cancellation/generation guard, one operation at a time, bounded deadlines, no background/late response navigation, session cleared on exit. No production path into legacy serial workflow.
- Tests use real serializers/parsers/coordinators with fake external transport, not live backend writes. Full builds run sequentially with a fresh DeliveryRoot beneath outputs.

## Task 1: Typed business REST and missing-contract adapters

Implement separate business classes alongside existing bootstrap without widening bootstrap's three-method service. Reuse transport with a closed business endpoint allowlist (not arbitrary URL). Implement typed credential requests type 1 (both phone and user_code), 2 QR, 4 card, type 5 face image identifier; type 3 requires caller-supplied provider, never stringify hardware features. Preserve leading zeroes and variable QR lengths with a generous resource bound, not credential-specific fixed lengths. Authenticate parses token, user_type, locker_check_status, uid; admins requiring dynamic code do not become normal users automatically. Implement memberDynamicCode, controlPanelPreview, useCabinetList, userBoard/openBoard and documented admin/setup request methods where exact contract is available. Empty mutation responses must be parsed per explicit fixtures. Installation path and multipart signing remain blocked if unresolved.

Face pipeline: capture bytes -> FaceImageUploadAdapter -> returned image identifier -> userInfo type 5. Unconfigured upload must fail without authenticating. Palm ID and MQTT remain explicit extension boundaries with missing reason and no external I/O.

Test requests against literal DOCX-derived fixtures, token and headers, escaping, empty/wrong/duplicate fields, HTTP/non-200 failures, cancellations, closed key lifecycle and no mutation retry. New files focused by responsibility. Existing bootstrap behavior unchanged. Report exact changed paths and focused RED/GREEN commands. Root owns final audit whitelist updates and MainActivity integration.

## Task 2: Asynchronous production customer journey

Create a pure coordinator owning session and one in-flight call, accepting a ready registration and credential (including both phone fields). Distinguish open-cabinet browsing versus return-cabinet browsing. Authenticate only through server. Reject admin identities needing the unresolved admin flow from the customer journey. Successful auth fetches server cabinet preview/page or occupied list using token. Region names/order from baseSetting; numeric layer keys/slots from preview, max 4 rows and 8 per row. Keep server names/IDs, never infer board IDs from area names. Pages driven by validated server page data; ambiguous page data must be unavailable rather than invented. No physical I/O. UserBoard/openBoard remain service-only, and action exposes a clear unavailable explanation.

Cancellation, timeout, duplicate scan, retry after failure, change region/page, stale callback after exit/background/device generation change, and malformed response tests required. State stores no raw credentials after requests finish; no logs include tokens or biometrics.

## Task 3: Variant assembly and unchanged-layout UI wiring

Wire production home paired phone fields and passive scans to new async journey after bootstrap readiness. Preserve localDemo routes and tests. Explicitly distinguish production customer authentication capability from legacy customerActionsEnabled, so enabling online credentials never enables discovery/serial/legacy authorizers. Reuse existing presentation components where safe; production cabinet selection shows server data and disabled physical action with explanation. Return identity can query authenticated occupied cabinets; no fake completion. Background/back/admin clears business session and cancels work. LocalDemo palm path stays as delivered. Face actual upload stays unavailable until adapter configured; do not show fake success. MainActivity edits kept to boundary calls; logic in new focused classes.

## Task 4: Review, regression and protected packaging

Review task diffs and resolve important findings. Update narrowly scoped source/audit baselines for intentional business endpoints/UI assembly without disabling security checks. Run focused behavioral tests then sequential localDemo and production full builds into a new output folder. Run available emulator UI regression, state clearly hardware/backend success is not validated. Keep every previous delivered APK.
