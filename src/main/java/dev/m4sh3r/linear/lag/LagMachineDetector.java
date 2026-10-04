package dev.m4sh3r.linear.lag;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.config.LinearConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * Finds lag machines by counting redstone updates, piston moves and falling block / TNT
 * activity per chunk, once per second. Events only bump a counter (a map lookup and an
 * atomic increment), so normal redstone pays next to nothing for the detection.
 *
 * <p>A chunk that stays over a limit for several seconds is flagged: staff are alerted and,
 * in THROTTLE mode, the chunk's redstone is frozen in place and its pistons, falling blocks
 * and TNT stop until the flag expires.
 */
public final class LagMachineDetector implements Listener {

    public record ChunkPos(UUID world, int x, int z) {
    }

    static final class Counter {
        final AtomicInteger redstone = new AtomicInteger();
        final AtomicInteger pistons = new AtomicInteger();
        final AtomicInteger falling = new AtomicInteger();
        volatile int lastX, lastY, lastZ;
        // Only touched by the evaluation task:
        int strikes;
        int idleSeconds;
    }

    /** What a chunk did during the last full second. */
    public record Activity(ChunkPos pos, String world, int redstone, int pistons, int falling, int x, int y, int z) {
        public int total() {
            return redstone + pistons + falling;
        }
    }

    public record Flag(ChunkPos pos, String world, long until, int offense, String reason, int x, int y, int z) {
    }

    private record Offense(int count, long lastMillis) {
    }

    private final Linear plugin;
    private final Map<ChunkPos, Counter> counters = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Flag> flags = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Offense> offenses = new ConcurrentHashMap<>();
    private volatile List<Activity> lastSecond = List.of();
    private final LongAdder blocked = new LongAdder();

