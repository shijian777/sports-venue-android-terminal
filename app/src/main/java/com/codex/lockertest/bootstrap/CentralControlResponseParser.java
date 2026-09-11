package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.ApiResult;
import com.codex.lockertest.server.JsonContractException;
import com.codex.lockertest.server.JsonNumber;
import com.codex.lockertest.server.ServerFailure;
import com.codex.lockertest.server.StrictJson;

import java.util.List;
import java.util.Map;

public abstract class CentralControlResponseParser<T> {
    private static final int SUCCESS_CODE = 200;
    private static final int MAX_MESSAGE_LENGTH = 512;

    public final ApiResult<T> parse(String responseBody) {
        Object parsed;
        try {
            parsed = StrictJson.parse(responseBody);
        } catch (JsonContractException invalidJson) {
            return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.INVALID_JSON));
        }

        try {
            Map<String, Object> root = object(parsed);
            int code = canonicalInt(root.get("code"), Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (code != SUCCESS_CODE) {
                return ApiResult.failure(
                        ServerFailure.of(ServerFailure.Kind.REMOTE_REJECTED));
            }
            exactKeys(root, "code", "message", "data");
            text(root.get("message"), MAX_MESSAGE_LENGTH, true);
            return ApiResult.success(parseSuccessData(object(root.get("data"))));
        } catch (ContractViolation | JsonContractException invalidContract) {
            return ApiResult.failure(ServerFailure.of(ServerFailure.Kind.CONTRACT));
        }
    }

    protected abstract T parseSuccessData(Map<String, Object> data);

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw contract();
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    protected static List<Object> array(Object value) {
        if (!(value instanceof List)) throw contract();
        return (List<Object>) value;
    }

    protected static int canonicalInt(Object value, int minimum, int maximum) {
        if (!(value instanceof JsonNumber)) throw contract();
        return ((JsonNumber) value).toInt(minimum, maximum);
    }

    protected static String text(Object value, int maximumLength, boolean allowEmpty) {
        return text(value, maximumLength, allowEmpty, false);
    }

    protected static String formattedText(
            Object value, int maximumLength, boolean allowEmpty) {
        return text(value, maximumLength, allowEmpty, true);
    }

    private static String text(Object value, int maximumLength,
            boolean allowEmpty, boolean allowFormattingControls) {
        if (!(value instanceof String)) throw contract();
        String text = (String) value;
        if ((!allowEmpty && text.length() == 0) || text.length() > maximumLength) {
            throw contract();
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character)
                    && !(allowFormattingControls
                    && (character == '\n' || character == '\r' || character == '\t'))) {
                throw contract();
            }
        }
        return text;
    }

    protected static void exactKeys(Map<String, Object> object, String... expected) {
        if (object.size() != expected.length) throw contract();
        for (String key : expected) {
            if (!object.containsKey(key)) throw contract();
        }
    }

    protected static ContractViolation contract() {
        return new ContractViolation();
    }

    protected static final class ContractViolation extends RuntimeException {
        private ContractViolation() { }
    }
}
