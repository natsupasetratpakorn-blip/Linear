package dev.m4sh3r.linear.ai;

import dev.m4sh3r.linear.Linear;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The only place where Linear switches mob AI on or off.
 *
 * <p>Linear turns AI off with {@link Mob#setAware(boolean)}: the mob stops running goals,
 * brains, sensors and pathfinding, but still moves with physics, ages, lays eggs, can be
 * traded with and drops loot. Every mob Linear touches is tagged in its persistent data, so
 * Linear never takes over mobs that another plugin or an admin made unaware, and can always
 * give back what it took - even after a restart.
 *
 * <p>All methods taking a mob must be called on the thread that owns that mob.
 */
public final class AiController {

    public enum Reason {
        VILLAGER((byte) 1), CROWD((byte) 2);

        final byte id;

        Reason(byte id) {
            this.id = id;
        }
    }

    private record Entry(Mob mob, Reason reason) {
    }

    private final Linear plugin;
    private final NamespacedKey key;
    private final NamespacedKey frozenKey;
    private final Map<UUID, Entry> throttled = new ConcurrentHashMap<>();
    /** Game time until which a mob is kept awake because a player interacted with it. */
    private final Map<UUID, Long> wakeUntil = new ConcurrentHashMap<>();
    private final LongAdder throttleCount = new LongAdder();

    public AiController(Linear plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "ai_off");
        this.frozenKey = new NamespacedKey(plugin, "no_ai");
    }

    public boolean isOurs(Mob mob) {
        return mob.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Re-registers a mob that was throttled before (e.g. loaded from disk). */
    public void adopt(Mob mob) {
        Byte id = mob.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        if (id != null) {
            throttled.put(mob.getUniqueId(), new Entry(mob, id == Reason.CROWD.id ? Reason.CROWD : Reason.VILLAGER));
        }
    }

    public void throttle(Mob mob, Reason reason) {
        mob.setAware(false);
        mob.getPersistentDataContainer().set(key, PersistentDataType.BYTE, reason.id);
        throttled.put(mob.getUniqueId(), new Entry(mob, reason));
        throttleCount.increment();
    }

    public void release(Mob mob) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        unfreeze(mob, pdc);
        if (pdc.has(key, PersistentDataType.BYTE)) {
            pdc.remove(key);
            mob.setAware(true);
        }
        throttled.remove(mob.getUniqueId());
    }

    /**
     * Sets or clears the NoAI flag on a throttled mob. Only needed on 1.21.x, where Paper
     * keeps ticking the brain of villagers outside the activation range even when they are
     * unaware. Callers only freeze mobs that are out of activation range, where the server
     * does not move them anyway.
     */
    public void setFrozen(Mob mob, boolean frozen) {
        PersistentDataContainer pdc = mob.getPersistentDataContainer();
        if (frozen) {
            if (!pdc.has(frozenKey, PersistentDataType.BYTE)) {
                pdc.set(frozenKey, PersistentDataType.BYTE, (byte) 1);
                mob.setAI(false);
            }
        } else {
            unfreeze(mob, pdc);
        }
    }

    private void unfreeze(Mob mob, PersistentDataContainer pdc) {
        if (pdc.has(frozenKey, PersistentDataType.BYTE)) {
            pdc.remove(frozenKey);
            mob.setAI(true);
        }
    }

    /** Keeps a mob's AI on for the given number of ticks, starting now. */
    public void wake(Mob mob, int ticks) {
        wakeUntil.put(mob.getUniqueId(), mob.getWorld().getGameTime() + ticks);
        release(mob);
    }

    public boolean awake(Mob mob) {
        Long until = wakeUntil.get(mob.getUniqueId());
        if (until == null) {
            return false;
        }
        if (mob.getWorld().getGameTime() >= until) {
            wakeUntil.remove(mob.getUniqueId(), until);
            return false;
        }
        return true;
    }

    /** Called when a mob leaves the world (death, unload). Its tag stays in its saved data. */
    public void forget(UUID id) {
        throttled.remove(id);
        wakeUntil.remove(id);
    }

    public int count(Reason reason) {
        int n = 0;
        for (Entry e : throttled.values()) {
            if (e.reason == reason) {
                n++;
            }
        }
        return n;
    }

    public long totalThrottles() {
        return throttleCount.sum();
    }

    /** Gives AI back to every throttled mob, each on its own thread. Returns how many were scheduled. */
    public int restoreAll(Reason reason) {
        int n = 0;
        for (Entry e : new ArrayList<>(throttled.values())) {
            if (reason != null && e.reason != reason) {
                continue;
            }
            Mob mob = e.mob;
            if (mob.getScheduler().run(plugin, task -> release(mob), () -> throttled.remove(mob.getUniqueId())) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Used on shutdown when no tasks can be scheduled any more. The server has stopped
     * ticking entities at this point, so touching them directly is safe.
     */
    public int restoreAllNow() {
        List<Entry> all = new ArrayList<>(throttled.values());
        int n = 0;
        for (Entry e : all) {
            try {
                PersistentDataContainer pdc = e.mob.getPersistentDataContainer();
                unfreeze(e.mob, pdc);
                pdc.remove(key);
                e.mob.setAware(true);
                n++;
            } catch (Throwable t) {
                plugin.getLogger().fine("Could not restore " + e.mob.getUniqueId() + ": " + t);
            }
        }
        throttled.clear();
        return n;
    }
}
