package cn.superiormc.economyriskbridge.integration;

import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.identity.IdentityService;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.util.Money;
import net.kyori.adventure.text.Component;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Records QuickShop purchases as verified player-to-player value movements when both parties are
 * connected by the recent anonymised IP graph. Completely disjoint IP histories are ignored. */
public final class QuickShopTransactionMonitor {

    private final IdentityService identities;
    private final Logger logger;
    private final ZoneId zoneId;
    private final Function<Violation, UUID> caseHandler;
    private final BiConsumer<Player, UUID> blockedNotifier;
    private final Map<PairDay, UUID> reportedPairs = new HashMap<>();
    private volatile RiskConfig config;

    public QuickShopTransactionMonitor(IdentityService identities, Logger logger, ZoneId zoneId,
                                       List<CaseRecord> cases, RiskConfig config,
                                       Function<Violation, UUID> caseHandler,
                                       BiConsumer<Player, UUID> blockedNotifier) {
        this.identities = identities;
        this.logger = logger;
        this.zoneId = zoneId;
        this.config = config;
        this.caseHandler = caseHandler;
        this.blockedNotifier = blockedNotifier;
        restoreReportedPairs(cases);
    }

    public void register(JavaPlugin owner, Plugin quickShop) throws ReflectiveOperationException {
        ClassLoader loader = quickShop.getClass().getClassLoader();
        Class<? extends Event> preClass = SparrowAccess.eventClass(loader,
            "com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent");
        Class<? extends Event> successClass = SparrowAccess.eventClass(loader,
            "com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent");
        EventAccess preAccess = access(preClass, "getTotal");
        EventAccess successAccess = access(successClass, "getBalanceWithoutTax");

        Listener preMarker = new Listener() {
        };
        owner.getServer().getPluginManager().registerEvent(preClass, preMarker, EventPriority.HIGHEST,
            (listener, event) -> {
                if (preClass.isInstance(event)) {
                    onPrePurchase(event, preAccess);
                }
            }, owner, true);
        Listener successMarker = new Listener() {
        };
        owner.getServer().getPluginManager().registerEvent(successClass, successMarker, EventPriority.MONITOR,
            (listener, event) -> {
                if (successClass.isInstance(event)) {
                    onSuccessfulPurchase(event, successAccess);
                }
            }, owner, true);
    }

    public void updateConfig(RiskConfig config) {
        this.config = config;
    }

    private void onPrePurchase(Event event, EventAccess access) {
        try {
            if (access.isCancelled != null
                && Boolean.TRUE.equals(SparrowAccess.invoke(access.isCancelled, event))) {
                return;
            }
            Trade trade = readTrade(event, access);
            if (trade == null || !isRiskRelated(trade, System.currentTimeMillis())) {
                return;
            }
            RiskConfig current = this.config;
            if (current.mode() != RiskConfig.Mode.ENFORCE || !current.limits().sameIpHardBlock()) {
                return;
            }
            if (!cancel(event, access)) {
                this.logger.warning("QuickShop pre-purchase event does not expose a supported cancellation API.");
                return;
            }
            Trade blocked = trade.withBlocked(true);
            UUID caseId = reportIfNeeded(blocked);
            Player player = trade.purchaser();
            if (player != null) {
                this.blockedNotifier.accept(player, caseId);
            }
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException exception) {
            this.logger.log(Level.WARNING, "Could not read a QuickShop pre-purchase event", exception);
        }
    }

    private boolean cancel(Event event, EventAccess access) throws ReflectiveOperationException {
        if (event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
            return true;
        }
        if (access.cancelWithReason == null) {
            return false;
        }
        SparrowAccess.invoke(access.cancelWithReason, event, true,
            Component.text("EconomyRiskBridge: ERB-R027"));
        return true;
    }

    private void onSuccessfulPurchase(Event event, EventAccess access) {
        try {
            Trade trade = readTrade(event, access);
            if (trade != null && isRiskRelated(trade, trade.createdAtMillis())) {
                reportIfNeeded(trade);
            }
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException exception) {
            this.logger.log(Level.WARNING, "Could not read a QuickShop successful-purchase event", exception);
        }
    }

    private boolean isRiskRelated(Trade trade, long nowMillis) {
        if (this.identities.isTrusted(trade.senderId(), trade.receiverId())) {
            return false;
        }
        return this.identities.hasRecentIpRelation(trade.senderId(), trade.receiverId(), nowMillis,
            this.config.ipRelationAccountCap());
    }

    private synchronized UUID reportIfNeeded(Trade trade) {
        prune(trade.createdAtMillis());
        PairDay key = PairDay.of(epochDay(trade.createdAtMillis()), trade.senderId(), trade.receiverId());
        UUID existing = this.reportedPairs.get(key);
        if (existing != null) {
            return existing;
        }
        Violation violation = new Violation(trade.createdAtMillis(), trade.senderId(), trade.senderName(),
            trade.receiverId(), trade.receiverName(), trade.currencyId(), trade.amountMicros(), trade.blocked());
        UUID caseId = this.caseHandler.apply(violation);
        this.reportedPairs.put(key, caseId);
        return caseId;
    }

