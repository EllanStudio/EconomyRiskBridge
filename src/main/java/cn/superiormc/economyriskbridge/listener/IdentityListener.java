package cn.superiormc.economyriskbridge.listener;

import cn.superiormc.economyriskbridge.identity.IdentityService;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class IdentityListener implements Listener {

    private final IdentityService identities;

    public IdentityListener(IdentityService identities) {
        this.identities = identities;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        this.identities.observe(event.getUniqueId(), event.getName(), event.getAddress(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        update(event.getPlayer());
    }

    public void refresh(Player player) {
        update(player);
    }

    private void update(Player player) {
        long playSeconds = Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        this.identities.updatePlaytime(player.getUniqueId(), player.getName(), player.getFirstPlayed(), playSeconds,
            System.currentTimeMillis());
    }
}
