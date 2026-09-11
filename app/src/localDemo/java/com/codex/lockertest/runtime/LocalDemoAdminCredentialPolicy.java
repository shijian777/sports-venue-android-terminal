package com.codex.lockertest.runtime;

import java.util.Arrays;

/** Local-demo administrator PIN comparison without storing candidate input. */
public final class LocalDemoAdminCredentialPolicy implements AdminCredentialPolicy {

    @Override
    public int requiredLength() {
        return 6;
    }

    @Override
    public boolean matches(char[] candidate) {
        char[] expected = DemoCredentials.ADMIN_PIN.toCharArray();
        try {
            if (candidate == null) {
                return false;
            }
            int diff = candidate.length ^ expected.length;
            for (int index = 0; index < Math.min(candidate.length, expected.length); index++) {
                diff |= candidate[index] ^ expected[index];
            }
            return diff == 0;
        } finally {
            Arrays.fill(expected, '\0');
        }
    }

    @Override
    public String unavailableMessage() {
        return null;
    }
}
