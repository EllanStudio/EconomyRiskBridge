package cn.superiormc.economyriskbridge.risk;

import cn.superiormc.economyriskbridge.model.CommittedTransfer;
import cn.superiormc.economyriskbridge.model.CounterSnapshot;
import cn.superiormc.economyriskbridge.model.TransferAttempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RiskLedger {

    private final ZoneId zoneId;
    private final Map<DailyKey, Long> committed = new HashMap<>();
    private final Map<DailyKey, Long> reserved = new HashMap<>();
    private final Map<UUID, TransferAttempt> reservations = new HashMap<>();
    private final Map<UUID, Set<UUID>> reservationsByParticipant = new HashMap<>();
    private final Map<UUID, TransferAttempt> bookedTransfers = new HashMap<>();
    private final Map<UUID, ArrayDeque<Flow>> outgoing = new HashMap<>();
    private final Map<UUID, ArrayDeque<Flow>> incoming = new HashMap<>();
    private final ArrayDeque<UUID> flowMaintenanceQueue = new ArrayDeque<>();
    private long lastPrunedEpochDay = Long.MIN_VALUE;
    private long nextFlowSweepMillis;

    public RiskLedger(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    public synchronized CounterSnapshot snapshot(TransferAttempt attempt, int velocityWindowSeconds,
                                                 int behaviorWindowSeconds) {
        long epochDay = epochDay(attempt.createdAtMillis());
        pruneDays(epochDay);
        long velocityCutoff = attempt.createdAtMillis() - velocityWindowSeconds * 1_000L;
        long behaviorCutoff = attempt.createdAtMillis() - behaviorWindowSeconds * 1_000L;
        pruneFlows(attempt.sender().uuid(), behaviorCutoff);
        pruneFlows(attempt.receiver().uuid(), behaviorCutoff);

        int velocity = 0;
        Set<UUID> fanOut = new HashSet<>();
        Set<UUID> fanIn = new HashSet<>();
        boolean reverse = false;
        boolean circular = false;
        long senderRecentIncoming = 0L;
        long receiverRecentOutgoing = 0L;
        int repeatedAmount = 0;

        ArrayDeque<Flow> senderOutgoing = this.outgoing.get(attempt.sender().uuid());
        if (senderOutgoing != null) for (Flow flow : senderOutgoing) {
            if (flow.atMillis >= velocityCutoff && flow.currencyId.equals(attempt.currencyId())) {
                velocity++;
            }
            if (flow.atMillis >= behaviorCutoff && flow.currencyId.equals(attempt.currencyId())) {
                fanOut.add(flow.counterparty);
                if (flow.amountMicros == attempt.amountMicros()) {
                    repeatedAmount++;
                }
            }
        }
        ArrayDeque<Flow> receiverIncoming = this.incoming.get(attempt.receiver().uuid());
        if (receiverIncoming != null) for (Flow flow : receiverIncoming) {
            if (flow.atMillis >= behaviorCutoff && flow.currencyId.equals(attempt.currencyId())) {
                fanIn.add(flow.counterparty);
            }
        }
        ArrayDeque<Flow> senderIncoming = this.incoming.get(attempt.sender().uuid());
        if (senderIncoming != null) for (Flow flow : senderIncoming) {
            if (flow.atMillis >= behaviorCutoff && flow.currencyId.equals(attempt.currencyId())) {
                senderRecentIncoming = safeAdd(senderRecentIncoming, flow.amountMicros);
            }
        }
        ArrayDeque<Flow> receiverOutgoing = this.outgoing.get(attempt.receiver().uuid());
        if (receiverOutgoing != null) for (Flow flow : receiverOutgoing) {
            if (flow.atMillis >= behaviorCutoff && flow.currencyId.equals(attempt.currencyId())
                ) {
                receiverRecentOutgoing = safeAdd(receiverRecentOutgoing, flow.amountMicros);
                if (flow.counterparty.equals(attempt.sender().uuid())) {
                    reverse = true;
                }
            }
        }
        circular = hasTwoHopPath(attempt.receiver().uuid(), attempt.sender().uuid(), attempt.currencyId(),
            behaviorCutoff);

        Set<UUID> senderReservations = this.reservationsByParticipant.getOrDefault(
            attempt.sender().uuid(), Set.of());
        Set<UUID> receiverReservations = this.reservationsByParticipant.getOrDefault(
            attempt.receiver().uuid(), Set.of());
        Collection<UUID> relevantReservations;
        if (senderReservations.isEmpty()) {
            relevantReservations = receiverReservations;
        } else if (receiverReservations.isEmpty() || senderReservations == receiverReservations) {
            relevantReservations = senderReservations;
        } else {
            Set<UUID> mergedReservations = new HashSet<>(senderReservations);
            mergedReservations.addAll(receiverReservations);
            relevantReservations = mergedReservations;
        }
        for (UUID transactionId : relevantReservations) {
            TransferAttempt pending = this.reservations.get(transactionId);
            if (pending == null) {
                continue;
            }
            if (!pending.currencyId().equals(attempt.currencyId())) {
                continue;
            }
            if (pending.sender().uuid().equals(attempt.sender().uuid())) {
                if (pending.createdAtMillis() >= velocityCutoff) {
                    velocity++;
                }
                if (pending.createdAtMillis() >= behaviorCutoff) {
                    fanOut.add(pending.receiver().uuid());
                    if (pending.amountMicros() == attempt.amountMicros()) {
                        repeatedAmount++;
                    }
                }
            }
            if (pending.receiver().uuid().equals(attempt.receiver().uuid())
                && pending.createdAtMillis() >= behaviorCutoff) {
                fanIn.add(pending.sender().uuid());
            }
            if (pending.receiver().uuid().equals(attempt.sender().uuid())
                && pending.createdAtMillis() >= behaviorCutoff) {
                senderRecentIncoming = safeAdd(senderRecentIncoming, pending.amountMicros());
            }
            if (pending.sender().uuid().equals(attempt.receiver().uuid())
                && pending.createdAtMillis() >= behaviorCutoff) {
                receiverRecentOutgoing = safeAdd(receiverRecentOutgoing, pending.amountMicros());
            }
            if (pending.sender().uuid().equals(attempt.receiver().uuid())
                && pending.receiver().uuid().equals(attempt.sender().uuid())
                && pending.createdAtMillis() >= behaviorCutoff) {
                reverse = true;
            }
        }

        return new CounterSnapshot(
            total(key(Dimension.SENDER, attempt.sender().uuid().toString(), attempt.currencyId(), epochDay)),
            total(key(Dimension.RECEIVER, attempt.receiver().uuid().toString(), attempt.currencyId(), epochDay)),
            total(key(Dimension.PAIR, pair(attempt.sender().uuid(), attempt.receiver().uuid()),
                attempt.currencyId(), epochDay)),
            total(key(Dimension.CLUSTER, attempt.sender().clusterKey(), attempt.currencyId(), epochDay)),
            velocity,
            fanIn.size(),
            fanOut.size(),
            reverse,
            circular,
            senderRecentIncoming,
            receiverRecentOutgoing,
            repeatedAmount
        );
    }

    public synchronized void reserve(TransferAttempt attempt) {
        if (this.reservations.putIfAbsent(attempt.transactionId(), attempt) != null) {
            return;
        }
        indexReservation(attempt.sender().uuid(), attempt.transactionId());
        indexReservation(attempt.receiver().uuid(), attempt.transactionId());
        for (DailyKey key : keys(attempt)) {
            add(this.reserved, key, attempt.amountMicros());
        }
    }

    public synchronized boolean commit(UUID transactionId, long settledAtMillis) {
        TransferAttempt attempt = this.reservations.remove(transactionId);
        if (attempt == null) {
            return false;
        }
        removeReservationIndex(attempt.sender().uuid(), transactionId);
        removeReservationIndex(attempt.receiver().uuid(), transactionId);
        for (DailyKey key : keys(attempt)) {
            add(this.reserved, key, -attempt.amountMicros());
            add(this.committed, key, attempt.amountMicros());
        }
        this.bookedTransfers.put(transactionId, attempt);
        appendFlow(transactionId, attempt.sender().uuid(), attempt.receiver().uuid(), attempt.currencyId(),
            attempt.amountMicros(), settledAtMillis);
        return true;
    }

    public synchronized boolean release(UUID transactionId) {
        TransferAttempt attempt = this.reservations.remove(transactionId);
        if (attempt == null) {
            return false;
        }
        removeReservationIndex(attempt.sender().uuid(), transactionId);
        removeReservationIndex(attempt.receiver().uuid(), transactionId);
        for (DailyKey key : keys(attempt)) {
            add(this.reserved, key, -attempt.amountMicros());
        }
        return true;
    }

    public synchronized void bootstrap(CommittedTransfer transfer) {
        long day = epochDay(transfer.settledAtMillis());
        TransferAttempt minimal = new TransferAttempt(
            transfer.transactionId(),
            new cn.superiormc.economyriskbridge.model.PartyProfile(
                transfer.senderId(), "", 0L, 0L, 0L, transfer.senderCluster(), "", 0, 0),
            new cn.superiormc.economyriskbridge.model.PartyProfile(
                transfer.receiverId(), "", 0L, 0L, 0L, "uuid:" + transfer.receiverId(), "", 0, 0),
            transfer.currencyId(), transfer.amountMicros(), 0L, transfer.settledAtMillis(), false, false, false
        );
        for (DailyKey key : keys(minimal, day)) {
            add(this.committed, key, transfer.amountMicros());
        }
        this.bookedTransfers.put(transfer.transactionId(), minimal);
        appendFlow(transfer.transactionId(), transfer.senderId(), transfer.receiverId(), transfer.currencyId(),
            transfer.amountMicros(), transfer.settledAtMillis());
    }

    public synchronized boolean reverseCommitted(UUID transactionId) {
        TransferAttempt attempt = this.bookedTransfers.remove(transactionId);
        if (attempt == null) {
            return false;
        }
        for (DailyKey key : keys(attempt)) {
            add(this.committed, key, -attempt.amountMicros());
        }
        removeFlow(this.outgoing.get(attempt.sender().uuid()), transactionId);
        removeFlow(this.incoming.get(attempt.receiver().uuid()), transactionId);
        return true;
    }

    public synchronized long outgoingToday(UUID playerId, String currencyId, long nowMillis) {
        return total(key(Dimension.SENDER, playerId.toString(), currencyId, epochDay(nowMillis)));
    }

    public synchronized long incomingToday(UUID playerId, String currencyId, long nowMillis) {
        return total(key(Dimension.RECEIVER, playerId.toString(), currencyId, epochDay(nowMillis)));
    }

    public synchronized int pendingCount() {
        return this.reservations.size();
    }

    public synchronized void maintenance(long nowMillis, int behaviorWindowSeconds, int maximumPlayers) {
        if (maximumPlayers <= 0) {
            return;
        }
        if (this.flowMaintenanceQueue.isEmpty()) {
            if (nowMillis < this.nextFlowSweepMillis) {
                return;
            }
            this.flowMaintenanceQueue.addAll(this.outgoing.keySet());
            this.flowMaintenanceQueue.addAll(this.incoming.keySet());
            this.nextFlowSweepMillis = nowMillis + 3_600_000L;
        }
        long cutoff = nowMillis - Math.max(1L, behaviorWindowSeconds) * 1_000L;
        for (int index = 0; index < maximumPlayers; index++) {
            UUID playerId = this.flowMaintenanceQueue.pollFirst();
            if (playerId == null) {
                break;
            }
            pruneFlows(playerId, cutoff);
        }
    }

    private List<DailyKey> keys(TransferAttempt attempt) {
        return keys(attempt, epochDay(attempt.createdAtMillis()));
    }

    private List<DailyKey> keys(TransferAttempt attempt, long epochDay) {
        List<DailyKey> keys = new ArrayList<>(4);
        keys.add(key(Dimension.SENDER, attempt.sender().uuid().toString(), attempt.currencyId(), epochDay));
        keys.add(key(Dimension.RECEIVER, attempt.receiver().uuid().toString(), attempt.currencyId(), epochDay));
        keys.add(key(Dimension.PAIR, pair(attempt.sender().uuid(), attempt.receiver().uuid()),
            attempt.currencyId(), epochDay));
        keys.add(key(Dimension.CLUSTER, attempt.sender().clusterKey(), attempt.currencyId(), epochDay));
        return keys;
    }

    private void appendFlow(UUID transactionId, UUID sender, UUID receiver, String currencyId, long amountMicros,
                            long atMillis) {
        this.outgoing.computeIfAbsent(sender, ignored -> new ArrayDeque<>())
            .addLast(new Flow(transactionId, receiver, currencyId, amountMicros, atMillis));
        this.incoming.computeIfAbsent(receiver, ignored -> new ArrayDeque<>())
            .addLast(new Flow(transactionId, sender, currencyId, amountMicros, atMillis));
    }

    private boolean hasTwoHopPath(UUID from, UUID to, String currencyId, long cutoff) {
        ArrayDeque<Flow> firstHop = this.outgoing.get(from);
        if (firstHop == null) {
            return false;
        }
        int inspectedFirst = 0;
        for (Flow first : firstHop) {
            if (first.atMillis < cutoff || !first.currencyId.equals(currencyId)) {
                continue;
            }
            if (++inspectedFirst > 128) {
                break;
            }
            ArrayDeque<Flow> secondHop = this.outgoing.get(first.counterparty);
            if (secondHop == null) {
                continue;
            }
            int inspectedSecond = 0;
            for (Flow second : secondHop) {
                if (++inspectedSecond > 128) {
                    break;
                }
                if (second.atMillis >= cutoff && second.currencyId.equals(currencyId)
                    && second.counterparty.equals(to)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void removeFlow(ArrayDeque<Flow> flows, UUID transactionId) {
        if (flows != null) {
            flows.removeIf(flow -> flow.transactionId.equals(transactionId));
        }
    }

    private void pruneFlows(UUID playerId, long cutoff) {
        prune(this.outgoing, playerId, cutoff);
        prune(this.incoming, playerId, cutoff);
    }

    private static void prune(Map<UUID, ArrayDeque<Flow>> index, UUID playerId, long cutoff) {
        ArrayDeque<Flow> flows = index.get(playerId);
        if (flows == null) {
            return;
        }
        while (!flows.isEmpty() && flows.peekFirst().atMillis < cutoff) {
            flows.removeFirst();
        }
        if (flows.isEmpty()) {
            index.remove(playerId, flows);
        }
    }

    private void pruneDays(long currentEpochDay) {
        if (this.lastPrunedEpochDay == currentEpochDay) {
            return;
        }
        this.lastPrunedEpochDay = currentEpochDay;
        long oldest = currentEpochDay - 1;
        this.committed.keySet().removeIf(key -> key.epochDay < oldest);
        this.reserved.keySet().removeIf(key -> key.epochDay < oldest);
        this.bookedTransfers.entrySet().removeIf(entry -> epochDay(entry.getValue().createdAtMillis()) < oldest);
    }

    private void indexReservation(UUID playerId, UUID transactionId) {
        this.reservationsByParticipant.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(transactionId);
    }

    private void removeReservationIndex(UUID playerId, UUID transactionId) {
        Set<UUID> indexed = this.reservationsByParticipant.get(playerId);
        if (indexed == null) {
            return;
        }
        indexed.remove(transactionId);
        if (indexed.isEmpty()) {
            this.reservationsByParticipant.remove(playerId, indexed);
        }
    }

    private long epochDay(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), this.zoneId).toEpochDay();
    }

    private long total(DailyKey key) {
        return safeAdd(this.committed.getOrDefault(key, 0L), this.reserved.getOrDefault(key, 0L));
    }

    private static void add(Map<DailyKey, Long> map, DailyKey key, long delta) {
        long value = safeAdd(map.getOrDefault(key, 0L), delta);
        if (value <= 0L) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    public static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String pair(UUID left, UUID right) {
        String one = left.toString();
        String two = right.toString();
        return one.compareTo(two) <= 0 ? one + ":" + two : two + ":" + one;
    }

    private static DailyKey key(Dimension dimension, String subject, String currencyId, long epochDay) {
        return new DailyKey(dimension, subject, currencyId, epochDay);
    }

    private enum Dimension {
        SENDER,
        RECEIVER,
        PAIR,
        CLUSTER
    }

    private record DailyKey(Dimension dimension, String subject, String currencyId, long epochDay) {
    }

    private record Flow(UUID transactionId, UUID counterparty, String currencyId, long amountMicros, long atMillis) {
    }
}
