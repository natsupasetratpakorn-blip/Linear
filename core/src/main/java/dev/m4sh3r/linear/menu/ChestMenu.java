package dev.m4sh3r.linear.menu;

import dev.m4sh3r.linear.Linear;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The chest version of Linear's menu, used on servers or clients without native dialogs.
 * Six rows: a dark frame, the header item on top, buttons in two columns in the empty middle and
 * Back/Close at the bottom centre.
 */
public final class ChestMenu implements MenuRenderer, Listener {

    private static final int SIZE = 54;
    private static final int HEADER_SLOT = 4;
    private static final int EXIT_SLOT = 49;
    /** Two columns like the dialog, centred vertically; a lone last button sits in the middle. */
    private static final int[] TWO_COLUMNS_6 = {20, 24, 29, 33, 38, 42};
    private static final int[] TWO_COLUMNS_8 = {11, 15, 20, 24, 29, 33, 38, 42};
    /** Lists (scan results, suggestions): the inner 7x4 area. */
    private static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private static final class Holder implements InventoryHolder {
        final Map<Integer, MenuButton> buttons = new HashMap<>();
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Linear plugin;

    public ChestMenu(Linear plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean supports(Player player) {
        return true;
    }

    @Override
    public void show(Player player, MenuPage page) {
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, SIZE, plugin.text(page.title()));
        holder.inventory = inv;

        // A dark frame around an empty middle keeps the buttons readable.
        ItemStack frame = item(Material.BLACK_STAINED_GLASS_PANE, Component.space(), List.of());
        for (int i = 0; i < SIZE; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, frame);
            }
        }

        if (page.headerItem() != null || !page.header().isEmpty()) {
            ItemStack header = page.headerItem() != null ? page.headerItem().clone() : new ItemStack(Material.COMPARATOR);
            List<String> lore = new ArrayList<>(page.header().size() > 1 ? page.header().subList(1, page.header().size()) : List.of());
            if (!page.lines().isEmpty()) {
                lore.add("");
                lore.addAll(page.lines());
            }
            String name = page.header().isEmpty() ? page.title() : page.header().get(0);
            inv.setItem(HEADER_SLOT, decorate(header, plugin.text(name), lore));
        } else if (!page.lines().isEmpty()) {
            inv.setItem(HEADER_SLOT, item(Material.PAPER, plugin.text(page.lines().get(0)),
                    page.lines().subList(1, page.lines().size())));
        }

        List<MenuButton> buttons = page.buttons();
        int[] slots = layout(buttons.size());
        for (int i = 0; i < buttons.size() && i < slots.length; i++) {
            MenuButton b = buttons.get(i);
            inv.setItem(slots[i], item(b.icon(), plugin.text(b.label()), b.tooltip()));
            holder.buttons.put(slots[i], b);
        }

        MenuButton exit = page.exit();
        Material exitIcon = exit.action() == null ? Material.BARRIER : Material.ARROW;
        inv.setItem(EXIT_SLOT, item(exitIcon, plugin.text(exit.label()), exit.tooltip()));
        holder.buttons.put(EXIT_SLOT, exit);

        player.openInventory(inv);
    }

    private static int[] layout(int count) {
        if (count > TWO_COLUMNS_8.length) {
            return GRID;
        }
        int[] base = count <= TWO_COLUMNS_6.length ? TWO_COLUMNS_6 : TWO_COLUMNS_8;
        int[] slots = java.util.Arrays.copyOf(base, count);
        if (count % 2 == 1) {
            slots[count - 1] = base[count - 1] + 2; // centre a lone last button (left column + 2 = middle)
        }
        return slots;
    }

    @Override
    public void close(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
            player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true); // nothing can be taken out of or put into the menu
        if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        MenuButton button = holder.buttons.get(event.getRawSlot());
        if (button == null) {
            return;
        }
        if (button.action() == null) {
            player.closeInventory();
            return;
        }
        // Next tick: opening another inventory from inside a click event is not safe.
        player.getScheduler().run(plugin, task -> button.action().accept(player), null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private ItemStack item(Material material, Component name, List<String> lore) {
        return decorate(new ItemStack(material), name, lore);
    }

    private ItemStack decorate(ItemStack stack, Component name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(plugin.text(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }
}
