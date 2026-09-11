package com.codex.lockertest.ui.business;

import java.util.function.BooleanSupplier;

/** UI routing only; the online controller remains the sole source of authorization. */
final class OnlineAdminNavigation {
    enum Page { LOGIN, MENU, SOURCES, MQTT, SERIAL, FACE, CLOSED }
    private final BooleanSupplier authorization;
    private final Runnable revoke;
    private volatile Page page = Page.LOGIN;

    OnlineAdminNavigation(BooleanSupplier authorization, Runnable revoke) {
        this.authorization = authorization;
        this.revoke = revoke;
    }
    Page page() { return page; }
    boolean authorized() {
        if (page == Page.CLOSED) return false;
        try { return authorization.getAsBoolean(); }
        catch (RuntimeException failure) { return false; }
    }
    boolean showMenu() {
        if (!checkAuthorization()) return false;
        page = Page.MENU;
        return true;
    }
    boolean open(Page destination) {
        if (destination == null || destination == Page.LOGIN
                || destination == Page.MENU || destination == Page.CLOSED
                || page != Page.MENU || !checkAuthorization()) return false;
        page = destination;
        return true;
    }
    void close() { page = Page.CLOSED; }
    private boolean checkAuthorization() {
        if (page == Page.CLOSED) return false;
        if (authorized()) return true;
        close();
        revoke.run();
        return false;
    }
}
