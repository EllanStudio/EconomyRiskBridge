package cn.superiormc.economyriskbridge;

import cn.superiormc.economyriskbridge.casebook.CaseBook;
import cn.superiormc.economyriskbridge.command.AdminCommand;
import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.escrow.EscrowBook;
import cn.superiormc.economyriskbridge.identity.IdentityService;
import cn.superiormc.economyriskbridge.listener.CommandInterceptionListener;
import cn.superiormc.economyriskbridge.listener.ExcellentEconomyBalanceListener;
import cn.superiormc.economyriskbridge.listener.IdentityListener;
import cn.superiormc.economyriskbridge.listener.OperatorAlertListener;
import cn.superiormc.economyriskbridge.integration.QuickShopTransactionMonitor;
import cn.superiormc.economyriskbridge.integration.UltimateShopSourceMonitor;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.persistence.AuditStore;
import cn.superiormc.economyriskbridge.provenance.SourceDetector;
import cn.superiormc.economyriskbridge.risk.RiskLedger;
import cn.superiormc.economyriskbridge.risk.RiskRule;
import cn.superiormc.economyriskbridge.transfer.TransferCoordinator;
import cn.superiormc.economyriskbridge.util.Money;
import net.momirealms.sparrow.reflection.SReflection;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class EconomyRiskBridgePlugin extends JavaPlugin {

    private RiskConfig riskConfig;
    private AuditStore auditStore;
    private IdentityService identities;
    private RiskLedger ledger;
    private CaseBook caseBook;
    private EscrowBook escrowBook;
    private TransferCoordinator coordinator;
    private CommandInterceptionListener commandListener;
    private IdentityListener identityListener;
    private UltimateShopSourceMonitor shopSourceMonitor;
    private QuickShopTransactionMonitor quickShopMonitor;
    private final ConcurrentHashMap<UUID, Long> transferNotices = new ConcurrentHashMap<>();
    private final ArrayDeque<UUID> identityRefreshQueue = new ArrayDeque<>();
    private final Set<UUID> queuedIdentityRefresh = new HashSet<>();
    private int identityRefreshBatchSize;

    @Override
    public void onEnable() {
        SReflection.setAsmClassPrefix("EconomyRiskBridge");
        this.saveDefaultConfig();
        ensureIpSecret();

        try {
            this.riskConfig = RiskConfig.load(this.getConfig());
            this.identityRefreshBatchSize = boundedPerformanceSetting(
                "performance.identity-refresh-batch-per-tick", 16, 1, 256);
        } catch (RuntimeException exception) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "Invalid EconomyRiskBridge configuration", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        ExcellentEconomyAPI economy = Bukkit.getServicesManager().load(ExcellentEconomyAPI.class);
        if (economy == null) {
            this.getLogger().severe("ExcellentEconomy API service is unavailable.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            this.auditStore = new AuditStore(this.getDataFolder().toPath(), this.riskConfig.storage(),
                this.getLogger());
            AuditStore.Bootstrap bootstrap = this.auditStore.start(this.riskConfig.zoneId(),
                this.riskConfig.ipLinkRetentionDays());

            this.identities = new IdentityService(this.getConfig().getString("privacy.ip-hmac-secret", ""),
                this.riskConfig.ipLinkRetentionDays(), this.auditStore, this.getLogger());
            this.identities.bootstrap(bootstrap);
            this.ledger = new RiskLedger(this.riskConfig.zoneId());
            bootstrap.committedTransfers().forEach(this.ledger::bootstrap);
            this.caseBook = new CaseBook();
            this.caseBook.bootstrap(bootstrap.cases());
            this.escrowBook = new EscrowBook();
            this.escrowBook.bootstrap(bootstrap.escrows());
            this.escrowBook.bootstrapTreasury(bootstrap.treasury());

            if (this.getConfig().getBoolean("integrations.ultimate-shop.enabled", true)
                && Bukkit.getPluginManager().isPluginEnabled("UltimateShop")) {
                this.shopSourceMonitor = new UltimateShopSourceMonitor(this.identities, this.auditStore,
                    this.getLogger(), this.riskConfig.zoneId(), bootstrap.shopSources(), bootstrap.cases(),
                    this.riskConfig, this::openShopSourceCase, this::notifyShopSaleBlocked);
                try {
                    this.shopSourceMonitor.register(this, Bukkit.getPluginManager().getPlugin("UltimateShop"));
                    this.getLogger().info("UltimateShop source-side alt-limit evidence is enabled.");
                } catch (ReflectiveOperationException exception) {
                    this.getLogger().log(java.util.logging.Level.WARNING,
                        "UltimateShop integration API is incompatible; source-side evidence is disabled", exception);
                    this.shopSourceMonitor = null;
                }
            }

            if (this.getConfig().getBoolean("integrations.quick-shop.enabled", true)
                && Bukkit.getPluginManager().isPluginEnabled("QuickShop-Hikari")) {
                this.quickShopMonitor = new QuickShopTransactionMonitor(this.identities, this.getLogger(),
                    this.riskConfig.zoneId(), bootstrap.cases(), this.riskConfig, this::openQuickShopCase,
                    this::notifyQuickShopBlocked);
                try {
                    this.quickShopMonitor.register(this,
                        Bukkit.getPluginManager().getPlugin("QuickShop-Hikari"));
                    this.getLogger().info("QuickShop related-IP transaction evidence is enabled.");
                } catch (ReflectiveOperationException exception) {
                    this.getLogger().log(java.util.logging.Level.WARNING,
                        "QuickShop integration API is incompatible; related-account transaction evidence is disabled",
                        exception);
                    this.quickShopMonitor = null;
                }
            }

            SourceDetector sourceDetector = new SourceDetector(Bukkit.getPluginManager().getPlugins());
            this.coordinator = new TransferCoordinator(this, economy, this.identities, this.auditStore,
                this.ledger, this.caseBook, this.escrowBook, this.shopSourceMonitor, sourceDetector,
                this.riskConfig);
            this.coordinator.markInterruptedEscrows();
        } catch (Exception exception) {
            this.getLogger().log(java.util.logging.Level.SEVERE, "Could not initialise EconomyRiskBridge", exception);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.commandListener = new CommandInterceptionListener(this, this.coordinator, this.riskConfig);
        this.identityListener = new IdentityListener(this.identities);
        Bukkit.getPluginManager().registerEvents(this.commandListener, this);
        Bukkit.getPluginManager().registerEvents(new ExcellentEconomyBalanceListener(this.coordinator), this);
        Bukkit.getPluginManager().registerEvents(this.identityListener, this);
        Bukkit.getPluginManager().registerEvents(new OperatorAlertListener(this), this);

        AdminCommand admin = new AdminCommand(this);
        PluginCommand command = this.getCommand("economyriskbridge");
        if (command != null) {
            command.setExecutor(admin);
            command.setTabCompleter(admin);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> this.coordinator.expirePending(System.currentTimeMillis()),
            20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::drainIdentityRefreshQueue, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::queueOnlineIdentityRefresh, 20L, 2_400L);
        Bukkit.getScheduler().runTaskTimer(this, this::runCacheMaintenance, 1_200L, 1_200L);
        queueOnlineIdentityRefresh();

        this.getLogger().info("Enabled in " + this.riskConfig.mode() + " mode with "
            + this.riskConfig.commandPatterns().size() + " transfer command pattern(s). Node="
            + this.riskConfig.network().currentNode() + ", transferNode="
            + this.riskConfig.network().transferNode() + ". Raw IP addresses are not stored.");
        if (this.riskConfig.network().singleTransferNode() && !this.riskConfig.network().acceptsTransfers()) {
            this.getLogger().warning("ERB-E005: player transfers on this backend will be blocked in ENFORCE mode; "
                + "the configured transfer node is " + this.riskConfig.network().transferNode() + ".");
        }
    }

    @Override
    public void onDisable() {
        if (this.identityListener != null) {
            Bukkit.getOnlinePlayers().forEach(this.identityListener::refresh);
        }
        if (this.auditStore != null) {
            this.auditStore.close();
        }
    }

    public boolean reloadRules(CommandSender sender) {
        this.reloadConfig();
        ensureIpSecret();
        RiskConfig updated;
        try {
            updated = RiskConfig.load(this.getConfig());
        } catch (RuntimeException exception) {
            tell(sender, "&c配置无效：" + exception.getMessage());
            return false;
        }
        if (!updated.zoneId().equals(this.riskConfig.zoneId())
            || updated.ipLinkRetentionDays() != this.riskConfig.ipLinkRetentionDays()
            || !updated.storage().equals(this.riskConfig.storage())) {
            tell(sender, "&c时区、隐私保留期或存储参数发生变化；为保证计数一致，请重启服务器应用。");
            return false;
        }
        this.riskConfig = updated;
        this.identityRefreshBatchSize = boundedPerformanceSetting(
            "performance.identity-refresh-batch-per-tick", 16, 1, 256);
        this.coordinator.updateConfig(updated);
        this.commandListener.updateConfig(updated);
        if (this.shopSourceMonitor != null) {
            this.shopSourceMonitor.updateConfig(updated);
        }
        if (this.quickShopMonitor != null) {
            this.quickShopMonitor.updateConfig(updated);
        }
        tell(sender, "&a风控规则与转账命令匹配器已重载，当前模式：" + updated.mode());
        return true;
    }

    public void tell(CommandSender sender, String message) {
        String prefix = this.riskConfig == null ? "&8[&6风控&8] &r" : this.riskConfig.messages().prefix();
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + message));
    }

    public void sendTransferNotice(org.bukkit.entity.Player player) {
        int cooldownHours = this.riskConfig.messages().noticeCooldownHours();
        if (cooldownHours <= 0 || this.riskConfig.messages().transferNotice().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = cooldownHours * 3_600_000L;
        Long previous = this.transferNotices.putIfAbsent(player.getUniqueId(), now);
        if (previous != null && now - previous < cooldown) {
            return;
        }
        this.transferNotices.put(player.getUniqueId(), now);
        tell(player, this.riskConfig.messages().transferNotice());
    }

    public void refreshIdentity(org.bukkit.entity.Player player) {
        if (this.identityListener != null) {
            this.identityListener.refresh(player);
        }
    }

    public void notifyOperators(CaseRecord record) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(this, () -> notifyOperators(record));
            return;
        }
        String summary = operatorCaseSummary(record);
        if (this.getConfig().getBoolean("notifications.operator-case-alerts", true)) {
            var operators = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission("economyriskbridge.admin"))
                .toList();
            if (operators.isEmpty()) {
                if (!this.auditStore.enqueueOperatorAlert(record.caseId(), record.createdAtMillis())) {
                    this.getLogger().warning("Could not queue the offline-operator reminder for case "
                        + record.caseId() + ".");
                }
            } else {
                operators.forEach(player ->
                    tell(player, "&c[OP] " + summary + " &7使用 /erb case show 查看完整信息"));
            }
        }
        if (this.getConfig().getBoolean("notifications.console-case-alerts", true)) {
            this.getLogger().warning(summary);
        }
    }

    public String operatorCaseSummary(CaseRecord record) {
        String reasons = Arrays.stream(record.reasonCodes().split(","))
            .map(code -> RiskRule.byCode(code)
                .map(rule -> rule.code() + " " + rule.operatorReason())
                .orElse(code + " 未知或旧版原因"))
            .collect(Collectors.joining("；"));
        if (record.isShopSource()) {
            return "案件 " + record.caseId().toString().substring(0, 8) + "：来源侧 "
                + record.senderName() + " 的 IP 关系簇在 " + abbreviate(record.sourceKey(), 48)
                + " 合计售出 " + record.sourceUnits() + "/" + record.sourceLimit() + "，" + reasons;
        }
        return "案件 " + record.caseId().toString().substring(0, 8) + "："
            + record.senderName() + " -> " + record.receiverName() + "，"
            + Money.format(record.amountMicros()) + " " + record.currencyId() + "，" + reasons;
    }

    private UUID openShopSourceCase(UltimateShopSourceMonitor.Violation violation) {
        boolean hard = this.riskConfig.profiling().shopAltLimitHardBlock();
        int score = this.riskConfig.scores().shopAltLimit();
        if (hard) {
            score = Math.max(score, this.riskConfig.scores().blockThreshold());
        }
        CaseRecord.Severity severity = violation.blocked() ? CaseRecord.Severity.CRITICAL
            : hard ? CaseRecord.Severity.HIGH : CaseRecord.Severity.MEDIUM;
        String relatedIds = violation.activePlayers().stream().sorted()
            .map(UUID::toString).collect(Collectors.joining(","));
        String relatedNames = violation.activePlayers().stream()
            .map(playerId -> this.identities.profile(playerId, playerId.toString(),
                violation.createdAtMillis()).name())
            .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
        String action = violation.blocked() ? "来源侧已在发钱前取消出售" : "来源侧已记录成功出售";
        String note = action + "；IP 关系账户：" + relatedNames;
        CaseRecord record = CaseRecord.shopSource(UUID.randomUUID(), violation.createdAtMillis(), severity,
            violation.playerId(), violation.playerName(), violation.sourceKey(), violation.totalUnits(),
            violation.individualLimit(), score, RiskRule.SHOP_ALT_LIMIT.code(), note, relatedIds);
        this.caseBook.add(record);
        if (!this.auditStore.enqueueCase(record)) {
            this.getLogger().warning("Could not persist UltimateShop source case " + record.caseId() + ".");
        }
        notifyOperators(record);
        return record.caseId();
    }

    private void notifyShopSaleBlocked(Player player, UUID caseId) {
        Runnable notification = () -> {
            String shortCase = caseId == null ? "unknown" : caseId.toString().substring(0, 8);
            tell(player, this.riskConfig.messages().shopSaleBlocked().replace("{case}", shortCase));
        };
        if (Bukkit.isPrimaryThread()) {
            notification.run();
        } else {
            Bukkit.getScheduler().runTask(this, notification);
        }
    }

    private UUID openQuickShopCase(QuickShopTransactionMonitor.Violation violation) {
        int score = Math.max(this.riskConfig.scores().sameRecentIp(),
            this.riskConfig.scores().reviewThreshold());
        if (violation.blocked()) {
            score = Math.max(score, this.riskConfig.scores().blockThreshold());
        }
        CaseRecord.Severity severity = violation.blocked()
            ? CaseRecord.Severity.CRITICAL : CaseRecord.Severity.HIGH;
        String action = violation.blocked() ? "成交前已取消" : "已由 QuickShop 成功事件确认";
        String note = action + "；双方存在近期直接或链式匿名 IP 关系，非 /pay 资金路径";
        CaseRecord record = CaseRecord.quickShop(UUID.randomUUID(), violation.createdAtMillis(), severity,
            violation.senderId(), violation.senderName(), violation.receiverId(), violation.receiverName(),
            violation.currencyId(), violation.amountMicros(), score,
            RiskRule.QUICK_SHOP_RELATED_ACCOUNTS.code(), note);
        this.caseBook.add(record);
        if (!this.auditStore.enqueueCase(record)) {
            this.getLogger().warning("Could not persist QuickShop related-account case " + record.caseId() + ".");
        }
        notifyOperators(record);
        return record.caseId();
    }

    private void notifyQuickShopBlocked(Player player, UUID caseId) {
        Runnable notification = () -> {
            String shortCase = caseId == null ? "unknown" : caseId.toString().substring(0, 8);
            tell(player, this.riskConfig.messages().quickShopBlocked().replace("{case}", shortCase));
        };
        if (Bukkit.isPrimaryThread()) {
            notification.run();
        } else {
            Bukkit.getScheduler().runTask(this, notification);
        }
    }

    public RiskConfig riskConfig() {
        return this.riskConfig;
    }

    public AuditStore auditStore() {
        return this.auditStore;
    }

    public IdentityService identities() {
        return this.identities;
    }

    public RiskLedger ledger() {
        return this.ledger;
    }

    public CaseBook caseBook() {
        return this.caseBook;
    }

    public EscrowBook escrowBook() {
        return this.escrowBook;
    }

    public TransferCoordinator coordinator() {
        return this.coordinator;
    }

    public UltimateShopSourceMonitor shopSourceMonitor() {
        return this.shopSourceMonitor;
    }

    public QuickShopTransactionMonitor quickShopMonitor() {
        return this.quickShopMonitor;
    }

    private void ensureIpSecret() {
        String current = this.getConfig().getString("privacy.ip-hmac-secret", "");
        if (current != null && !current.isBlank()) {
            return;
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.getConfig().set("privacy.ip-hmac-secret", Base64.getEncoder().encodeToString(secret));
        this.saveConfig();
        this.getLogger().info("Generated a private HMAC key for pseudonymous IP linking.");
    }

    private void queueOnlineIdentityRefresh() {
        for (var player : Bukkit.getOnlinePlayers()) {
            if (this.queuedIdentityRefresh.add(player.getUniqueId())) {
                this.identityRefreshQueue.addLast(player.getUniqueId());
            }
        }
    }

    private void drainIdentityRefreshQueue() {
        for (int index = 0; index < this.identityRefreshBatchSize; index++) {
            UUID playerId = this.identityRefreshQueue.pollFirst();
            if (playerId == null) {
                return;
            }
            this.queuedIdentityRefresh.remove(playerId);
            var player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                this.identityListener.refresh(player);
            }
        }
    }

    private void runCacheMaintenance() {
        long now = System.currentTimeMillis();
        int profileBatch = boundedPerformanceSetting("performance.identity-maintenance-batch", 256, 1, 10_000);
        this.identities.maintenance(now, profileBatch);
        int ledgerBatch = boundedPerformanceSetting("performance.risk-ledger-maintenance-batch", 1_024, 1, 50_000);
        this.ledger.maintenance(now, this.riskConfig.profiling().behaviorWindowSeconds(), ledgerBatch);
        int cooldownHours = this.riskConfig.messages().noticeCooldownHours();
        long cutoff = now - Math.max(1L, cooldownHours) * 3_600_000L;
        this.transferNotices.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private int boundedPerformanceSetting(String path, int fallback, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, this.getConfig().getInt(path, fallback)));
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }
}
