package cn.superiormc.economyriskbridge.integration;

import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.identity.IdentityService;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.model.ShopSourceRecord;
import cn.superiormc.economyriskbridge.persistence.AuditStore;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UltimateShopSourceMonitor {

    private final IdentityService identities;
    private final AuditStore store;
    private final Logger logger;
    private final ZoneId zoneId;
    private final ShopSourceLedger ledger;
    private final Function<Violation, UUID> caseHandler;
    private final BiConsumer<Player, UUID> blockedNotifier;
    private final Map<IncidentKey, ReportedIncident> reportedIncidents = new HashMap<>();
    private final AtomicLong lastQueueWarning = new AtomicLong();
    private volatile RiskConfig config;
    private volatile boolean preEnforcementAvailable;

    public UltimateShopSourceMonitor(IdentityService identities, AuditStore store, Logger logger, ZoneId zoneId,
                                     List<ShopSourceRecord> bootstrap, List<CaseRecord> cases, RiskConfig config,
                                     Function<Violation, UUID> caseHandler,
                                     BiConsumer<Player, UUID> blockedNotifier) {
        this.identities = identities;
        this.store = store;
        this.logger = logger;
        this.zoneId = zoneId;
        this.ledger = new ShopSourceLedger(bootstrap);
        this.config = config;
        this.caseHandler = caseHandler;
        this.blockedNotifier = blockedNotifier;
        restoreReportedIncidents(cases);
    }

    public void register(JavaPlugin owner, Plugin ultimateShop) throws ReflectiveOperationException {
        ClassLoader loader = ultimateShop.getClass().getClassLoader();
        Class<? extends Event> finishClass = SparrowAccess.eventClass(loader,
            "cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent");
        EventAccess finishAccess = access(finishClass);
        Listener finishMarker = new Listener() {
        };
        owner.getServer().getPluginManager().registerEvent(finishClass, finishMarker, EventPriority.MONITOR,
            (listener, event) -> onFinishedSale(event, finishAccess), owner, true);

        try {
            Class<? extends Event> preClass = SparrowAccess.eventClass(loader,
                "cn.superiormc.ultimateshop.api.ItemPreTransactionEvent");
            EventAccess preAccess = access(preClass);
            Listener preMarker = new Listener() {
            };
            owner.getServer().getPluginManager().registerEvent(preClass, preMarker, EventPriority.HIGHEST,
                (listener, event) -> onPreSale(event, preAccess), owner, true);
            this.preEnforcementAvailable = true;
        } catch (ReflectiveOperationException exception) {
            this.preEnforcementAvailable = false;
            this.logger.log(Level.WARNING, "UltimateShop pre-transaction API is unavailable; source evidence "
                + "will still be recorded, but source-side blocking is disabled", exception);
        }
    }

    public void updateConfig(RiskConfig config) {
        this.config = config;
    }

    public boolean preEnforcementAvailable() {
        return this.preEnforcementAvailable;
    }

    public boolean hasViolation(UUID playerId, long nowMillis) {
        Set<UUID> members = relatedGroup(playerId, nowMillis);
        synchronized (this) {
            pruneReportedIncidents(nowMillis);
            for (IncidentKey key : this.reportedIncidents.keySet()) {
                if (members.contains(key.playerId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void onPreSale(Event event, EventAccess access) {
        try {
            ShopSale sale = readSale(event, access);
            if (sale == null) {
                return;
            }
            RiskConfig current = this.config;
            if (current.mode() != RiskConfig.Mode.ENFORCE
                || !current.profiling().shopAltLimitHardBlock()) {
                return;
            }
            long now = System.currentTimeMillis();
            Set<UUID> related = this.identities.relatedAccountIds(sale.player().getUniqueId(), now,
                current.ipRelationAccountCap());
            if (related.isEmpty()) {
                return;
            }
            ShopSourceLedger.Assessment assessment = this.ledger.assess(epochDay(now),
                sale.player().getUniqueId(), sale.sourceKey(), sale.units(), sale.individualLimit(), related);
            if (!assessment.violation()) {
                return;
            }
            if (!(event instanceof Cancellable cancellable)) {
                this.logger.warning("UltimateShop pre-transaction event is unexpectedly not cancellable; "
                    + "source-side enforcement was skipped.");
                return;
            }
            cancellable.setCancelled(true);
            UUID caseId = reportIfNeeded(sale, assessment, now, true);
            this.blockedNotifier.accept(sale.player(), caseId);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            this.logger.log(Level.WARNING, "Could not read an UltimateShop pre-transaction event", exception);
        }
    }

    private void onFinishedSale(Event event, EventAccess access) {
        try {
            ShopSale sale = readSale(event, access);
            if (sale == null) {
                return;
            }
            long now = System.currentTimeMillis();
            PartyProfile profile = this.identities.profile(sale.player().getUniqueId(), sale.player().getName(), now);
            ShopSourceRecord record = new ShopSourceRecord(now, epochDay(now), sale.player().getUniqueId(),
                profile.clusterKey(), sale.sourceKey(), sale.units(), sale.individualLimit());
            this.ledger.commit(record);
            persist(record, now);

            Set<UUID> related = this.identities.relatedAccountIds(sale.player().getUniqueId(), now,
                this.config.ipRelationAccountCap());
            if (related.isEmpty()) {
                return;
            }
            ShopSourceLedger.Assessment assessment = this.ledger.assess(record.epochDay(), record.playerId(),
                record.sourceKey(), 0L, record.individualLimit(), related);
            if (assessment.violation()) {
                reportIfNeeded(sale, assessment, now, false);
            }
        } catch (ReflectiveOperationException | ClassCastException exception) {
            this.logger.log(Level.WARNING, "Could not read an UltimateShop transaction event", exception);
        }
    }

    private UUID reportIfNeeded(ShopSale sale, ShopSourceLedger.Assessment assessment, long nowMillis,
                                boolean blocked) {
        synchronized (this) {
            pruneReportedIncidents(nowMillis);
            for (UUID member : assessment.activePlayers()) {
                ReportedIncident existing = this.reportedIncidents.get(
                    new IncidentKey(epochDay(nowMillis), member));
                if (existing != null) {
                    return existing.caseId;
                }
            }

            Violation violation = new Violation(nowMillis, sale.player().getUniqueId(), sale.player().getName(),
                sale.sourceKey(), assessment.totalUnits(), assessment.effectiveLimit(),
                assessment.activePlayers(), blocked);
            UUID caseId = this.caseHandler.apply(violation);
            ReportedIncident incident = new ReportedIncident(caseId, nowMillis);
            for (UUID member : assessment.activePlayers()) {
                this.reportedIncidents.put(new IncidentKey(epochDay(nowMillis), member), incident);
            }
            return caseId;
        }
    }

    private void persist(ShopSourceRecord record, long nowMillis) {
        if (this.store.enqueueShopSource(record)) {
            return;
        }
        long previous = this.lastQueueWarning.get();
        if (nowMillis - previous > 60_000L && this.lastQueueWarning.compareAndSet(previous, nowMillis)) {
            this.logger.warning("Could not persist UltimateShop source evidence because the audit queue is unavailable.");
        }
    }

    private ShopSale readSale(Event event, EventAccess access) throws ReflectiveOperationException {
        // UltimateShop uses false for SELL and true for BUY.
        if ((boolean) SparrowAccess.invoke(access.isBuyOrSell, event)) {
            return null;
        }
        int amount = (int) SparrowAccess.invoke(access.getAmount, event);
        if (amount <= 0) {
            return null;
        }
        Player player = (Player) SparrowAccess.invoke(access.getPlayer, event);
        Object item = SparrowAccess.invoke(access.getItem, event);
        int limit = effectiveLimit(access, item, player);
        if (limit <= 0) {
            return null;
        }
        return new ShopSale(player, item.toString(), amount, limit);
    }

    private int effectiveLimit(EventAccess access, Object item, Player player) throws ReflectiveOperationException {
        int transactionLimit = (int) SparrowAccess.invoke(access.getPlayerSellLimit, item, player);
        int useTimesLimit = (int) SparrowAccess.invoke(access.getSellTimesMaxValue, item, player);
        if (transactionLimit > 0 && useTimesLimit > 0) {
            return Math.min(transactionLimit, useTimesLimit);
        }
        return Math.max(transactionLimit, useTimesLimit);
    }

    private EventAccess access(Class<? extends Event> eventClass) throws ReflectiveOperationException {
        SparrowAccess.Accessor getPlayer = SparrowAccess.required(eventClass, "getPlayer");
        SparrowAccess.Accessor isBuyOrSell = SparrowAccess.required(eventClass, "isBuyOrSell");
        SparrowAccess.Accessor getAmount = SparrowAccess.required(eventClass, "getAmount");
        SparrowAccess.Accessor getItem = SparrowAccess.required(eventClass, "getItem");
        Class<?> itemClass = getItem.returnType();
        return new EventAccess(getPlayer, isBuyOrSell, getAmount, getItem,
            SparrowAccess.required(itemClass, "getPlayerSellLimit", Player.class),
            SparrowAccess.required(itemClass, "getSellTimesMaxValue", Player.class));
    }

    private Set<UUID> relatedGroup(UUID playerId, long nowMillis) {
        Set<UUID> members = new LinkedHashSet<>();
        members.add(playerId);
        members.addAll(this.identities.relatedAccountIds(playerId, nowMillis,
            this.config.ipRelationAccountCap()));
        return members;
    }

    private void restoreReportedIncidents(List<CaseRecord> cases) {
        for (CaseRecord record : cases) {
            if (!record.isShopSource() || record.status() == CaseRecord.Status.CLOSED) {
                continue;
            }
            ReportedIncident incident = new ReportedIncident(record.caseId(), record.createdAtMillis());
            this.reportedIncidents.put(new IncidentKey(epochDay(record.createdAtMillis()), record.senderId()),
                incident);
            for (String value : record.relatedAccounts().split(",")) {
                try {
                    UUID playerId = UUID.fromString(value.trim());
                    this.reportedIncidents.put(new IncidentKey(epochDay(record.createdAtMillis()), playerId),
                        incident);
                } catch (IllegalArgumentException ignored) {
                    // Keep the triggering account even if an older record used a display-only member list.
                }
            }
        }
        pruneReportedIncidents(System.currentTimeMillis());
    }

    private void pruneReportedIncidents(long nowMillis) {
        long cutoff = nowMillis - this.config.shopRisk().violationLookbackDays() * 86_400_000L;
        this.reportedIncidents.entrySet().removeIf(entry -> entry.getValue().createdAtMillis < cutoff);
    }

    private long epochDay(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), this.zoneId).toEpochDay();
    }

    public record Violation(long createdAtMillis, UUID playerId, String playerName, String sourceKey,
                            long totalUnits, long individualLimit, Set<UUID> activePlayers, boolean blocked) {
    }

    private record EventAccess(SparrowAccess.Accessor getPlayer, SparrowAccess.Accessor isBuyOrSell,
                               SparrowAccess.Accessor getAmount, SparrowAccess.Accessor getItem,
                               SparrowAccess.Accessor getPlayerSellLimit,
                               SparrowAccess.Accessor getSellTimesMaxValue) {
    }

    private record ShopSale(Player player, String sourceKey, long units, long individualLimit) {
    }

    private record IncidentKey(long epochDay, UUID playerId) {
    }

    private record ReportedIncident(UUID caseId, long createdAtMillis) {
    }
}
