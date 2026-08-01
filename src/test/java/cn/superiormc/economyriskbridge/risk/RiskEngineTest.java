package cn.superiormc.economyriskbridge.risk;

import cn.superiormc.economyriskbridge.command.CommandPattern;
import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.model.CounterSnapshot;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.model.RiskDecision;
import cn.superiormc.economyriskbridge.model.TransferAttempt;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskEngineTest {

    @Test
    void lowPlaytimeLargeTransferIsHardReview() {
        long now = 1_800_000_000_000L;
        PartyProfile newPlayer = profile("New", now - 3_600_000L, 1_800L, 0);
        PartyProfile oldPlayer = profile("Old", now - 100L * 86_400_000L, 500L * 3_600L, 0);
        TransferAttempt attempt = new TransferAttempt(UUID.randomUUID(), newPlayer, oldPlayer, "money",
            30_000L * 1_000_000L, 40_000L * 1_000_000L, now, false, false, false);

        RiskDecision decision = new RiskEngine().evaluate(attempt, emptySnapshot(), config());

        assertEquals(RiskDecision.Action.BLOCK, decision.action());
        assertEquals(RiskRule.PROBATION_LARGE_TRANSFER.code(), decision.primaryCode());
        assertTrue(decision.reasons().stream().anyMatch(reason -> reason.rule() == RiskRule.UNEXPLAINED_FUNDS));
    }

    @Test
    void sameIpAloneDoesNotConvict() {
        long now = 1_800_000_000_000L;
        PartyProfile one = profile("One", now - 100L * 86_400_000L, 500L * 3_600L, 1);
        PartyProfile two = profile("Two", now - 100L * 86_400_000L, 500L * 3_600L, 1);
        TransferAttempt attempt = new TransferAttempt(UUID.randomUUID(), one, two, "money",
            123_450_000L, 1_000_000_000L, now, true, false, false);

        RiskDecision decision = new RiskEngine().evaluate(attempt, emptySnapshot(), config());

        assertEquals(RiskDecision.Action.PASS, decision.action());
        assertEquals(35, decision.score());
    }

    private static PartyProfile profile(String name, long firstSeen, long playSeconds, int linked) {
        return new PartyProfile(UUID.randomUUID(), name, firstSeen, firstSeen + 1_000L, playSeconds,
            "ip:" + name, name, 1, linked);
    }

    private static CounterSnapshot emptySnapshot() {
        return new CounterSnapshot(0L, 0L, 0L, 0L, 0, 0, 0, false, false, 0L, 0L, 0);
    }

    private static RiskConfig config() {
        return new RiskConfig(
            RiskConfig.Mode.ENFORCE,
            ZoneId.of("UTC"),
            new RiskConfig.Network(true, "spawn", "spawn"),
            List.of(new CommandPattern("pay {target} {amount}", "money")),
            15,
            RiskConfig.UnknownTargetPolicy.BLOCK,
            30,
            new RiskConfig.Limits(
                1_000_000L * 1_000_000L,
                2_000_000L * 1_000_000L,
                2_000_000L * 1_000_000L,
                1_000_000L * 1_000_000L,
                2_000_000L * 1_000_000L,
                false, false, 60, 8, true
            ),
            new RiskConfig.Profiling(
                24, 120, 24,
                25_000L * 1_000_000L,
                10_000L * 1_000_000L,
                5_000L * 1_000_000L,
                true, true,
                14, 25_000L * 1_000_000L,
                3, 4, 0.8D, 3, 0.9D, 3,
                600, 4, 6, 0.85D, 10_000L * 1_000_000L
            ),
            new RiskConfig.Scores(
                35, 20, 15, 40, 60,
                30, 25, 15, 35, 40, 35, 20, 15,
                25, 25, 20, 25, 15, 5, 40, 80
            ),
            new RiskConfig.Storage("risk.db", 100, 10, 10, 180, true),
            new RiskConfig.Messages("", "", 24, "", "", "", "", "", "")
        );
    }
}
