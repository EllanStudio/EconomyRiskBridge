package cn.superiormc.economyriskbridge.model;

import java.util.UUID;

public record PartyProfile(
    UUID uuid,
    String name,
    long firstSeenMillis,
    long previousSeenMillis,
    long playSeconds,
    String clusterKey,
    String ipHash,
    int recentIpCount,
    int linkedAccountCount
) {
}
