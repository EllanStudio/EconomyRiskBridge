package cn.superiormc.economyriskbridge.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandMatcherTest {

    @Test
    void routesOnlyConfiguredCommandRoots() {
        CommandMatcher matcher = new CommandMatcher(List.of(
            new CommandPattern("pay {target} {amount}", "ellan_coin"),
            new CommandPattern("ellan_coin pay {target} {amount}", "ellan_coin")
        ));

        assertTrue(matcher.match("/home").isEmpty());
        assertTrue(matcher.match("/ellan_coin balance").isEmpty());
        assertEquals("Alice", matcher.match("/ExcellentEconomy:pay Alice 2k").orElseThrow().targetName());
        assertEquals(2_000_000_000L,
            matcher.match("/ExcellentEconomy:ellan_coin pay Alice 2k").orElseThrow().amountMicros());
    }
}
