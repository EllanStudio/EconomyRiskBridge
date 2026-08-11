package cn.superiormc.economyriskbridge.integration;

import cn.superiormc.economyriskbridge.model.ShopSourceRecord;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps raw per-player shop usage so account relationships can be evaluated at decision time.
 * Persisted cluster keys are deliberately not used: a later IP observation may connect accounts
 * that were unrelated when an earlier sale completed.
 */
public final class ShopSourceLedger {

    private final Map<PlayerDay, Map<String, SourceState>> states = new HashMap<>();
    private long lastPrunedEpochDay = Long.MIN_VALUE;

    public ShopSourceLedger(List<ShopSourceRecord> bootstrap) {
        bootstrap.forEach(this::commit);
    }

    public synchronized void commit(ShopSourceRecord record) {
        PlayerDay playerDay = new PlayerDay(record.epochDay(), record.playerId());
        SourceState state = this.states.computeIfAbsent(playerDay, ignored -> new HashMap<>())
            .computeIfAbsent(record.sourceKey(), ignored -> new SourceState());
        state.units = safeAdd(state.units, record.units());
        state.limit = Math.max(state.limit, record.individualLimit());
        prune(record.epochDay());
    }

    public synchronized Assessment assess(long epochDay, UUID playerId, String sourceKey, long additionalUnits,
                                          long individualLimit, Set<UUID> relatedAccounts) {
        Set<UUID> members = new LinkedHashSet<>();
        members.add(playerId);
        members.addAll(relatedAccounts);

        long total = 0L;
        long effectiveLimit = Math.max(0L, individualLimit);
        Set<UUID> activePlayers = new LinkedHashSet<>();
        for (UUID member : members) {
            SourceState state = state(epochDay, member, sourceKey);
            if (state == null) {
                continue;
            }
            total = safeAdd(total, state.units);
            effectiveLimit = Math.max(effectiveLimit, state.limit);
            if (state.units > 0L) {
                activePlayers.add(member);
            }
        }

        if (additionalUnits > 0L) {
            total = safeAdd(total, additionalUnits);
            activePlayers.add(playerId);
        }
        boolean violation = activePlayers.size() >= 2 && effectiveLimit > 0L && total > effectiveLimit;
        return new Assessment(sourceKey, violation, total, effectiveLimit, Set.copyOf(activePlayers));
    }

    private SourceState state(long epochDay, UUID playerId, String sourceKey) {
        Map<String, SourceState> sources = this.states.get(new PlayerDay(epochDay, playerId));
        return sources == null ? null : sources.get(sourceKey);
    }

    private void prune(long currentEpochDay) {
        if (currentEpochDay == this.lastPrunedEpochDay) {
            return;
        }
        this.lastPrunedEpochDay = currentEpochDay;
        long oldestDay = currentEpochDay - 1L;
        this.states.keySet().removeIf(existing -> existing.epochDay < oldestDay);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public record Assessment(String sourceKey, boolean violation, long totalUnits, long effectiveLimit,
                             Set<UUID> activePlayers) {
    }

    private record PlayerDay(long epochDay, UUID playerId) {
    }

    private static final class SourceState {
        private long units;
        private long limit;
    }
}
