package dev.m4sh3r.linear.menu;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * Everything one menu screen shows. The dialog and the chest renderer draw the same page, so
 * both menus always offer the same information and actions.
 *
 * @param title       themed MiniMessage title
 * @param headerItem  item shown next to the header lines, or {@code null}
 * @param header      themed lines next to the header item (the first line is the headline)
 * @param lines       themed body lines below the header
 * @param buttons     the page's buttons, laid out in two columns
 * @param exit        the bottom button (Close or Back)
 */
public record MenuPage(String title, ItemStack headerItem, List<String> header, List<String> lines,
                       List<MenuButton> buttons, MenuButton exit) {
}
