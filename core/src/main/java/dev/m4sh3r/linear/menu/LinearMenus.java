package dev.m4sh3r.linear.menu;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.advise.ConfigAdvisor;
import dev.m4sh3r.linear.ai.AiController;
import dev.m4sh3r.linear.lag.LagMachineDetector;
import dev.m4sh3r.linear.monitor.TickMonitor;
import dev.m4sh3r.linear.scan.LagScanner;
import dev.m4sh3r.linear.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** The pages of Linear's menu. Each method builds a fresh page from live data. */
public final class LinearMenus {

    private static final String TITLE = "<gradient:#38bdf8:#818cf8><bold>Linear</bold></gradient>";

    private final Linear plugin;
    private final MenuService menus;

    public LinearMenus(Linear plugin, MenuService menus) {
        this.plugin = plugin;
        this.menus = menus;
    }

    // ---- main ----

    public MenuPage main(Player viewer) {
        TickMonitor tm = plugin.tickMonitor();
        AiController ai = plugin.ai();
        LagMachineDetector lag = plugin.lagDetector();
        int flagged = lag.flags().size();

        String health = healthTag(tm.mspt());
        String status;
        if (flagged > 0) {
            status = "<bad>● " + flagged + " lag machine" + (flagged == 1 ? "" : "s") + " throttled</bad>";
        } else if (plugin.stressed()) {
            status = "<warn>● stressed, stricter limits</warn>";
        } else {
            status = "<" + health + ">● " + (health.equals("good") ? "healthy" : health.equals("warn") ? "busy" : "overloaded")
                    + "</" + health + ">";
        }
        List<String> header = List.of(
                "<value><bold>" + Text.verbatim(viewer.getName()) + "</bold></value>",
                "<muted>server</muted> <value>" + plugin.getServer().getName() + " " + plugin.getServer().getMinecraftVersion()
                        + "</value> <dim>·</dim> <muted>linear</muted> <value>v" + plugin.getPluginMeta().getVersion() + "</value>",
                "<muted>status</muted> " + status);
        List<String> lines = List.of(
                "<muted>tps</muted> <" + health + ">" + fmt(Math.min(20.0, tm.tps())) + "</" + health + "> <dim>·</dim> <muted>ms/tick</muted> <"
                        + health + ">" + fmt(tm.mspt()) + "</" + health + "> <dim>·</dim> <muted>adaptive</muted> "
                        + (plugin.stressed() ? "<warn>active</warn>" : "<value>idle</value>"),
                "<muted>brains off</muted> <value>" + ai.count(AiController.Reason.VILLAGER) + "</value> <dim>·</dim> <muted>mobs off</muted> <value>"
                        + ai.count(AiController.Reason.CROWD) + "</value> <dim>·</dim> <muted>items packed</muted> <value>"
                        + plugin.itemGuard().removedEntities() + "</value>",
                "<muted>actions stopped</muted> <value>" + lag.blockedActions() + "</value> <dim>·</dim> <muted>spawns blocked</muted> <value>"
                        + plugin.limiter().blockedSpawns() + "</value> <dim>·</dim> <muted>tracked</muted> <value>"
                        + plugin.tracker().trackedCount() + "</value>");

        List<MenuButton> buttons = List.of(
                button("<accent>Lag finder</accent>", Material.SPYGLASS, "Rank the heaviest chunks on the server.", this::openScan),
                button((flagged > 0 ? "<bad>" : "<rose>") + "Lag machines" + (flagged > 0 ? " (" + flagged + ")" : "") + (flagged > 0 ? "</bad>" : "</rose>"),
                        Material.REDSTONE, "Throttled chunks and the busiest redstone right now.", p -> menus.open(p, lagMachines())),
                button("<violet>Config advisor</violet>", Material.WRITABLE_BOOK, "Settings that cost performance. Read-only.", this::openAdvisor),
                button("<teal>Modules</teal>", Material.COMPARATOR, "Switch Linear's modules on or off.", p -> menus.open(p, modules())),
                button("<warn>Reload config</warn>", Material.BOOK, "Reload config.yml.", p -> {
                    plugin.reload();
                    p.sendMessage(plugin.message("<good>Configuration reloaded.</good>"));
                    menus.open(p, main(p));
                }),
                button("<rose>Restore AI</rose>", Material.TOTEM_OF_UNDYING, "Give every mob its AI back.", p -> menus.open(p, confirmRestore())));
        return new MenuPage(TITLE, head(viewer), header, lines, buttons, close());
    }

