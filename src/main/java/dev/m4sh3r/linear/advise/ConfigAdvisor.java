package dev.m4sh3r.linear.advise;

import dev.m4sh3r.linear.Linear;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import net.kyori.adventure.text.event.HoverEvent;
import dev.m4sh3r.linear.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads the server's own configuration files and suggests settings that are known to cost
 * performance. It only reads: Linear never changes these files.
 */
public final class ConfigAdvisor {

    /** impact: 3 = high, 2 = medium, 1 = low. */
    record Tip(int impact, String file, String setting, String current, String suggested, String why) {
    }

    private final Linear plugin;

    public ConfigAdvisor(Linear plugin) {
        this.plugin = plugin;
    }

    public void advise(CommandSender sender) {
        // File reading stays off the server threads.
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<Tip> tips;
            try {
                tips = collect();
            } catch (Throwable t) {
                sender.sendMessage(plugin.message("<bad>Could not read the server configuration: " + t.getMessage() + "</bad>"));
                return;
            }
            report(sender, tips);
        });
    }

    private List<Tip> collect() {
        List<Tip> tips = new ArrayList<>();
        File root = new File(".");

        Properties props = new Properties();
        File propsFile = new File(root, "server.properties");
        if (propsFile.isFile()) {
            try (Reader r = new FileReader(propsFile, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException ignored) {
                // reported as nothing found
            }
            int sim = parseInt(props.getProperty("simulation-distance"), 10);
            int view = parseInt(props.getProperty("view-distance"), 10);
            if (sim > 8) {
                tips.add(new Tip(3, "server.properties", "simulation-distance", String.valueOf(sim), "6",
                        "How many chunks around each player tick mobs, redstone and crops. The biggest single lever."));
            }
            if (view > 10) {
                tips.add(new Tip(2, "server.properties", "view-distance", String.valueOf(view), "8",
                        "How far chunks are sent to players. Costs chunk loading, memory and bandwidth."));
            }
        }

        YamlConfiguration paper = yaml(new File(root, "config/paper-world-defaults.yml"));
        if (paper != null) {
            check(tips, paper, "config/paper-world-defaults.yml", "misc.redstone-implementation", "VANILLA", "ALTERNATE_CURRENT", 3,
                    "Much faster redstone dust. Behaves like vanilla for almost every build; a few update-order tricks can differ.");
            check(tips, paper, "config/paper-world-defaults.yml", "environment.optimize-explosions", "false", "true", 2,
                    "Caches explosion calculations. Big win for TNT and creeper farms, same result.");
            check(tips, paper, "config/paper-world-defaults.yml", "misc.update-pathfinding-on-block-update", "true", "false", 2,
                    "Stops every nearby mob from re-planning its path whenever any block changes.");
            check(tips, paper, "config/paper-world-defaults.yml", "tick-rates.mob-spawner", "1", "2", 2,
                    "Spawners check half as often. Spawn rates barely change.");
            check(tips, paper, "config/paper-world-defaults.yml", "hopper.disable-move-event", "false", "true", 2,
                    "Hoppers stop firing an event per item move. Only if no plugin needs InventoryMoveItemEvent (hopper filters, protection).");
            check(tips, paper, "config/paper-world-defaults.yml", "collisions.max-entity-collisions", "8", "2", 2,
                    "Fewer collision checks per mob. Large gain in crowded farms.");
            check(tips, paper, "config/paper-world-defaults.yml", "hopper.ignore-occluding-blocks", "false", "true", 1,
                    "Hoppers skip looking for containers inside solid blocks.");
            check(tips, paper, "config/paper-world-defaults.yml", "tick-rates.grass-spread", "1", "4", 1,
                    "Grass and mycelium spread checks run less often.");
            check(tips, paper, "config/paper-world-defaults.yml", "entities.armor-stands.tick", "true", "false", 1,
                    "Armor stands stop ticking. Only if no plugin or map needs them to move or fall.");
            check(tips, paper, "config/paper-world-defaults.yml", "entities.armor-stands.do-collision-entity-lookups", "true", "false", 1,
                    "Armor stands stop looking for entities to collide with.");
            check(tips, paper, "config/paper-world-defaults.yml", "entities.spawning.alt-item-despawn-rate.enabled", "false", "true", 1,
                    "Lets junk items like cobblestone and netherrack despawn faster (configure the list in the same section).");
            check(tips, paper, "config/paper-world-defaults.yml", "chunks.prevent-moving-into-unloaded-chunks", "false", "true", 1,
                    "Players and entities can't force-load chunks by moving into them very fast.");
            for (String type : List.of("arrow", "experience_orb", "snowball", "ender_pearl")) {
                String path = "chunks.entity-per-chunk-save-limit." + type;
                if (paper.isSet(path) && paper.getInt(path) < 0) {
                    tips.add(new Tip(1, "config/paper-world-defaults.yml", path, "-1", "16",
                            "Caps how many of these are saved per chunk, so floods don't survive restarts."));
                }
            }
        }

        YamlConfiguration spigot = yaml(new File(root, "spigot.yml"));
        if (spigot != null) {
            String base = "world-settings.default.";
            if (spigot.isSet(base + "merge-radius.item") && spigot.getDouble(base + "merge-radius.item") < 2.0) {
                tips.add(new Tip(2, "spigot.yml", base + "merge-radius.item", fmt(spigot.getDouble(base + "merge-radius.item")), "2.5",
                        "Dropped items merge from further away, so there are fewer item entities."));
            }
            check(tips, spigot, "spigot.yml", base + "nerf-spawner-mobs", "false", "true", 2,
                    "Spawner mobs get no AI. Huge for spawner farms, but they won't walk on their own (water still pushes them).");
            if (!plugin.active(Linear.VILLAGERS)) {
                check(tips, spigot, "spigot.yml", base + "entity-activation-range.tick-inactive-villagers", "true", "false", 2,
                        "Villagers far from players stop running their brain. Linear's villager optimizer does this more safely.");
            }
        }

        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (maxHeapMb < 3500) {
            tips.add(new Tip(2, "startup flags", "-Xmx", maxHeapMb + " MB", "6G or more",
                    "Little memory means frequent garbage collection pauses (lag spikes)."));
        }
        List<String> jvm = ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean aikar = jvm.stream().anyMatch(a -> a.startsWith("-XX:MaxGCPauseMillis"))
                && jvm.stream().anyMatch(a -> a.equals("-XX:+ParallelRefProcEnabled"));
        boolean zgc = jvm.stream().anyMatch(a -> a.equals("-XX:+UseZGC"));
        if (!aikar && !zgc) {
            tips.add(new Tip(2, "startup flags", "JVM flags", "default", "Aikar's flags",
                    "Tuned garbage collector settings give shorter, rarer pauses. See " + Text.verbatim("docs.papermc.io/paper/aikars-flags")));
        }
        return tips;
    }

    private static void check(List<Tip> tips, YamlConfiguration yaml, String file, String path, String bad, String good,
                              int impact, String why) {
        if (!yaml.isSet(path)) {
            return;
        }
        String current = String.valueOf(yaml.get(path));
        if (current.equalsIgnoreCase(bad)) {
            tips.add(new Tip(impact, file, path, current, good, why));
        }
    }

    private static YamlConfiguration yaml(File file) {
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : null;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private void report(CommandSender sender, List<Tip> tips) {
        if (tips.isEmpty()) {
            sender.sendMessage(plugin.message("<good>Your configuration already uses every setting Linear checks.</good>"));
            return;
        }
        tips.sort(Comparator.comparingInt(Tip::impact).reversed());
        sender.sendMessage(plugin.message("<value>" + tips.size() + "</value> <muted>suggestions, biggest impact first.</muted> "
                + "<dim>Linear never changes these files.</dim>"));
        for (Tip tip : tips) {
            String colour = tip.impact() == 3 ? "bad" : tip.impact() == 2 ? "warn" : "muted";
            String label = tip.impact() == 3 ? "high" : tip.impact() == 2 ? "medium" : "low";
            String shortName = tip.setting().substring(tip.setting().lastIndexOf('.') + 1);
            sender.sendMessage(plugin.text(" <" + colour + ">●</" + colour + "> <value>" + Text.verbatim(shortName) + "</value> <muted>"
                            + Text.verbatim(tip.current()) + "</muted> <dim>→</dim> <good>" + Text.verbatim(tip.suggested())
                            + "</good> <dim>" + Text.verbatim(tip.file()) + "</dim>")
                    .hoverEvent(HoverEvent.showText(plugin.text("<" + colour + ">" + label + " impact</" + colour + "><newline><value>"
                            + Text.verbatim(tip.setting()) + "</value><newline><muted>" + tip.why() + "</muted>"))));
            sender.sendMessage(plugin.text("   <dim>" + tip.why() + "</dim>"));
        }
    }
}