    public LagMachineDetector(Linear plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> evaluate(), 1, 1, TimeUnit.SECONDS);
    }

    // ---- counting (hot path) ----

    private Counter counter(Block b) {
        ChunkPos pos = new ChunkPos(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4);
        Counter c = counters.get(pos);
        if (c == null) {
            c = counters.computeIfAbsent(pos, k -> new Counter());
        }
        c.lastX = b.getX();
        c.lastY = b.getY();
        c.lastZ = b.getZ();
        return c;
    }

    private boolean throttled(Block b) {
        if (flags.isEmpty()) {
            return false;
        }
        Flag flag = flags.get(new ChunkPos(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4));
        return flag != null && plugin.settings().lag.throttle && plugin.active(Linear.LAG);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!plugin.active(Linear.LAG)) {
            return;
        }
        Block block = event.getBlock();
        counter(block).redstone.incrementAndGet();
        if (throttled(block) && event.getNewCurrent() != event.getOldCurrent()) {
            event.setNewCurrent(event.getOldCurrent()); // freeze the signal where it is
            blocked.increment();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        piston(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        piston(event.getBlock(), event);
    }

    private void piston(Block block, org.bukkit.event.Cancellable event) {
        if (!plugin.active(Linear.LAG)) {
            return;
        }
        counter(block).pistons.incrementAndGet();
        if (throttled(block)) {
            event.setCancelled(true);
            blocked.increment();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockStartsFalling(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock) || !plugin.active(Linear.LAG)) {
            return;
        }
        Material to = event.getTo();
        if (!to.isAir() && to != Material.WATER && to != Material.LAVA) {
            return; // landing, not starting to fall
        }
        Block block = event.getBlock();
        counter(block).falling.incrementAndGet();
        if (throttled(block)) {
            event.setCancelled(true); // the block simply stays where it is
            blocked.increment();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        if (!plugin.active(Linear.LAG)) {
            return;
        }
        Block block = event.getBlock();
        counter(block).falling.incrementAndGet();
        if (throttled(block)) {
            event.setCancelled(true);
            blocked.increment();
        }
    }

    // ---- evaluation (once per second, async) ----

    private void evaluate() {
        long now = System.currentTimeMillis();
        flags.values().removeIf(flag -> {
            if (flag.until() <= now) {
                plugin.getLogger().info("Lag machine throttle lifted at " + describe(flag));
                return true;
            }
            return false;
        });
        offenses.values().removeIf(o -> now - o.lastMillis() > TimeUnit.MINUTES.toMillis(30));

        LinearConfig.LagMachines cfg = plugin.settings().lag;
        double scale = plugin.stressed() ? plugin.settings().adaptive.lagMultiplier : 1.0;
        int redstoneLimit = (int) Math.max(1, cfg.redstone * scale);
        int pistonLimit = (int) Math.max(1, cfg.pistons * scale);
        int fallingLimit = (int) Math.max(1, cfg.fallingAndTnt * scale);

        List<Activity> activity = new ArrayList<>();
        var it = counters.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ChunkPos pos = entry.getKey();
            Counter c = entry.getValue();
            int r = c.redstone.getAndSet(0);
            int p = c.pistons.getAndSet(0);
            int f = c.falling.getAndSet(0);
            if (r == 0 && p == 0 && f == 0) {
                if (++c.idleSeconds > 30) {
                    it.remove();
                }
                c.strikes = 0;
                continue;
            }
            c.idleSeconds = 0;
            World world = plugin.getServer().getWorld(pos.world());
            String worldName = world == null ? "?" : world.getName();
            activity.add(new Activity(pos, worldName, r, p, f, c.lastX, c.lastY, c.lastZ));

            String reason = r > redstoneLimit ? r + " redstone updates/s"
                    : p > pistonLimit ? p + " piston moves/s"
                    : f > fallingLimit ? f + " falling blocks & TNT/s"
                    : null;
            if (reason == null) {
                c.strikes = Math.max(0, c.strikes - 1);
                continue;
            }
            if (++c.strikes >= cfg.strikes && !flags.containsKey(pos)) {
                c.strikes = 0;
                flag(pos, worldName, reason, c, cfg, now);
            }
        }
        activity.sort(Comparator.comparingInt(Activity::total).reversed());
        lastSecond = activity.size() > 50 ? List.copyOf(activity.subList(0, 50)) : List.copyOf(activity);
    }

    private void flag(ChunkPos pos, String worldName, String reason, Counter c, LinearConfig.LagMachines cfg, long now) {
        Offense previous = offenses.get(pos);
        int offense = previous == null ? 1 : previous.count() + 1;
        offenses.put(pos, new Offense(offense, now));
        long seconds = Math.min((long) cfg.maxThrottleSeconds, (long) cfg.throttleSeconds << Math.min(20, offense - 1));
        Flag flag = new Flag(pos, worldName, now + seconds * 1000, offense, reason, c.lastX, c.lastY, c.lastZ);
        if (cfg.throttle) {
            flags.put(pos, flag);
        }
        String action = cfg.throttle ? "throttled for " + seconds + "s" : "detected";
        plugin.getLogger().warning("Lag machine " + action + " at " + describe(flag) + ": " + reason
                + (offense > 1 ? " (offense #" + offense + ")" : ""));
        alert(flag, action);
    }

    private void alert(Flag flag, String action) {
        Component msg = plugin.message("<red>Lag machine " + action + "</red> <gray>at</gray> <white>" + describe(flag)
                + "</white> <dark_gray>(" + flag.reason() + ")</dark_gray> <aqua><u>[teleport]</u></aqua>")
                .clickEvent(ClickEvent.runCommand("/linear tp " + flag.world() + " " + flag.x() + " " + flag.y() + " " + flag.z()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to teleport")));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("linear.alerts")) {
                player.sendMessage(msg);
            }
        }
    }

    private static String describe(Flag f) {
        return f.world() + " " + f.x() + " " + f.y() + " " + f.z() + " (chunk " + f.pos().x() + ", " + f.pos().z() + ")";
    }

    // ---- queries for /linear ----

    public List<Activity> lastSecond() {
        return lastSecond;
    }

    public List<Flag> flags() {
        return List.copyOf(flags.values());
    }

    public long blockedActions() {
        return blocked.sum();
    }

    public void clearFlags() {
        flags.clear();
    }
}