    private Trade readTrade(Event event, EventAccess access) throws ReflectiveOperationException {
        Object purchaserUser = SparrowAccess.invoke(access.getPurchaser, event);
        Object shop = SparrowAccess.invoke(access.getShop, event);
        if ((boolean) SparrowAccess.invoke(access.shopIsUnlimited, shop)) {
            return null;
        }
        Object ownerUser = SparrowAccess.invoke(access.shopGetOwner, shop);
        UUID purchaserId = (UUID) SparrowAccess.invoke(access.userGetUniqueId, purchaserUser);
        UUID ownerId = (UUID) SparrowAccess.invoke(access.userGetUniqueId, ownerUser);
        if (purchaserId == null || ownerId == null || purchaserId.equals(ownerId)) {
            return null;
        }
        long amount = Money.fromDouble(((Number) SparrowAccess.invoke(access.getTotal, event)).doubleValue());
        if (amount <= 0L) {
            return null;
        }
        String purchaserName = userName(access, purchaserUser, purchaserId);
        String ownerName = userName(access, ownerUser, ownerId);
        Object rawCurrency = SparrowAccess.invoke(access.shopGetCurrency, shop);
        String currency = rawCurrency == null || rawCurrency.toString().isBlank()
            ? "default" : rawCurrency.toString().toLowerCase(Locale.ROOT);
        boolean shopBuying = (boolean) SparrowAccess.invoke(access.shopIsBuying, shop);
        Player purchaser = access.userGetBukkitPlayer == null ? null
            : optionalPlayer(SparrowAccess.invoke(access.userGetBukkitPlayer, purchaserUser));
        long now = System.currentTimeMillis();
        return shopBuying
            ? new Trade(now, ownerId, ownerName, purchaserId, purchaserName, purchaser, currency, amount, false)
            : new Trade(now, purchaserId, purchaserName, ownerId, ownerName, purchaser, currency, amount, false);
    }

    private static Player optionalPlayer(Object optional) {
        if (optional instanceof java.util.Optional<?> value && value.orElse(null) instanceof Player player) {
            return player;
        }
        return null;
    }

    private static String userName(EventAccess access, Object user, UUID fallback) throws ReflectiveOperationException {
        Object value = SparrowAccess.invoke(access.userGetUsername, user);
        return value == null || value.toString().isBlank() ? fallback.toString() : value.toString();
    }

    private EventAccess access(Class<? extends Event> eventClass, String totalMethod)
        throws ReflectiveOperationException {
        SparrowAccess.Accessor getPurchaser = SparrowAccess.required(eventClass, "getPurchaser");
        SparrowAccess.Accessor getShop = SparrowAccess.required(eventClass, "getShop");
        Class<?> userClass = getPurchaser.returnType();
        Class<?> shopClass = getShop.returnType();
        return new EventAccess(getPurchaser, getShop, SparrowAccess.required(eventClass, totalMethod),
            SparrowAccess.required(shopClass, "getOwner"), SparrowAccess.required(shopClass, "isBuying"),
            SparrowAccess.required(shopClass, "isUnlimited"), SparrowAccess.required(shopClass, "getCurrency"),
            SparrowAccess.required(userClass, "getUniqueId"), SparrowAccess.required(userClass, "getUsername"),
            SparrowAccess.optional(userClass, "getBukkitPlayer"),
            SparrowAccess.optional(eventClass, "isCancelled"),
            SparrowAccess.optional(eventClass, "setCancelled", boolean.class, Component.class));
    }

    private void restoreReportedPairs(List<CaseRecord> cases) {
        for (CaseRecord record : cases) {
            if (record.kind() != CaseRecord.Kind.QUICK_SHOP || record.status() == CaseRecord.Status.CLOSED) {
                continue;
            }
            this.reportedPairs.put(PairDay.of(epochDay(record.createdAtMillis()), record.senderId(),
                record.receiverId()), record.caseId());
        }
        prune(System.currentTimeMillis());
    }

    private void prune(long nowMillis) {
        long currentDay = epochDay(nowMillis);
        this.reportedPairs.keySet().removeIf(key -> key.epochDay < currentDay - 1L);
    }

    private long epochDay(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), this.zoneId).toEpochDay();
    }

    public record Violation(long createdAtMillis, UUID senderId, String senderName, UUID receiverId,
                            String receiverName, String currencyId, long amountMicros, boolean blocked) {
    }

    private record EventAccess(SparrowAccess.Accessor getPurchaser, SparrowAccess.Accessor getShop,
                               SparrowAccess.Accessor getTotal, SparrowAccess.Accessor shopGetOwner,
                               SparrowAccess.Accessor shopIsBuying, SparrowAccess.Accessor shopIsUnlimited,
                               SparrowAccess.Accessor shopGetCurrency, SparrowAccess.Accessor userGetUniqueId,
                               SparrowAccess.Accessor userGetUsername,
                               SparrowAccess.Accessor userGetBukkitPlayer,
                               SparrowAccess.Accessor isCancelled,
                               SparrowAccess.Accessor cancelWithReason) {
    }

    private record Trade(long createdAtMillis, UUID senderId, String senderName, UUID receiverId,
                         String receiverName, Player purchaser, String currencyId, long amountMicros,
                         boolean blocked) {

        private Trade withBlocked(boolean value) {
            return new Trade(this.createdAtMillis, this.senderId, this.senderName, this.receiverId,
                this.receiverName, this.purchaser, this.currencyId, this.amountMicros, value);
        }
    }

    private record PairDay(long epochDay, UUID left, UUID right) {

        private static PairDay of(long epochDay, UUID one, UUID two) {
            return one.compareTo(two) <= 0 ? new PairDay(epochDay, one, two) : new PairDay(epochDay, two, one);
        }
    }
}
