package com.codex.lockertest.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NumericKeypadModelTest {
    @Test
    public void acceptsDigitsOnlyUntilTheConfiguredLimit() {
        NumericKeypadModel model = new NumericKeypadModel(4, false);

        assertTrue(model.pressDigit('1'));
        assertTrue(model.pressDigit('2'));
        assertTrue(model.pressDigit('3'));
        assertTrue(model.pressDigit('4'));
        assertFalse(model.pressDigit('5'));

        assertEquals("1234", model.rawValue());
        assertEquals("1234", model.displayValue());
    }

    @Test
    public void ignoresNonDigitsWithoutChangingTheValue() {
        NumericKeypadModel model = new NumericKeypadModel(6, false);

        assertFalse(model.pressDigit('a'));
        assertFalse(model.pressDigit('-'));
        assertFalse(model.pressDigit(' '));

        assertEquals("", model.rawValue());
    }

    @Test
    public void deleteRemovesOneDigitAndIsHarmlessWhenEmpty() {
        NumericKeypadModel model = new NumericKeypadModel(6, false);
        model.pressDigit('1');
        model.pressDigit('2');

        assertTrue(model.delete());
        assertEquals("1", model.rawValue());
        assertTrue(model.delete());
        assertFalse(model.delete());
        assertEquals("", model.rawValue());
    }

    @Test
    public void clearRemovesEveryDigit() {
        NumericKeypadModel model = new NumericKeypadModel(11, false);
        model.pressDigit('1');
        model.pressDigit('3');
        model.pressDigit('8');

        model.clear();

        assertEquals("", model.rawValue());
        assertEquals("", model.displayValue());
    }

    @Test
    public void maskedDisplayUsesOneDotPerRawDigit() {
        NumericKeypadModel model = new NumericKeypadModel(6, true);
        model.pressDigit('1');
        model.pressDigit('2');
        model.pressDigit('3');

        assertEquals("123", model.rawValue());
        assertEquals("•••", model.displayValue());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAZeroLengthLimit() {
        new NumericKeypadModel(0, false);
    }
}
