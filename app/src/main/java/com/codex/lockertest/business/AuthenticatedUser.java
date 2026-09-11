package com.codex.lockertest.business;

/** Server-authenticated identity; administrator state is never treated as a customer. */
public final class AuthenticatedUser {
    private final SessionToken token;
    private final UserType userType;
    private final int lockerCheckStatus;
    private final long uid;

    public AuthenticatedUser(SessionToken token, UserType userType,
            int lockerCheckStatus, long uid) {
        if (token == null || userType == null || uid < 0) {
            throw new IllegalArgumentException("Authenticated user is invalid");
        }
        this.token = token;
        this.userType = userType;
        this.lockerCheckStatus = BusinessValues.bounded(
                lockerCheckStatus, 0, 1, "Locker check status");
        this.uid = uid;
    }

    public SessionToken token() { return token; }
    public UserType userType() { return userType; }
    public int lockerCheckStatus() { return lockerCheckStatus; }
    public boolean dynamicCodeRequired() {
        return userType == UserType.ADMIN && lockerCheckStatus == 1;
    }
    public long uid() { return uid; }
    public boolean isCustomerReady() {
        return userType == UserType.USER && !dynamicCodeRequired();
    }

    @Override public String toString() {
        return "AuthenticatedUser{userType=" + userType
                + ", dynamicCodeRequired=" + dynamicCodeRequired() + "}";
    }
}
