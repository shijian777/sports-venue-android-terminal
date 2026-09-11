package com.codex.lockertest.server;

/** Synchronous boundary used by the bootstrap worker. */
public interface ServerTransport {
    ApiResult<TransportResponse> execute(TransportRequest request);
}
