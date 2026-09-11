package com.codex.lockertest.ui.business;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class OnlineAdminNavigationTest {
    @Test public void unauthenticatedMenuRequestExitsWithoutExposingMaintenance() {
        Fixture f = new Fixture();
        assertFalse(f.navigation.showMenu());
        assertEquals(OnlineAdminNavigation.Page.CLOSED, f.navigation.page());
        assertEquals(1, f.exits);
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.SERIAL));
    }

    @Test public void maintenanceCannotBeOpenedFromLoginEvenWithAStaleClick() {
        Fixture f = new Fixture();
        f.authorized.set(true);
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.FACE));
        assertEquals(OnlineAdminNavigation.Page.LOGIN, f.navigation.page());
    }

    @Test public void everyMaintenancePageReturnsToMenuUnderTheSameAuthorization() {
        Fixture f = new Fixture();
        f.authorized.set(true);
        assertTrue(f.navigation.showMenu());
        OnlineAdminNavigation.Page[] destinations = {
                OnlineAdminNavigation.Page.SERIAL, OnlineAdminNavigation.Page.FACE,
                OnlineAdminNavigation.Page.SOURCES, OnlineAdminNavigation.Page.MQTT};
        for (OnlineAdminNavigation.Page destination : destinations) {
            assertTrue(f.navigation.open(destination));
            assertEquals(destination, f.navigation.page());
            assertTrue(f.navigation.authorized());
            assertTrue(f.navigation.showMenu());
            assertEquals(OnlineAdminNavigation.Page.MENU, f.navigation.page());
        }
        assertEquals(0, f.exits);
    }

    @Test public void staleMenuClickCannotReplaceAnAlreadyOpenedMaintenancePage() {
        Fixture f = authenticated();
        assertTrue(f.navigation.open(OnlineAdminNavigation.Page.SERIAL));
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.FACE));
        assertEquals(OnlineAdminNavigation.Page.SERIAL, f.navigation.page());
    }

    @Test public void expiryBeforeMenuClickRevokesInsteadOfOpeningMaintenance() {
        Fixture f = authenticated();
        f.authorized.set(false);
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.SERIAL));
        assertEquals(OnlineAdminNavigation.Page.CLOSED, f.navigation.page());
        assertEquals(1, f.exits);
    }

    @Test public void expiryWhileMaintenanceIsOpenCannotReturnToAuthorizedMenu() {
        Fixture f = authenticated();
        assertTrue(f.navigation.open(OnlineAdminNavigation.Page.FACE));
        f.authorized.set(false);
        assertFalse(f.navigation.authorized());
        assertFalse(f.navigation.showMenu());
        assertEquals(OnlineAdminNavigation.Page.CLOSED, f.navigation.page());
        assertEquals(1, f.exits);
    }

    @Test public void closedSessionCannotBeReopenedByLateAuthorizationOrReturn() {
        Fixture f = authenticated();
        assertTrue(f.navigation.open(OnlineAdminNavigation.Page.SERIAL));
        f.navigation.close();
        assertFalse(f.navigation.authorized());
        assertFalse(f.navigation.showMenu());
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.MQTT));
        assertEquals(OnlineAdminNavigation.Page.CLOSED, f.navigation.page());
        assertEquals(0, f.exits);
    }

    @Test public void repeatedExpiredReturnsRequestExitOnlyOnce() {
        Fixture f = authenticated();
        f.authorized.set(false);
        assertFalse(f.navigation.showMenu());
        assertFalse(f.navigation.showMenu());
        assertEquals(1, f.exits);
    }

    @Test public void authorizationFailureFailsClosedWithoutExposingItsMessage() {
        int[] exits = {0};
        OnlineAdminNavigation navigation = new OnlineAdminNavigation(
                () -> { throw new IllegalStateException("sensitive-authorization-detail"); },
                () -> exits[0]++);
        assertFalse(navigation.authorized());
        assertFalse(navigation.showMenu());
        assertEquals(OnlineAdminNavigation.Page.CLOSED, navigation.page());
        assertEquals(1, exits[0]);
    }

    @Test public void internalLoginOrClosedDestinationsAreNotMaintenanceActions() {
        Fixture f = authenticated();
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.LOGIN));
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.CLOSED));
        assertFalse(f.navigation.open(OnlineAdminNavigation.Page.MENU));
        assertFalse(f.navigation.open(null));
        assertEquals(OnlineAdminNavigation.Page.MENU, f.navigation.page());
    }

    private static Fixture authenticated() {
        Fixture f = new Fixture();
        f.authorized.set(true);
        assertTrue(f.navigation.showMenu());
        return f;
    }

    private static final class Fixture {
        final AtomicBoolean authorized = new AtomicBoolean();
        int exits;
        final OnlineAdminNavigation navigation = new OnlineAdminNavigation(authorized::get, () -> exits++);
    }
}