    // ---- modules ----

    private record ModuleInfo(String id, String name, Material icon, String about) {
    }

    private static final List<ModuleInfo> MODULES = List.of(
            new ModuleInfo(Linear.VILLAGERS, "Villagers", Material.EMERALD, "Villager brains off in trading halls and far from players."),
            new ModuleInfo(Linear.CROWD, "Crowded mobs", Material.WHEAT, "AI off for animals packed into pens."),
            new ModuleInfo(Linear.LAG, "Lag machines", Material.REDSTONE, "Detect and throttle redstone and piston lag machines."),
            new ModuleInfo(Linear.ITEMS, "Item floods", Material.HOPPER, "Pack dropped-item floods into full stacks."),
            new ModuleInfo(Linear.LIMITS, "Chunk limits", Material.EGG, "Cap breeding and spawners per chunk."),
            new ModuleInfo(Linear.ADAPTIVE, "Adaptive", Material.NETHER_STAR, "Stricter limits while the server is overloaded."));

    public MenuPage modules() {
        List<MenuButton> buttons = new ArrayList<>();
        for (ModuleInfo m : MODULES) {
            boolean on = plugin.active(m.id());
            String label = (on ? "<good>●</good> " : "<bad>○</bad> ") + "<value>" + m.name() + "</value>";
            List<String> tip = List.of("<muted>" + m.about() + "</muted>",
                    on ? "<good>On</good> <dim>· click to switch off</dim>" : "<bad>Off</bad> <dim>· click to switch on</dim>");
            buttons.add(new MenuButton(label, m.icon(), tip, p -> {
                plugin.toggle(m.id(), !plugin.active(m.id()));
                menus.open(p, modules());
            }));
        }
        return new MenuPage("<teal>Modules</teal>", null, List.of(),
                List.of("<muted>Changes last until the next reload.</muted>"), buttons, back());
    }

    // ---- lag finder ----

    private void openScan(Player player) {
        int started = plugin.scanner().scan((stats, scanned) ->
                player.getScheduler().run(plugin, task -> menus.open(player, scanResults(stats, scanned)), null));
        if (started > 0) {
            menus.open(player, notice("<accent>Lag finder</accent>", "<muted>Scanning</muted> <value>" + started + "</value> <muted>chunks...</muted>"));
        } else {
            menus.open(player, notice("<accent>Lag finder</accent>", started < 0 ? "<warn>A scan is already running.</warn>"
                    : "<muted>No loaded chunks to scan.</muted>"));
        }
    }

