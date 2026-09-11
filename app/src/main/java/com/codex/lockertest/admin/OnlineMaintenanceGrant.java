package com.codex.lockertest.admin;

import java.util.function.BooleanSupplier;
import java.util.UUID;

/**
 * Process-local, one-use handoff of an existing online administrator authorization.
 * No credentials or server tokens are stored. The host must finish its ticket when its
 * short session ends, even if the destination never claims it. Callbacks must be nonblocking.
 */
public final class OnlineMaintenanceGrant {
    public enum Target { SERIAL, FACE_SDK }
    private static final OnlineMaintenanceGrant SHARED = new OnlineMaintenanceGrant();
    private Entry current;

    OnlineMaintenanceGrant() { }

    public static OnlineMaintenanceGrant shared() { return SHARED; }

    /** Replaces any older grant without revoking that older parent session. */
    public synchronized Ticket issue(
            Target target, BooleanSupplier liveAuthorization, Runnable revokeSession) {
        if (current != null) finish(current, false);
        if (target == null || liveAuthorization == null || revokeSession == null) return null;
        Entry entry = new Entry(UUID.randomUUID().toString(), target,
                liveAuthorization, revokeSession);
        current = entry;
        return authorized(entry) ? new Ticket(this, entry) : null;
    }

    /** A mismatch does not consume the matching ticket; successful claims cannot replay. */
    public synchronized Lease claim(String id, Target target) {
        Entry entry = current;
        if (entry == null || entry.claimed || target != entry.target
                || !entry.id.equals(id) || !authorized(entry)) return null;
        if (entry.claimed) return null; // Authorization may have reentered claim on this ticket.
        entry.claimed = true;
        return new Lease(this, entry);
    }

    private synchronized boolean authorized(Entry entry) {
        if (current != entry || entry.liveAuthorization == null) return false;
        boolean allowed = false;
        try {
            allowed = entry.liveAuthorization.getAsBoolean();
        } catch (RuntimeException | LinkageError unavailable) {
            // Fail closed without exposing callback details or retaining captured objects.
        }
        // A callback may reenter the broker and supersede or finish this exact entry.
        if (current != entry) return false;
        if (!allowed) finish(entry, false);
        return allowed;
    }

    private synchronized void finish(Entry entry, boolean revokeSession) {
        if (current != entry) return;
        current = null;
        Runnable revoke = entry.revokeSession;
        entry.liveAuthorization = null;
        entry.revokeSession = null;
        if (revokeSession && revoke != null) {
            try {
                // Serialize revocation with issue so an old callback cannot race a new grant.
                revoke.run();
            } catch (RuntimeException | LinkageError unavailable) {
                // The grant and its captured objects are already released.
            }
        }
    }

    public static final class Ticket {
        private final OnlineMaintenanceGrant owner;
        private final Entry entry;

        private Ticket(OnlineMaintenanceGrant owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public String id() { return entry.id; }

        /** Also invalidates an already claimed lease belonging to this ticket only. */
        public void finish(boolean revokeSession) { owner.finish(entry, revokeSession); }
    }

    public static final class Lease {
        private final OnlineMaintenanceGrant owner;
        private final Entry entry;

        private Lease(OnlineMaintenanceGrant owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public boolean authorized() { return owner.authorized(entry); }

        public void finish(boolean revokeSession) { owner.finish(entry, revokeSession); }
    }

    private static final class Entry {
        final String id;
        final Target target;
        BooleanSupplier liveAuthorization;
        Runnable revokeSession;
        boolean claimed;

        Entry(String id, Target target, BooleanSupplier liveAuthorization, Runnable revokeSession) {
            this.id = id;
            this.target = target;
            this.liveAuthorization = liveAuthorization;
            this.revokeSession = revokeSession;
        }
    }
}
