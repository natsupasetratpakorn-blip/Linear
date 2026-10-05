package dev.m4sh3r.linear.ai;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.config.LinearConfig;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Switches villager brains off where they do nothing useful (trading halls, villagers far
 * from every player) while keeping trading, restocking, levelling, breeding and golem
 * farms working.
 */
public final class VillagerOptimizer implements MobHandler, Listener {

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Set<Material> WORKSTATIONS = EnumSet.of(
            Material.BARREL, Material.BLAST_FURNACE, Material.BREWING_STAND, Material.CARTOGRAPHY_TABLE,
            Material.CAULDRON, Material.WATER_CAULDRON, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON,
            Material.COMPOSTER, Material.FLETCHING_TABLE, Material.GRINDSTONE, Material.LECTERN, Material.LOOM,
            Material.SMITHING_TABLE, Material.SMOKER, Material.STONECUTTER);
    /** Vanilla work hours: villagers restock at their job site between these day times. */
    private static final long WORK_START = 2000, WORK_END = 9000;

    /**
     * Paper 1.21.x ticks the brain of villagers outside the entity activation range without
     * looking at the aware flag (fixed in 26.1). There, villagers far from players also get
     * the NoAI flag while they are out of range.
     */
    private static final boolean LEGACY_INACTIVE_TICK = Bukkit.getMinecraftVersion().startsWith("1.");
    /** Extra distance on top of the activation range, so a villager is unfrozen before a player arrives. */
    private static final double FREEZE_MARGIN = 24;

    private final Linear plugin;
    private final AiController ai;
    private final PlayerPositions players;
    private final NamespacedKey lastRestockKey;
    private final Map<String, Integer> activationRanges = new ConcurrentHashMap<>();

    public VillagerOptimizer(Linear plugin, AiController ai, PlayerPositions players) {
        this.plugin = plugin;
        this.ai = ai;
        this.players = players;
        this.lastRestockKey = new NamespacedKey(plugin, "last_restock");
    }

    @Override
    public boolean handles(Mob mob) {
        return mob instanceof Villager;
    }

    @Override
    public int interval() {
        return plugin.settings().villagers.interval;
    }

    @Override
    public int wakeTicks() {
        return plugin.settings().villagers.wakeTicks;
    }

    @Override
    public void evaluate(Mob mob) {
        Villager villager = (Villager) mob;
        boolean ours = ai.isOurs(villager);
        if (!ours && (!villager.isAware() || !villager.hasAI())) {
            return; // AI turned off by someone else - not ours to manage
        }
        LinearConfig.Villagers c = plugin.settings().villagers;
        if (plugin.active(Linear.VILLAGERS) && shouldSleep(villager, c)) {
            if (!ours) {
                ai.throttle(villager, AiController.Reason.VILLAGER);
            }
            if (LEGACY_INACTIVE_TICK) {
                ai.setFrozen(villager, canFreeze(villager));
            }
            if (c.restock) {
                restockIfDue(villager, c);
            }
        } else if (ours) {
            ai.release(villager);
        }
    }

