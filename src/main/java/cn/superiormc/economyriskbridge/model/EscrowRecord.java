package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record EscrowRecord(
    UUID caseId,
    UUID transactionId,
    Status status,
    double senderBalanceBefore,
    long heldAtMillis,
    long resolvedAtMillis,
    String resolver,
    String note
) {
    public EscrowRecord transition(Status next, long heldAt, long resolvedAt, String actor, String resolutionNote) {
        return new EscrowRecord(this.caseId, this.transactionId, next, this.senderBalanceBefore,
            heldAt == 0L ? this.heldAtMillis : heldAt,
            resolvedAt,
            actor == null ? "" : actor,
            resolutionNote == null ? "" : resolutionNote);
    }

    public enum Status {
        PREPARED,
        HELD,
        RESOLVING_RELEASE,
        RESOLVING_REFUND,
        RELEASED,
        CONFISCATED,
        REFUNDED,
        FAILED,
        NEEDS_RECONCILIATION;

        public boolean isOpen() {
            return this == PREPARED || this == HELD || this == RESOLVING_RELEASE
                || this == RESOLVING_REFUND || this == NEEDS_RECONCILIATION;
        }
    }
}
