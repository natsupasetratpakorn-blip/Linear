package dev.m4sh3r.linear.menu;

import dev.m4sh3r.linear.Linear;
import java.util.logging.Level;
import org.bukkit.entity.Player;

/**
 * Opens Linear's menu as a native dialog where the server and the player's client support
 * it (1.21.6+), and as a chest menu everywhere else.
 */
public final class MenuService {

    private static final String DIALOG_RENDERER = "dev.m4sh3r.linear.menu.dialog.DialogMenu";

    private final Linear plugin;
    private final ChestMenu chest;
    private final LinearMenus pages;
    private volatile MenuRenderer dialog;

    public MenuService(Linear plugin) {
        this.plugin = plugin;
        this.chest = new ChestMenu(plugin);
        this.pages = new LinearMenus(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(chest, plugin);
        this.dialog = loadDialogRenderer();
    }

    /** The dialog renderer is compiled against a newer API and only loaded when the server has dialogs. */
    private MenuRenderer loadDialogRenderer() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
        } catch (ClassNotFoundException e) {
            return null; // server older than 1.21.6
        }
        try {
            return (MenuRenderer) Class.forName(DIALOG_RENDERER).getConstructor(Linear.class).newInstance(plugin);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Native dialog menus are unavailable, using chest menus", t);
            return null;
        }
    }

    public boolean dialogsAvailable() {
        return dialog != null;
    }

    public void openMain(Player player) {
        open(player, pages.main(player));
    }

    /** Shows a page with the best renderer for the player. Call on the player's thread. */
    public void open(Player player, MenuPage page) {
        if (!player.isOnline() || !player.hasPermission("linear.admin")) {
            return;
        }
        MenuRenderer d = dialog;
        boolean useDialog = d != null && !plugin.settings().menu.forceChest && d.supports(player);
        if (useDialog) {
            try {
                chest.close(player);
                d.show(player, page);
                return;
            } catch (LinkageError | RuntimeException e) {
                // A server build whose dialog API differs from the one Linear was built against.
                dialog = null;
                plugin.getLogger().log(Level.WARNING, "Native dialog menus failed, switching to chest menus", e);
            }
        }
        chest.show(player, page);
    }

    public void close(Player player) {
        chest.close(player);
        MenuRenderer d = dialog;
        if (d != null) {
            d.close(player);
        }
    }
}
