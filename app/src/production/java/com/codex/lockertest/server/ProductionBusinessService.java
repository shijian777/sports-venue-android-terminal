package com.codex.lockertest.server;

import com.codex.lockertest.bootstrap.DeviceRegistration;
import com.codex.lockertest.business.AdminLogin;
import com.codex.lockertest.business.AssignedCabinet;
import com.codex.lockertest.business.AuthenticatedUser;
import com.codex.lockertest.business.BoardAction;
import com.codex.lockertest.business.BusinessEndpoint;
import com.codex.lockertest.business.BusinessResponseParsers;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.ControlPanelPreview;
import com.codex.lockertest.business.EmptyBusinessResult;
import com.codex.lockertest.business.InstallerSession;
import com.codex.lockertest.business.PalmKeywordProvider;
import com.codex.lockertest.business.SessionToken;
import com.codex.lockertest.business.SignalRecord;
import com.codex.lockertest.business.UserInfoRequest;
import com.codex.lockertest.business.UserType;
import com.codex.lockertest.business.UsedCabinetList;

import java.util.Arrays;
import java.util.List;

/** Fixed-host signed implementation of the documented JSON business endpoints. */
public final class ProductionBusinessService implements BusinessService, AutoCloseable {
    private final Object keyLock = new Object();
    private final ServerTransport transport;
    private final ProtocolClock clock;
    private final BootstrapHeaders headers;
    private final char[] sysCode;
    private boolean closed;

    ProductionBusinessService(ServerTransport transport, ProtocolClock clock,
            DeviceRegistration registration, char[] callerSysCode) {
        if (transport == null || clock == null || registration == null
                || callerSysCode == null || callerSysCode.length == 0) {
            throw invalidConfiguration();
        }
        this.transport = transport;
        this.clock = clock;
        this.headers = BootstrapHeaders.bound(
                registration.merchantCode(), registration.deviceNo());
        this.sysCode = Arrays.copyOf(callerSysCode, callerSysCode.length);
        for (char character : this.sysCode) {
            if (character == '\0') {
                Arrays.fill(this.sysCode, '\0');
                throw invalidConfiguration();
            }
        }
    }

    /** Creates the fixed-host implementation with a fresh internally owned key copy. */
    public static ProductionBusinessService createLive(DeviceRegistration registration) {
        char[] key = ProductionSecretProvider.copyOrEmpty();
        try {
            return new ProductionBusinessService(
                    new HttpsUrlConnectionTransport(), new SystemProtocolClock(),
                    registration, key);
        } finally {
            if (key != null) Arrays.fill(key, '\0');
        }
    }

    @Override public ApiResult<AuthenticatedUser> authenticate(
            UserInfoRequest request, CallToken token) {
        if (request == null) return configurationFailure();
        StringBuilder fields = new StringBuilder(256);
        appendNumber(fields, "type", request.type());
        appendText(fields, "keyword", request.keyword());
        if (request.hasUserCode()) appendText(fields, "user_code", request.userCode());
        return execute(BusinessEndpoint.USER_INFO,
                CentralControlData.fromRawJson("{" + fields + "}"), null,
                token, new Parser<AuthenticatedUser>() {
                    @Override public ApiResult<AuthenticatedUser> parse(String body) {
                        return BusinessResponseParsers.userInfo(body);
                    }
                });
    }

    @Override public ApiResult<AuthenticatedUser> verifyMemberDynamicCode(
            final AuthenticatedUser user, String code, CallToken token) {
        if (user == null || user.userType() != UserType.ADMIN
                || !user.dynamicCodeRequired() || !validText(code, 256)) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(128);
        appendText(fields, "dynamic_code", code);
        appendNumber(fields, "uid", user.uid());
        ApiResult<SessionToken> result = execute(
                BusinessEndpoint.MEMBER_DYNAMIC_CODE, arrayObject(fields), user.token(),
                token, new Parser<SessionToken>() {
                    @Override public ApiResult<SessionToken> parse(String body) {
                        return BusinessResponseParsers.token(body);
                    }
                });
        if (!result.isSuccess()) return ApiResult.failure(result.failure());
        return ApiResult.success(new AuthenticatedUser(
                result.value(), UserType.ADMIN, 0, user.uid()));
    }

