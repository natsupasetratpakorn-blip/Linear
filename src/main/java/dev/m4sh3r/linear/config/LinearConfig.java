package dev.m4sh3r.linear.config;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

/**
 * Immutable snapshot of config.yml. A new snapshot replaces the old one on reload,
 * so tasks running on other threads always see a consistent set of values.
 */
public final class LinearConfig {

    public final Villagers villagers;
    public final Crowd crowd;
    public final LagMachines lag;
    public final ChunkLimits limits;
    public final Adaptive adaptive;
    public final String prefix;
    public final boolean smallCaps;

    public LinearConfig(FileConfiguration c, Consumer<String> warn) {
        this.villagers = new Villagers(c, warn);
        this.crowd = new Crowd(c, warn);
        this.lag = new LagMachines(c, warn);
        this.limits = new ChunkLimits(c, warn);
        this.adaptive = new Adaptive(c);
        this.prefix = c.getString("messages.prefix", "<gradient:#38bdf8:#818cf8><bold>Linear</bold></gradient> <dim>»</dim> ");
        this.smallCaps = c.getBoolean("messages.small-caps", true);
    }

    public static final class Villagers {
        public final boolean enabled;
        public final int interval;
        public final int graceTicks;
        public final boolean confined;
        public final int confinedMaxArea;
        public final boolean idle;
        public final double idleRadius;
        public final Set<String> keepProfessions;
        public final boolean keepBreedReady;
        public final boolean keepIfBed;
        public final List<String> keepNameTags;
        public final int wakeTicks;
        public final int workstationWakeTicks;
        public final boolean restock;
        public final boolean restockRequireJobSite;

        Villagers(FileConfiguration c, Consumer<String> warn) {
            enabled = c.getBoolean("villagers.enabled", true);
            interval = Math.max(10, c.getInt("villagers.check-interval-ticks", 40));
            graceTicks = Math.max(0, c.getInt("villagers.grace-ticks", 600));
            confined = c.getBoolean("villagers.confined.enabled", true);
            confinedMaxArea = Math.max(1, Math.min(16, c.getInt("villagers.confined.max-area", 2)));
            idle = c.getBoolean("villagers.idle-without-players.enabled", true);
            idleRadius = Math.max(8, c.getDouble("villagers.idle-without-players.radius", 48));
            keepProfessions = professions(c.getStringList("villagers.idle-without-players.keep-professions"), warn);
            keepBreedReady = c.getBoolean("villagers.idle-without-players.keep-breed-ready", true);
            keepIfBed = c.getBoolean("villagers.keep-ai-if-bed-claimed", true);
            keepNameTags = c.getStringList("villagers.keep-ai-name-tags").stream()
                    .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).toList();
            wakeTicks = Math.max(20, c.getInt("villagers.wake-on-interact-ticks", 200));
            workstationWakeTicks = Math.max(20, c.getInt("villagers.wake-on-workstation-ticks", 600));
            restock = c.getBoolean("villagers.restock.enabled", true);
            restockRequireJobSite = c.getBoolean("villagers.restock.require-job-site", true);
        }

