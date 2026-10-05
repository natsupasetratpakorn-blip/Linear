package dev.m4sh3r.linear.ai;

import dev.m4sh3r.linear.Linear;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * A thread-safe snapshot of where players are, refreshed every second by each player's
 * own scheduler. Mobs read the snapshot instead of touching player objects, which keeps
 * the check cheap and safe on Folia where players live on other region threads.
 */
public final class PlayerPositions implements Listener {

    /** Where a player was at the last refresh. */
    public record Snapshot(UUID world, double x, double y, double z) {
    }

    private final Linear plugin;
    private final Map<UUID, Snapshot> positions = new ConcurrentHashMap<>();

    public PlayerPositions(Linear plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            follow(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        follow(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        positions.remove(event.getPlayer().getUniqueId());
    }

    private void follow(Player player) {
        UUID id = player.getUniqueId();
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (!player.isOnline()) {
                positions.remove(id);
                task.cancel();
                return;
            }
            if (player.getGameMode() == GameMode.SPECTATOR) {
                positions.remove(id);
                return;
            }
            Location l = player.getLocation();
            positions.put(id, new Snapshot(l.getWorld().getUID(), l.getX(), l.getY(), l.getZ()));
        }, () -> positions.remove(id), 1L, 20L);
    }

    public List<Snapshot> snapshot() {
        return List.copyOf(positions.values());
    }

    /** Whether any non-spectator player is within {@code radius} blocks of the location. */
    public boolean anyNear(Location loc, double radius) {
        UUID world = loc.getWorld().getUID();
        double r2 = radius * radius;
        for (Snapshot p : positions.values()) {
            if (p.world.equals(world)) {
                double dx = p.x - loc.getX();
                double dy = p.y - loc.getY();
                double dz = p.z - loc.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    return true;
                }
            }
        }
        return false;
    }
}
