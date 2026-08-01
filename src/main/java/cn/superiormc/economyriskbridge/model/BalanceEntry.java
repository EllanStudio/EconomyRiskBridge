package cn.superiormc.economyriskbridge.model;

import cn.superiormc.economyriskbridge.provenance.SourceCatalog;

import java.util.UUID;

public record BalanceEntry(
    UUID entryId,
    long occurredAtMillis,
    UUID playerId,
    String playerName,
    String currencyId,
    long deltaMicros,
    long balanceBeforeMicros,
    long balanceAfterMicros,
    SourceCatalog source,
    Confidence confidence,
    String detail,
    UUID transactionId,
    UUID caseId,
    UUID counterpartyId,
    String counterpartyName
) {

    public enum Confidence {
        VERIFIED,
        INFERRED,
        UNKNOWN
    }
}