    @Override public ApiResult<ControlPanelPreview> controlPanelPreview(
            AuthenticatedUser user, int type, long areaId, int page, CallToken token) {
        if (user == null || !user.isCustomerReady()) return configurationFailure();
        return controlPanelPreview(user.token(), type, areaId, page, token);
    }

    @Override public ApiResult<ControlPanelPreview> controlPanelPreview(
            SessionToken session, int type, long areaId, int page, CallToken token) {
        if (session == null || type < 0 || type > 2 || areaId <= 0
                || page < 1 || page > 100) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(96);
        appendNumber(fields, "type", type);
        appendNumber(fields, "area_id", areaId);
        appendNumber(fields, "page", page);
        return execute(BusinessEndpoint.CONTROL_PANEL_PREVIEW,
                CentralControlData.fromRawJson("{" + fields + "}"), session,
                token, new Parser<ControlPanelPreview>() {
                    @Override public ApiResult<ControlPanelPreview> parse(String body) {
                        return BusinessResponseParsers.controlPanelPreview(body);
                    }
                });
    }

    @Override public ApiResult<UsedCabinetList> useCabinetList(
            AuthenticatedUser user, CallToken token) {
        if (user == null || !user.isCustomerReady()) return configurationFailure();
        return execute(BusinessEndpoint.USE_CABINET_LIST,
                CentralControlData.fromRawJson("[]"), user.token(), token,
                new Parser<UsedCabinetList>() {
                    @Override public ApiResult<UsedCabinetList> parse(String body) {
                        return BusinessResponseParsers.useCabinetList(body);
                    }
                });
    }

    @Override public ApiResult<AssignedCabinet> userBoard(
            AuthenticatedUser user, long areaId, long fcId, CallToken token) {
        if (user == null || !user.isCustomerReady() || areaId <= 0 || fcId < 0) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(64);
        appendNumber(fields, "area_id", areaId);
        appendNumber(fields, "fc_id", fcId);
        return execute(BusinessEndpoint.USER_BOARD,
                CentralControlData.fromRawJson("{" + fields + "}"), user.token(),
                token, new Parser<AssignedCabinet>() {
                    @Override public ApiResult<AssignedCabinet> parse(String body) {
                        return BusinessResponseParsers.userBoard(body);
                    }
                });
    }

    @Override public ApiResult<EmptyBusinessResult> openBoard(
            AuthenticatedUser user, BoardAction action, long fcId, CallToken token) {
        if (user == null || !user.isCustomerReady() || action == null || fcId <= 0) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(64);
        appendNumber(fields, "type", action.wireValue());
        appendNumber(fields, "fc_id", fcId);
        return emptyObjectMutation(BusinessEndpoint.OPEN_BOARD,
                CentralControlData.fromRawJson("{" + fields + "}"), user.token(), token);
    }

    @Override public ApiResult<EmptyBusinessResult> mobileSmsCode(
            String mobile, CallToken token) {
        if (!validText(mobile, 256)) return configurationFailure();
        StringBuilder fields = new StringBuilder(64);
        appendText(fields, "mobile", mobile);
        return emptyMutation(BusinessEndpoint.MOBILE_SMS_CODE,
                arrayObject(fields), null, token);
    }

    @Override public ApiResult<InstallerSession> installerLogin(
            String mobile, String useCode, CallToken token) {
        if (!validText(mobile, 256) || !validText(useCode, 256)) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(96);
        appendText(fields, "mobile", mobile);
        appendText(fields, "usecode", useCode);
        ApiResult<SessionToken> result = execute(BusinessEndpoint.RIG_LOGIN,
                arrayObject(fields), null, token, new Parser<SessionToken>() {
                    @Override public ApiResult<SessionToken> parse(String body) {
                        return BusinessResponseParsers.token(body);
                    }
                });
        return result.isSuccess()
                ? ApiResult.success(new InstallerSession(result.value()))
                : ApiResult.<InstallerSession>failure(result.failure());
    }