        private static Set<String> professions(List<String> names, Consumer<String> warn) {
            Set<String> out = new java.util.HashSet<>();
            for (String name : names) {
                NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
                Villager.Profession p = key == null ? null : Registry.VILLAGER_PROFESSION.get(key);
                if (p == null) {
                    warn.accept("Unknown villager profession in villagers.idle-without-players.keep-professions: " + name);
                } else {
                    out.add(p.getKey().getKey());
                }
            }
            return Set.copyOf(out);
        }
    }

    public static final class Crowd {
        public final boolean enabled;
        public final int interval;
        public final double radius;
        public final int threshold;
        public final Set<EntityType> types;
        public final boolean keepNamed;
        public final int wakeTicks;

        Crowd(FileConfiguration c, Consumer<String> warn) {
            enabled = c.getBoolean("crowded-mobs.enabled", true);
            interval = Math.max(20, c.getInt("crowded-mobs.check-interval-ticks", 60));
            radius = Math.max(0.5, Math.min(16, c.getDouble("crowded-mobs.radius", 3.0)));
            threshold = Math.max(2, c.getInt("crowded-mobs.threshold", 10));
            EnumSet<EntityType> t = EnumSet.noneOf(EntityType.class);
            for (String s : c.getStringList("crowded-mobs.types")) {
                EntityType type = entityType(s);
                if (type == null || type.getEntityClass() == null
                        || !org.bukkit.entity.Mob.class.isAssignableFrom(type.getEntityClass())) {
                    warn.accept("Ignoring crowded-mobs type that is not a mob: " + s);
                } else if (type == EntityType.VILLAGER) {
                    warn.accept("Villagers are handled by the villagers section, not crowded-mobs.");
                } else {
                    t.add(type);
                }
            }
            types = t;
            keepNamed = c.getBoolean("crowded-mobs.keep-named", true);
            wakeTicks = Math.max(20, c.getInt("crowded-mobs.wake-on-interact-ticks", 600));
        }
    }

    public static final class LagMachines {
        public final boolean enabled;
        public final int redstone;
        public final int pistons;
        public final int fallingAndTnt;
        public final int strikes;
        public final boolean throttle;
        public final int throttleSeconds;
        public final int maxThrottleSeconds;

        LagMachines(FileConfiguration c, Consumer<String> warn) {
            enabled = c.getBoolean("lag-machines.enabled", true);
            redstone = Math.max(1, c.getInt("lag-machines.limits.redstone", 3000));
            pistons = Math.max(1, c.getInt("lag-machines.limits.pistons", 200));
            fallingAndTnt = Math.max(1, c.getInt("lag-machines.limits.falling-blocks-and-tnt", 150));
            strikes = Math.max(1, c.getInt("lag-machines.strikes", 3));
            String action = c.getString("lag-machines.action", "THROTTLE").toUpperCase(Locale.ROOT);
            if (!action.equals("THROTTLE") && !action.equals("ALERT")) {
                warn.accept("lag-machines.action must be THROTTLE or ALERT, using THROTTLE");
                action = "THROTTLE";
            }
            throttle = action.equals("THROTTLE");
            throttleSeconds = Math.max(1, c.getInt("lag-machines.throttle-seconds", 30));
            maxThrottleSeconds = Math.max(throttleSeconds, c.getInt("lag-machines.max-throttle-seconds", 600));
        }
    }

    public static final class ChunkLimits {
        public final boolean enabled;
        public final int breeding;
        public final int spawners;
        public final Map<EntityType, Integer> perType;

        ChunkLimits(FileConfiguration c, Consumer<String> warn) {
            enabled = c.getBoolean("chunk-limits.enabled", true);
            breeding = Math.max(1, c.getInt("chunk-limits.breeding", 80));
            spawners = Math.max(1, c.getInt("chunk-limits.spawners", 40));
            EnumMap<EntityType, Integer> map = new EnumMap<>(EntityType.class);
            ConfigurationSection sec = c.getConfigurationSection("chunk-limits.per-type");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    EntityType type = entityType(key);
                    if (type == null) {
                        warn.accept("Unknown entity type in chunk-limits.per-type: " + key);
                    } else {
                        map.put(type, Math.max(1, sec.getInt(key)));
                    }
                }
            }
            perType = map;
        }

        public int breedingLimit(EntityType type) {
            return perType.getOrDefault(type, breeding);
        }

        public int spawnerLimit(EntityType type) {
            return perType.getOrDefault(type, spawners);
        }
    }

    public static final class Adaptive {
        public final boolean enabled;
        public final double stressedMspt;
        public final double recoveredMspt;
        public final double villagerRadius;
        public final int crowdThreshold;
        public final double lagMultiplier;

        Adaptive(FileConfiguration c) {
            enabled = c.getBoolean("adaptive.enabled", true);
            stressedMspt = c.getDouble("adaptive.stressed-mspt", 45.0);
            recoveredMspt = Math.min(stressedMspt, c.getDouble("adaptive.recovered-mspt", 35.0));
            villagerRadius = Math.max(8, c.getDouble("adaptive.stressed.villager-radius", 24));
            crowdThreshold = Math.max(2, c.getInt("adaptive.stressed.crowd-threshold", 6));
            lagMultiplier = Math.max(0.05, Math.min(1.0, c.getDouble("adaptive.stressed.lag-limit-multiplier", 0.5)));
        }
    }

    private static EntityType entityType(String name) {
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
