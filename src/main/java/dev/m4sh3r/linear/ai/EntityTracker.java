package dev.m4sh3r.linear.ai;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.m4sh3r.linear.Linear;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.MerchantInventory;

/**
 * Gives every mob that an optimizer cares about its own repeating task on the mob's
 * scheduler. Entity schedulers follow the mob across threads on Folia and run on the
 * main thread on Paper, so the same code is correct on both.
 */
public final class EntityTracker implements Listener {

    private final Linear plugin;
    private final AiController ai;
    private final List<MobHandler> handlers;
    private final Map<UUID, Mob> tracked = new ConcurrentHashMap<>();
    private volatile boolean loggedError;

    public EntityTracker(Linear plugin, AiController ai, MobHandler... handlers) {
        this.plugin = plugin;
        this.ai = ai;
        this.handlers = List.of(handlers);
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        rescan();
    }

    /**
     * Picks up mobs that were loaded before Linear was enabled or became relevant after a
     * reload. On Paper every loaded entity is visited; on Folia, where no thread may walk a
     * whole world, the area around each player is visited and everything else is picked up
     * when its chunk loads.
     */
    public void rescan() {
        if (Linear.folia()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.getScheduler().run(plugin, task -> {
                    try {
                        for (Entity e : player.getNearbyEntities(64, 64, 64)) {
                            if (e instanceof Mob mob) {
                                track(mob);
                            }
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().fine("Rescan near " + player.getName() + " failed: " + t);
                    }
                }, null);
            }
        } else {
            for (World world : plugin.getServer().getWorlds()) {
                for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                    track(mob);
                }
            }
        }
    }

    public int trackedCount() {
        return tracked.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            track(mob);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            tracked.remove(mob.getUniqueId(), mob);
            ai.forget(mob.getUniqueId());
        }
    }

    private MobHandler handlerFor(Mob mob) {
        for (MobHandler h : handlers) {
            if (h.handles(mob)) {
                return h;
            }
        }
        return null;
    }

    private void track(Mob mob) {
        MobHandler handler = handlerFor(mob);
        if (handler == null) {
            // Left over from an older config (type no longer optimized): give its AI back.
            if (ai.isOurs(mob)) {
                ai.release(mob);
            }
            return;
        }
        UUID id = mob.getUniqueId();
        Mob previous = tracked.put(id, mob);
        if (previous == mob) {
            return;
        }
        if (ai.isOurs(mob)) {
            ai.adopt(mob);
        }
        int period = handler.interval();
        long delay = 1 + ThreadLocalRandom.current().nextInt(period); // spread the work over the interval
        ScheduledTask task = mob.getScheduler().runAtFixedRate(plugin, t -> {
            if (!mob.isValid() || tracked.get(id) != mob) {
                t.cancel();
                return;
            }
            try {
                handler.evaluate(mob);
            } catch (Throwable error) {
                if (!loggedError) {
                    loggedError = true;
                    plugin.getLogger().log(Level.WARNING, "Error while optimizing " + mob.getType(), error);
                }
            }
        }, () -> tracked.remove(id, mob), delay, period);
        if (task == null) {
            tracked.remove(id, mob);
        }
    }

    // ---- players interacting with optimized mobs get a fully working mob ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Mob mob) {
            MobHandler handler = handlerFor(mob);
            if (handler != null) {
                ai.wake(mob, handler.wakeTicks());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTradeClose(InventoryCloseEvent event) {
        // Level-ups and reputation are applied by the villager's AI right after trading ends.
        if (event.getInventory() instanceof MerchantInventory inv && inv.getMerchant() instanceof Villager villager) {
            MobHandler handler = handlerFor(villager);
            if (handler != null && villager.isValid()) {
                ai.wake(villager, handler.wakeTicks());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        // A villager turned into a zombie villager (or back) must not inherit a disabled AI.
        if (!(event.getEntity() instanceof Mob original) || !ai.isOurs(original)) {
            return;
        }
        for (Entity e : event.getTransformedEntities()) {
            if (e instanceof Mob converted) {
                converted.getScheduler().run(plugin, task -> {
                    if (ai.isOurs(converted) || !converted.isAware() || !converted.hasAI()) {
                        ai.release(converted);
                        converted.setAware(true);
                        converted.setAI(true);
                    }
                }, null);
            }
        }
    }
}
