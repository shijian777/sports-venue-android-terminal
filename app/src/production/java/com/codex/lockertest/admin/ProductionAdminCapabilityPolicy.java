package com.codex.lockertest.admin;

/** Production maintenance matrix; business mutations require fresh server authority. */
public final class ProductionAdminCapabilityPolicy implements AdminCapabilityPolicy {
    @Override
    public boolean allows(
            AdminCapability capability, boolean online, boolean serverAuthorized) {
        if (capability == null) return false;
        switch (capability) {
            case VIEW_DIAGNOSTICS:
            case MANAGE_SERIAL_CONNECTION:
                return true;
            case ACTIVATE_FACE_SDK:
                return online;
            case OPEN_LOCKER:
            case CLEAR_LOCKERS:
            case CHANGE_MAPPING:
                return online && serverAuthorized;
            default:
                return false;
        }
    }
}
