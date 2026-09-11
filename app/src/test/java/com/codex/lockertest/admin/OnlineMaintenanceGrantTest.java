package com.codex.lockertest.admin;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class OnlineMaintenanceGrantTest {
    @Test public void sameProcessSharedBrokerAndIndependentInstancesAreAvailable() {
        assertSame(OnlineMaintenanceGrant.shared(), OnlineMaintenanceGrant.shared());
        OnlineMaintenanceGrant first = new OnlineMaintenanceGrant();
        OnlineMaintenanceGrant second = new OnlineMaintenanceGrant();
        OnlineMaintenanceGrant.Ticket ticket = first.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, () -> { });
        assertNotNull(ticket);
        assertNull(second.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
        assertNotNull(first.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
    }

    @Test public void matchingTargetClaimsOnceWhileReplayCannotReplaceTheLiveLease() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, () -> { });
        assertNotNull(ticket);
        assertFalse(ticket.id().isEmpty());

        OnlineMaintenanceGrant.Lease lease = grants.claim(
                ticket.id(), OnlineMaintenanceGrant.Target.SERIAL);

        assertNotNull(lease);
        assertTrue(lease.authorized());
        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
        assertTrue(lease.authorized());
    }

    @Test public void wrongTargetOrUnknownIdCannotClaimOrConsumeTheMatchingTicket() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.FACE_SDK, () -> true, () -> { });
        assertNotNull(ticket);

        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
        assertNull(grants.claim("unknown-ticket", OnlineMaintenanceGrant.Target.FACE_SDK));
        assertNull(grants.claim(null, OnlineMaintenanceGrant.Target.FACE_SDK));
        assertNull(grants.claim(ticket.id(), null));
        assertNotNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.FACE_SDK));
    }

    @Test public void missingOrDeniedAuthorizationDoesNotIssueATicket() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        assertNull(grants.issue(OnlineMaintenanceGrant.Target.SERIAL, () -> false, () -> { }));
        assertNull(grants.issue(null, () -> true, () -> { }));
        assertNull(grants.issue(OnlineMaintenanceGrant.Target.SERIAL, null, () -> { }));
        assertNull(grants.issue(OnlineMaintenanceGrant.Target.SERIAL, () -> true, null));
    }

    @Test public void expiredAuthorizationBeforeClaimPermanentlyInvalidatesTheTicket() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicBoolean live = new AtomicBoolean(true);
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, live::get, () -> { });
        assertNotNull(ticket);
        live.set(false);

        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));

        live.set(true);
        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
    }

    @Test public void everyLeaseCheckUsesLiveAuthorizationAndFailureCannotRevive() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger checks = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(OnlineMaintenanceGrant.Target.SERIAL,
                () -> { checks.incrementAndGet(); return live.get(); }, () -> { });
        assertNotNull(ticket);
        OnlineMaintenanceGrant.Lease lease = grants.claim(
                ticket.id(), OnlineMaintenanceGrant.Target.SERIAL);
        assertNotNull(lease);
        int before = checks.get();
        assertTrue(lease.authorized());
        assertTrue(lease.authorized());
        assertEquals(before + 2, checks.get());

        live.set(false);
        assertFalse(lease.authorized());
        int afterExpiry = checks.get();
        live.set(true);
        assertFalse(lease.authorized());
        assertEquals(afterExpiry, checks.get());
    }

    @Test public void supplierRuntimeAndLinkageFailuresCloseAtIssueClaimAndLeaseCheck() {
        for (boolean linkage : new boolean[]{false, true}) {
            OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
            AtomicBoolean broken = new AtomicBoolean(true);
            BooleanSupplier supplier = () -> {
                if (broken.get()) {
                    if (linkage) throw new LinkageError("unavailable dependency");
                    throw new IllegalStateException("unavailable authorization");
                }
                return true;
            };
            assertNull(grants.issue(OnlineMaintenanceGrant.Target.SERIAL, supplier, () -> { }));

            broken.set(false);
            OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                    OnlineMaintenanceGrant.Target.SERIAL, supplier, () -> { });
            assertNotNull(ticket);
            broken.set(true);
            assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
            broken.set(false);
            assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));

            ticket = grants.issue(OnlineMaintenanceGrant.Target.SERIAL, supplier, () -> { });
            assertNotNull(ticket);
            OnlineMaintenanceGrant.Lease lease = grants.claim(
                    ticket.id(), OnlineMaintenanceGrant.Target.SERIAL);
            assertNotNull(lease);
            broken.set(true);
            assertFalse(lease.authorized());
        }
    }

    @Test public void supersededTicketCannotClaimOrRevokeANewerSession() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger oldRevocations = new AtomicInteger();
        AtomicInteger newRevocations = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket old = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, oldRevocations::incrementAndGet);
        OnlineMaintenanceGrant.Ticket current = grants.issue(
                OnlineMaintenanceGrant.Target.FACE_SDK, () -> true, newRevocations::incrementAndGet);
        assertNotNull(old);
        assertNotNull(current);
        assertNotEquals(old.id(), current.id());

        old.finish(true);
        assertNull(grants.claim(old.id(), OnlineMaintenanceGrant.Target.SERIAL));
        OnlineMaintenanceGrant.Lease lease = grants.claim(
                current.id(), OnlineMaintenanceGrant.Target.FACE_SDK);

        assertNotNull(lease);
        assertTrue(lease.authorized());
        assertEquals(0, oldRevocations.get());
        assertEquals(0, newRevocations.get());
    }

    @Test public void supersededLeaseFinishCannotRevokeTheCurrentTicketOrSession() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger oldRevocations = new AtomicInteger();
        AtomicInteger newRevocations = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket old = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, oldRevocations::incrementAndGet);
        assertNotNull(old);
        OnlineMaintenanceGrant.Lease staleLease = grants.claim(
                old.id(), OnlineMaintenanceGrant.Target.SERIAL);
        assertNotNull(staleLease);
        OnlineMaintenanceGrant.Ticket current = grants.issue(
                OnlineMaintenanceGrant.Target.FACE_SDK, () -> true, newRevocations::incrementAndGet);
        assertNotNull(current);

        assertFalse(staleLease.authorized());
        staleLease.finish(true);
        old.finish(true);
        OnlineMaintenanceGrant.Lease lease = grants.claim(
                current.id(), OnlineMaintenanceGrant.Target.FACE_SDK);

        assertNotNull(lease);
        assertTrue(lease.authorized());
        assertEquals(0, oldRevocations.get());
        assertEquals(0, newRevocations.get());
        lease.finish(true);
        assertEquals(1, newRevocations.get());
    }

    @Test public void finishWithoutRevocationReleasesTheGrantWithoutCancellingParentSession() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger revocations = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, revocations::incrementAndGet);
        assertNotNull(ticket);
        OnlineMaintenanceGrant.Lease lease = grants.claim(
                ticket.id(), OnlineMaintenanceGrant.Target.SERIAL);
        assertNotNull(lease);

        lease.finish(false);
        lease.finish(true);
        ticket.finish(true);

        assertFalse(lease.authorized());
        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
        assertEquals(0, revocations.get());
    }

    @Test public void finishWithRevocationCancelsParentExactlyOnceAndInvalidatesTheLease() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicBoolean live = new AtomicBoolean(true);
        AtomicInteger revocations = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(OnlineMaintenanceGrant.Target.SERIAL,
                live::get, () -> { live.set(false); revocations.incrementAndGet(); });
        assertNotNull(ticket);
        OnlineMaintenanceGrant.Lease lease = grants.claim(
                ticket.id(), OnlineMaintenanceGrant.Target.SERIAL);
        assertNotNull(lease);

        lease.finish(true);
        lease.finish(true);
        ticket.finish(true);

        assertFalse(live.get());
        assertFalse(lease.authorized());
        assertEquals(1, revocations.get());
    }

    @Test public void pendingTicketCanBeRevokedWhenActivityLaunchFails() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger revocations = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, revocations::incrementAndGet);
        assertNotNull(ticket);

        ticket.finish(true);
        ticket.finish(true);

        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
        assertEquals(1, revocations.get());
    }

    @Test public void hostCanRevokeClaimedLeaseFromBackgroundWithoutAReplayWindow() throws Exception {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger revocations = new AtomicInteger();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.FACE_SDK, () -> true, revocations::incrementAndGet);
        assertNotNull(ticket);
        OnlineMaintenanceGrant.Lease lease = grants.claim(
                ticket.id(), OnlineMaintenanceGrant.Target.FACE_SDK);
        assertNotNull(lease);
        Thread revoker = new Thread(() -> ticket.finish(true));

        revoker.start();
        revoker.join(2000);

        assertFalse("Background revocation did not finish", revoker.isAlive());
        assertFalse(lease.authorized());
        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.FACE_SDK));
        assertEquals(1, revocations.get());
    }

    @Test public void throwingRevocationStillReleasesOldGrantAndAllowsASeparateNewSession() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true,
                () -> { throw new IllegalStateException("parent already gone"); });
        assertNotNull(ticket);

        ticket.finish(true);

        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
        OnlineMaintenanceGrant.Ticket next = grants.issue(
                OnlineMaintenanceGrant.Target.SERIAL, () -> true, () -> { });
        assertNotNull(next);
        assertNotNull(grants.claim(next.id(), OnlineMaintenanceGrant.Target.SERIAL));
    }

    @Test public void reentrantSupplierCannotResurrectSupersededGrantOrOverwriteNewIssue() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<OnlineMaintenanceGrant.Ticket> replacement = new AtomicReference<>();
        OnlineMaintenanceGrant.Ticket old = grants.issue(OnlineMaintenanceGrant.Target.SERIAL,
                () -> {
                    if (checks.incrementAndGet() == 2) replacement.set(grants.issue(
                            OnlineMaintenanceGrant.Target.FACE_SDK, () -> true, () -> { }));
                    return true;
                }, () -> { });
        assertNotNull(old);

        assertNull(grants.claim(old.id(), OnlineMaintenanceGrant.Target.SERIAL));

        assertNotNull(replacement.get());
        assertNotNull(grants.claim(replacement.get().id(), OnlineMaintenanceGrant.Target.FACE_SDK));
    }

    @Test public void reentrantClaimOfTheSameTicketCanProduceOnlyOneLease() {
        OnlineMaintenanceGrant grants = new OnlineMaintenanceGrant();
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<OnlineMaintenanceGrant.Lease> inner = new AtomicReference<>();
        OnlineMaintenanceGrant.Ticket ticket = grants.issue(OnlineMaintenanceGrant.Target.SERIAL,
                () -> {
                    if (checks.incrementAndGet() == 2) inner.set(grants.claim(
                            id.get(), OnlineMaintenanceGrant.Target.SERIAL));
                    return true;
                }, () -> { });
        assertNotNull(ticket);
        id.set(ticket.id());

        OnlineMaintenanceGrant.Lease outer = grants.claim(
                ticket.id(), OnlineMaintenanceGrant.Target.SERIAL);

        assertNotNull(inner.get());
        assertNull("The same ticket produced a second lease", outer);
        assertTrue(inner.get().authorized());
        assertNull(grants.claim(ticket.id(), OnlineMaintenanceGrant.Target.SERIAL));
    }
}
