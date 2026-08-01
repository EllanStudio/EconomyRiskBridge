package cn.superiormc.economyriskbridge.risk;

import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.model.CounterSnapshot;
import cn.superiormc.economyriskbridge.model.RiskDecision;
import cn.superiormc.economyriskbridge.model.TransferAttempt;

import java.util.ArrayList;
import java.util.List;

public final class RiskEngine {

    public RiskDecision evaluate(TransferAttempt attempt, CounterSnapshot snapshot, RiskConfig config) {
        RiskConfig.Limits limits = config.limits();
        RiskConfig.Profiling profiling = config.profiling();
        RiskConfig.Scores scores = config.scores();
        List<RiskDecision.Reason> reasons = new ArrayList<>();

        if (attempt.sender().uuid().equals(attempt.receiver().uuid())) {
            hard(reasons, RiskRule.SELF_TRANSFER);
        }
        if (exceeds(0L, attempt.amountMicros(), limits.maxSingle())) {
            hard(reasons, RiskRule.SINGLE_LIMIT);
        }
        if (exceeds(snapshot.senderDaily(), attempt.amountMicros(), limits.senderDaily())) {
            hard(reasons, RiskRule.SENDER_DAILY_LIMIT);
        }
        if (exceeds(snapshot.receiverDaily(), attempt.amountMicros(), limits.receiverDaily())) {
            hard(reasons, RiskRule.RECEIVER_DAILY_LIMIT);
        }
        if (exceeds(snapshot.pairDaily(), attempt.amountMicros(), limits.pairDaily())) {
            hard(reasons, RiskRule.PAIR_DAILY_LIMIT);
        }
        if (exceeds(snapshot.clusterDaily(), attempt.amountMicros(), limits.clusterDaily())) {
            if (limits.clusterDailyHardBlock()) {
                hard(reasons, RiskRule.CLUSTER_DAILY_LIMIT);
            } else {
                add(reasons, RiskRule.CLUSTER_DAILY_LIMIT, scores.nearLimit());
            }
        }

        if (snapshot.velocityCount() >= limits.velocityMaxTransfers()) {
            if (limits.velocityHardBlock()) {
                hard(reasons, RiskRule.TRANSFER_VELOCITY);
            } else {
                add(reasons, RiskRule.TRANSFER_VELOCITY, scores.highVelocity());
            }
        }

        if (attempt.sameRecentIp() && !attempt.trustedPair()) {
            if (limits.sameIpHardBlock()) {
                hard(reasons, RiskRule.SAME_RECENT_IP);
            } else {
                add(reasons, RiskRule.SAME_RECENT_IP, scores.sameRecentIp());
            }
        }

        long newAccountMillis = profiling.newAccountHours() * 3_600_000L;
        if (newAccountMillis > 0L && (isNew(attempt.sender().firstSeenMillis(), attempt.createdAtMillis(), newAccountMillis)
            || isNew(attempt.receiver().firstSeenMillis(), attempt.createdAtMillis(), newAccountMillis))) {
            add(reasons, RiskRule.NEW_ACCOUNT, scores.newAccount());
        }

        long lowPlaySeconds = profiling.lowPlaytimeMinutes() * 60L;
        if (lowPlaySeconds > 0L && (attempt.sender().playSeconds() < lowPlaySeconds
            || attempt.receiver().playSeconds() < lowPlaySeconds)) {
            add(reasons, RiskRule.LOW_PLAYTIME, scores.lowPlaytime());
        }

        long probationPlaySeconds = profiling.probationPlaytimeHours() * 3_600L;
        boolean probationParty = (newAccountMillis > 0L
            && (isNew(attempt.sender().firstSeenMillis(), attempt.createdAtMillis(), newAccountMillis)
            || isNew(attempt.receiver().firstSeenMillis(), attempt.createdAtMillis(), newAccountMillis)))
            || (probationPlaySeconds > 0L && (attempt.sender().playSeconds() < probationPlaySeconds
            || attempt.receiver().playSeconds() < probationPlaySeconds));
        if (profiling.probationLargeTransfer() > 0L && probationParty
            && attempt.amountMicros() >= profiling.probationLargeTransfer()) {
            if (profiling.sourceOfFundsHardBlock()) {
                hard(reasons, RiskRule.PROBATION_LARGE_TRANSFER);
            } else {
                add(reasons, RiskRule.PROBATION_LARGE_TRANSFER, scores.sourceOfFunds());
            }
        }

        long senderCapacity = earnedCapacity(attempt.sender().playSeconds(), profiling);
        long receiverCapacity = earnedCapacity(attempt.receiver().playSeconds(), profiling);
        boolean unexplainedSource = senderCapacity > 0L
            && RiskLedger.safeAdd(snapshot.senderDaily(), attempt.amountMicros()) > senderCapacity;
        boolean unexplainedDestination = receiverCapacity > 0L
            && RiskLedger.safeAdd(snapshot.receiverDaily(), attempt.amountMicros()) > receiverCapacity;
        if (unexplainedSource || unexplainedDestination) {
            if (profiling.sourceOfFundsHardBlock()) {
                hard(reasons, RiskRule.UNEXPLAINED_FUNDS);
            } else {
                add(reasons, RiskRule.UNEXPLAINED_FUNDS, scores.sourceOfFunds());
            }
        }
        if (attempt.shopAltLimitPattern()) {
            if (profiling.shopAltLimitHardBlock()) {
                hard(reasons, RiskRule.SHOP_ALT_LIMIT);
            } else {
                add(reasons, RiskRule.SHOP_ALT_LIMIT, scores.shopAltLimit());
            }
        }

        if (snapshot.fanInDistinct() >= profiling.fanInDistinctSenders()) {
            add(reasons, RiskRule.FAN_IN, scores.fanIn());
        }
        if (snapshot.fanOutDistinct() >= profiling.fanOutDistinctReceivers()) {
            add(reasons, RiskRule.FAN_OUT, scores.fanOut());
        }
        if (snapshot.reverseFlow()) {
            add(reasons, RiskRule.REVERSE_FLOW, scores.reverseFlow());
        }
        if (snapshot.circularFlow()) {
            add(reasons, RiskRule.CIRCULAR_FLOW, scores.circularFlow());
        }
        if (profiling.passThroughRatio() > 0.0D
            && snapshot.senderRecentIncoming() >= attempt.amountMicros() * profiling.passThroughRatio()) {
            add(reasons, RiskRule.RAPID_PASS_THROUGH, scores.passThrough());
        }

        boolean nearLimit = nearAnyLimit(attempt.amountMicros(), snapshot, limits, profiling.nearLimitRatio());
        if (snapshot.velocityCount() + 1 >= profiling.structuringMinTransfers() && nearLimit) {
            add(reasons, RiskRule.STRUCTURING, scores.structuring());
        }
        if (profiling.probationLargeTransfer() > 0L && attempt.senderBalanceMicros() > 0L
            && attempt.amountMicros() >= profiling.probationLargeTransfer()
            && attempt.amountMicros() >= attempt.senderBalanceMicros() * profiling.balanceDrainRatio()) {
            add(reasons, RiskRule.BALANCE_DRAIN, scores.balanceDrain());
        }
        if (attempt.sender().linkedAccountCount() + 1 >= profiling.ipClusterAccounts()
            || attempt.receiver().linkedAccountCount() + 1 >= profiling.ipClusterAccounts()) {
            add(reasons, RiskRule.IP_CLUSTER, scores.ipCluster());
        }
        if (attempt.sender().recentIpCount() >= profiling.ipChurnCount()
            || attempt.receiver().recentIpCount() >= profiling.ipChurnCount()) {
            add(reasons, RiskRule.IP_CHURN, scores.ipChurn());
        }

        long dormantMillis = profiling.dormantDays() * 86_400_000L;
        if (dormantMillis > 0L && attempt.amountMicros() >= profiling.dormantLargeTransfer()
            && (wasDormant(attempt.sender().previousSeenMillis(), attempt.createdAtMillis(), dormantMillis)
            || wasDormant(attempt.receiver().previousSeenMillis(), attempt.createdAtMillis(), dormantMillis))) {
            add(reasons, RiskRule.DORMANT_REACTIVATION, scores.dormantReactivation());
        }
        if (snapshot.repeatedAmountCount() + 1 >= profiling.repeatedAmountCount()) {
            add(reasons, RiskRule.REPEATED_AMOUNT, scores.repeatedAmount());
        }
        if (nearLimit) {
            add(reasons, RiskRule.NEAR_LIMIT, scores.nearLimit());
        }
        if (profiling.roundAmountUnit() > 0L && attempt.amountMicros() >= profiling.roundAmountUnit()
            && attempt.amountMicros() % profiling.roundAmountUnit() == 0L) {
            add(reasons, RiskRule.ROUND_AMOUNT, scores.roundAmount());
        }

        int score = reasons.stream().mapToInt(RiskDecision.Reason::score).sum();
        boolean hard = reasons.stream().anyMatch(RiskDecision.Reason::hard);
        if (hard) {
            score = Math.max(score, scores.blockThreshold());
        }

        RiskDecision.Action action;
        if (hard || score >= scores.blockThreshold()) {
            action = RiskDecision.Action.BLOCK;
        } else if (score >= scores.reviewThreshold()) {
            action = RiskDecision.Action.REVIEW;
        } else {
            action = RiskDecision.Action.PASS;
        }
        return new RiskDecision(action, score, List.copyOf(reasons));
    }

