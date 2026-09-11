package com.codex.lockertest.business;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.JsonContractException;
import com.codex.lockertest.server.JsonNumber;
import com.codex.lockertest.server.ServerFailure;
import com.codex.lockertest.server.StrictJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict endpoint-specific decoders for the supplied DOCX response fixtures. */
public final class BusinessResponseParsers {
    private static final int MAX_MESSAGE = 512;

    private BusinessResponseParsers() { }

    public static ApiResult<AuthenticatedUser> userInfo(String body) {
        return parse(body, new Decoder<AuthenticatedUser>() {
            @Override public AuthenticatedUser decode(Object data) {
                Map<String, Object> object = object(data);
                boolean tableName = object.containsKey("user_type");
                boolean exampleName = object.containsKey("usr_type");
                if (tableName == exampleName || object.size() != 4
                        || !object.containsKey("token")
                        || !object.containsKey("uid")) {
                    throw contract();
                }
                int userType = integer(object.get(
                        tableName ? "user_type" : "usr_type"), 0, 1);
                // The server renamed this field for both roles. Members also
                // accept the legacy spelling; ambiguous responses fail closed.
                boolean administrator = userType == UserType.ADMIN.wireValue();
                boolean dynamicFlag = object.containsKey("dynamic_verification_code");
                boolean legacyFlag = object.containsKey("locker_check_status");
                if (dynamicFlag == legacyFlag || administrator && !dynamicFlag) {
                    throw contract();
                }
                String checkField = dynamicFlag
                        ? "dynamic_verification_code" : "locker_check_status";
                int checkStatus = (int) canonicalNumberOrString(object.get(checkField), 0, 1);
                return new AuthenticatedUser(
                        SessionToken.of(text(object.get("token"), 4096, false)),
                        UserType.fromWire(userType),
                        checkStatus,
                        number(object.get("uid"), 0, Long.MAX_VALUE));
            }
        }, true);
    }

    public static ApiResult<SessionToken> token(String body) {
        return parse(body, new Decoder<SessionToken>() {
            @Override public SessionToken decode(Object data) {
                Map<String, Object> object = exactObject(data, "token");
                return SessionToken.of(text(object.get("token"), 4096, false));
            }
        });
    }

