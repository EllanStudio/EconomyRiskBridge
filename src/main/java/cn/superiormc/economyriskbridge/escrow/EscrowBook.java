package cn.superiormc.economyriskbridge.escrow;

import cn.superiormc.economyriskbridge.model.EscrowRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class EscrowBook {

    private static final int MAX_MEMORY_ESCROWS = 2_000;

    private final ArrayDeque<UUID> order = new ArrayDeque<>();
    private final Map<UUID, EscrowRecord> records = new LinkedHashMap<>();
    private final Map<String, Long> treasury = new LinkedHashMap<>();

    public synchronized void bootstrap(List<EscrowRecord> escrows) {
        for (EscrowRecord escrow : escrows) {
            add(escrow);
        }
    }

    public synchronized void bootstrapTreasury(Map<String, Long> totals) {
        this.treasury.clear();
        this.treasury.putAll(totals);
    }

    public synchronized void add(EscrowRecord escrow) {
        if (this.records.put(escrow.caseId(), escrow) == null) {
            this.order.addLast(escrow.caseId());
        }
        while (this.order.size() > MAX_MEMORY_ESCROWS) {
            this.records.remove(this.order.removeFirst());
        }
    }

    public synchronized Optional<EscrowRecord> findByPrefix(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        EscrowRecord match = null;
        for (EscrowRecord record : this.records.values()) {
            if (!record.caseId().toString().toLowerCase(Locale.ROOT).startsWith(normalized)) {
                continue;
            }
            if (match != null) {
                return Optional.empty();
            }
            match = record;
        }
        return Optional.ofNullable(match);
    }

    public synchronized Optional<EscrowRecord> transition(UUID caseId, EscrowRecord.Status expected,
                                                           EscrowRecord.Status next, long heldAt, long resolvedAt,
                                                           String actor, String note) {
        EscrowRecord current = this.records.get(caseId);
        if (current == null || current.status() != expected) {
            return Optional.empty();
        }
        EscrowRecord updated = current.transition(next, heldAt, resolvedAt, actor, note);
        this.records.put(caseId, updated);
        return Optional.of(updated);
    }

    public synchronized List<EscrowRecord> recentOpen(int offset, int limit) {
        List<EscrowRecord> result = new ArrayList<>(limit);
        int skipped = 0;
        var iterator = this.order.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) {
            EscrowRecord record = this.records.get(iterator.next());
            if (record == null || !record.status().isOpen()) {
                continue;
            }
            if (skipped++ < offset) {
                continue;
            }
            result.add(record);
        }
        return result;
    }

    public synchronized long heldCount() {
        return this.records.values().stream().filter(record -> record.status() == EscrowRecord.Status.HELD).count();
    }

    public synchronized void addConfiscated(String currencyId, long amountMicros) {
        this.treasury.merge(currencyId, amountMicros, (left, right) -> {
            try {
                return Math.addExact(left, right);
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        });
    }

    public synchronized Map<String, Long> treasurySnapshot() {
        return Map.copyOf(this.treasury);
    }
}
