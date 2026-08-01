package cn.superiormc.economyriskbridge.listener;

import cn.superiormc.economyriskbridge.transfer.TransferCoordinator;
import cn.superiormc.economyriskbridge.util.Money;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;

public final class ExcellentEconomyBalanceListener implements Listener {

    private final TransferCoordinator coordinator;

    public ExcellentEconomyBalanceListener(TransferCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBalanceChange(ChangeBalanceEvent event) {
        long before;
        long after;
        try {
            before = Money.fromDouble(event.getOldAmount());
            after = Money.fromDouble(event.getNewAmount());
            Math.subtractExact(after, before);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return;
        }
        this.coordinator.onBalanceChange(event.getUser().getId(), event.getUser().getName(),
            event.getCurrency().getId(), before, after, System.currentTimeMillis());
    }
}
