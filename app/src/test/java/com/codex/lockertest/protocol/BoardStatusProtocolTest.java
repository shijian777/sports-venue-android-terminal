package com.codex.lockertest.protocol;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

public final class BoardStatusProtocolTest {
    @Test
    public void eachSupportedBoardBuildsTheManufacturerStatusQueryLiteral() {
        assertArrayEquals(bytes(0x80, 0x01, 0x01, 0x33, 0xB3),
                BoardStatusProtocol.query(1));
        assertArrayEquals(bytes(0x80, 0x02, 0x01, 0x33, 0xB0),
                BoardStatusProtocol.query(2));
        assertArrayEquals(bytes(0x80, 0x03, 0x01, 0x33, 0xB1),
                BoardStatusProtocol.query(3));
    }

    @Test
    public void everyQueryCallReturnsFreshBytes() {
        byte[] first = BoardStatusProtocol.query(1);
        byte[] second = BoardStatusProtocol.query(1);

        assertNotSame(first, second);
        first[0] = 0;
        assertArrayEquals(bytes(0x80, 0x01, 0x01, 0x33, 0xB3), second);
        assertArrayEquals(bytes(0x80, 0x01, 0x01, 0x33, 0xB3),
                BoardStatusProtocol.query(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void addressZeroCannotCreateAQuery() {
        BoardStatusProtocol.query(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void addressFourCannotCreateAQuery() {
        BoardStatusProtocol.query(4);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
