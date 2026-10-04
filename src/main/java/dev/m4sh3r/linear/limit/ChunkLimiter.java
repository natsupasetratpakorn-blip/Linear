package dev.m4sh3r.linear.limit;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.config.LinearConfig;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Stops breeding, egg hatching and spawners once a chunk already holds a configured number
 * of mobs of the same type, so farms cannot grow until they drag the server down.
 */
public final class ChunkLimiter implements Listener {

    private final Linear plugin;
    private final LongAdder blocked = new LongAdder();

    public ChunkLimiter(Linear plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        int limit;
        LinearConfig.ChunkLimits c = plugin.settings().limits;
        EntityType type = event.getEntityType();
        switch (event.getSpawnReason()) {
            case BREEDING, EGG, DISPENSE_EGG -> limit = c.breedingLimit(type);
            case SPAWNER -> limit = c.spawnerLimit(type);
            default -> {
                return;
            }
        }
        if (!plugin.active(Linear.LIMITS)) {
            return;
        }
        Chunk chunk = event.getLocation().getChunk();
        int count = 0;
        for (Entity e : chunk.getEntities()) {
            if (e.getType() == type && ++count >= limit) {
                event.setCancelled(true);
                blocked.increment();
                return;
            }
        }
    }

    public long blockedSpawns() {
        return blocked.sum();
    }
}
