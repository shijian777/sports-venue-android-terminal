package com.codex.lockertest.server;

/** Builds the compact request envelope in the protocol's fixed field order. */
public final class CentralControlEnvelopeFactory {
    private CentralControlEnvelopeFactory() { }

    public static String create(
            CentralControlSigner.Result signature,
            ProtocolTimestamp timestamp,
            String token,
            CentralControlData data) {
        if (signature == null || timestamp == null || data == null) {
            throw new IllegalArgumentException("Invalid envelope input");
        }
        StringBuilder json = new StringBuilder(data.wireJson().length() + 128);
        json.append("{\"scode\":")
                .append(CentralControlData.quoteJsonString(signature.scode()))
                .append(",\"sign\":")
                .append(CentralControlData.quoteJsonString(signature.sign()))
                .append(",\"timestamp\":")
                .append(timestamp.epochSeconds());
        if (token != null) {
            json.append(",\"token\":")
                    .append(CentralControlData.quoteJsonString(token));
        }
        return json.append(",\"data\":").append(data.wireJson()).append('}').toString();
    }
}
