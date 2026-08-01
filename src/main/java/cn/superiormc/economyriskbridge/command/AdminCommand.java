package cn.superiormc.economyriskbridge.command;

import cn.superiormc.economyriskbridge.EconomyRiskBridgePlugin;
import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import cn.superiormc.economyriskbridge.model.BalanceEntry;
import cn.superiormc.economyriskbridge.model.EscrowRecord;
import cn.superiormc.economyriskbridge.model.PartyProfile;
import cn.superiormc.economyriskbridge.risk.RiskRule;
import cn.superiormc.economyriskbridge.provenance.SourceCatalog;
import cn.superiormc.economyriskbridge.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 8;
    private static final DateTimeFormatter STATEMENT_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final EconomyRiskBridgePlugin plugin;

    public AdminCommand(EconomyRiskBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!sender.hasPermission("economyriskbridge.admin")) {
            this.plugin.tell(sender, "&c你没有权限。");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "reload" -> this.plugin.reloadRules(sender);
            case "inspect" -> inspect(sender, args);
            case "statement" -> statement(sender, args);
            case "cases" -> cases(sender, args);
            case "case" -> caseCommand(sender, args);
            case "rules" -> rules(sender, args);
            case "sources" -> sources(sender, args);
            case "escrow" -> escrow(sender, args);
            case "bank" -> bank(sender);
            case "trust" -> trust(sender, args);
            case "link" -> link(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        this.plugin.tell(sender, "&e/erb status &7- 运行状态");
        this.plugin.tell(sender, "&e/erb inspect <玩家> [货币] &7- 玩家风险画像");
        this.plugin.tell(sender, "&e/erb statement <玩家> [页] [货币] &7- 银行式余额流水");
        this.plugin.tell(sender, "&e/erb cases [页] &7- 最近案件");
        this.plugin.tell(sender, "&e/erb case ack <案件> [备注] &7- 标记已阅");
        this.plugin.tell(sender, "&e/erb case show <案件> &7- 显示 OP 可见原因");
        this.plugin.tell(sender, "&e/erb rules [错误代码] &7- 代码与原因目录");
        this.plugin.tell(sender, "&e/erb sources [来源代码] &7- 流水来源目录");
        this.plugin.tell(sender, "&e/erb escrow list [页] &7- 待处理托管");
        this.plugin.tell(sender, "&e/erb escrow release|refund|confiscate <案件> [备注]");
        this.plugin.tell(sender, "&e/erb escrow reconcile <案件> held|released|refunded|failed [备注]");
        this.plugin.tell(sender, "&e/erb bank &7- 已充公服务器银行余额");
        this.plugin.tell(sender, "&e/erb trust add|remove <玩家A> <玩家B>");
        this.plugin.tell(sender, "&e/erb link add <玩家> <组名> &7或 &e/erb link remove <玩家>");
        this.plugin.tell(sender, "&e/erb reload &7- 重载热更新规则");
    }

    private void status(CommandSender sender) {
        var store = this.plugin.auditStore();
        this.plugin.tell(sender, "&e模式：&f" + this.plugin.riskConfig().mode()
            + " &e审计：" + (store.isHealthy() ? "&a正常" : "&c异常"));
        this.plugin.tell(sender, "&e审计队列：&f" + store.queueSize() + "/" + store.queueCapacity()
            + " &e原生待核验：&f" + this.plugin.coordinator().pendingCount()
            + " &e托管待审：&f" + this.plugin.escrowBook().heldCount());
        this.plugin.tell(sender, "&e未结案件：&f" + this.plugin.caseBook().openCount()
            + " &e命令模式：&f" + this.plugin.riskConfig().commandPatterns().size());
        RiskConfig.Network network = this.plugin.riskConfig().network();
        this.plugin.tell(sender, "&e当前节点：&f" + network.currentNode()
            + " &e转账节点：&f" + network.transferNode()
            + " &e本节点可转账：" + (network.acceptsTransfers() ? "&a是" : "&c否"));
        if (!store.isHealthy()) {
            this.plugin.tell(sender, "&c最近错误：" + store.lastError());
        }
    }

    private void inspect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            this.plugin.tell(sender, "&c用法：/erb inspect <玩家> [货币]");
            return;
        }
        Optional<UUID> id = this.plugin.coordinator().resolveTarget(args[1]);
        if (id.isEmpty()) {
            this.plugin.tell(sender, "&c未知玩家；对方需要至少登录过一次。");
            return;
        }
        long now = System.currentTimeMillis();
        PartyProfile profile = this.plugin.identities().profile(id.get(), args[1], now);
        String currency = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT)
            : this.plugin.riskConfig().commandPatterns().getFirst().currencyId();
        long ageHours = Math.max(0L, now - profile.firstSeenMillis()) / 3_600_000L;
        this.plugin.tell(sender, "&e" + profile.name() + " &7(" + profile.uuid() + ")");
        this.plugin.tell(sender, "&e首次进服距今：&f" + ageHours + "h &e累计在线：&f"
            + (profile.playSeconds() / 3_600L) + "h &e集群：&f" + abbreviate(profile.clusterKey(), 28));
        this.plugin.tell(sender, "&e今日转出：&f"
            + Money.format(this.plugin.ledger().outgoingToday(profile.uuid(), currency, now)) + " " + currency
            + " &e流入：&f" + Money.format(this.plugin.ledger().incomingToday(profile.uuid(), currency, now)));
        List<PartyProfile> linked = this.plugin.identities().linkedAccounts(profile.uuid(), now);
        String names = linked.isEmpty() ? "无" : linked.stream().limit(12).map(PartyProfile::name)
            .collect(Collectors.joining(", "));
        this.plugin.tell(sender, "&e近期同网络账户（仅风险信号）：&f" + names);
    }

    private void statement(CommandSender sender, String[] args) {
        if (args.length < 2) {
            this.plugin.tell(sender, "&c用法：/erb statement <玩家> [页] [货币]");
            return;
        }
        Optional<UUID> playerId = this.plugin.coordinator().resolveTarget(args[1]);
        if (playerId.isEmpty()) {
            this.plugin.tell(sender, "&c未知玩家；对方需要至少登录过一次。");
            return;
        }
        int page = page(args, 2);
        String currency = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "";
        this.plugin.tell(sender, "&7正在读取 " + args[1] + " 的结构化流水……");
        this.plugin.auditStore().statement(playerId.get(), currency, (page - 1) * PAGE_SIZE, PAGE_SIZE)
            .whenComplete((entries, throwable) -> {
                if (!this.plugin.isEnabled()) {
                    return;
                }
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (throwable != null || entries == null) {
                        this.plugin.tell(sender, "&c流水查询失败；请检查 /erb status 与服务器日志。");
                        return;
                    }
                    this.plugin.tell(sender, "&e" + args[1] + " 的余额流水 第 " + page + " 页"
                        + (currency.isEmpty() ? "：" : "（" + currency + "）："));
                    if (entries.isEmpty()) {
                        this.plugin.tell(sender, "&7没有更多记录。流水从安装本插件后开始采集。");
                        return;
                    }
                    entries.forEach(entry -> showStatementEntry(sender, entry));
                });
            });
    }

    private void showStatementEntry(CommandSender sender, BalanceEntry entry) {
        String time = STATEMENT_TIME.format(Instant.ofEpochMilli(entry.occurredAtMillis())
            .atZone(this.plugin.riskConfig().zoneId()));
        String amount = (entry.deltaMicros() >= 0L ? "&a+" : "&c-")
            + Money.format(entry.deltaMicros() == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(entry.deltaMicros()));
        String counterparty = entry.counterpartyName().isBlank() ? "" : " &7对手方：&f" + entry.counterpartyName();
        String reference = entry.caseId() != null ? " &7案件：&f" + shortId(entry.caseId())
            : entry.transactionId() != null ? " &7交易：&f" + shortId(entry.transactionId()) : "";
        this.plugin.tell(sender, "&7" + time + " " + amount + " &f" + entry.currencyId()
            + " &e" + entry.source().code() + " &7余额：&f" + Money.format(entry.balanceAfterMicros())
            + counterparty + reference);
        this.plugin.tell(sender, "  &8[" + confidence(entry.confidence()) + "] &7"
            + abbreviate(entry.detail(), 90));
    }

    private void sources(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Optional<SourceCatalog> found = SourceCatalog.byCode(args[1]);
            if (found.isEmpty()) {
                this.plugin.tell(sender, "&c未知流水来源代码。");
                return;
            }
            this.plugin.tell(sender, "&e" + found.get().code() + " &f" + found.get().displayName());
            return;
        }
        this.plugin.tell(sender, "&e流水来源代码目录（VERIFIED 为直接关联，INFERRED 为调用栈归因）：");
        for (SourceCatalog source : SourceCatalog.values()) {
            this.plugin.tell(sender, "&e" + source.code() + " &f" + source.displayName());
        }
    }

    private void cases(CommandSender sender, String[] args) {
        int page = page(args, 1);
        List<CaseRecord> records = this.plugin.caseBook().recentOpen((page - 1) * PAGE_SIZE, PAGE_SIZE);
        this.plugin.tell(sender, "&e案件列表 第 " + page + " 页：");
        if (records.isEmpty()) {
            this.plugin.tell(sender, "&7没有更多案件。");
            return;
        }
        for (CaseRecord record : records) {
            String escrow = this.plugin.escrowBook().findByPrefix(record.caseId().toString())
                .map(value -> "/" + value.status()).orElse("");
            this.plugin.tell(sender, "&7" + shortId(record.caseId()) + " &f" + record.senderName() + " -> "
                + record.receiverName() + " &e" + Money.format(record.amountMicros()) + " " + record.currencyId()
                + " &c" + record.score() + " &7" + record.status() + escrow + " " + record.reasonCodes());
        }
    }

    private void caseCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            this.plugin.tell(sender, "&c用法：/erb case show|ack <案件> [备注]");
            return;
        }
        if (args[1].equalsIgnoreCase("show")) {
            Optional<CaseRecord> found = this.plugin.caseBook().findByPrefix(args[2]);
            if (found.isEmpty()) {
                this.plugin.tell(sender, "&c案件编号不存在或前缀不唯一。");
                return;
            }
            CaseRecord record = found.get();
            this.plugin.tell(sender, "&e案件 " + shortId(record.caseId()) + "：&f" + record.senderName()
                + " -> " + record.receiverName() + "，" + Money.format(record.amountMicros()) + " "
                + record.currencyId() + "，分数 " + record.score());
            for (String code : record.reasonCodes().split(",")) {
                RiskRule.byCode(code).ifPresentOrElse(
                    rule -> this.plugin.tell(sender, "&c" + rule.code() + " &7[" + rule.category() + "] &f"
                        + rule.operatorReason()),
                    () -> this.plugin.tell(sender, "&c" + code + " &7（旧版本或未知规则）"));
            }
            this.plugin.tell(sender, "&e备注：&f" + record.note() + " &e状态：&f" + record.status());
            return;
        }
        if (!args[1].equalsIgnoreCase("ack")) {
            this.plugin.tell(sender, "&c用法：/erb case show|ack <案件> [备注]");
            return;
        }
        String note = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "已阅";
        Optional<CaseRecord> updated = this.plugin.caseBook().acknowledge(args[2], sender.getName(), note);
        if (updated.isEmpty()) {
            this.plugin.tell(sender, "&c案件编号不存在或前缀不唯一。");
            return;
        }
        if (!this.plugin.auditStore().enqueueCaseStatus(updated.get())) {
            this.plugin.tell(sender, "&c案件已在内存标记，但审计数据库当前不可用。");
            return;
        }
        this.plugin.tell(sender, "&a案件 " + shortId(updated.get().caseId()) + " 已标记为已阅。");
    }

    private void rules(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Optional<RiskRule> rule = RiskRule.byCode(args[1]);
            if (rule.isEmpty()) {
                this.plugin.tell(sender, "&c未知错误代码。");
                return;
            }
            this.plugin.tell(sender, "&c" + rule.get().code() + " &7[" + rule.get().category() + "] &f"
                + rule.get().operatorReason());
            return;
        }
        this.plugin.tell(sender, "&e风控错误代码目录（使用 /erb rules <代码> 查看）：");
        for (RiskRule rule : RiskRule.values()) {
            this.plugin.tell(sender, "&c" + rule.code() + " &7[" + rule.category() + "] &f"
                + rule.operatorReason());
        }
    }

    private void escrow(CommandSender sender, String[] args) {
        if (args.length < 2) {
            this.plugin.tell(sender, "&c用法：/erb escrow list|release|refund|confiscate ...");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            int page = page(args, 2);
            List<EscrowRecord> escrows = this.plugin.escrowBook().recentOpen((page - 1) * PAGE_SIZE, PAGE_SIZE);
            this.plugin.tell(sender, "&e托管列表 第 " + page + " 页：");
            for (EscrowRecord escrow : escrows) {
                CaseRecord record = this.plugin.caseBook().get(escrow.caseId()).orElse(null);
                if (record == null) {
                    this.plugin.tell(sender, "&7" + shortId(escrow.caseId()) + " &c案件元数据缺失 " + escrow.status());
                    continue;
                }
                this.plugin.tell(sender, "&7" + shortId(escrow.caseId()) + " &f" + record.senderName()
                    + " -> " + record.receiverName() + " &e" + Money.format(record.amountMicros()) + " "
                    + record.currencyId() + " &6" + escrow.status() + " &7" + record.reasonCodes());
            }
            return;
        }
        if (args.length < 3) {
            this.plugin.tell(sender, "&c缺少案件编号。");
            return;
        }
        if (action.equals("reconcile")) {
            if (args.length < 4) {
                this.plugin.tell(sender, "&c用法：/erb escrow reconcile <案件> held|released|refunded|failed [备注]");
                return;
            }
            String reconcileNote = args.length > 4
                ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : "已对照 EE 操作日志";
            this.plugin.coordinator().reconcileEscrow(args[2], args[3], sender, reconcileNote);
            return;
        }
        String note = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "管理员处置";
        switch (action) {
            case "release" -> this.plugin.coordinator().releaseEscrow(args[2], sender);
            case "refund" -> this.plugin.coordinator().refundEscrow(args[2], sender);
            case "confiscate" -> this.plugin.coordinator().confiscateEscrow(args[2], sender, note);
            default -> this.plugin.tell(sender, "&c未知托管操作。");
        }
    }

    private void bank(CommandSender sender) {
        var balances = this.plugin.escrowBook().treasurySnapshot();
        this.plugin.tell(sender, "&e服务器银行已充公余额（逻辑账）：");
        if (balances.isEmpty()) {
            this.plugin.tell(sender, "&7暂无。");
            return;
        }
        balances.forEach((currency, amount) ->
            this.plugin.tell(sender, "&f" + Money.format(amount) + " " + currency));
    }

    private void trust(CommandSender sender, String[] args) {
        if (args.length < 4 || (!args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("remove"))) {
            this.plugin.tell(sender, "&c用法：/erb trust add|remove <玩家A> <玩家B>");
            return;
        }
        Optional<UUID> left = this.plugin.coordinator().resolveTarget(args[2]);
        Optional<UUID> right = this.plugin.coordinator().resolveTarget(args[3]);
        if (left.isEmpty() || right.isEmpty()) {
            this.plugin.tell(sender, "&c至少一个玩家无法识别。");
            return;
        }
        boolean add = args[1].equalsIgnoreCase("add");
        boolean changed = this.plugin.identities().setTrusted(left.get(), right.get(), add, sender.getName());
        this.plugin.tell(sender, changed ? "&a可信关系已更新。" : "&7可信关系没有变化。");
    }

    private void link(CommandSender sender, String[] args) {
        if (args.length < 3) {
            this.plugin.tell(sender, "&c用法：/erb link add <玩家> <组名> 或 /erb link remove <玩家>");
            return;
        }
        Optional<UUID> player = this.plugin.coordinator().resolveTarget(args[2]);
        if (player.isEmpty()) {
            this.plugin.tell(sender, "&c无法识别玩家。");
            return;
        }
        if (args[1].equalsIgnoreCase("add") && args.length >= 4) {
            boolean changed = this.plugin.identities().setConfirmedCluster(player.get(), args[3], sender.getName());
            this.plugin.tell(sender, changed ? "&a已加入确认关联组。" : "&c组名无效或没有变化。");
        } else if (args[1].equalsIgnoreCase("remove")) {
            boolean changed = this.plugin.identities().removeConfirmedCluster(player.get(), sender.getName());
            this.plugin.tell(sender, changed ? "&a已移出确认关联组。" : "&7该玩家不在确认关联组中。");
        } else {
            this.plugin.tell(sender, "&c用法：/erb link add <玩家> <组名> 或 /erb link remove <玩家>");
        }
    }

    private static int page(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }

    private static String confidence(BalanceEntry.Confidence confidence) {
        return switch (confidence) {
            case VERIFIED -> "已验证";
            case INFERRED -> "插件推断";
            case UNKNOWN -> "未知";
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "inspect", "statement", "cases", "case", "rules", "sources",
                "escrow", "bank", "trust", "link", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("escrow")) {
            return filter(List.of("list", "release", "refund", "confiscate", "reconcile"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("link"))) {
            return filter(List.of("add", "remove"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("case")) {
            return filter(List.of("show", "ack"), args[1]);
        }
        if ((args.length >= 2 && (args[0].equalsIgnoreCase("inspect")
            || args[0].equalsIgnoreCase("statement")))
            || (args.length >= 3 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("link")))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
