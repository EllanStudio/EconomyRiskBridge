package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record ShopSourceRecord(
    long createdAtMillis,
    long epochDay,
    UUID playerId,
    String clusterKey,
    String sourceKey,
    long units,
    long individualLimit
) {
}