    private static boolean isNew(long firstSeen, long now, long thresholdMillis) {
        return firstSeen <= 0L || now - firstSeen < thresholdMillis;
    }

    private static boolean wasDormant(long previousSeen, long now, long thresholdMillis) {
        return previousSeen > 0L && now > previousSeen && now - previousSeen >= thresholdMillis;
    }

    private static boolean exceeds(long current, long amount, long limit) {
        return limit > 0L && RiskLedger.safeAdd(current, amount) > limit;
    }

    private static boolean nearAnyLimit(long amount, CounterSnapshot snapshot, RiskConfig.Limits limits,
                                        double ratio) {
        return near(snapshot.senderDaily(), amount, limits.senderDaily(), ratio)
            || near(snapshot.receiverDaily(), amount, limits.receiverDaily(), ratio)
            || near(snapshot.pairDaily(), amount, limits.pairDaily(), ratio)
            || near(snapshot.clusterDaily(), amount, limits.clusterDaily(), ratio)
            || near(0L, amount, limits.maxSingle(), ratio);
    }

    private static boolean near(long current, long amount, long limit, double ratio) {
        if (limit <= 0L || ratio <= 0.0D) {
            return false;
        }
        return RiskLedger.safeAdd(current, amount) >= (long) Math.floor(limit * ratio);
    }

    private static long earnedCapacity(long playSeconds, RiskConfig.Profiling profiling) {
        long rate = profiling.earnedCapacityPerPlayHour();
        long wholeHours = Math.max(0L, playSeconds) / 3_600L;
        long remainder = Math.max(0L, playSeconds) % 3_600L;
        long whole;
        long partial;
        try {
            whole = Math.multiplyExact(rate, wholeHours);
            partial = Math.multiplyExact(rate, remainder) / 3_600L;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
        return RiskLedger.safeAdd(profiling.baseEarnedCapacity(), RiskLedger.safeAdd(whole, partial));
    }

    private static void hard(List<RiskDecision.Reason> reasons, RiskRule rule) {
        reasons.add(new RiskDecision.Reason(rule, 0, true));
    }

    private static void add(List<RiskDecision.Reason> reasons, RiskRule rule, int score) {
        if (score > 0) {
            reasons.add(new RiskDecision.Reason(rule, score, false));
        }
    }
}
