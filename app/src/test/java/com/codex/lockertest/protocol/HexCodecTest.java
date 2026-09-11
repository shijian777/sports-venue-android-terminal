package com.codex.lockertest.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HexCodecTest {
    @Test
    public void decodeAcceptsCompactLowercaseHex() {
        assertArrayEquals(
                new byte[]{(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                HexCodec.decode("8a0101119b"));
    }

    @Test
    public void decodeAcceptsWhitespaceBetweenBytes() {
        assertArrayEquals(
                new byte[]{(byte) 0x8A, 0x01, 0x01, 0x11, (byte) 0x9B},
                HexCodec.decode(" 8A 01\n01\t11 9B "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsOddLength() {
        HexCodec.decode("8A0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void decodeRejectsNonHexCharacter() {
        HexCodec.decode("8A01GG");
    }

    @Test
    public void formatUsesUppercaseSpacedBytes() {
        assertEquals(
                "8A 01 01 00 8A",
                HexCodec.format(new byte[]{(byte) 0x8A, 0x01, 0x01, 0x00, (byte) 0x8A}));
    }
}
