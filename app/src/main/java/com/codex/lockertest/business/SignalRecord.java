package com.codex.lockertest.business;

/** One documented board send/receive record for setup mutations. */
public final class SignalRecord {
    private final String boardHex;
    private final String send;
    private final String receive;

    public SignalRecord(String boardHex, String send, String receive) {
        this.boardHex = BusinessValues.text(boardHex, "Board hex", 64);
        this.send = BusinessValues.text(send, "Sent signal", 512);
        this.receive = BusinessValues.text(receive, "Received signal", 512);
    }

    public String boardHex() { return boardHex; }
    public String send() { return send; }
    public String receive() { return receive; }
}
