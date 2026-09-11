package com.codex.lockertest.business;

/** Typed request for the five documented userInfo identity modes. */
public final class UserInfoRequest {
    private static final int MAX_CREDENTIAL_LENGTH = 4096;
    private static final int MAX_USER_CODE_LENGTH = 256;

    private final int type;
    private final String keyword;
    private final String userCode;

    private UserInfoRequest(int type, String keyword, String userCode) {
        this.type = type;
        this.keyword = BusinessValues.text(
                keyword, "Identity keyword", MAX_CREDENTIAL_LENGTH);
        this.userCode = userCode;
    }

    public static UserInfoRequest phone(String mobile, String userCode) {
        return new UserInfoRequest(1, mobile,
                BusinessValues.text(userCode, "User code", MAX_USER_CODE_LENGTH));
    }

    public static UserInfoRequest qr(String value) {
        return new UserInfoRequest(2, value, null);
    }

    public static UserInfoRequest palm(PalmKeywordProvider provider) {
        if (provider == null) throw new IllegalArgumentException("Palm keyword provider is required");
        return new UserInfoRequest(3, provider.keyword(), null);
    }

    public static UserInfoRequest card(String value) {
        return new UserInfoRequest(4, value, null);
    }

    public static UserInfoRequest faceImage(String imageIdentifier) {
        return new UserInfoRequest(5, imageIdentifier, null);
    }

    public int type() { return type; }
    public String keyword() { return keyword; }
    public boolean hasUserCode() { return userCode != null; }
    public String userCode() { return userCode; }

    @Override public String toString() {
        return "UserInfoRequest{type=" + type + ", credential=<redacted>}";
    }
}
