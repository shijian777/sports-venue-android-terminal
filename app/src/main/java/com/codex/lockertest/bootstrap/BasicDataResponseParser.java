package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.JsonNumber;

import java.util.Map;

public final class BasicDataResponseParser
        extends CentralControlResponseParser<BasicDataSnapshot> {
    private static final int MAX_LOCKER_COUNT = 1000000;

    @Override
    protected BasicDataSnapshot parseSuccessData(Map<String, Object> data) {
        exactKeys(data, "num", "surplus_num", "dynamic_verification_code");
        int total = canonicalInt(data.get("num"), 0, MAX_LOCKER_COUNT);
        int available = canonicalInt(
                data.get("surplus_num"), 0, MAX_LOCKER_COUNT);
        if (available > total) throw contract();
        Object dynamicValue = data.get("dynamic_verification_code");
        String dynamic = dynamicValue instanceof JsonNumber
                ? Integer.toString(canonicalInt(dynamicValue, 0, 1))
                : text(dynamicValue, 1, false);
        if (!"0".equals(dynamic) && !"1".equals(dynamic)) throw contract();
        return new BasicDataSnapshot(total, available, dynamic);
    }
}
