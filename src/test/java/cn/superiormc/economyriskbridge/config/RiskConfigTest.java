package cn.superiormc.economyriskbridge.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskConfigTest {

    @Test
    void singleTransferNodeRejectsOtherBackends() {
        assertTrue(new RiskConfig.Network(true, "spawn", "spawn").acceptsTransfers());
        assertFalse(new RiskConfig.Network(true, "survival", "spawn").acceptsTransfers());
        assertTrue(new RiskConfig.Network(false, "survival", "spawn").acceptsTransfers());
    }

    @Test
    void bundledConfigurationLoadsIpGraphAndShopSettings() throws Exception {
        try (InputStream stream = RiskConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));

            RiskConfig config = RiskConfig.load(yaml);

            assertEquals(256, config.ipRelationAccountCap());
            assertEquals(7, config.shopRisk().violationLookbackDays());
            assertTrue(yaml.getBoolean("integrations.quick-shop.enabled"));
        }
    }
}
