package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public final class PendingTransfer {

    private final TransferAttempt attempt;
    private final RiskDecision decision;
    private final long expiresAtMillis;
    private boolean creditSeen;
    private boolean debitSeen;

    public PendingTransfer(TransferAttempt attempt, RiskDecision decision, long expiresAtMillis) {
        this.attempt = attempt;
        this.decision = decision;
        this.expiresAtMillis = expiresAtMillis;
    }

    public TransferAttempt attempt() {
        return this.attempt;
    }

    public RiskDecision decision() {
        return this.decision;
    }

    public long expiresAtMillis() {
        return this.expiresAtMillis;
    }

    public synchronized LegResult markLeg(Leg leg) {
        if (leg == Leg.CREDIT) {
            if (this.creditSeen) {
                return LegResult.DUPLICATE;
            }
            this.creditSeen = true;
        } else {
            if (this.debitSeen) {
                return LegResult.DUPLICATE;
            }
            this.debitSeen = true;
        }
        return this.creditSeen && this.debitSeen ? LegResult.COMPLETE : LegResult.PARTIAL;
    }

    public synchronized boolean hasAnyLeg() {
        return this.creditSeen || this.debitSeen;
    }

    public UUID id() {
        return this.attempt.transactionId();
    }

    public enum Leg {
        CREDIT,
        DEBIT
    }

    public enum LegResult {
        PARTIAL,
        COMPLETE,
        DUPLICATE
    }
}
