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
    String reviewer,
    Kind kind,
    String sourceKey,
    long sourceUnits,
    long sourceLimit,
    String relatedAccounts
) {
    public CaseRecord(UUID caseId, UUID transactionId, long createdAtMillis, Severity severity, Status status,
                      UUID senderId, String senderName, UUID receiverId, String receiverName, String currencyId,
                      long amountMicros, int score, String reasonCodes, String note, String reviewer) {
        this(caseId, transactionId, createdAtMillis, severity, status, senderId, senderName, receiverId,
            receiverName, currencyId, amountMicros, score, reasonCodes, note, reviewer, Kind.TRANSFER,
            "", 0L, 0L, "");
    }

    public static CaseRecord shopSource(UUID caseId, long createdAtMillis, Severity severity, UUID playerId,
                                        String playerName, String sourceKey, long sourceUnits, long sourceLimit,
                                        int score, String reasonCodes, String note, String relatedAccounts) {
        return new CaseRecord(caseId, UUID.randomUUID(), createdAtMillis, severity, Status.OPEN, playerId,
            playerName, playerId, "UltimateShop", "", 0L, score, reasonCodes, note, "", Kind.SHOP_SOURCE,
            sourceKey, sourceUnits, sourceLimit, relatedAccounts);
    }

    public static CaseRecord quickShop(UUID caseId, long createdAtMillis, Severity severity, UUID senderId,
                                       String senderName, UUID receiverId, String receiverName, String currencyId,
                                       long amountMicros, int score, String reasonCodes, String note) {
        return new CaseRecord(caseId, UUID.randomUUID(), createdAtMillis, severity, Status.OPEN, senderId,
            senderName, receiverId, receiverName, currencyId, amountMicros, score, reasonCodes, note, "",
            Kind.QUICK_SHOP, "", 0L, 0L, "");
    }

    public CaseRecord withStatus(Status newStatus, String newReviewer, String newNote) {
        return new CaseRecord(this.caseId, this.transactionId, this.createdAtMillis, this.severity, newStatus,
            this.senderId, this.senderName, this.receiverId, this.receiverName, this.currencyId, this.amountMicros,
            this.score, this.reasonCodes, newNote, newReviewer, this.kind, this.sourceKey, this.sourceUnits,
            this.sourceLimit, this.relatedAccounts);
    }

    public boolean isShopSource() {
        return this.kind == Kind.SHOP_SOURCE;
    }

    public enum Kind {
        TRANSFER,
        SHOP_SOURCE,
        QUICK_SHOP
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
