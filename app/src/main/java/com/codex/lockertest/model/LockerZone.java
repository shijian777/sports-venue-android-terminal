package com.codex.lockertest.model;

public enum LockerZone {
    A(1),
    B(2),
    C(3);

    private final int boardAddress;

    LockerZone(int boardAddress) {
        this.boardAddress = boardAddress;
    }

    public int boardAddress() {
        return boardAddress;
    }
}
