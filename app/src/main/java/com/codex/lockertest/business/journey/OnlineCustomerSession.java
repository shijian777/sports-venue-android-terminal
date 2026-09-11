package com.codex.lockertest.business.journey;

import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.DeviceRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable binding between one ready bootstrap generation and its ordered regions. */
public final class OnlineCustomerSession {
    private final long bootstrapGeneration;
    private final String merchantCode;
    private final String deviceNo;
    private final List<Region> regions;

    public OnlineCustomerSession(
            long bootstrapGeneration,
            String merchantCode,
            String deviceNo,
            List<Region> regions) {
        if (bootstrapGeneration < 1L
                || invalidIdentity(merchantCode)
                || invalidIdentity(deviceNo)
                || regions == null) {
            throw new IllegalArgumentException("Online customer session is invalid");
        }
        ArrayList<Region> copy = new ArrayList<>(regions.size());
        Set<Long> ids = new HashSet<>();
        for (Region region : regions) {
            if (region == null || !ids.add(region.id())) {
                throw new IllegalArgumentException("Online customer regions are invalid");
            }
            copy.add(region);
        }
        this.bootstrapGeneration = bootstrapGeneration;
        this.merchantCode = merchantCode;
        this.deviceNo = deviceNo;
        this.regions = Collections.unmodifiableList(copy);
    }

    public static OnlineCustomerSession fromBootstrap(
            long bootstrapGeneration,
            DeviceRegistration registration,
            BaseSettingSnapshot baseSetting) {
        if (registration == null || baseSetting == null) {
            throw new IllegalArgumentException("Ready bootstrap data is required");
        }
        ArrayList<Region> regions = new ArrayList<>();
        for (BaseSettingSnapshot.Area area : baseSetting.areas()) {
            regions.add(new Region(area.areaId(), area.areaName()));
        }
        return new OnlineCustomerSession(
                bootstrapGeneration,
                registration.merchantCode(),
                registration.deviceNo(),
                regions);
    }

    public long bootstrapGeneration() { return bootstrapGeneration; }
    public String merchantCode() { return merchantCode; }
    public String deviceNo() { return deviceNo; }
    public List<Region> regions() { return regions; }

    public boolean matches(
            long expectedGeneration, String expectedMerchantCode, String expectedDeviceNo) {
        return bootstrapGeneration == expectedGeneration
                && merchantCode.equals(expectedMerchantCode)
                && deviceNo.equals(expectedDeviceNo);
    }

    @Override public String toString() {
        return "OnlineCustomerSession{generation=" + bootstrapGeneration
                + ", registration=<redacted>, regions=" + regions.size() + "}";
    }

    private static boolean invalidIdentity(String value) {
        return value == null || value.trim().isEmpty() || value.length() > 256;
    }

    public static final class Region {
        private final long id;
        private final String name;

        public Region(long id, String name) {
            if (id < 1L || name == null || name.trim().isEmpty() || name.length() > 256) {
                throw new IllegalArgumentException("Online customer region is invalid");
            }
            this.id = id;
            this.name = name;
        }

        public long id() { return id; }
        public String name() { return name; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Region)) return false;
            Region that = (Region) other;
            return id == that.id && name.equals(that.name);
        }

        @Override public int hashCode() {
            return 31 * Long.valueOf(id).hashCode() + name.hashCode();
        }

        @Override public String toString() {
            return "Region{id=" + id + ", name=" + name + "}";
        }
    }
}
