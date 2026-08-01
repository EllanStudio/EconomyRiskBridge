package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record CaseRecord(
    UUID caseId,
    UUID transactionId,
    long createdAtMillis,
    Severity severity,
    Status status,
    UUID senderId,
    String senderName,
    UUID receiverId,
    String receiverName,
    String currencyId,
    long amountMicros,
    int score,
    String reasonCodes,
    String note,
    String reviewer
) {
    public CaseRecord withStatus(Status newStatus, String newReviewer, String newNote) {
        return new CaseRecord(this.caseId, this.transactionId, this.createdAtMillis, this.severity, newStatus,
            this.senderId, this.senderName, this.receiverId, this.receiverName, this.currencyId, this.amountMicros,
            this.score, this.reasonCodes, newNote, newReviewer);
    }

    public enum Severity {
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public enum Status {
        OPEN,
        ACKNOWLEDGED,
        CLOSED
    }
}