    public static ApiResult<ControlPanelPreview> controlPanelPreview(String body) {
        return parse(body, new Decoder<ControlPanelPreview>() {
            @Override public ControlPanelPreview decode(Object data) {
                Map<String, Object> object = object(data);
                if (!object.containsKey("page") || !object.containsKey("all_open_command")) {
                    throw contract();
                }
                List<Object> rawPages = array(object.get("page"));
                if (rawPages.isEmpty()) throw contract();
                ArrayList<Integer> pages = new ArrayList<>(rawPages.size());
                for (Object rawPage : rawPages) {
                    int page = previewPage(rawPage);
                    if (pages.contains(page)) throw contract();
                    pages.add(page);
                }

                List<Object> rawCommands = array(object.get("all_open_command"));
                ArrayList<ControlPanelPreview.AllOpenCommand> commands =
                        new ArrayList<>(rawCommands.size());
                for (Object rawCommand : rawCommands) {
                    Map<String, Object> command = exactObject(
                            rawCommand, "board_hex", "command");
                    commands.add(new ControlPanelPreview.AllOpenCommand(
                            text(command.get("board_hex"), 64, false),
                            text(command.get("command"), 512, false)));
                }

                LinkedHashMap<Integer, List<ControlPanelPreview.Cabinet>> layers =
                        new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : object.entrySet()) {
                    String key = entry.getKey();
                    if ("page".equals(key) || "all_open_command".equals(key)) continue;
                    int layer = canonicalLayer(key);
                    if (layers.containsKey(layer)) throw contract();
                    List<Object> rawCabinets = array(entry.getValue());
                    ArrayList<ControlPanelPreview.Cabinet> cabinets =
                            new ArrayList<>(rawCabinets.size());
                    for (Object rawCabinet : rawCabinets) cabinets.add(cabinet(rawCabinet));
                    layers.put(layer, cabinets);
                }
                if (layers.isEmpty()) throw contract();
                return new ControlPanelPreview(pages, commands, layers);
            }
        });
    }

    public static ApiResult<UsedCabinetList> useCabinetList(String body) {
        return parse(body, new Decoder<UsedCabinetList>() {
            @Override public UsedCabinetList decode(Object data) {
                Map<String, Object> object = exactObject(
                        data, "user_name", "mobile", "list");
                List<Object> rawList = array(object.get("list"));
                ArrayList<UsedCabinet> cabinets = new ArrayList<>(rawList.size());
                for (Object rawCabinet : rawList) {
                    Map<String, Object> cabinet = object(rawCabinet);
                    if (cabinet.containsKey("use_notice")) {
                        exactKeys(cabinet, "record_id", "fc_id", "name", "start_use",
                                "use_time", "use_notice");
                    } else {
                        // Live API omits this display-only field; IDs remain mandatory.
                        exactKeys(cabinet, "record_id", "fc_id", "name", "start_use", "use_time");
                    }
                    cabinets.add(new UsedCabinet(
                            canonicalNumberOrString(
                                    cabinet.get("record_id"), 1, Long.MAX_VALUE),
                            canonicalNumberOrString(
                                    cabinet.get("fc_id"), 1, Long.MAX_VALUE),
                            text(cabinet.get("name"), 256, false),
                            text(cabinet.get("start_use"), 128, false),
                            useTimeMinutes(cabinet.get("use_time")),
                            cabinet.containsKey("use_notice")
                                    ? formattedText(cabinet.get("use_notice"), 16384) : ""));
                }
                return new UsedCabinetList(
                        text(object.get("user_name"), 256, false),
                        text(object.get("mobile"), 256, false), cabinets);
            }
        });
    }

    public static ApiResult<AssignedCabinet> userBoard(String body) {
        return parse(body, new Decoder<AssignedCabinet>() {
            @Override public AssignedCabinet decode(Object data) {
                Map<String, Object> object = object(data);
                if (object.containsKey("order_id")) {
                    exactKeys(object, "fc_id", "order_id");
                    number(object.get("order_id"), 0, Long.MAX_VALUE);
                } else {
                    exactKeys(object, "fc_id");
                }
                return new AssignedCabinet(number(
                        object.get("fc_id"), 1, Long.MAX_VALUE));
            }
        }, false, true);
    }

    public static ApiResult<EmptyBusinessResult> empty(String body) {
        return parse(body, new Decoder<EmptyBusinessResult>() {
            @Override public EmptyBusinessResult decode(Object data) {
                if (!array(data).isEmpty()) throw contract();
                return EmptyBusinessResult.INSTANCE;
            }
        });
    }

    public static ApiResult<EmptyBusinessResult> emptyObject(String body) {
        return parse(body, new Decoder<EmptyBusinessResult>() {
            @Override public EmptyBusinessResult decode(Object data) {
                exactObject(data);
                return EmptyBusinessResult.INSTANCE;
            }
        });
    }

    public static ApiResult<AdminLogin> adminLogin(String body) {
        return parse(body, new Decoder<AdminLogin>() {
            @Override public AdminLogin decode(Object data) {
                Map<String, Object> object = exactObject(data,
                        "token", "venue_name", "device_name", "device_serial",
                        "area_name", "username", "name");
                return new AdminLogin(
                        SessionToken.of(text(object.get("token"), 4096, false)),
                        text(object.get("venue_name"), 256, false),
                        text(object.get("device_name"), 256, false),
                        text(object.get("device_serial"), 256, false),
                        text(object.get("area_name"), 256, false),
                        text(object.get("username"), 256, false),
                        text(object.get("name"), 256, false));
            }
        });
    }

    private static ControlPanelPreview.Cabinet cabinet(Object value) {
        Map<String, Object> object = exactObject(value,
                "fc_id", "channel_id", "cabinet_label", "status", "area_id",
                "board_hex", "channel_no", "open_command", "check_status");
        return new ControlPanelPreview.Cabinet(
                canonicalNumberOrString(object.get("fc_id"), 1, Long.MAX_VALUE),
                canonicalNumberOrString(object.get("channel_id"), 1, Long.MAX_VALUE),
                text(object.get("cabinet_label"), 256, false),
                (int) canonicalNumberOrString(object.get("status"), 0, 4),
                canonicalNumberOrString(object.get("area_id"), 1, Long.MAX_VALUE),
                text(object.get("board_hex"), 64, false),
                text(object.get("channel_no"), 64, false),
                text(object.get("open_command"), 512, false),
                integer(object.get("check_status"), 0, 4));
    }

    private static <T> ApiResult<T> parse(String body, Decoder<T> decoder) {
        return parse(body, decoder, false);
    }

    private static <T> ApiResult<T> parse(
            String body, Decoder<T> decoder, boolean userInfoResponse) {
        return parse(body, decoder, userInfoResponse, false);
    }

    private static <T> ApiResult<T> parse(
            String body, Decoder<T> decoder, boolean userInfoResponse,
            boolean allowAllocationMessage) {
        Object parsed;
        try {
            parsed = StrictJson.parse(body);
        } catch (JsonContractException invalidJson) {
            return failure(ServerFailure.Kind.INVALID_JSON);
        }
        try {
            Map<String, Object> root = object(parsed);
            int code = integer(root.get("code"), Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (code != 200) {
                if (allowAllocationMessage && code == 400
                        && root.get("message") instanceof String
                        && (root.size() == 2 || (root.size() == 3 && root.containsKey("data")))) {
                    return ApiResult.failure(ServerFailure.businessRejection((String) root.get("message")));
                }
                // Classify only this verified userInfo rejection; never retain server text/data.
                return failure(userInfoResponse && code == 400
                        && "无进场记录".equals(root.get("message"))
                        ? ServerFailure.Kind.NO_ENTRY_RECORD : ServerFailure.Kind.REMOTE_REJECTED);
            }
            exactKeys(root, "code", "message", "data");
            text(root.get("message"), MAX_MESSAGE, true);
            return ApiResult.success(decoder.decode(root.get("data")));
        } catch (ContractViolation | JsonContractException | IllegalArgumentException invalid) {
            return failure(ServerFailure.Kind.CONTRACT);
        }
    }

    private static int canonicalLayer(String value) {
        if (value == null || value.length() == 0 || value.length() > 3
                || value.length() > 1 && value.charAt(0) == '0') {
            throw contract();
        }
        int result = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') throw contract();
            result = result * 10 + character - '0';
        }
        if (result > 100) throw contract();
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw contract();
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> exactObject(Object value, String... keys) {
        Map<String, Object> object = object(value);
        exactKeys(object, keys);
        return object;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        if (!(value instanceof List)) throw contract();
        return (List<Object>) value;
    }

    private static void exactKeys(Map<String, Object> object, String... keys) {
        if (object.size() != keys.length) throw contract();
        for (String key : keys) if (!object.containsKey(key)) throw contract();
    }

    private static int integer(Object value, int minimum, int maximum) {
        if (!(value instanceof JsonNumber)) throw contract();
        return ((JsonNumber) value).toInt(minimum, maximum);
    }

    private static long number(Object value, long minimum, long maximum) {
        if (!(value instanceof JsonNumber)) throw contract();
        return ((JsonNumber) value).toLong(minimum, maximum);
    }

    /** Only explicitly verified endpoint fields accept canonical decimal strings. */
    private static long canonicalNumberOrString(
            Object value, long minimum, long maximum) {
        if (value instanceof JsonNumber) return number(value, minimum, maximum);
        if (!(value instanceof String)) throw contract();
        String raw = (String) value;
        if (raw.length() == 0 || raw.length() > 19
                || raw.length() > 1 && raw.charAt(0) == '0') {
            throw contract();
        }
        for (int index = 0; index < raw.length(); index++) {
            char character = raw.charAt(index);
            if (character < '0' || character > '9') throw contract();
        }
        long parsed = Long.parseLong(raw);
        if (parsed < minimum || parsed > maximum) throw contract();
        return parsed;
    }

    private static int previewPage(Object value) {
        return (int) canonicalNumberOrString(value, 1, 100);
    }

    private static int useTimeMinutes(Object value) {
        if (value instanceof JsonNumber) return integer(value, 0, Integer.MAX_VALUE);
        if (!(value instanceof String)) throw contract();
        String raw = (String) value;
        if (raw.endsWith("分钟")) raw = raw.substring(0, raw.length() - 2);
        return (int) canonicalNumberOrString(raw, 0, Integer.MAX_VALUE);
    }

    private static String text(Object value, int maximum, boolean allowEmpty) {
        if (!(value instanceof String)) throw contract();
        String text = (String) value;
        if (!allowEmpty && text.length() == 0 || text.length() > maximum) throw contract();
        for (int index = 0; index < text.length(); index++) {
            if (Character.isISOControl(text.charAt(index))) throw contract();
        }
        return text;
    }

    private static String formattedText(Object value, int maximum) {
        if (!(value instanceof String)) throw contract();
        String text = (String) value;
        if (text.length() > maximum) throw contract();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t') {
                throw contract();
            }
        }
        return text;
    }

    private static <T> ApiResult<T> failure(ServerFailure.Kind kind) {
        return ApiResult.failure(ServerFailure.of(kind));
    }

    private static ContractViolation contract() { return new ContractViolation(); }

    private interface Decoder<T> { T decode(Object data); }
    private static final class ContractViolation extends RuntimeException { }
}
