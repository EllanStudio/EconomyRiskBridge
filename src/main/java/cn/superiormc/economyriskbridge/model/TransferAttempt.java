package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record TransferAttempt(
    UUID transactionId,
    PartyProfile sender,
    PartyProfile receiver,
    String currencyId,
    long amountMicros,
    long senderBalanceMicros,
    long createdAtMillis,
    boolean sameRecentIp,
    boolean trustedPair,
    boolean shopAltLimitPattern
) {
}
