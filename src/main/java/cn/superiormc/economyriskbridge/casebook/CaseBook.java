package cn.superiormc.economyriskbridge.casebook;

import cn.superiormc.economyriskbridge.model.CaseRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CaseBook {

    private static final int MAX_MEMORY_CASES = 2_000;

    private final ArrayDeque<UUID> order = new ArrayDeque<>();
    private final Map<UUID, CaseRecord> cases = new LinkedHashMap<>();

    public synchronized void bootstrap(List<CaseRecord> records) {
        records.stream().sorted(java.util.Comparator.comparingLong(CaseRecord::createdAtMillis)).forEach(this::add);
    }

    public synchronized void add(CaseRecord record) {
        if (this.cases.put(record.caseId(), record) == null) {
            this.order.addLast(record.caseId());
        }
        while (this.order.size() > MAX_MEMORY_CASES) {
            UUID oldest = this.order.removeFirst();
            this.cases.remove(oldest);
        }
    }

    public synchronized Optional<CaseRecord> findByPrefix(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        CaseRecord match = null;
        for (CaseRecord record : this.cases.values()) {
            if (record.caseId().toString().toLowerCase(Locale.ROOT).startsWith(normalized)) {
                if (match != null) {
                    return Optional.empty();
                }
                match = record;
            }
        }
        return Optional.ofNullable(match);
    }

    public synchronized Optional<CaseRecord> get(UUID caseId) {
        return Optional.ofNullable(this.cases.get(caseId));
    }

    public synchronized Optional<CaseRecord> update(UUID caseId, CaseRecord.Status status, String reviewer,
                                                    String note) {
        CaseRecord current = this.cases.get(caseId);
        if (current == null) {
            return Optional.empty();
        }
        CaseRecord updated = current.withStatus(status, reviewer, note);
        this.cases.put(caseId, updated);
        return Optional.of(updated);
    }

    public synchronized Optional<CaseRecord> acknowledge(String prefix, String reviewer, String note) {
        Optional<CaseRecord> found = findByPrefix(prefix);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CaseRecord updated = found.get().withStatus(CaseRecord.Status.ACKNOWLEDGED, reviewer, note);
        this.cases.put(updated.caseId(), updated);
        return Optional.of(updated);
    }

    public synchronized List<CaseRecord> recentOpen(int offset, int limit) {
        List<CaseRecord> result = new ArrayList<>(limit);
        int skipped = 0;
        var iterator = this.order.descendingIterator();
        while (iterator.hasNext() && result.size() < limit) {
            CaseRecord record = this.cases.get(iterator.next());
            if (record == null || record.status() == CaseRecord.Status.CLOSED) {
                continue;
            }
            if (skipped++ < offset) {
                continue;
            }
            result.add(record);
        }
        return result;
    }

    public synchronized long openCount() {
        return this.cases.values().stream().filter(record -> record.status() != CaseRecord.Status.CLOSED).count();
    }
}
