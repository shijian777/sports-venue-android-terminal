package com.codex.lockertest.business;

/** Supplies the backend's opaque type-3 identity keyword without exposing feature bytes. */
public interface PalmKeywordProvider {
    String keyword();

    default String unavailableReason() {
        return null;
    }

    static PalmKeywordProvider unconfigured() {
        return new PalmKeywordProvider() {
            @Override public String keyword() {
                throw new IllegalStateException(unavailableReason());
            }

            @Override public String unavailableReason() {
                return "掌静脉身份编码协议未配置";
            }
        };
    }
}
