package cn.superiormc.economyriskbridge.integration;

import cn.superiormc.economyriskbridge.model.ShopSourceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopSourceLedgerTest {

    @Test
    void disjointAccountsAreNeverCombined() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ShopSourceLedger ledger = new ShopSourceLedger(List.of());
        ledger.commit(record(first, 80));
        ledger.commit(record(second, 80));

        assertFalse(ledger.assess(10L, first, "shop/item", 0L, 100L, Set.of()).violation());
        assertFalse(ledger.assess(10L, second, "shop/item", 0L, 100L, Set.of()).violation());
    }

    @Test
    void relatedAccountsShareOneEffectiveLimit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ShopSourceLedger ledger = new ShopSourceLedger(List.of());
        ledger.commit(record(first, 80));

        ShopSourceLedger.Assessment attempted = ledger.assess(
            10L, second, "shop/item", 30L, 100L, Set.of(first));

        assertTrue(attempted.violation());
        assertTrue(attempted.activePlayers().containsAll(Set.of(first, second)));
    }

    @Test
    void newlyDiscoveredRelationshipReevaluatesEarlierPerPlayerSales() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ShopSourceLedger ledger = new ShopSourceLedger(List.of());
        ledger.commit(record(first, 60));
        ledger.commit(record(second, 60));

        assertTrue(ledger.assess(10L, first, "shop/item", 0L, 100L, Set.of(second)).violation());
    }

    private static ShopSourceRecord record(UUID playerId, long units) {
        return new ShopSourceRecord(1_000L, 10L, playerId, "ignored-snapshot", "shop/item", units, 100L);
    }
}
