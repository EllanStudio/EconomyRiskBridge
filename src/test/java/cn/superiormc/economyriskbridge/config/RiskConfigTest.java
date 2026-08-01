package cn.superiormc.economyriskbridge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskConfigTest {

    @Test
    void singleTransferNodeRejectsOtherBackends() {
        assertTrue(new RiskConfig.Network(true, "spawn", "spawn").acceptsTransfers());
        assertFalse(new RiskConfig.Network(true, "survival", "spawn").acceptsTransfers());
        assertTrue(new RiskConfig.Network(false, "survival", "spawn").acceptsTransfers());
    }
}
