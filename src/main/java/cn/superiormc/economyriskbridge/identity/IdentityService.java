package cn.superiormc.economyriskbridge.identity;

import cn.superiormc.economyriskbridge.model.IdentityRecord;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.persistence.AuditStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class IdentityService {

    private static final int LINKED_PROFILE_COUNT_CAP = 256;

    private final AuditStore store;
    private final Logger logger;
    private final long retentionMillis;
    private final ThreadLocal<Mac> hmac;
    private final Map<UUID, ProfileData> profiles = new HashMap<>();
    private final Map<String, UUID> names = new HashMap<>();
    private final Map<String, Set<UUID>> playersByIp = new HashMap<>();
    private final Set<PairKey> trustedPairs = new HashSet<>();
    private final Map<UUID, String> confirmedClusters = new HashMap<>();
    private final ArrayDeque<UUID> maintenanceQueue = new ArrayDeque<>();
    private final AtomicLong lastQueueWarning = new AtomicLong();
    private long nextMaintenanceSweepMillis;

    public IdentityService(String encodedSecret, int retentionDays, AuditStore store, Logger logger) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("privacy.ip-hmac-secret is not valid Base64", exception);
        }
        if (secret.length < 32) {
            throw new IllegalArgumentException("privacy.ip-hmac-secret must contain at least 32 random bytes");
        }
        this.store = store;
        this.logger = logger;
        this.retentionMillis = retentionDays * 86_400_000L;
        this.hmac = ThreadLocal.withInitial(() -> createMac(secret));
    }

    public synchronized void bootstrap(AuditStore.Bootstrap bootstrap) {
        long cutoff = System.currentTimeMillis() - this.retentionMillis;
        for (IdentityRecord record : bootstrap.identities()) {
            if (record.lastSeenMillis() < cutoff) {
                continue;
            }
            ProfileData profile = this.profiles.computeIfAbsent(record.playerId(), ProfileData::new);
            profile.name = record.playerName();
            profile.firstSeen = profile.firstSeen == 0L ? record.firstSeenMillis()
                : Math.min(profile.firstSeen, record.firstSeenMillis());
            profile.lastSeen = Math.max(profile.lastSeen, record.lastSeenMillis());
            profile.playSeconds = Math.max(profile.playSeconds, record.playSeconds());
            if (!record.ipHash().isEmpty()) {
                profile.ipLastSeen.merge(record.ipHash(), record.lastSeenMillis(), Math::max);
                this.playersByIp.computeIfAbsent(record.ipHash(), ignored -> new HashSet<>()).add(record.playerId());
            }
            this.names.put(record.playerName().toLowerCase(Locale.ROOT), record.playerId());
        }
        for (AuditStore.TrustedPair pair : bootstrap.trustedPairs()) {
            this.trustedPairs.add(PairKey.of(pair.left(), pair.right()));
        }
        for (AuditStore.ConfirmedCluster cluster : bootstrap.clusters()) {
            this.confirmedClusters.put(cluster.playerId(), cluster.clusterId());
        }
        this.profiles.values().forEach(profile -> profile.previousSeen = profile.lastSeen);
    }

    public void observe(UUID playerId, String name, InetAddress address, long nowMillis) {
        String ipHash = hash(address);
        IdentityRecord record;
        synchronized (this) {
            ProfileData profile = this.profiles.computeIfAbsent(playerId, ProfileData::new);
            if (profile.name != null) {
                this.names.remove(profile.name.toLowerCase(Locale.ROOT), playerId);
            }
            profile.name = name;
            if (profile.lastSeen > 0L && nowMillis > profile.lastSeen) {
                profile.previousSeen = profile.lastSeen;
            }
            profile.firstSeen = profile.firstSeen == 0L ? nowMillis : Math.min(profile.firstSeen, nowMillis);
            profile.lastSeen = Math.max(profile.lastSeen, nowMillis);
            profile.ipLastSeen.put(ipHash, nowMillis);
            this.names.put(name.toLowerCase(Locale.ROOT), playerId);
            this.playersByIp.computeIfAbsent(ipHash, ignored -> new HashSet<>()).add(playerId);
            pruneProfile(profile, nowMillis - this.retentionMillis);
            record = new IdentityRecord(playerId, name, ipHash, profile.firstSeen, nowMillis, profile.playSeconds);
        }
        persist(record);
    }

    public void rememberName(UUID playerId, String name, long nowMillis) {
        IdentityRecord record;
        synchronized (this) {
            ProfileData profile = this.profiles.computeIfAbsent(playerId, ProfileData::new);
            if (profile.name != null) {
                this.names.remove(profile.name.toLowerCase(Locale.ROOT), playerId);
            }
            profile.name = name;
            if (profile.firstSeen == 0L) {
                profile.firstSeen = nowMillis;
                profile.lastSeen = nowMillis;
            }
            this.names.put(name.toLowerCase(Locale.ROOT), playerId);
            record = new IdentityRecord(playerId, name, "", profile.firstSeen, profile.lastSeen, profile.playSeconds);
        }
        persist(record);
    }

    public void updatePlaytime(UUID playerId, String name, long firstPlayedMillis, long playSeconds, long nowMillis) {
        List<IdentityRecord> records = new ArrayList<>();
        synchronized (this) {
            ProfileData profile = this.profiles.computeIfAbsent(playerId, ProfileData::new);
            profile.name = name;
            long reliableFirstSeen = firstPlayedMillis > 0L ? firstPlayedMillis : nowMillis;
            profile.firstSeen = profile.firstSeen == 0L ? reliableFirstSeen
                : Math.min(profile.firstSeen, reliableFirstSeen);
            profile.lastSeen = Math.max(profile.lastSeen, nowMillis);
            profile.playSeconds = Math.max(profile.playSeconds, playSeconds);
            this.names.put(name.toLowerCase(Locale.ROOT), playerId);
            if (profile.ipLastSeen.isEmpty()) {
                records.add(new IdentityRecord(playerId, name, "", profile.firstSeen, profile.lastSeen,
                    profile.playSeconds));
            } else {
                for (var entry : profile.ipLastSeen.entrySet()) {
                    records.add(new IdentityRecord(playerId, name, entry.getKey(), profile.firstSeen,
                        entry.getValue(), profile.playSeconds));
                }
            }
        }
        records.forEach(this::persist);
    }

    public synchronized Optional<UUID> resolveName(String name) {
        return Optional.ofNullable(this.names.get(name.toLowerCase(Locale.ROOT)));
    }

    public PartyProfile profile(UUID playerId, String fallbackName, long nowMillis) {
        return profile(playerId, fallbackName, nowMillis, LINKED_PROFILE_COUNT_CAP);
    }

    public synchronized PartyProfile profile(UUID playerId, String fallbackName, long nowMillis,
                                             int maximumLinkedAccounts) {
        ProfileData profile = this.profiles.get(playerId);
        if (profile == null) {
            return new PartyProfile(playerId, fallbackName, nowMillis, 0L, 0L, "uuid:" + playerId, "", 0, 0);
        }
        pruneProfile(profile, nowMillis - this.retentionMillis);
        String mostRecentIp = profile.ipLastSeen.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        String cluster = this.confirmedClusters.get(playerId);
        if (cluster == null) {
            cluster = mostRecentIp.isEmpty() ? "uuid:" + playerId : "ip:" + mostRecentIp;
        } else {
            cluster = "confirmed:" + cluster;
        }
        Set<UUID> linked = linkedIds(playerId, profile, nowMillis - this.retentionMillis,
            Math.max(0, maximumLinkedAccounts));
        return new PartyProfile(playerId, profile.name == null ? fallbackName : profile.name,
            profile.firstSeen, profile.previousSeen, profile.playSeconds, cluster, mostRecentIp,
            profile.ipLastSeen.size(), linked.size());
    }

    public synchronized boolean shareRecentIp(UUID left, UUID right, long nowMillis) {
        if (left.equals(right)) {
            return true;
        }
        ProfileData one = this.profiles.get(left);
        ProfileData two = this.profiles.get(right);
        if (one == null || two == null) {
            return false;
        }
        long cutoff = nowMillis - this.retentionMillis;
        for (var entry : one.ipLastSeen.entrySet()) {
            if (entry.getValue() >= cutoff && two.ipLastSeen.getOrDefault(entry.getKey(), 0L) >= cutoff) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<PartyProfile> linkedAccounts(UUID playerId, long nowMillis) {
        ProfileData profile = this.profiles.get(playerId);
        if (profile == null) {
            return List.of();
        }
        long cutoff = nowMillis - this.retentionMillis;
        Set<UUID> linked = linkedIds(playerId, profile, cutoff, LINKED_PROFILE_COUNT_CAP);
        return linked.stream().map(id -> profile(id, id.toString(), nowMillis))
            .sorted(Comparator.comparing(PartyProfile::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /**
     * Returns every account connected to {@code playerId} through one or more recent shared-IP edges.
     * A and C are therefore related when A shared an IP with B and B shared another IP with C, while
     * accounts whose retained IP sets are completely disjoint are never returned.
     */
    public synchronized Set<UUID> relatedAccountIds(UUID playerId, long nowMillis, int maximum) {
        if (maximum <= 0) {
            return Set.of();
        }
        ProfileData profile = this.profiles.get(playerId);
        if (profile == null) {
            return Set.of();
        }
        return Set.copyOf(linkedIds(playerId, profile, nowMillis - this.retentionMillis, maximum));
    }

    public synchronized boolean hasRecentIpRelation(UUID left, UUID right, long nowMillis, int maximum) {
        if (left.equals(right)) {
            return true;
        }
        if (maximum <= 0) {
            return false;
        }
        ProfileData profile = this.profiles.get(left);
        if (profile == null || !this.profiles.containsKey(right)) {
            return false;
        }
        return linkedIds(left, profile, nowMillis - this.retentionMillis, maximum).contains(right);
    }

    public synchronized boolean isTrusted(UUID left, UUID right) {
        return this.trustedPairs.contains(PairKey.of(left, right));
    }

    public synchronized boolean setTrusted(UUID left, UUID right, boolean trusted, String actor) {
        if (left.equals(right)) {
            return false;
        }
        PairKey pair = PairKey.of(left, right);
        boolean changed = trusted ? this.trustedPairs.add(pair) : this.trustedPairs.remove(pair);
        if (changed) {
            this.store.enqueueTrust(pair.left, pair.right, trusted, actor, System.currentTimeMillis());
        }
        return changed;
    }

    public synchronized boolean setConfirmedCluster(UUID playerId, String clusterId, String actor) {
        String normalized = clusterId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (normalized.isEmpty() || normalized.length() > 48) {
            return false;
        }
        String old = this.confirmedClusters.put(playerId, normalized);
        if (normalized.equals(old)) {
            return false;
        }
        this.store.enqueueCluster(playerId, normalized, true, actor, System.currentTimeMillis());
        return true;
    }

    public synchronized boolean removeConfirmedCluster(UUID playerId, String actor) {
        if (this.confirmedClusters.remove(playerId) == null) {
            return false;
        }
        this.store.enqueueCluster(playerId, "", false, actor, System.currentTimeMillis());
        return true;
    }

    public synchronized void maintenance(long nowMillis, int maximumProfiles) {
        if (maximumProfiles <= 0) {
            return;
        }
        if (this.maintenanceQueue.isEmpty()) {
            if (nowMillis < this.nextMaintenanceSweepMillis) {
                return;
            }
            this.maintenanceQueue.addAll(this.profiles.keySet());
            this.nextMaintenanceSweepMillis = nowMillis + 3_600_000L;
        }
        long cutoff = nowMillis - this.retentionMillis;
        for (int index = 0; index < maximumProfiles; index++) {
            UUID playerId = this.maintenanceQueue.pollFirst();
            if (playerId == null) {
                break;
            }
            ProfileData profile = this.profiles.get(playerId);
            if (profile == null) {
                continue;
            }
            pruneProfile(profile, cutoff);
            if (profile.lastSeen >= cutoff) {
                continue;
            }
            this.profiles.remove(playerId, profile);
            if (profile.name != null) {
                this.names.remove(profile.name.toLowerCase(Locale.ROOT), playerId);
            }
        }
    }

    private String hash(InetAddress address) {
        Mac mac = this.hmac.get();
        mac.reset();
        byte[] digest = mac.doFinal(address.getAddress());
        return HexFormat.of().formatHex(digest);
    }

    private void persist(IdentityRecord record) {
        if (!this.store.enqueueIdentity(record)) {
            long now = System.currentTimeMillis();
            long previous = this.lastQueueWarning.get();
            if (now - previous > 60_000L && this.lastQueueWarning.compareAndSet(previous, now)) {
                this.logger.warning("Audit queue unavailable while persisting identity metadata.");
            }
        }
    }

    private void pruneProfile(ProfileData profile, long cutoff) {
        var iterator = profile.ipLastSeen.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() >= cutoff) {
                continue;
            }
            Set<UUID> members = this.playersByIp.get(entry.getKey());
            if (members != null) {
                members.remove(profile.playerId);
                if (members.isEmpty()) {
                    this.playersByIp.remove(entry.getKey());
                }
            }
            iterator.remove();
        }
    }

    private Set<UUID> linkedIds(UUID playerId, ProfileData profile, long cutoff, int maximum) {
        if (maximum <= 0) {
            return Set.of();
        }
        Set<UUID> linked = new LinkedHashSet<>();
        Set<UUID> visitedPlayers = new HashSet<>();
        Set<String> visitedIps = new HashSet<>();
        ArrayDeque<UUID> pendingPlayers = new ArrayDeque<>();
        visitedPlayers.add(playerId);
        pendingPlayers.addLast(playerId);

        while (!pendingPlayers.isEmpty()) {
            UUID currentId = pendingPlayers.removeFirst();
            ProfileData current = currentId.equals(playerId) ? profile : this.profiles.get(currentId);
            if (current == null) {
                continue;
            }
            for (var entry : current.ipLastSeen.entrySet()) {
                if (entry.getValue() < cutoff || !visitedIps.add(entry.getKey())) {
                    continue;
                }
                for (UUID candidate : this.playersByIp.getOrDefault(entry.getKey(), Set.of())) {
                    ProfileData candidateProfile = this.profiles.get(candidate);
                    if (candidateProfile == null
                        || candidateProfile.ipLastSeen.getOrDefault(entry.getKey(), 0L) < cutoff
                        || !visitedPlayers.add(candidate)) {
                        continue;
                    }
                    pendingPlayers.addLast(candidate);
                    if (!candidate.equals(playerId)) {
                        linked.add(candidate);
                        if (linked.size() >= maximum) {
                            return linked;
                        }
                    }
                }
            }
        }
        return linked;
    }

    private static Mac createMac(byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static final class ProfileData {
        private final UUID playerId;
        private String name;
        private long firstSeen;
        private long previousSeen;
        private long lastSeen;
        private long playSeconds;
        private final Map<String, Long> ipLastSeen = new HashMap<>();

        private ProfileData(UUID playerId) {
            this.playerId = playerId;
        }
    }

    private record PairKey(UUID left, UUID right) {
        private static PairKey of(UUID one, UUID two) {
            return one.compareTo(two) <= 0 ? new PairKey(one, two) : new PairKey(two, one);
        }
    }
}
