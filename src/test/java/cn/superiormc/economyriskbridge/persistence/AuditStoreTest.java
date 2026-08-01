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

    private static PartyProfile profile(String name, long now) {
        return new PartyProfile(UUID.randomUUID(), name, now - 100_000L, now - 50_000L, 10_000L,
            "ip:" + name, name, 1, 0);
    }
}
