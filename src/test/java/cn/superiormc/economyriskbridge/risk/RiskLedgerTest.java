package cn.superiormc.economyriskbridge.risk;

import cn.superiormc.economyriskbridge.model.CounterSnapshot;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.model.TransferAttempt;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskLedgerTest {

    @Test
    void reservationsImmediatelyConsumeQuotaAndCanBeReleased() {
        RiskLedger ledger = new RiskLedger(ZoneId.of("UTC"));
        long now = 1_800_000_000_000L;
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        TransferAttempt first = attempt(UUID.randomUUID(), sender, receiver, 50_000_000L, now);
        ledger.reserve(first);

        TransferAttempt second = attempt(UUID.randomUUID(), sender, receiver, 10_000_000L, now + 1_000L);
        CounterSnapshot reserved = ledger.snapshot(second, 60, 600);
        assertEquals(50_000_000L, reserved.senderDaily());
        assertEquals(1, reserved.velocityCount());

        assertTrue(ledger.release(first.transactionId()));
        CounterSnapshot released = ledger.snapshot(second, 60, 600);
        assertEquals(0L, released.senderDaily());
        assertEquals(0, released.velocityCount());
    }

    @Test
    void committedFlowCanBeReversedAfterEscrowRefund() {
        RiskLedger ledger = new RiskLedger(ZoneId.of("UTC"));
        long now = 1_800_000_000_000L;
        TransferAttempt transfer = attempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            25_000_000L, now);
        ledger.reserve(transfer);
        assertTrue(ledger.commit(transfer.transactionId(), now));
        assertEquals(25_000_000L, ledger.outgoingToday(transfer.sender().uuid(), "money", now));

        assertTrue(ledger.reverseCommitted(transfer.transactionId()));
        assertEquals(0L, ledger.outgoingToday(transfer.sender().uuid(), "money", now));
    }

    private static TransferAttempt attempt(UUID transactionId, UUID sender, UUID receiver, long amount, long now) {
        PartyProfile from = new PartyProfile(sender, "Sender", now - 100_000L, now - 10_000L,
            10_000L, "ip:one", "one", 1, 0);
        PartyProfile to = new PartyProfile(receiver, "Receiver", now - 100_000L, now - 10_000L,
            10_000L, "ip:two", "two", 1, 0);
        return new TransferAttempt(transactionId, from, to, "money", amount, amount * 10L, now,
            false, false, false);
    }
}
