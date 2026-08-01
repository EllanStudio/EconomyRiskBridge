package cn.superiormc.economyriskbridge.listener;

import cn.superiormc.economyriskbridge.EconomyRiskBridgePlugin;
import cn.superiormc.economyriskbridge.command.CommandMatcher;
import cn.superiormc.economyriskbridge.command.CommandPattern;
import cn.superiormc.economyriskbridge.config.RiskConfig;
import cn.superiormc.economyriskbridge.transfer.TransferCoordinator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class CommandInterceptionListener implements Listener {

    private final EconomyRiskBridgePlugin plugin;
    private final TransferCoordinator coordinator;
    private final Map<PlayerCommandPreprocessEvent, StagedCommand> staged =
        Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<PlayerCommandPreprocessEvent> forcedCancellation =
        Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private volatile RiskConfig config;
    private volatile CommandMatcher matcher;

    public CommandInterceptionListener(EconomyRiskBridgePlugin plugin, TransferCoordinator coordinator,
                                       RiskConfig config) {
        this.plugin = plugin;
        this.coordinator = coordinator;
        this.config = config;
        this.matcher = new CommandMatcher(config.commandPatterns());
    }

    public void updateConfig(RiskConfig config) {
        this.matcher = new CommandMatcher(config.commandPatterns());
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandEarly(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled() || event.getPlayer().hasPermission("economyriskbridge.bypass")) {
            return;
        }
        RiskConfig current = this.config;
        Optional<CommandPattern.Match> matched = this.matcher.match(event.getMessage());
        if (matched.isEmpty()) {
            return;
        }

        if (current.mode() == RiskConfig.Mode.ENFORCE && !current.network().acceptsTransfers()) {
            event.setCancelled(true);
            this.forcedCancellation.add(event);
            this.plugin.tell(event.getPlayer(), current.messages().wrongTransferNode()
                .replace("{node}", current.network().transferNode()));
            return;
        }

        CommandPattern.Match match = matched.get();
        this.plugin.sendTransferNotice(event.getPlayer());
        Optional<UUID> targetId = this.coordinator.resolveTarget(match.targetName());
        if (targetId.isEmpty()) {
            if (current.unknownTargetPolicy() == RiskConfig.UnknownTargetPolicy.BLOCK) {
                event.setCancelled(true);
                this.forcedCancellation.add(event);
                this.plugin.tell(event.getPlayer(), current.messages().unknownTarget());
            }
            return;
        }
        Optional<ExcellentCurrency> currency = this.coordinator.currency(match.currencyId());
        if (currency.isEmpty()) {
            event.setCancelled(true);
            this.forcedCancellation.add(event);
            this.plugin.tell(event.getPlayer(), "&c转账系统配置异常。错误代码：ERB-E003");
            this.plugin.getLogger().warning("ERB-E003: configured currency does not exist: " + match.currencyId());
            return;
        }

        TransferCoordinator.PrecheckResult result = this.coordinator.precheck(event.getPlayer(), targetId.get(),
            match.targetName(), currency.get(), match.amountMicros());
        switch (result.outcome()) {
            case ALLOW_NATIVE -> this.staged.put(event,
                new StagedCommand(result.transactionId(), event.getMessage()));
            case CANCEL_ESCROW, CANCEL_ERROR -> {
                event.setCancelled(true);
                this.forcedCancellation.add(event);
                this.plugin.tell(event.getPlayer(), result.message());
            }
            case IGNORE -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void enforceCancellation(PlayerCommandPreprocessEvent event) {
        if (this.forcedCancellation.contains(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandFinal(PlayerCommandPreprocessEvent event) {
        this.forcedCancellation.remove(event);
        StagedCommand command = this.staged.remove(event);
        if (command == null) {
            return;
        }
        if (event.isCancelled() || !event.getMessage().equals(command.originalMessage())) {
            this.coordinator.cancelPending(command.transactionId(),
                event.isCancelled() ? "command cancelled by another plugin" : "command rewritten by another plugin");
        }
    }

    private record StagedCommand(UUID transactionId, String originalMessage) {
    }
}
