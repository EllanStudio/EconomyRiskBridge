package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record IdentityRecord(
    UUID playerId,
    String playerName,
    String ipHash,
    long firstSeenMillis,
    long lastSeenMillis,
    long playSeconds
) {
}
