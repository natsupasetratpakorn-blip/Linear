package dev.m4sh3r.linear.limit;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.lag.LagMachineDetector.ChunkPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps dropped-item floods in check without deleting anything. When a chunk holds more
 * loose item entities than the limit, identical items in that chunk are packed into full
 * stacks: the total amount of every item stays exactly the same, there are just far fewer
 * entities to tick. Items that look like display or shop items are never touched.
 */
public final class ItemFloodGuard implements Listener {

    private static final long CHECK_COOLDOWN_MS = 1000;
    /** Paper/Bukkit use this pickup delay for items that must never be picked up (displays). */
    private static final int NO_PICKUP = 32767;

    private final Linear plugin;
    private final Map<ChunkPos, Long> lastCheck = new ConcurrentHashMap<>();
    private final LongAdder removed = new LongAdder();

    public ItemFloodGuard(Linear plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        trigger(event.getLocation());
    }

    /** Piles at the end of water streams merge constantly, so merges are a trigger too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMerge(ItemMergeEvent event) {
        trigger(event.getTarget().getLocation());
    }

    private void trigger(Location loc) {
        if (!plugin.active(Linear.ITEMS)) {
            return;
        }
        World world = loc.getWorld();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        ChunkPos pos = new ChunkPos(world.getUID(), cx, cz);
        long now = System.currentTimeMillis();
        Long last = lastCheck.get(pos);
        if (last != null && now - last < CHECK_COOLDOWN_MS) {
            return;
        }
        lastCheck.put(pos, now);
        if (lastCheck.size() > 4096) {
            lastCheck.values().removeIf(t -> now - t > CHECK_COOLDOWN_MS * 10);
        }
        // Next tick, so the item that triggered this is in the world as well.
        plugin.getServer().getRegionScheduler().runDelayed(plugin, world, cx, cz, task -> compact(world, cx, cz), 1L);
    }

    private void compact(World world, int cx, int cz) {
        if (!world.isChunkLoaded(cx, cz)) {
            return;
        }
        int limit = plugin.settings().items.maxPerChunk;
        List<Item> items = new ArrayList<>();
        for (Entity e : world.getChunkAt(cx, cz).getEntities()) {
            if (e instanceof Item item && item.isValid()) {
                items.add(item);
            }
        }
        if (items.size() <= limit) {
            return;
        }
        Map<Material, List<List<Item>>> groups = new EnumMap<>(Material.class);
        for (Item item : items) {
            if (!mergeable(item)) {
                continue;
            }
            ItemStack stack = item.getItemStack();
            List<List<Item>> sameType = groups.computeIfAbsent(stack.getType(), k -> new ArrayList<>());
            List<Item> group = null;
            for (List<Item> g : sameType) {
                if (g.get(0).getItemStack().isSimilar(stack)) {
                    group = g;
                    break;
                }
            }
            if (group == null) {
                group = new ArrayList<>();
                sameType.add(group);
            }
            group.add(item);
        }
        for (List<List<Item>> sameType : groups.values()) {
            for (List<Item> group : sameType) {
                pack(group);
            }
        }
    }

    private static boolean mergeable(Item item) {
        return item.getPickupDelay() < NO_PICKUP
                && item.canPlayerPickup()
                && item.getOwner() == null
                && item.getItemStack().getMaxStackSize() > 1;
    }

    /** Packs a group of identical items into as few full stacks as possible; the total never changes. */
    private void pack(List<Item> group) {
        if (group.size() < 2) {
            return;
        }
        int max = group.get(0).getItemStack().getMaxStackSize();
        long total = 0;
        for (Item item : group) {
            total += item.getItemStack().getAmount();
        }
        int needed = (int) ((total + max - 1) / max);
        if (needed >= group.size()) {
            return;
        }
        group.sort(Comparator.comparingInt((Item i) -> i.getItemStack().getAmount()).reversed());
        long left = total;
        for (int i = 0; i < group.size(); i++) {
            Item item = group.get(i);
            if (i < needed) {
                int amount = (int) Math.min(max, left);
                left -= amount;
                ItemStack stack = item.getItemStack();
                if (stack.getAmount() != amount) {
                    stack.setAmount(amount);
                    item.setItemStack(stack);
                }
            } else {
                item.remove();
                removed.increment();
            }
        }
    }

    public long removedEntities() {
        return removed.sum();
    }
}