    // ---- job sites and beds changing nearby need a working brain to be noticed ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        poiChanged(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        poiChanged(event.getBlock());
    }

    private void poiChanged(Block block) {
        Material type = block.getType();
        if (!WORKSTATIONS.contains(type) && !Tag.BEDS.isTagged(type)) {
            return;
        }
        int ticks = plugin.settings().villagers.workstationWakeTicks;
        for (Entity e : block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 0.5, 0.5), 4, 3, 4)) {
            if (e instanceof Villager v) {
                ai.wake(v, ticks);
            }
        }
    }

    private boolean shouldSleep(Villager v, LinearConfig.Villagers c) {
        if (v.getTicksLived() < c.graceTicks || v.isTrading() || ai.awake(v) || v.isLeashed() || v.isSleeping()) {
            return false;
        }
        if (!c.keepNameTags.isEmpty() && hasKeepName(v, c.keepNameTags)) {
            return false;
        }
        if (c.keepIfBed && v.getMemory(MemoryKey.HOME) != null) {
            return false;
        }
        if (c.confined && isConfined(v, c.confinedMaxArea)) {
            return true;
        }
        if (!c.idle) {
            return false;
        }
        double radius = c.idleRadius;
        if (plugin.stressed()) {
            radius = Math.min(radius, plugin.settings().adaptive.villagerRadius);
        }
        if (players.anyNear(v.getLocation(), radius)) {
            return false;
        }
        if (c.keepProfessions.contains(professionKey(v))) {
            return false;
        }
        return !(c.keepBreedReady && isBreedReady(v));
    }

    /**
     * Freezing (NoAI) also stops physics, so it is only used where the server would not move
     * the villager anyway: out of activation range, standing on the ground or riding
     * something, and not in water (water streams wake inactive entities up).
     */
    private boolean canFreeze(Villager v) {
        if (!v.isAdult() || v.isInWater() || !(v.isOnGround() || v.isInsideVehicle())) {
            return false;
        }
        return !players.anyNear(v.getLocation(), activationRange(v.getWorld().getName()) + FREEZE_MARGIN);
    }

    private int activationRange(String world) {
        return activationRanges.computeIfAbsent(world, name -> {
            try {
                var spigot = Bukkit.spigot().getConfig();
                return spigot.getInt("world-settings." + name + ".entity-activation-range.villagers",
                        spigot.getInt("world-settings.default.entity-activation-range.villagers", 32));
            } catch (Throwable t) {
                return 32;
            }
        });
    }

    private static boolean hasKeepName(Villager v, List<String> tags) {
        Component name = v.customName();
        if (name == null) {
            return false;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name).toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (plain.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation") // Keyed#getKey is the only accessor present on every supported version
    private static String professionKey(Villager v) {
        return v.getProfession().getKey().getKey();
    }

    /** Same rule as vanilla: an adult without breeding cooldown and with 12+ food points. */
    private static boolean isBreedReady(Villager v) {
        if (!v.isAdult() || v.getAge() != 0) {
            return false;
        }
        int points = 0;
        for (ItemStack item : v.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            Material type = item.getType();
            if (type == Material.BREAD) {
                points += 4 * item.getAmount();
            } else if (type == Material.CARROT || type == Material.POTATO || type == Material.BEETROOT) {
                points += item.getAmount();
            }
        }
        return points >= 12;
    }

    // ---- confinement: can the villager walk anywhere? ----

    /**
     * Flood fills the floor the villager can walk on, up to {@code maxArea} blocks. Any way
     * out (more room, a drop, a step up, an unloaded chunk) means the villager is not
     * confined. Villagers riding something (minecart, boat) are confined.
     */
    static boolean isConfined(Villager v, int maxArea) {
        if (v.isInsideVehicle()) {
            return true;
        }
        Location loc = v.getLocation();
        World world = loc.getWorld();
        // Standing on a slab, carpet or path block puts the feet slightly above the block grid.
        int y = (int) Math.ceil(loc.getY() - 1.0E-3);
        int startX = loc.getBlockX();
        int startZ = loc.getBlockZ();
        if (!isOpen(world.getBlockAt(startX, y + 1, startZ))) {
            return false; // head inside a block (odd geometry): don't guess
        }
        boolean headroomForJump = isOpen(world.getBlockAt(startX, y + 2, startZ));

        HashSet<Long> visited = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        visited.add(key(startX, startZ));
        queue.add(new long[] {startX, startZ});
        while (!queue.isEmpty()) {
            long[] cell = queue.poll();
            int cx = (int) cell[0];
            int cz = (int) cell[1];
            for (int[] d : DIRECTIONS) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (visited.contains(key(nx, nz))) {
                    continue;
                }
                if (!world.isChunkLoaded(nx >> 4, nz >> 4)) {
                    return false;
                }
                Block feet = world.getBlockAt(nx, y, nz);
                Block head = world.getBlockAt(nx, y + 1, nz);
                if (isOpen(feet) && isOpen(head)) {
                    if (isOpen(world.getBlockAt(nx, y - 1, nz))) {
                        return false; // it could drop down
                    }
                    visited.add(key(nx, nz));
                    if (visited.size() > maxArea) {
                        return false;
                    }
                    queue.add(new long[] {nx, nz});
                } else if (headroomForJump && canStepOnto(feet) && isOpen(head) && isOpen(world.getBlockAt(nx, y + 2, nz))) {
                    return false; // it could jump up
                }
            }
        }
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Open for a walking villager: passable blocks plus wooden doors, which villagers open. */
    private static boolean isOpen(Block block) {
        return block.isPassable() || Tag.WOODEN_DOORS.isTagged(block.getType());
    }

    /** Blocks no taller than one block can be jumped onto; fences and walls cannot. */
    private static boolean canStepOnto(Block block) {
        return block.getBoundingBox().getMaxY() - block.getY() <= 1.0;
    }

    // ---- restocking for villagers whose brain is off ----

    /**
     * Mirrors vanilla: during work hours, at most twice per day and at least 2400 ticks
     * apart, only when a trade has been used. Restocking also updates demand, so prices
     * react to trading exactly like they do for a normal villager.
     */
    private void restockIfDue(Villager v, LinearConfig.Villagers c) {
        String profession = professionKey(v);
        if (profession.equals("none") || profession.equals("nitwit")) {
            return;
        }
        World world = v.getWorld();
        long dayTime = world.getTime();
        if (dayTime < WORK_START || dayTime >= WORK_END) {
            return;
        }
        if (c.restockRequireJobSite) {
            Location site = v.getMemory(MemoryKey.JOB_SITE);
            if (site == null || site.getWorld() != world || site.distanceSquared(v.getLocation()) > 6 * 6) {
                return;
            }
        }
        List<MerchantRecipe> recipes = v.getRecipes();
        boolean used = false;
        for (MerchantRecipe recipe : recipes) {
            if (recipe.getUses() > 0) {
                used = true;
                break;
            }
        }
        if (!used) {
            return;
        }
        PersistentDataContainer pdc = v.getPersistentDataContainer();
        long now = world.getGameTime();
        long last = pdc.getOrDefault(lastRestockKey, PersistentDataType.LONG, Long.MIN_VALUE / 2);
        int today = v.getRestocksToday();
        if (now > last + 12000) {
            today = 0; // a new day
        }
        if (today != 0 && (today >= 2 || now <= last + 2400)) {
            return;
        }
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            recipe.setDemand(Math.max(0, recipe.getDemand() + recipe.getUses() - (recipe.getMaxUses() - recipe.getUses())));
            recipe.setUses(0);
            v.setRecipe(i, recipe);
        }
        v.setRestocksToday(today + 1);
        pdc.set(lastRestockKey, PersistentDataType.LONG, now);
    }
}
