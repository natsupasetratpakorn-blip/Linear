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
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class LinearCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("status", "chunks", "toggle", "restore", "reload");

    private final Linear plugin;

    public LinearCommand(Linear plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "chunks" -> chunks(sender);
            case "toggle" -> toggle(sender, args);
            case "restore" -> restore(sender);
            case "reload" -> reload(sender);
            case "tp" -> teleport(sender, args);
            default -> sender.sendMessage(plugin.message("<gray>Usage: /" + label + " <white><status|chunks|toggle|restore|reload></white>"));
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
        sender.sendMessage(plugin.message("<white>v" + version + "</white> <gray>by M4sh3r on " + plugin.getServer().getMinecraftVersion()
                + (Linear.folia() ? " (Folia)" : "") + "</gray>"));
        line(sender, "Server", "<white>" + tps + "</white> TPS, <white>" + mspt + "</white> ms/tick"
                + (plugin.stressed() ? " <red>(stressed: stricter limits)</red>" : ""));
        line(sender, "Villagers", onOff(Linear.VILLAGERS) + " <white>" + ai.count(AiController.Reason.VILLAGER)
                + "</white> brains switched off");
        line(sender, "Crowded mobs", onOff(Linear.CROWD) + " <white>" + ai.count(AiController.Reason.CROWD)
                + "</white> mobs with AI switched off");
        line(sender, "Lag machines", onOff(Linear.LAG) + " <white>" + lag.flags().size() + "</white> chunks throttled, <white>"
                + lag.blockedActions() + "</white> actions stopped");
        line(sender, "Chunk limits", onOff(Linear.LIMITS) + " <white>" + plugin.limiter().blockedSpawns() + "</white> spawns prevented");
        line(sender, "Adaptive", onOff(Linear.ADAPTIVE) + (plugin.stressed() ? " <red>active</red>" : " <gray>idle</gray>"));
        line(sender, "Tracked mobs", "<white>" + plugin.tracker().trackedCount() + "</white>");
    }

    private void line(CommandSender sender, String name, String value) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(" <dark_gray>•</dark_gray> <gray>" + name + ":</gray> " + value));
    }

    private String onOff(String module) {
        return plugin.active(module) ? "<green>[on]</green>" : "<red>[off]</red>";
    }

    private void chunks(CommandSender sender) {
        LagMachineDetector lag = plugin.lagDetector();
        List<LagMachineDetector.Flag> flags = lag.flags();
        if (!flags.isEmpty()) {
            sender.sendMessage(plugin.message("<red>Throttled chunks:</red>"));
            long now = System.currentTimeMillis();
            for (LagMachineDetector.Flag f : flags) {
                sender.sendMessage(link(" <dark_gray>•</dark_gray> <white>" + f.world() + " " + f.x() + " " + f.y() + " " + f.z()
                        + "</white> <gray>" + f.reason() + ", " + Math.max(0, (f.until() - now) / 1000) + "s left</gray>",
                        f.world(), f.x(), f.y(), f.z()));
            }
        }
        List<LagMachineDetector.Activity> top = lag.lastSecond();
        if (top.isEmpty()) {
            sender.sendMessage(plugin.message("<gray>No redstone, piston or falling block activity in the last second.</gray>"));
            return;
        }
        sender.sendMessage(plugin.message("<gray>Busiest chunks (last second):</gray>"));
        for (LagMachineDetector.Activity a : top.subList(0, Math.min(8, top.size()))) {
            sender.sendMessage(link(" <dark_gray>•</dark_gray> <white>" + a.world() + " " + a.x() + " " + a.y() + " " + a.z()
                    + "</white> <gray>redstone " + a.redstone() + ", pistons " + a.pistons() + ", falling/TNT " + a.falling() + "</gray>",
                    a.world(), a.x(), a.y(), a.z()));
        }
    }

    private Component link(String text, String world, int x, int y, int z) {
        return MiniMessage.miniMessage().deserialize(text)
                .clickEvent(ClickEvent.runCommand("/linear tp " + world + " " + x + " " + y + " " + z));
    }

    private void toggle(CommandSender sender, String[] args) {
        if (args.length < 2 || !Arrays.asList(Linear.MODULES).contains(args[1].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(plugin.message("<gray>Usage: /linear toggle <white><" + String.join("|", Linear.MODULES) + "> [on|off]</white>"));
            return;
        }
        String module = args[1].toLowerCase(Locale.ROOT);
        boolean enable = args.length >= 3 ? args[2].equalsIgnoreCase("on") : !plugin.active(module);
        plugin.setOverride(module, enable);
        if (!enable) {
            if (module.equals(Linear.VILLAGERS)) {
                plugin.ai().restoreAll(AiController.Reason.VILLAGER);
            } else if (module.equals(Linear.CROWD)) {
                plugin.ai().restoreAll(AiController.Reason.CROWD);
            } else if (module.equals(Linear.LAG)) {
                plugin.lagDetector().clearFlags();
            }
        }
        sender.sendMessage(plugin.message("<white>" + module + "</white> " + (enable ? "<green>enabled</green>" : "<red>disabled</red>")
                + " <gray>until the next reload.</gray>"));
    }

    private void restore(CommandSender sender) {
        plugin.setOverride(Linear.VILLAGERS, false);
        plugin.setOverride(Linear.CROWD, false);
        int n = plugin.ai().restoreAll(null);
        sender.sendMessage(plugin.message("<green>Giving AI back to " + n + " mobs.</green> <gray>Villager and crowd optimizers are off until <white>/linear reload</white>.</gray>"));
    }

    private void reload(CommandSender sender) {
        plugin.loadSettings();
        plugin.tracker().rescan();
        sender.sendMessage(plugin.message("<green>Configuration reloaded.</green>"));
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
