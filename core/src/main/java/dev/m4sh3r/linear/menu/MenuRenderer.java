package dev.m4sh3r.linear.menu;

import org.bukkit.entity.Player;

/** Draws a {@link MenuPage} for a player: as a native dialog or as a chest inventory. */
public interface MenuRenderer {

    /** Whether this renderer can show menus to the player (server and client version). */
    boolean supports(Player player);

    /** Shows the page. Called on the player's thread. */
    void show(Player player, MenuPage page);

    /** Closes whatever this renderer has open for the player. */
    void close(Player player);
}
