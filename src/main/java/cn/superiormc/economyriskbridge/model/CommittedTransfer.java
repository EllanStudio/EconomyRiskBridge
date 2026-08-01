package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record CommittedTransfer(
    UUID transactionId,
    long settledAtMillis,
    UUID senderId,
    UUID receiverId,
    String currencyId,
    long amountMicros,
    String senderCluster
) {
}
