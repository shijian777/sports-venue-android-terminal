package com.codex.lockertest.face.verification;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class FaceVerificationTicketRegistry {
    enum EntryState {
        ACTIVE,
        REVOKED,
        UNKNOWN
    }

    private static final class Entry {
        final FaceVerificationResult issuedResult;
        EntryState state;

        Entry(FaceVerificationResult issuedResult) {
            this.issuedResult = issuedResult;
            this.state = EntryState.ACTIVE;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();

    synchronized void registerIssued(FaceVerificationResult result) {
        registerIssued(result, Long.MIN_VALUE);
    }

    synchronized void registerIssued(FaceVerificationResult result, long nowEpochMillis) {
        if (result == null || !result.isPassed()) {
            throw new IllegalArgumentException("only a passed issued result can be registered");
        }
        purgeExpired(nowEpochMillis);
        if (entries.containsKey(result.ticketId())) {
            throw new IllegalStateException("ticket ID collision");
        }
        entries.put(result.ticketId(), new Entry(result));
    }

    synchronized boolean containsTicketId(String ticketId, long nowEpochMillis) {
        purgeExpired(nowEpochMillis);
        return entries.containsKey(ticketId);
    }

    synchronized void revokeAll() {
        for (Entry entry : entries.values()) {
            if (entry.state == EntryState.ACTIVE) {
                entry.state = EntryState.REVOKED;
            }
        }
    }

    synchronized EntryState stateOf(FaceVerificationResult result, long nowEpochMillis) {
        purgeExpired(nowEpochMillis);
        if (result == null || result.ticketId() == null) {
            return EntryState.UNKNOWN;
        }
        Entry entry = entries.get(result.ticketId());
        if (entry == null || entry.issuedResult != result) {
            return EntryState.UNKNOWN;
        }
        return entry.state;
    }

    synchronized int size(long nowEpochMillis) {
        purgeExpired(nowEpochMillis);
        return entries.size();
    }

    private void purgeExpired(long nowEpochMillis) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (nowEpochMillis >= entry.issuedResult.expiresAtEpochMillis()) {
                iterator.remove();
            }
        }
    }
}
