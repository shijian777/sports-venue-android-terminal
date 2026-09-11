package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.CallToken;

/** The three read-only calls in the provisional terminal bootstrap contract. */
public interface BootstrapService {
    ApiResult<DeviceRegistration> checkDevice(String deviceSerial, CallToken token);

    ApiResult<BaseSettingSnapshot> baseSetting(
            DeviceRegistration registration, CallToken token);

    ApiResult<BasicDataSnapshot> basicData(
            DeviceRegistration registration, CallToken token);
}
