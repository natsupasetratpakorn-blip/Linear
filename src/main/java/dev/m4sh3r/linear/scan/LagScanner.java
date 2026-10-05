package dev.m4sh3r.linear.scan;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.ai.PlayerPositions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;

/**
 * Finds the chunks that cost the most: many entities, dropped items, hoppers and block
 * entities. Each chunk is counted on the thread that owns it, a few dozen chunks per tick,
 * so a scan never freezes the server and is safe on Folia.
 */
public final class LagScanner {

    private static final int CHUNKS_PER_TICK = 64;
    private static final long TIMEOUT_TICKS = 20L * 15;

    public record ChunkStats(String world, int cx, int cz, int entities, int items, int mobs, int hoppers,
                             int blockEntities, EntityType topType, int topCount, int x, int y, int z) {
        /** A rough cost estimate: entities tick every tick, items also search for merges, hoppers search for items. */
        double score() {
            return entities + items * 0.5 + hoppers * 3 + blockEntities * 0.25;
        }
    }

    private record Target(World world, int cx, int cz) {
    }

    private final Linear plugin;
    private final PlayerPositions players;
    private final AtomicBoolean running = new AtomicBoolean();

    public LagScanner(Linear plugin, PlayerPositions players) {
        this.plugin = plugin;
        this.players = players;
    }

