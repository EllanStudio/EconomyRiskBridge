package cn.superiormc.economyriskbridge.persistence;

import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.model.BalanceEntry;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.model.EscrowRecord;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.model.RiskDecision;
import cn.superiormc.economyriskbridge.model.TransferAttempt;
import cn.superiormc.economyriskbridge.model.TransferState;
import cn.superiormc.economyriskbridge.risk.RiskRule;
import cn.superiormc.economyriskbridge.provenance.SourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditStoreTest {

    @TempDir
    Path directory;

    @Test
    void escrowIntentAndHeldStateSurviveRestart() throws Exception {
        RiskConfig.Storage config = new RiskConfig.Storage("audit.db", 100, 20, 10, 180, true);
        long now = System.currentTimeMillis();
        PartyProfile sender = profile("Sender", now);
        PartyProfile receiver = profile("Receiver", now);
        TransferAttempt attempt = new TransferAttempt(UUID.randomUUID(), sender, receiver, "money",
            50_000_000L, 100_000_000L, now, true, false, false);
        RiskDecision decision = new RiskDecision(RiskDecision.Action.BLOCK, 80,
            List.of(new RiskDecision.Reason(RiskRule.PROBATION_LARGE_TRANSFER, 0, true)));
        CaseRecord caseRecord = new CaseRecord(UUID.randomUUID(), attempt.transactionId(), now,
            CaseRecord.Severity.CRITICAL, CaseRecord.Status.OPEN, sender.uuid(), sender.name(), receiver.uuid(),
            receiver.name(), "money", attempt.amountMicros(), decision.score(), decision.reasonCodes(), "test", "");

        AuditStore first = new AuditStore(this.directory, config, Logger.getAnonymousLogger());
        first.start(ZoneId.of("UTC"), 30);
        assertTrue(first.prepareEscrow(attempt, decision, caseRecord, 100.0D).get(5, TimeUnit.SECONDS));

        EscrowRecord held = new EscrowRecord(caseRecord.caseId(), attempt.transactionId(),
            EscrowRecord.Status.HELD, 100.0D, now + 10L, 0L, "SYSTEM", "held");
        BalanceEntry journal = new BalanceEntry(UUID.randomUUID(), now + 10L, sender.uuid(), sender.name(),
            "money", -50_000_000L, 100_000_000L, 50_000_000L, SourceCatalog.SERVER_BANK_HOLD,
            BalanceEntry.Confidence.VERIFIED, "test hold", attempt.transactionId(), caseRecord.caseId(),
            null, "server bank");
        assertTrue(first.transitionEscrowDurable(caseRecord.caseId(), EscrowRecord.Status.PREPARED, held,
            TransferState.ESCROW_HELD, null, journal));
        assertTrue(first.enqueueOperatorAlert(caseRecord.caseId(), now));
        first.close();

        try (AuditStore second = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            AuditStore.Bootstrap bootstrap = second.start(ZoneId.of("UTC"), 30);
            assertEquals(1, bootstrap.escrows().size());
            assertEquals(EscrowRecord.Status.HELD, bootstrap.escrows().getFirst().status());
            assertEquals(1, bootstrap.committedTransfers().size());
            assertEquals(RiskRule.PROBATION_LARGE_TRANSFER.code(),
                bootstrap.cases().getFirst().reasonCodes());
            List<BalanceEntry> statement = second.statement(sender.uuid(), "money", 0, 8)
                .get(5, TimeUnit.SECONDS);
            assertEquals(1, statement.size());
            assertEquals(SourceCatalog.SERVER_BANK_HOLD, statement.getFirst().source());
            assertEquals(-50_000_000L, statement.getFirst().deltaMicros());
            assertEquals(List.of(caseRecord.caseId()), second.pendingOperatorAlerts(100)
                .get(5, TimeUnit.SECONDS));
            assertTrue(second.enqueueOperatorAlertsDelivered(List.of(caseRecord.caseId()), "Admin", now + 20L));
        }

        try (AuditStore third = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            third.start(ZoneId.of("UTC"), 30);
            assertTrue(third.pendingOperatorAlerts(100).get(5, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    void shopSourceCaseMetadataSurvivesRestart() throws Exception {
        RiskConfig.Storage config = new RiskConfig.Storage("source-cases.db", 100, 20, 10, 180, true);
        long now = System.currentTimeMillis();
        UUID playerId = UUID.randomUUID();
        CaseRecord sourceCase = CaseRecord.shopSource(UUID.randomUUID(), now, CaseRecord.Severity.HIGH,
            playerId, "AltAccount", "Shop: blocks Product: iron", 130L, 100L, 80,
            RiskRule.SHOP_ALT_LIMIT.code(), "completed source violation", "AltAccount, MainAccount");

        try (AuditStore first = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            first.start(ZoneId.of("UTC"), 30);
            assertTrue(first.enqueueCase(sourceCase));
        }

        try (AuditStore second = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            AuditStore.Bootstrap bootstrap = second.start(ZoneId.of("UTC"), 30);
            CaseRecord restored = bootstrap.cases().getFirst();
            assertEquals(CaseRecord.Kind.SHOP_SOURCE, restored.kind());
            assertEquals(sourceCase.sourceKey(), restored.sourceKey());
            assertEquals(130L, restored.sourceUnits());
            assertEquals(100L, restored.sourceLimit());
            assertEquals("AltAccount, MainAccount", restored.relatedAccounts());
        }
    }

    @Test
    void quickShopCaseKindSurvivesRestart() throws Exception {
        RiskConfig.Storage config = new RiskConfig.Storage("quickshop-cases.db", 100, 20, 10, 180, true);
        long now = System.currentTimeMillis();
        CaseRecord quickShopCase = CaseRecord.quickShop(UUID.randomUUID(), now, CaseRecord.Severity.HIGH,
            UUID.randomUUID(), "RelatedBuyer", UUID.randomUUID(), "RelatedOwner", "money", 25_000_000L,
            60, RiskRule.QUICK_SHOP_RELATED_ACCOUNTS.code(), "verified QuickShop path");

        try (AuditStore first = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            first.start(ZoneId.of("UTC"), 30);
            assertTrue(first.enqueueCase(quickShopCase));
        }

        try (AuditStore second = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            CaseRecord restored = second.start(ZoneId.of("UTC"), 30).cases().getFirst();
            assertEquals(CaseRecord.Kind.QUICK_SHOP, restored.kind());
            assertEquals(quickShopCase.senderId(), restored.senderId());
            assertEquals(quickShopCase.receiverId(), restored.receiverId());
            assertEquals(25_000_000L, restored.amountMicros());
        }
    }

    @Test
    void legacyCaseTableIsMigratedWithoutLosingCases() throws Exception {
        Path database = this.directory.resolve("legacy-cases.db");
        UUID caseId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID receiverId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE erb_cases (
                    case_id TEXT PRIMARY KEY,
                    tx_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    severity TEXT NOT NULL,
                    status TEXT NOT NULL,
                    sender_uuid TEXT NOT NULL,
                    sender_name TEXT NOT NULL,
                    receiver_uuid TEXT NOT NULL,
                    receiver_name TEXT NOT NULL,
                    currency_id TEXT NOT NULL,
                    amount_micros INTEGER NOT NULL,
                    risk_score INTEGER NOT NULL,
                    reason_codes TEXT NOT NULL,
                    note TEXT NOT NULL,
                    reviewer TEXT NOT NULL
                )
                """);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO erb_cases VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                insert.setString(1, caseId.toString());
                insert.setString(2, transactionId.toString());
                insert.setLong(3, now);
                insert.setString(4, CaseRecord.Severity.HIGH.name());
                insert.setString(5, CaseRecord.Status.OPEN.name());
                insert.setString(6, senderId.toString());
                insert.setString(7, "LegacySender");
                insert.setString(8, receiverId.toString());
                insert.setString(9, "LegacyReceiver");
                insert.setString(10, "money");
                insert.setLong(11, 10_000_000L);
                insert.setInt(12, 50);
                insert.setString(13, RiskRule.SAME_RECENT_IP.code());
                insert.setString(14, "legacy case");
                insert.setString(15, "");
                insert.executeUpdate();
            }
        }

        RiskConfig.Storage config = new RiskConfig.Storage("legacy-cases.db", 100, 20, 10, 180, true);
        try (AuditStore store = new AuditStore(this.directory, config, Logger.getAnonymousLogger())) {
            CaseRecord restored = store.start(ZoneId.of("UTC"), 30).cases().getFirst();
            assertEquals(caseId, restored.caseId());
            assertEquals(CaseRecord.Kind.TRANSFER, restored.kind());
            assertTrue(restored.sourceKey().isEmpty());
        }
    }

    private static PartyProfile profile(String name, long now) {
        return new PartyProfile(UUID.randomUUID(), name, now - 100_000L, now - 50_000L, 10_000L,
            "ip:" + name, name, 1, 0);
    }
}
