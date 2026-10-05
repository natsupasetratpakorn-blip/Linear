package dev.m4sh3r.linear.command;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.ai.AiController;
import dev.m4sh3r.linear.lag.LagMachineDetector;
import dev.m4sh3r.linear.monitor.TickMonitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class LinearCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("menu", "status", "scan", "chunks", "advise", "toggle", "restore", "reload");

    private final Linear plugin;

    public LinearCommand(Linear plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? (sender instanceof Player ? "menu" : "status") : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "gui" -> {
                if (sender instanceof Player player) {
                    plugin.menus().openMain(player);
                } else {
                    status(sender);
                }
            }
            case "status" -> status(sender);
            case "chunks" -> chunks(sender);
            case "scan" -> plugin.scanner().scan(sender);
            case "advise" -> plugin.advisor().advise(sender);
            case "toggle" -> toggle(sender, args);
            case "restore" -> restore(sender);
            case "reload" -> reload(sender);
            case "tp" -> teleport(sender, args);
            default -> usage(sender, label);
        }
        return true;
    }

    private void status(CommandSender sender) {
        TickMonitor tm = plugin.tickMonitor();
        AiController ai = plugin.ai();
        LagMachineDetector lag = plugin.lagDetector();
        String mspt = Double.isNaN(tm.mspt()) ? "n/a" : String.format(Locale.ROOT, "%.2f", tm.mspt());
        String tps = Double.isNaN(tm.tps()) ? "n/a" : String.format(Locale.ROOT, "%.2f", Math.min(20.0, tm.tps()));
        String version = plugin.getPluginMeta().getVersion();
        sender.sendMessage(plugin.message("<value>v" + version + "</value> <muted>by</muted> <accent>M4sh3r</accent> <dim>·</dim> <muted>"
                + plugin.getServer().getMinecraftVersion() + (Linear.folia() ? " Folia" : "") + "</muted>"));
        line(sender, "Server", "<" + healthColor(tm) + ">" + tps + "</" + healthColor(tm) + "> <muted>TPS</muted> <dim>·</dim> <"
                + healthColor(tm) + ">" + mspt + "</" + healthColor(tm) + "> <muted>ms/tick</muted>"
                + (plugin.stressed() ? " <warn>(stressed, stricter limits)</warn>" : ""));
        line(sender, "Villagers", onOff(Linear.VILLAGERS) + " <value>" + ai.count(AiController.Reason.VILLAGER)
                + "</value> <muted>brains switched off</muted>");
        line(sender, "Crowded mobs", onOff(Linear.CROWD) + " <value>" + ai.count(AiController.Reason.CROWD)
                + "</value> <muted>mobs with AI switched off</muted>");
        line(sender, "Lag machines", onOff(Linear.LAG) + " <value>" + lag.flags().size() + "</value> <muted>chunks throttled</muted> <dim>·</dim> <value>"
                + lag.blockedActions() + "</value> <muted>actions stopped</muted>");
        line(sender, "Item floods", onOff(Linear.ITEMS) + " <value>" + plugin.itemGuard().removedEntities()
                + "</value> <muted>item entities packed into stacks</muted>");
        line(sender, "Chunk limits", onOff(Linear.LIMITS) + " <value>" + plugin.limiter().blockedSpawns() + "</value> <muted>spawns prevented</muted>");
        line(sender, "Adaptive", onOff(Linear.ADAPTIVE) + (plugin.stressed() ? " <warn>active</warn>" : " <muted>idle</muted>"));
        line(sender, "Tracked mobs", "<value>" + plugin.tracker().trackedCount() + "</value>");
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(plugin.message("<muted>Commands:</muted>"));
        for (String sub : SUBCOMMANDS) {
            sender.sendMessage(plugin.text(" <dim>›</dim> <accent>/" + label + " " + sub + "</accent>")
                    .clickEvent(ClickEvent.suggestCommand("/" + label + " " + sub)));
        }
    }

    private void line(CommandSender sender, String name, String value) {
        sender.sendMessage(plugin.text(" <dim>›</dim> <muted>" + name + "</muted> " + value));
    }

    private String onOff(String module) {
        return plugin.active(module) ? "<good>●</good>" : "<bad>○</bad>";
    }

    /** Green below 40 ms/tick, amber up to 50, red once the server can no longer keep 20 TPS. */
    private static String healthColor(TickMonitor tm) {
        double mspt = tm.mspt();
        if (Double.isNaN(mspt)) {
            return "value";
        }
        return mspt < 40 ? "good" : mspt < 50 ? "warn" : "bad";
    }

    private void chunks(CommandSender sender) {
        LagMachineDetector lag = plugin.lagDetector();
        List<LagMachineDetector.Flag> flags = lag.flags();
        if (!flags.isEmpty()) {
            sender.sendMessage(plugin.message("<bad>Throttled chunks</bad>"));
            long now = System.currentTimeMillis();
            for (LagMachineDetector.Flag f : flags) {
                sender.sendMessage(link(" <dim>›</dim> <value>" + f.world() + " " + f.x() + " " + f.y() + " " + f.z()
                        + "</value> <muted>" + f.reason() + "</muted> <dim>·</dim> <warn>" + Math.max(0, (f.until() - now) / 1000) + "s left</warn>",
                        f.world(), f.x(), f.y(), f.z()));
            }
        }
        List<LagMachineDetector.Activity> top = lag.lastSecond();
        if (top.isEmpty()) {
            sender.sendMessage(plugin.message("<good>No redstone, piston or falling block activity in the last second.</good>"));
            return;
        }
        sender.sendMessage(plugin.message("<muted>Busiest chunks in the last second</muted>"));
        for (LagMachineDetector.Activity a : top.subList(0, Math.min(8, top.size()))) {
            sender.sendMessage(link(" <dim>›</dim> <value>" + a.world() + " " + a.x() + " " + a.y() + " " + a.z()
                    + "</value> <muted>redstone</muted> <accent>" + a.redstone() + "</accent> <muted>pistons</muted> <accent>" + a.pistons()
                    + "</accent> <muted>falling/TNT</muted> <accent>" + a.falling() + "</accent>",
                    a.world(), a.x(), a.y(), a.z()));
        }
    }

    private Component link(String text, String world, int x, int y, int z) {
        return plugin.text(text)
                .hoverEvent(HoverEvent.showText(plugin.text("<accent>Click to teleport</accent>")))
                .clickEvent(ClickEvent.runCommand("/linear tp " + world + " " + x + " " + y + " " + z));
    }

    private void toggle(CommandSender sender, String[] args) {
        if (args.length < 2 || !Arrays.asList(Linear.MODULES).contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(plugin.message("<muted>Usage:</muted> <accent>/linear toggle " + String.join("|", Linear.MODULES) + " [on|off]</accent>"));
            return;
        }
        String module = args[1].toLowerCase(Locale.ROOT);
        boolean enable = args.length >= 3 ? args[2].equalsIgnoreCase("on") : !plugin.active(module);
        plugin.toggle(module, enable);
        sender.sendMessage(plugin.message("<value>" + module + "</value> " + (enable ? "<good>enabled</good>" : "<bad>disabled</bad>")
                + " <muted>until the next reload.</muted>"));
    }

    private void restore(CommandSender sender) {
        int n = plugin.restoreAi();
        sender.sendMessage(plugin.message("<good>Giving AI back to " + n + " mobs.</good> <muted>Villager and crowd optimizers are off until</muted> <accent>/linear reload</accent><muted>.</muted>"));
    }

    private void reload(CommandSender sender) {
        plugin.reload();
        sender.sendMessage(plugin.message("<good>Configuration reloaded.</good>"));
    }

    private void teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 5) {
            return;
        }
        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            return;
        }
        try {
            Location target = new Location(world, Integer.parseInt(args[2]) + 0.5, Integer.parseInt(args[3]) + 1, Integer.parseInt(args[4]) + 0.5);
            player.teleportAsync(target);
        } catch (NumberFormatException ignored) {
            // malformed click command
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            options.addAll(Arrays.asList(Linear.MODULES));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("toggle")) {
            options.addAll(List.of("on", "off"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.startsWith(prefix));
        return options;
    }
}
