package cn.superiormc.economyriskbridge.provenance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceCatalogTest {

    @Test
    void mapsObservedServerPluginsToStableSourceCodes() {
        assertEquals(SourceCatalog.ULTIMATE_SHOP, SourceCatalog.fromPlugin("UltimateShop"));
        assertEquals(SourceCatalog.QUICK_SHOP, SourceCatalog.fromPlugin("QuickShop-Hikari"));
        assertEquals(SourceCatalog.TIMED_ORDERS, SourceCatalog.fromPlugin("EllanTimedOrders"));
        assertEquals(SourceCatalog.CROP_ACTION, SourceCatalog.fromPlugin("CraftEngine"));
        assertEquals(SourceCatalog.OTHER_PLUGIN, SourceCatalog.fromPlugin("FutureEconomySource"));
    }
}
