package dev.m4sh3r.linear.menu;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * One button of a menu page.
 *
 * @param label   themed MiniMessage text
 * @param icon    item shown next to the label (dialog sprite) or as the button item (chest)
 * @param tooltip themed MiniMessage lines shown on hover
 * @param action  what happens on click, run on the player's thread; {@code null} closes the menu
 */
public record MenuButton(String label, Material icon, List<String> tooltip, Consumer<Player> action) {
}
