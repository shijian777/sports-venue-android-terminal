package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.runtime.CredentialAdmissionPolicy;
import com.codex.lockertest.runtime.InitialLayoutPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Explicit deterministic policy dependencies for common JVM UI tests. */
@org.junit.Ignore("Test dependency fixture")
public final class RuntimePolicyFixtures {
    private static final String QR_CODE =
            "111993413628001787216027-00144049324404404044044~712~1~3~"
                    + "30303030303137373331";

    private RuntimePolicyFixtures() {
    }

    public static CredentialAdmissionPolicy demoCredentialPolicy() {
        return (requestedMethod, rawCredential) -> {
            if (requestedMethod == UnlockMethod.PHONE
                    && "13800138000".equals(rawCredential)) {
                return CredentialAdmission.accepted(UnlockMethod.PHONE);
            }
            if (requestedMethod == UnlockMethod.PASSWORD
                    && "123456".equals(rawCredential)) {
                return CredentialAdmission.accepted(UnlockMethod.PASSWORD);
            }
            if (requestedMethod == UnlockMethod.ID_CARD
                    && "0014872138".equals(rawCredential)) {
                return CredentialAdmission.accepted(UnlockMethod.ID_CARD);
            }
            if (requestedMethod == UnlockMethod.ID_CARD
                    && QR_CODE.equals(rawCredential)) {
                return CredentialAdmission.accepted(UnlockMethod.QR);
            }
            if (requestedMethod == UnlockMethod.PHONE) {
                return CredentialAdmission.rejected("手机号不正确");
            }
            if (requestedMethod == UnlockMethod.PASSWORD) {
                return CredentialAdmission.rejected("密码不正确");
            }
            return CredentialAdmission.rejected("凭证未登记");
        };
    }

    public static InitialLayoutPolicy legacyLayoutPolicy() {
        return new InitialLayoutPolicy() {
            @Override
            public LockerLayoutSnapshot initialLayout() {
                return legacyLayout(Collections.singletonList(LockerZone.A));
            }

            @Override
            public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
                return legacyLayout(onlineZones);
            }
        };
    }

    private static LockerLayoutSnapshot legacyLayout(List<LockerZone> onlineZones) {
        boolean onlineA = onlineZones.contains(LockerZone.A);
        boolean onlineB = onlineZones.contains(LockerZone.B);
        boolean onlineC = onlineZones.contains(LockerZone.C);
        long version = (onlineA ? 1 : 0) | (onlineB ? 2 : 0) | (onlineC ? 4 : 0);
        return new LockerLayoutSnapshot(version, Arrays.asList(
                area(LockerZone.A, onlineA),
                area(LockerZone.B, onlineB),
                area(LockerZone.C, onlineC)));
    }

    private static LockerArea area(LockerZone zone, boolean enabled) {
        List<LockerSlot> slots = new ArrayList<>(12);
        for (int localLock = 1; localLock <= 12; localLock++) {
            int row = (localLock - 1) % 4 + 1;
            int column = (localLock - 1) / 4 + 1;
            String name = zone.name();
            slots.add(new LockerSlot(
                    "legacy-slot-" + name + "-" + twoDigits(localLock),
                    name + localLock,
                    row,
                    column,
                    enabled,
                    new LockerTarget(zone, zone.boardAddress(), localLock,
                            FeedbackPolarity.SHORT_WHEN_LOCKED)));
        }
        LockerPage page = new LockerPage(
                "legacy-page-" + zone.name() + "-1", 1, 4, 8, slots);
        return new LockerArea(
                "legacy-area-" + zone.name(), zone.name() + "区",
                Collections.singletonList(page));
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
