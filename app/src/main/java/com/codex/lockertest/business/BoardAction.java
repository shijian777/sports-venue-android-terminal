package com.codex.lockertest.business;

public enum BoardAction {
    OPEN(1), RETURN(2);

    private final int wireValue;

    BoardAction(int wireValue) { this.wireValue = wireValue; }
    public int wireValue() { return wireValue; }
}
