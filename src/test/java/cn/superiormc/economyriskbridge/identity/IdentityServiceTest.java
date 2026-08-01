package cn.superiormc.economyriskbridge.identity;

import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.persistence.AuditStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityServiceTest {

    @TempDir
    Path directory;

    @Test
    void maintenanceRemovesProfilesPastPrivacyRetention() throws Exception {
        RiskConfig.Storage config = new RiskConfig.Storage("audit.db", 100, 20, 10, 180, true);
        try (AuditStore store = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            AuditStore.Bootstrap bootstrap = store.start(ZoneId.of("UTC"), 30);
            String secret = Base64.getEncoder().encodeToString(new byte[32]);
            IdentityService identities = new IdentityService(secret, 30, store, Logger.getAnonymousLogger());
            identities.bootstrap(bootstrap);
            UUID playerId = UUID.randomUUID();
            long old = System.currentTimeMillis() - 31L * 86_400_000L;
            identities.rememberName(playerId, "OldPlayer", old);

            identities.maintenance(System.currentTimeMillis(), 16);

            assertTrue(identities.resolveName("OldPlayer").isEmpty());
        }
    }

    @Test
    void linkedAccountCountingStopsAtTheRequestedRiskThreshold() throws Exception {
        RiskConfig.Storage config = new RiskConfig.Storage("audit.db", 100, 20, 10, 180, true);
        try (AuditStore store = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            AuditStore.Bootstrap bootstrap = store.start(ZoneId.of("UTC"), 30);
            String secret = Base64.getEncoder().encodeToString(new byte[32]);
            IdentityService identities = new IdentityService(secret, 30, store, Logger.getAnonymousLogger());
            identities.bootstrap(bootstrap);
            long now = System.currentTimeMillis();
            UUID playerId = UUID.randomUUID();
            InetAddress address = InetAddress.getLoopbackAddress();
            identities.observe(playerId, "Primary", address, now);
            identities.observe(UUID.randomUUID(), "LinkedOne", address, now);
            identities.observe(UUID.randomUUID(), "LinkedTwo", address, now);

            assertEquals(1, identities.profile(playerId, "Primary", now, 1).linkedAccountCount());
            assertEquals(2, identities.profile(playerId, "Primary", now, 10).linkedAccountCount());
        }
    }
}