    public void scan(CommandSender sender) {
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage(plugin.message("<warn>A scan is already running.</warn>"));
            return;
        }
        List<Target> targets = targets();
        if (targets.isEmpty()) {
            running.set(false);
            sender.sendMessage(plugin.message("<muted>No loaded chunks to scan.</muted>"));
            return;
        }
        sender.sendMessage(plugin.message("<muted>Scanning</muted> <value>" + targets.size() + "</value> <muted>chunks...</muted>"));
        ConcurrentLinkedQueue<ChunkStats> results = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(targets.size());
        AtomicBoolean reported = new AtomicBoolean();
        Runnable finish = () -> {
            if (reported.compareAndSet(false, true)) {
                running.set(false);
                report(sender, new ArrayList<>(results), targets.size());
            }
        };
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            long delay = 1 + i / CHUNKS_PER_TICK;
            plugin.getServer().getRegionScheduler().runDelayed(plugin, t.world(), t.cx(), t.cz(), task -> {
                try {
                    ChunkStats stats = measure(t);
                    if (stats != null) {
                        results.add(stats);
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        finish.run();
                    }
                }
            }, delay);
        }
        // Chunks that unload mid-scan may never run their task; report what we have.
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> finish.run(),
                TIMEOUT_TICKS + targets.size() / CHUNKS_PER_TICK);
    }

    /**
     * Every loaded chunk on Paper. Folia has no thread that may walk a whole world, so there
     * the chunks within simulation distance of each player are scanned (that is where
     * entities and redstone actually tick).
     */
    private List<Target> targets() {
        List<Target> out = new ArrayList<>();
        if (!Linear.folia()) {
            for (World world : plugin.getServer().getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    out.add(new Target(world, chunk.getX(), chunk.getZ()));
                }
            }
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (PlayerPositions.Snapshot p : players.snapshot()) {
            World world = plugin.getServer().getWorld(p.world());
            if (world == null) {
                continue;
            }
            int radius = Math.max(2, world.getSimulationDistance());
            int pcx = (int) Math.floor(p.x()) >> 4;
            int pcz = (int) Math.floor(p.z()) >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (seen.add(world.getName() + ':' + (pcx + dx) + ':' + (pcz + dz))) {
                        out.add(new Target(world, pcx + dx, pcz + dz));
                    }
                }
            }
        }
        return out;
    }

    private static ChunkStats measure(Target t) {
        World world = t.world();
        if (!world.isChunkLoaded(t.cx(), t.cz())) {
            return null;
        }
        Chunk chunk = world.getChunkAt(t.cx(), t.cz());
        Entity[] entities = chunk.getEntities();
        int items = 0;
        int mobs = 0;
        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        Entity sample = null;
        for (Entity e : entities) {
            if (e instanceof Item) {
                items++;
            } else if (e instanceof Mob) {
                mobs++;
            }
            byType.merge(e.getType(), 1, Integer::sum);
        }
        EntityType top = null;
        int topCount = 0;
        for (Map.Entry<EntityType, Integer> e : byType.entrySet()) {
            if (e.getValue() > topCount) {
                top = e.getKey();
                topCount = e.getValue();
            }
        }
        for (Entity e : entities) {
            if (e.getType() == top) {
                sample = e;
                break;
            }
        }
        BlockState[] blockEntities = chunk.getTileEntities(false);
        int hoppers = 0;
        BlockState hopperSample = null;
        for (BlockState state : blockEntities) {
            if (state instanceof Hopper) {
                hoppers++;
                hopperSample = state;
            }
        }
        if (entities.length == 0 && blockEntities.length == 0) {
            return null;
        }
        int x, y, z;
        if (sample != null) {
            x = sample.getLocation().getBlockX();
            y = sample.getLocation().getBlockY();
            z = sample.getLocation().getBlockZ();
        } else if (hopperSample != null) {
            x = hopperSample.getX();
            y = hopperSample.getY();
            z = hopperSample.getZ();
        } else {
            x = (t.cx() << 4) + 8;
            z = (t.cz() << 4) + 8;
            y = world.getHighestBlockYAt(x, z);
        }
        return new ChunkStats(world.getName(), t.cx(), t.cz(), entities.length, items, mobs, hoppers,
                blockEntities.length, top, topCount, x, y, z);
    }

    private void report(CommandSender sender, List<ChunkStats> stats, int scanned) {
        int entities = 0, items = 0, hoppers = 0;
        for (ChunkStats s : stats) {
            entities += s.entities();
            items += s.items();
            hoppers += s.hoppers();
        }
        sender.sendMessage(plugin.message("<muted>Scanned</muted> <value>" + scanned + "</value> <muted>chunks</muted> <dim>·</dim> <value>"
                + entities + "</value> <muted>entities</muted> <dim>·</dim> <value>" + items + "</value> <muted>items</muted> <dim>·</dim> <value>"
                + hoppers + "</value> <muted>hoppers</muted>"));
        stats.sort(Comparator.comparingDouble(ChunkStats::score).reversed());
        List<ChunkStats> top = stats.subList(0, Math.min(8, stats.size()));
        if (top.isEmpty()) {
            sender.sendMessage(plugin.message("<good>Nothing worth reporting.</good>"));
            return;
        }
        sender.sendMessage(plugin.message("<muted>Heaviest chunks</muted>"));
        for (ChunkStats s : top) {
            String colour = s.score() >= 300 ? "bad" : s.score() >= 100 ? "warn" : "value";
            StringBuilder line = new StringBuilder(" <dim>›</dim> <" + colour + ">" + s.world() + " " + s.x() + " " + s.y() + " " + s.z()
                    + "</" + colour + "> <muted>entities</muted> <accent>" + s.entities() + "</accent>");
            if (s.items() > 0) {
                line.append(" <muted>items</muted> <accent>").append(s.items()).append("</accent>");
            }
            if (s.hoppers() > 0) {
                line.append(" <muted>hoppers</muted> <accent>").append(s.hoppers()).append("</accent>");
            }
            if (s.topType() != null && s.topCount() > 1 && s.topType() != EntityType.ITEM) {
                line.append(" <dim>(mostly ").append(s.topType().name().toLowerCase().replace('_', ' ')).append(")</dim>");
            }
            sender.sendMessage(plugin.text(line.toString())
                    .hoverEvent(HoverEvent.showText(plugin.text("<accent>Click to teleport</accent><newline><muted>Chunk "
                            + s.cx() + ", " + s.cz() + " · " + s.blockEntities() + " block entities</muted>")))
                    .clickEvent(ClickEvent.runCommand("/linear tp " + s.world() + " " + s.x() + " " + s.y() + " " + s.z())));
        }
    }
}
