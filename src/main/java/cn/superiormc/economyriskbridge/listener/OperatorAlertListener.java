package cn.superiormc.economyriskbridge.listener;

import cn.superiormc.economyriskbridge.EconomyRiskBridgePlugin;
import cn.superiormc.economyriskbridge.model.CaseRecord;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class OperatorAlertListener implements Listener {

    private static final int DELIVERY_BATCH = 100;
    private static final int DISPLAY_LIMIT = 5;

    private final EconomyRiskBridgePlugin plugin;

    public OperatorAlertListener(EconomyRiskBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPermission("economyriskbridge.admin")
            || !this.plugin.getConfig().getBoolean("notifications.operator-case-alerts", true)) {
            return;
        }
        UUID operatorId = event.getPlayer().getUniqueId();
        this.plugin.auditStore().pendingOperatorAlerts(DELIVERY_BATCH).whenComplete((caseIds, throwable) -> {
            if (throwable != null) {
                this.plugin.getLogger().log(Level.WARNING, "Could not read offline-operator reminders", throwable);
                return;
            }
            if (caseIds == null || caseIds.isEmpty() || !this.plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> deliver(operatorId, caseIds));
        });
    }

    private void deliver(UUID operatorId, List<UUID> caseIds) {
        var operator = Bukkit.getPlayer(operatorId);
        if (operator == null || !operator.isOnline()
            || !operator.hasPermission("economyriskbridge.admin")) {
            return;
        }
        List<CaseRecord> openCases = caseIds.stream()
            .map(caseId -> this.plugin.caseBook().get(caseId).orElse(null))
            .filter(record -> record != null && record.status() != CaseRecord.Status.CLOSED)
            .toList();
        if (!openCases.isEmpty()) {
            this.plugin.tell(operator, "&c离线期间有 " + openCases.size()
                + " 个未送达的风控案件，最近案件如下：");
            openCases.stream().limit(DISPLAY_LIMIT).forEach(record ->
                this.plugin.tell(operator, "&c[OP] " + this.plugin.operatorCaseSummary(record)));
            if (openCases.size() > DISPLAY_LIMIT) {
                this.plugin.tell(operator, "&7另有 " + (openCases.size() - DISPLAY_LIMIT)
                    + " 个；使用 /erb cases 查看。提示以最多 " + DELIVERY_BATCH + " 个为一批。 ");
            } else {
                this.plugin.tell(operator, "&7使用 /erb case show <案件> 查看完整信息。");
            }
        }
        if (!this.plugin.auditStore().enqueueOperatorAlertsDelivered(caseIds, operator.getName(),
            System.currentTimeMillis())) {
            this.plugin.getLogger().warning("Offline-operator reminders were displayed but could not be marked "
                + "delivered; they may be shown again on the next login.");
        }
    }
}
