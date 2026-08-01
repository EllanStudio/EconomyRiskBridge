package cn.superiormc.economyriskbridge.integration;

import cn.superiormc.economyriskbridge.identity.IdentityService;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.model.ShopSourceRecord;
import cn.superiormc.economyriskbridge.persistence.AuditStore;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UltimateShopSourceMonitor {

    private final IdentityService identities;
    private final AuditStore store;
    private final Logger logger;
    private final ZoneId zoneId;
    private final Map<SourceKey, SourceState> states = new HashMap<>();
    private final Set<ClusterDay> violatedClusters = new HashSet<>();
    private final AtomicLong lastQueueWarning = new AtomicLong();
    private Method getPlayer;
    private Method isBuyOrSell;
    private Method getAmount;
    private Method getItem;
    private Method getPlayerSellLimit;
    private Method getSellTimesMaxValue;

    public UltimateShopSourceMonitor(IdentityService identities, AuditStore store, Logger logger, ZoneId zoneId,
                                     List<ShopSourceRecord> bootstrap) {
        this.identities = identities;
        this.store = store;
        this.logger = logger;
        this.zoneId = zoneId;
        bootstrap.forEach(this::apply);
    }

    public void register(JavaPlugin owner, Plugin ultimateShop) throws ReflectiveOperationException {
        ClassLoader loader = ultimateShop.getClass().getClassLoader();
        Class<? extends Event> eventClass = Class.forName(
            "cn.superiormc.ultimateshop.api.ItemFinishTransactionEvent", true, loader).asSubclass(Event.class);
        this.getPlayer = eventClass.getMethod("getPlayer");
        this.isBuyOrSell = eventClass.getMethod("isBuyOrSell");
        this.getAmount = eventClass.getMethod("getAmount");
        this.getItem = eventClass.getMethod("getItem");
        Class<?> itemClass = this.getItem.getReturnType();
        this.getPlayerSellLimit = itemClass.getMethod("getPlayerSellLimit", Player.class);
        this.getSellTimesMaxValue = itemClass.getMethod("getSellTimesMaxValue", Player.class);

        Listener marker = new Listener() {
        };
        owner.getServer().getPluginManager().registerEvent(eventClass, marker, EventPriority.MONITOR,
            (listener, event) -> onShopTransaction(event), owner, true);
    }

    private void onShopTransaction(Event event) {
        try {
            // UltimateShop uses false for SELL and true for BUY.
            if ((boolean) this.isBuyOrSell.invoke(event)) {
                return;
            }
            int amount = (int) this.getAmount.invoke(event);
            if (amount <= 0) {
                return;
            }
            Player player = (Player) this.getPlayer.invoke(event);
            Object item = this.getItem.invoke(event);
            int limit = effectiveLimit(item, player);
            if (limit <= 0) {
                return;
            }
            long now = System.currentTimeMillis();
            PartyProfile profile = this.identities.profile(player.getUniqueId(), player.getName(), now);
            ShopSourceRecord record = new ShopSourceRecord(now, epochDay(now), player.getUniqueId(),
                profile.clusterKey(), item.toString(), amount, limit);
            apply(record);
            if (!this.store.enqueueShopSource(record)) {
                long previous = this.lastQueueWarning.get();
                if (now - previous > 60_000L && this.lastQueueWarning.compareAndSet(previous, now)) {
                    this.logger.warning("Could not persist UltimateShop source evidence because the audit queue is unavailable.");
                }
            }
        } catch (ReflectiveOperationException | ClassCastException exception) {
            this.logger.log(Level.WARNING, "Could not read an UltimateShop transaction event", exception);
        }
    }

    public synchronized boolean hasViolation(String clusterKey, long nowMillis) {
        return this.violatedClusters.contains(new ClusterDay(epochDay(nowMillis), clusterKey));
    }

    private synchronized void apply(ShopSourceRecord record) {
        SourceKey key = new SourceKey(record.epochDay(), record.clusterKey(), record.sourceKey());
        SourceState state = this.states.computeIfAbsent(key, ignored -> new SourceState());
        state.units = safeAdd(state.units, record.units());
        state.limit = Math.max(state.limit, record.individualLimit());
        state.players.add(record.playerId());
        if (state.players.size() >= 2 && state.limit > 0L && state.units > state.limit) {
            this.violatedClusters.add(new ClusterDay(record.epochDay(), record.clusterKey()));
        }
        long oldestDay = epochDay(System.currentTimeMillis()) - 1L;
        this.states.keySet().removeIf(existing -> existing.epochDay < oldestDay);
        this.violatedClusters.removeIf(existing -> existing.epochDay < oldestDay);
    }

    private int effectiveLimit(Object item, Player player) throws ReflectiveOperationException {
        int transactionLimit = (int) this.getPlayerSellLimit.invoke(item, player);
        int useTimesLimit = (int) this.getSellTimesMaxValue.invoke(item, player);
        if (transactionLimit > 0 && useTimesLimit > 0) {
            return Math.min(transactionLimit, useTimesLimit);
        }
        return Math.max(transactionLimit, useTimesLimit);
    }

    private long epochDay(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), this.zoneId).toEpochDay();
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private record SourceKey(long epochDay, String clusterKey, String sourceKey) {
    }

    private record ClusterDay(long epochDay, String clusterKey) {
    }

    private static final class SourceState {
        private long units;
        private long limit;
        private final Set<UUID> players = new HashSet<>();
    }
}
