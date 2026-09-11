package com.codex.lockertest.business;

/** Inert boundary for the installation UI and physical verification flow. */
public interface InstallationVerifyAdapter {
    String unavailableReason();

    static InstallationVerifyAdapter unconfigured() {
        return new InstallationVerifyAdapter() {
            @Override public String unavailableReason() {
                return "安装校验实机流程尚未接入";
            }
        };
    }
}