    @Override public ApiResult<EmptyBusinessResult> storeyCabinet(
            InstallerSession session, long fcId, int storey, int number, CallToken token) {
        if (session == null || fcId <= 0 || storey <= 0 || number <= 0) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(96);
        appendNumber(fields, "fc_id", fcId);
        appendNumber(fields, "storey", storey);
        appendNumber(fields, "number", number);
        return emptyMutation(BusinessEndpoint.STOREY_CABINET,
                arrayObject(fields), session.token(), token);
    }

    @Override public ApiResult<EmptyBusinessResult> installationVerify(
            SessionToken session, long areaId, long fcId, int type,
            List<SignalRecord> commands, CallToken token) {
        if (session == null || areaId <= 0 || fcId <= 0 || type < 0 || type > 1
                || commands == null || commands.isEmpty() || commands.size() > 256) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(commands.size() * 128 + 96);
        appendNumber(fields, "verify_type", 0);
        appendNumber(fields, "type", type);
        appendNumber(fields, "area_id", areaId);
        appendNumber(fields, "fc_id", fcId);
        comma(fields).append("\"command\":[");
        for (int index = 0; index < commands.size(); index++) {
            SignalRecord command = commands.get(index);
            if (command == null) return configurationFailure();
            if (index > 0) fields.append(',');
            StringBuilder item = new StringBuilder(128);
            appendText(item, "board_hex", command.boardHex());
            appendText(item, "send", command.send());
            appendText(item, "receive", command.receive());
            fields.append('{').append(item).append('}');
        }
        fields.append(']');
        return emptyMutation(BusinessEndpoint.INSTALLATION_VERIFY,
                arrayObject(fields), session, token);
    }

    @Override public ApiResult<AdminLogin> adminLogin(String username, String password,
            String dynamicCode, CallToken token) {
        if (!validText(username, 256) || !validText(password, 4096)
                || dynamicCode != null && !validText(dynamicCode, 256)) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(256);
        appendText(fields, "username", username);
        appendText(fields, "password", password);
        if (dynamicCode != null) appendText(fields, "dynamic_code", dynamicCode);
        return execute(BusinessEndpoint.MBR_LOGIN,
                CentralControlData.fromRawJson("{" + fields + "}"), null, token,
                new Parser<AdminLogin>() {
                    @Override public ApiResult<AdminLogin> parse(String body) {
                        return BusinessResponseParsers.adminLogin(body);
                    }
                });
    }

    @Override public ApiResult<EmptyBusinessResult> quickClearCabinet(
            SessionToken session, long areaId, List<SignalRecord> commands,
            CallToken token) {
        if (session == null || areaId <= 0 || commands == null || commands.isEmpty()
                || commands.size() > 256) {
            return configurationFailure();
        }
        StringBuilder fields = new StringBuilder(commands.size() * 128 + 64);
        appendNumber(fields, "area_id", areaId);
        comma(fields).append("\"command\":[");
        for (int index = 0; index < commands.size(); index++) {
            SignalRecord command = commands.get(index);
            if (command == null) return configurationFailure();
            if (index > 0) fields.append(',');
            StringBuilder item = new StringBuilder(128);
            appendText(item, "board_hex", command.boardHex());
            appendText(item, "send", command.send());
            appendText(item, "receive", command.receive());
            fields.append('{').append(item).append('}');
        }
        fields.append(']');
        return emptyMutation(BusinessEndpoint.QUICK_CLEAR_CABINET,
                arrayObject(fields), session, token);
    }