    public MenuPage scanResults(List<LagScanner.ChunkStats> stats, int scanned) {
        int entities = 0, items = 0, hoppers = 0;
        for (LagScanner.ChunkStats s : stats) {
            entities += s.entities();
            items += s.items();
            hoppers += s.hoppers();
        }
        List<String> lines = List.of("<muted>scanned</muted> <value>" + scanned + "</value> <muted>chunks</muted> <dim>·</dim> <value>" + entities
                + "</value> <muted>entities</muted> <dim>·</dim> <value>" + items + "</value> <muted>items</muted> <dim>·</dim> <value>"
                + hoppers + "</value> <muted>hoppers</muted>");
        List<MenuButton> buttons = new ArrayList<>();
        for (LagScanner.ChunkStats s : stats.subList(0, Math.min(8, stats.size()))) {
            String colour = s.score() >= 300 ? "bad" : s.score() >= 100 ? "warn" : "value";
            List<String> tip = new ArrayList<>();
            tip.add("<muted>entities</muted> <value>" + s.entities() + "</value> <dim>·</dim> <muted>items</muted> <value>" + s.items()
                    + "</value> <dim>·</dim> <muted>hoppers</muted> <value>" + s.hoppers() + "</value>");
            if (s.topType() != null && s.topType() != EntityType.ITEM && s.topCount() > 1) {
                tip.add("<muted>mostly " + s.topType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "</muted>");
            }
            tip.add("<dim>chunk " + s.cx() + ", " + s.cz() + " · click to teleport</dim>");
            buttons.add(new MenuButton("<" + colour + ">" + s.world() + " " + s.x() + " " + s.y() + " " + s.z() + "</" + colour + ">",
                    iconFor(s), tip, p -> teleport(p, s.world(), s.x(), s.y(), s.z())));
        }
        if (buttons.isEmpty()) {
            lines = List.of(lines.get(0), "<good>Nothing worth reporting.</good>");
        }
        buttons.add(button("<accent>Scan again</accent>", Material.SPYGLASS, "Run a fresh scan.", this::openScan));
        return new MenuPage("<accent>Lag finder</accent>", null, List.of(), lines, buttons, back());
    }

    private static Material iconFor(LagScanner.ChunkStats s) {
        if (s.items() * 2 >= s.entities() && s.items() > 0) {
            return Material.HOPPER_MINECART;
        }
        if (s.hoppers() * 3 > s.entities()) {
            return Material.HOPPER;
        }
        return Material.ENDER_PEARL;
    }

    // ---- lag machines ----

