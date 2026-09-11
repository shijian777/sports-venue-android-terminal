package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.BaseSettingResponseParser;
import com.codex.lockertest.bootstrap.BaseSettingSnapshot;
import com.codex.lockertest.bootstrap.BasicDataResponseParser;
import com.codex.lockertest.bootstrap.BasicDataSnapshot;
import com.codex.lockertest.bootstrap.BootstrapService;
import com.codex.lockertest.bootstrap.CentralControlResponseParser;
import com.codex.lockertest.bootstrap.CheckDeviceResponseParser;
import com.codex.lockertest.bootstrap.DeviceRegistration;

import java.util.Arrays;

/** Builds the three fixed-host, read-only production bootstrap requests. */
public final class ProductionBootstrapService implements BootstrapService, AutoCloseable {
    private static final int MIN_SERIAL_LENGTH = 8;
    private static final int MAX_SERIAL_LENGTH = 128;

    private final Object keyLock = new Object();
    private final ServerTransport transport;
    private final ProtocolClock clock;
    private final char[] sysCode;
    private boolean closed;

    ProductionBootstrapService(
            ServerTransport transport, ProtocolClock clock, char[] callerSysCode) {
        this.sysCode = copyAndValidateConfiguration(
                transport, clock, callerSysCode);
        this.transport = transport;
        this.clock = clock;
    }

    /** Creates the fixed-host live service without exposing transport injection. */
    public static ProductionBootstrapService createLive(char[] callerSysCode) {
        return new ProductionBootstrapService(
                new HttpsUrlConnectionTransport(),
                new SystemProtocolClock(),
                callerSysCode);
    }

    @Override
    public ApiResult<DeviceRegistration> checkDevice(
            String deviceSerial, CallToken token) {
        if (!validSerial(deviceSerial) || token == null) {
            return configurationFailure();
        }
        ApiResult<DeviceRegistration> stopped = stopped(token);
        if (stopped != null) return stopped;

        CentralControlData data;
        try {
            data = CentralControlData.fromRawJson(
                    "{\"device_serial\":"
                            + CentralControlData.quoteJsonString(deviceSerial) + "}");
        } catch (IllegalArgumentException invalidData) {
            return configurationFailure();
        }
        return execute(
                ApiEndpoint.CHECK_DEVICE,
                data,
                BootstrapHeaders.none(),
                token,
                new CheckDeviceResponseParser());
    }

    @Override
    public ApiResult<BaseSettingSnapshot> baseSetting(
            DeviceRegistration registration, CallToken token) {
        BootstrapHeaders headers = validatedHeaders(registration, token);
        if (headers == null) return configurationFailure();
        ApiResult<BaseSettingSnapshot> stopped = stopped(token);
        if (stopped != null) return stopped;
        return execute(
                ApiEndpoint.BASE_SETTING,
                CentralControlData.fromRawJson("[]"),
                headers,
                token,
                new BaseSettingResponseParser());
    }

    @Override
    public ApiResult<BasicDataSnapshot> basicData(
            DeviceRegistration registration, CallToken token) {
        BootstrapHeaders headers = validatedHeaders(registration, token);
        if (headers == null) return configurationFailure();
        ApiResult<BasicDataSnapshot> stopped = stopped(token);
        if (stopped != null) return stopped;
        return execute(
                ApiEndpoint.BASIC_DATA,
                CentralControlData.fromRawJson("[]"),
                headers,
                token,
                new BasicDataResponseParser());
    }

    @Override
    public void close() {
        synchronized (keyLock) {
            if (closed) return;
            closed = true;
            Arrays.fill(sysCode, '\0');
        }
    }

    private <T> ApiResult<T> execute(
            ApiEndpoint endpoint,
            CentralControlData data,
            BootstrapHeaders headers,
            CallToken token,
            CentralControlResponseParser<T> parser) {
        char[] requestKey = copyKeyUnlessClosed();
        if (requestKey == null) return configurationFailure();
        try {
            ApiResult<T> stopped = stopped(token);
            if (stopped != null) return stopped;

            ProtocolTimestamp timestamp;
            try {
                timestamp = clock.now();
            } catch (ProtocolTimestamp.ClockException invalidClock) {
                return ApiResult.failure(
                        ServerFailure.of(ServerFailure.Kind.CLOCK_INVALID));
            }
            CentralControlSigner.Result signature = CentralControlSigner.sign(
                    data, timestamp, requestKey);
            String body = CentralControlEnvelopeFactory.create(
                    signature, timestamp, null, data);

            stopped = stopped(token);
            if (stopped != null) return stopped;
            ApiResult<TransportResponse> response = transport.execute(
                    new TransportRequest(endpoint, body, headers, token));
            if (!response.isSuccess()) {
                return ApiResult.failure(response.failure());
            }
            return parser.parse(response.value().body());
        } finally {
            Arrays.fill(requestKey, '\0');
        }
    }

    private char[] copyKeyUnlessClosed() {
        synchronized (keyLock) {
            return closed ? null : Arrays.copyOf(sysCode, sysCode.length);
        }
    }

    private static BootstrapHeaders validatedHeaders(
            DeviceRegistration registration, CallToken token) {
        if (registration == null || token == null) return null;
        try {
            return BootstrapHeaders.bound(
                    registration.merchantCode(), registration.deviceNo());
        } catch (IllegalArgumentException invalidHeaders) {
            return null;
        }
    }

    private static boolean validSerial(String value) {
        if (value == null || value.length() < MIN_SERIAL_LENGTH
                || value.length() > MAX_SERIAL_LENGTH) {
            return false;
        }
        boolean nonSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) return false;
            if (character != ' ') nonSpace = true;
        }
        return nonSpace;
    }

    private static char[] copyAndValidateConfiguration(
            ServerTransport transport, ProtocolClock clock, char[] callerSysCode) {
        if (callerSysCode == null) throw invalidConfiguration();
        char[] owned = Arrays.copyOf(callerSysCode, callerSysCode.length);
        boolean accepted = false;
        try {
            if (transport == null || clock == null || owned.length == 0) {
                throw invalidConfiguration();
            }
            for (char character : owned) {
                if (character == '\0') throw invalidConfiguration();
            }
            accepted = true;
            return owned;
        } finally {
            if (!accepted) Arrays.fill(owned, '\0');
        }
    }

    private static <T> ApiResult<T> stopped(CallToken token) {
        if (token == null || !token.isCancelled()) return null;
        ServerFailure.Kind kind = token.reason() == CallToken.Reason.TIMEOUT
                ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED;
        return ApiResult.failure(ServerFailure.of(kind));
    }

    private static <T> ApiResult<T> configurationFailure() {
        return ApiResult.failure(
                ServerFailure.of(ServerFailure.Kind.CONFIGURATION));
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Invalid bootstrap service configuration");
    }
}
