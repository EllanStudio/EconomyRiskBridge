package cn.superiormc.economyriskbridge.config;

import cn.superiormc.economyriskbridge.command.CommandPattern;
import cn.superiormc.economyriskbridge.util.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record RiskConfig(
    Mode mode,
    ZoneId zoneId,
    Network network,
    List<CommandPattern> commandPatterns,
    int pendingTimeoutSeconds,
    UnknownTargetPolicy unknownTargetPolicy,
    int ipLinkRetentionDays,
    Limits limits,
    Profiling profiling,
    Scores scores,
    Storage storage,
    Messages messages
) {

    public static RiskConfig load(FileConfiguration config) {
        List<CommandPattern> patterns = new ArrayList<>();
        for (var entry : config.getMapList("interception.patterns")) {
            Object command = entry.get("command");
            Object currency = entry.get("currency");
            if (command != null && currency != null) {
                patterns.add(new CommandPattern(command.toString(), currency.toString()));
            }
        }
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("interception.patterns must contain at least one valid command");
        }

        String currentNode = config.getString("network.current-node", "auto");
        if (currentNode == null || currentNode.isBlank() || currentNode.equalsIgnoreCase("auto")) {
            Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path fileName = workingDirectory.getFileName();
            currentNode = fileName == null ? "unknown" : fileName.toString();
        }
        String transferNode = config.getString("network.transfer-node", "spawn");
        if (transferNode == null || transferNode.isBlank()) {
            throw new IllegalArgumentException("network.transfer-node must not be blank");
        }
        Network network = new Network(
            config.getBoolean("network.single-transfer-node", true),
            currentNode.trim().toLowerCase(Locale.ROOT),
            transferNode.trim().toLowerCase(Locale.ROOT)
        );

        Limits limits = new Limits(
            money(config, "limits.max-single"),
            money(config, "limits.sender-daily"),
            money(config, "limits.receiver-daily"),
            money(config, "limits.pair-daily"),
            money(config, "limits.cluster-daily"),
            config.getBoolean("limits.cluster-daily-hard-block", false),
            config.getBoolean("limits.same-ip-hard-block", false),
            positive(config.getInt("limits.velocity-window-seconds", 60), "limits.velocity-window-seconds"),
            positive(config.getInt("limits.velocity-max-transfers", 8), "limits.velocity-max-transfers"),
            config.getBoolean("limits.velocity-hard-block", true)
        );

        Profiling profiling = new Profiling(
            Math.max(0, config.getInt("profiling.new-account-hours", 24)),
            Math.max(0, config.getInt("profiling.low-playtime-minutes", 120)),
            Math.max(0, config.getInt("profiling.probation-playtime-hours", 24)),
            money(config, "profiling.probation-large-transfer"),
            money(config, "profiling.base-earned-capacity"),
            money(config, "profiling.earned-capacity-per-play-hour"),
            config.getBoolean("profiling.source-of-funds-hard-block", true),
            config.getBoolean("profiling.shop-alt-limit-hard-block", true),
            Math.max(0, config.getInt("profiling.dormant-days", 14)),
            money(config, "profiling.dormant-large-transfer"),
            positive(config.getInt("profiling.ip-cluster-accounts", 3), "profiling.ip-cluster-accounts"),
            positive(config.getInt("profiling.ip-churn-count", 4), "profiling.ip-churn-count"),
            bounded(config.getDouble("profiling.pass-through-ratio", 0.80), 0.0D, 1.0D,
                "profiling.pass-through-ratio"),
            positive(config.getInt("profiling.structuring-min-transfers", 3),
                "profiling.structuring-min-transfers"),
            bounded(config.getDouble("profiling.balance-drain-ratio", 0.90), 0.0D, 1.0D,
                "profiling.balance-drain-ratio"),
            positive(config.getInt("profiling.repeated-amount-count", 3),
                "profiling.repeated-amount-count"),
            positive(config.getInt("profiling.behavior-window-seconds", 600), "profiling.behavior-window-seconds"),
            positive(config.getInt("profiling.fan-in-distinct-senders", 4), "profiling.fan-in-distinct-senders"),
            positive(config.getInt("profiling.fan-out-distinct-receivers", 6), "profiling.fan-out-distinct-receivers"),
            bounded(config.getDouble("profiling.near-limit-ratio", 0.85), 0.0D, 1.0D,
                "profiling.near-limit-ratio"),
            money(config, "profiling.round-amount-unit")
        );

        Scores scores = new Scores(
            score(config, "scores.same-recent-ip", 35),
            score(config, "scores.new-account", 20),
            score(config, "scores.low-playtime", 15),
            score(config, "scores.source-of-funds", 40),
            score(config, "scores.shop-alt-limit", 60),
            score(config, "scores.dormant-reactivation", 30),
            score(config, "scores.ip-cluster", 25),
            score(config, "scores.ip-churn", 15),
            score(config, "scores.pass-through", 35),
            score(config, "scores.circular-flow", 40),
            score(config, "scores.structuring", 35),
            score(config, "scores.balance-drain", 20),
            score(config, "scores.repeated-amount", 15),
            score(config, "scores.high-velocity", 25),
            score(config, "scores.fan-in", 25),
            score(config, "scores.fan-out", 20),
            score(config, "scores.reverse-flow", 25),
            score(config, "scores.near-limit", 15),
            score(config, "scores.round-amount", 5),
            score(config, "scores.review-threshold", 40),
            score(config, "scores.block-threshold", 80)
        );
        if (scores.blockThreshold() < scores.reviewThreshold()) {
            throw new IllegalArgumentException("scores.block-threshold must be >= review-threshold");
        }

        Storage storage = new Storage(
            config.getString("storage.file", "risk-audit.db"),
            positive(config.getInt("storage.queue-capacity", 20_000), "storage.queue-capacity"),
            positive(config.getInt("storage.batch-size", 256), "storage.batch-size"),
            positive(config.getInt("storage.flush-millis", 100), "storage.flush-millis"),
            positive(config.getInt("storage.retention-days", 180), "storage.retention-days"),
            config.getBoolean("storage.fail-closed", true)
        );

        return new RiskConfig(
            enumValue(Mode.class, config.getString("mode", "SHADOW"), Mode.SHADOW),
            ZoneId.of(config.getString("time-zone", "Asia/Shanghai")),
            network,
            List.copyOf(patterns),
            positive(config.getInt("interception.pending-timeout-seconds", 60),
                "interception.pending-timeout-seconds"),
            enumValue(UnknownTargetPolicy.class,
                config.getString("interception.unknown-target-policy", "BLOCK"), UnknownTargetPolicy.BLOCK),
            positive(config.getInt("privacy.ip-link-retention-days", 30), "privacy.ip-link-retention-days"),
            limits,
            profiling,
            scores,
            storage,
            new Messages(
                config.getString("messages.prefix", "&8[&6风控&8] &r"),
                config.getString("messages.transfer-notice", "&e服务器转账已启用风控，风险款项将由服务器银行托管复核。"),
                Math.max(0, config.getInt("messages.notice-cooldown-hours", 24)),
                config.getString("messages.blocked", "&c转账已暂缓。错误代码：{code} &7(案件 {case})"),
                config.getString("messages.escrow-processing", "&e该笔转账触发风险复核，正在提交服务器银行托管。错误代码：{code} &7(案件 {case})"),
                config.getString("messages.escrow-held", "&e{amount} {currency} 已由服务器银行托管。错误代码：{code} &7(案件 {case})"),
                config.getString("messages.audit-unavailable", "&c审计系统繁忙，转账已暂缓。错误代码：ERB-E002"),
                config.getString("messages.unknown-target", "&c无法安全识别收款玩家。错误代码：ERB-E001"),
                config.getString("messages.wrong-transfer-node",
                    "&c请前往 {node} 服务器转账。错误代码：ERB-E005")
            )
        );
    }

    private static long money(FileConfiguration config, String path) {
        Object value = config.get(path, "0");
        return Money.nonNegativeConfig(String.valueOf(value), path);
    }

    private static int score(FileConfiguration config, String path, int fallback) {
        return Math.max(0, config.getInt(path, fallback));
    }

    private static int positive(int value, String path) {
        if (value <= 0) {
            throw new IllegalArgumentException(path + " must be positive");
        }
        return value;
    }

    private static double bounded(double value, double minimum, double maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String input, E fallback) {
        if (input == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public enum Mode {
        SHADOW,
        ENFORCE
    }

    public enum UnknownTargetPolicy {
        BLOCK,
        IGNORE
    }

    public record Network(boolean singleTransferNode, String currentNode, String transferNode) {

        public boolean acceptsTransfers() {
            return !this.singleTransferNode || this.currentNode.equalsIgnoreCase(this.transferNode);
        }
    }

    public record Limits(
        long maxSingle,
        long senderDaily,
        long receiverDaily,
        long pairDaily,
        long clusterDaily,
        boolean clusterDailyHardBlock,
        boolean sameIpHardBlock,
        int velocityWindowSeconds,
        int velocityMaxTransfers,
        boolean velocityHardBlock
    ) {
    }

    public record Profiling(
        int newAccountHours,
        int lowPlaytimeMinutes,
        int probationPlaytimeHours,
        long probationLargeTransfer,
        long baseEarnedCapacity,
        long earnedCapacityPerPlayHour,
        boolean sourceOfFundsHardBlock,
        boolean shopAltLimitHardBlock,
        int dormantDays,
        long dormantLargeTransfer,
        int ipClusterAccounts,
        int ipChurnCount,
        double passThroughRatio,
        int structuringMinTransfers,
        double balanceDrainRatio,
        int repeatedAmountCount,
        int behaviorWindowSeconds,
        int fanInDistinctSenders,
        int fanOutDistinctReceivers,
        double nearLimitRatio,
        long roundAmountUnit
    ) {
    }

    public record Scores(
        int sameRecentIp,
        int newAccount,
        int lowPlaytime,
        int sourceOfFunds,
        int shopAltLimit,
        int dormantReactivation,
        int ipCluster,
        int ipChurn,
        int passThrough,
        int circularFlow,
        int structuring,
        int balanceDrain,
        int repeatedAmount,
        int highVelocity,
        int fanIn,
        int fanOut,
        int reverseFlow,
        int nearLimit,
        int roundAmount,
        int reviewThreshold,
        int blockThreshold
    ) {
    }

    public record Storage(
        String file,
        int queueCapacity,
        int batchSize,
        int flushMillis,
        int retentionDays,
        boolean failClosed
    ) {
    }

    public record Messages(String prefix, String transferNotice, int noticeCooldownHours,
                           String blocked, String escrowProcessing, String escrowHeld,
                           String auditUnavailable, String unknownTarget, String wrongTransferNode) {
    }
}