    public MenuPage lagMachines() {
        LagMachineDetector lag = plugin.lagDetector();
        List<MenuButton> buttons = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LagMachineDetector.Flag f : lag.flags()) {
            buttons.add(new MenuButton("<bad>" + f.world() + " " + f.x() + " " + f.y() + " " + f.z() + "</bad>", Material.REDSTONE,
                    List.of("<muted>" + f.reason() + "</muted>", "<warn>" + Math.max(0, (f.until() - now) / 1000) + "s left</warn>",
                            "<dim>click to teleport</dim>"),
                    p -> teleport(p, f.world(), f.x(), f.y(), f.z())));
        }
        for (LagMachineDetector.Activity a : lag.lastSecond()) {
            if (buttons.size() >= 8) {
                break;
            }
            buttons.add(new MenuButton("<value>" + a.world() + " " + a.x() + " " + a.y() + " " + a.z() + "</value>", Material.REPEATER,
                    List.of("<muted>redstone</muted> <accent>" + a.redstone() + "</accent> <muted>pistons</muted> <accent>" + a.pistons()
                            + "</accent> <muted>falling/TNT</muted> <accent>" + a.falling() + "</accent>", "<dim>click to teleport</dim>"),
                    p -> teleport(p, a.world(), a.x(), a.y(), a.z())));
        }
        List<String> lines = new ArrayList<>();
        int flagged = lag.flags().size();
        lines.add(flagged > 0 ? "<bad>" + flagged + " chunk" + (flagged == 1 ? "" : "s") + " throttled</bad> <dim>·</dim> <value>"
                + lag.blockedActions() + "</value> <muted>actions stopped</muted>" : "<good>No lag machines right now.</good>");
        lines.add(lag.lastSecond().isEmpty() ? "<muted>No redstone activity in the last second.</muted>"
                : "<muted>Busiest redstone chunks in the last second are listed too.</muted>");
        return new MenuPage("<rose>Lag machines</rose>", null, List.of(), lines, buttons, back());
    }

    // ---- advisor ----

    private void openAdvisor(Player player) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<ConfigAdvisor.Tip> tips = plugin.advisor().collect();
            player.getScheduler().run(plugin, t -> menus.open(player, advisor(tips)), null);
        });
    }

    public MenuPage advisor(List<ConfigAdvisor.Tip> tips) {
        List<String> lines = new ArrayList<>();
        List<MenuButton> buttons = new ArrayList<>();
        if (tips.isEmpty()) {
            lines.add("<good>Your configuration already uses every setting Linear checks.</good>");
        } else {
            lines.add("<value>" + tips.size() + "</value> <muted>suggestions, biggest impact first. Hover for details, click to copy them to chat.</muted>");
            lines.add("<muted>Linear never changes these files.</muted>");
            for (ConfigAdvisor.Tip tip : tips) {
                String colour = tip.impact() == 3 ? "bad" : tip.impact() == 2 ? "warn" : "muted";
                String label = tip.impact() == 3 ? "high" : tip.impact() == 2 ? "medium" : "low";
                buttons.add(new MenuButton("<" + colour + ">●</" + colour + "> <value>" + tip.title() + "</value>  <muted>"
                        + Text.verbatim(tip.current().toLowerCase(Locale.ROOT)) + "</muted> <muted>→</muted> <good>"
                        + Text.verbatim(tip.suggested().toLowerCase(Locale.ROOT)) + "</good>",
                        Material.PAPER,
                        List.of("<" + colour + ">" + label + " impact</" + colour + ">",
                                "<muted>" + tip.why() + "</muted>",
                                "<value>" + Text.verbatim(tip.setting()) + "</value>",
                                "<muted>in " + Text.verbatim(tip.file()) + "</muted>"),
                        p -> {
                            p.sendMessage(plugin.message("<value>" + Text.verbatim(tip.setting()) + "</value> <muted>in</muted> <value>"
                                    + Text.verbatim(tip.file()) + "</value><muted>:</muted> <muted>" + Text.verbatim(tip.current())
                                    + "</muted> <dim>→</dim> <good>" + Text.verbatim(tip.suggested()) + "</good>"));
                            p.sendMessage(plugin.text("   <dim>" + tip.why() + "</dim>"));
                        }));
            }
        }
        return new MenuPage("<violet>Config advisor</violet>", null, List.of(), lines, buttons, back(), 1);
    }

    // ---- restore ----

    public MenuPage confirmRestore() {
        List<MenuButton> buttons = List.of(new MenuButton("<bad>Restore AI</bad>", Material.TOTEM_OF_UNDYING,
                List.of("<muted>Every mob Linear optimized gets its AI back.</muted>"), p -> {
                    int n = plugin.restoreAi();
                    p.sendMessage(plugin.message("<good>Giving AI back to " + n + " mobs.</good> <muted>Villager and crowd optimizers are off until</muted> <accent>/linear reload</accent><muted>.</muted>"));
                    menus.open(p, main(p));
                }));
        return new MenuPage("<rose>Restore AI</rose>", null, List.of(),
                List.of("<value>Give every mob its AI back?</value>",
                        "<muted>The villager and crowd optimizers pause until the next reload.</muted>"), buttons, back("Cancel"));
    }

    // ---- helpers ----

    private MenuPage notice(String title, String line) {
        return new MenuPage(title, null, List.of(), List.of(line), List.of(), back());
    }

    private MenuButton button(String label, Material icon, String tooltip, Consumer<Player> action) {
        return new MenuButton(label, icon, List.of("<muted>" + tooltip + "</muted>"), action);
    }

    private MenuButton close() {
        return new MenuButton("<muted>Close</muted>", Material.BARRIER, List.of(), null);
    }

    private MenuButton back() {
        return back("Back");
    }

    private MenuButton back(String label) {
        return new MenuButton("<muted>" + label + "</muted>", Material.ARROW, List.of(), p -> menus.open(p, main(p)));
    }

    private void teleport(Player player, String worldName, int x, int y, int z) {
        World world = plugin.getServer().getWorld(worldName);
        menus.close(player);
        if (world != null) {
            player.teleportAsync(new Location(world, x + 0.5, y + 1, z + 0.5));
        }
    }

    private static ItemStack head(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            head.setItemMeta(meta);
        }
        return head;
    }

    private static String healthTag(double mspt) {
        if (Double.isNaN(mspt)) {
            return "value";
        }
        return mspt < 40 ? "good" : mspt < 50 ? "warn" : "bad";
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "n/a" : String.format(Locale.ROOT, "%.1f", d);
    }
}