    @Override public ApiResult<EmptyBusinessResult> bindUserHand(
            AuthenticatedUser user, PalmKeywordProvider provider, CallToken token) {
        if (user == null || !user.isCustomerReady() || provider == null
                || provider.unavailableReason() != null) {
            return configurationFailure();
        }
        String handPersonId;
        try {
            handPersonId = provider.keyword();
        } catch (RuntimeException unavailable) {
            return configurationFailure();
        }
        if (!validText(handPersonId, 4096)) return configurationFailure();
        StringBuilder fields = new StringBuilder(128);
        appendText(fields, "hand_person_id", handPersonId);
        return emptyObjectMutation(BusinessEndpoint.BIND_USER_HAND,
                arrayObject(fields), user.token(), token);
    }

    @Override public void close() {
        synchronized (keyLock) {
            if (closed) return;
            closed = true;
            Arrays.fill(sysCode, '\0');
        }
    }

    private ApiResult<EmptyBusinessResult> emptyMutation(BusinessEndpoint endpoint,
            CentralControlData data, SessionToken session, CallToken token) {
        return execute(endpoint, data, session, token,
                new Parser<EmptyBusinessResult>() {
                    @Override public ApiResult<EmptyBusinessResult> parse(String body) {
                        return BusinessResponseParsers.empty(body);
                    }
                });
    }

    private ApiResult<EmptyBusinessResult> emptyObjectMutation(BusinessEndpoint endpoint,
            CentralControlData data, SessionToken session, CallToken token) {
        return execute(endpoint, data, session, token,
                new Parser<EmptyBusinessResult>() {
                    @Override public ApiResult<EmptyBusinessResult> parse(String body) {
                        return BusinessResponseParsers.emptyObject(body);
                    }
                });
    }

    private <T> ApiResult<T> execute(BusinessEndpoint endpoint, CentralControlData data,
            SessionToken session, CallToken token, Parser<T> parser) {
        if (endpoint == null || data == null || token == null || parser == null) {
            return configurationFailure();
        }
        ApiResult<T> stopped = stopped(token);
        if (stopped != null) return stopped;
        char[] requestKey = copyKeyUnlessClosed();
        if (requestKey == null) return configurationFailure();
        try {
            ProtocolTimestamp timestamp;
            try {
                timestamp = clock.now();
            } catch (ProtocolTimestamp.ClockException invalidClock) {
                return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CLOCK_INVALID));
            }
            CentralControlSigner.Result signature = CentralControlSigner.sign(
                    data, timestamp, requestKey);
            String body = CentralControlEnvelopeFactory.create(
                    signature, timestamp, session == null ? null : session.value(), data);
            stopped = stopped(token);
            if (stopped != null) return stopped;
            ApiResult<TransportResponse> response = transport.execute(
                    new TransportRequest(endpoint, body, headers, token));
            if (!response.isSuccess()) return ApiResult.failure(response.failure());
            stopped = stopped(token);
            if (stopped != null) return stopped;
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

    private static CentralControlData arrayObject(StringBuilder fields) {
        return CentralControlData.fromRawJson("[{" + fields + "}]");
    }

    private static void appendText(StringBuilder target, String name, String value) {
        comma(target).append(CentralControlData.quoteJsonString(name)).append(':')
                .append(CentralControlData.quoteJsonString(value));
    }

    private static void appendNumber(StringBuilder target, String name, long value) {
        comma(target).append(CentralControlData.quoteJsonString(name)).append(':').append(value);
    }

    private static StringBuilder comma(StringBuilder target) {
        if (target.length() > 0) target.append(',');
        return target;
    }

    private static boolean validText(String value, int maximum) {
        if (value == null || value.length() == 0 || value.length() > maximum) return false;
        boolean nonSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) return false;
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                nonSpace = true;
            }
        }
        return nonSpace;
    }

    private static <T> ApiResult<T> stopped(CallToken token) {
        if (token == null || !token.isCancelled()) return null;
        return ApiResult.failure(ServerFailure.of(token.reason() == CallToken.Reason.TIMEOUT
                ? ServerFailure.Kind.TIMEOUT : ServerFailure.Kind.CANCELLED));
    }

    private static <T> ApiResult<T> configurationFailure() {
        return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CONFIGURATION));
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Invalid business service configuration");
    }

    private interface Parser<T> { ApiResult<T> parse(String body); }
}
