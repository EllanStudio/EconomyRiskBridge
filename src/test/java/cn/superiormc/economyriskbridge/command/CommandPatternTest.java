package cn.superiormc.economyriskbridge.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPatternTest {

    @Test
    void matchesAliasesCaseInsensitivelyAndPreventsExtraArguments() {
        CommandPattern pattern = new CommandPattern("money pay {target} {amount}", "Money");
        assertEquals("money", pattern.rootCommand());
        CommandPattern.Match match = pattern.match("/MONEY PAY Alice 2.5k").orElseThrow();

        assertEquals("Alice", match.targetName());
        assertEquals("money", match.currencyId());
        assertEquals(2_500_000_000L, match.amountMicros());
        assertTrue(pattern.match("/money pay Alice 10 unexpected").isEmpty());
    }

    @Test
    void acceptsNamespacedCommandLabelsToCloseAliasBypasses() {
        CommandPattern pattern = new CommandPattern("pay {target} {amount}", "money");
        assertTrue(pattern.match("/excellenteconomy:pay Alice 10").isPresent());
    }
}
