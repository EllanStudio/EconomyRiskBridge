package cn.superiormc.economyriskbridge.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void parsesCompactAmountsWithoutFloatingPointDrift() {
        assertEquals(1_500_000_000L, Money.parseMicros("1.5k"));
        assertEquals(1_234_567L, Money.parseMicros("1.2345674"));
        assertEquals("1.234567", Money.format(1_234_567L));
    }

    @Test
    void rejectsNegativeAndOversizedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.parseMicros("-1"));
        assertThrows(IllegalArgumentException.class, () -> Money.parseMicros("999999999999999999999t"));
    }
}
