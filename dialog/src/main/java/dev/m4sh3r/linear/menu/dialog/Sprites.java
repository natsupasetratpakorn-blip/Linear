package dev.m4sh3r.linear.menu.dialog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;

/**
 * Item icons inside text. Kept in its own class so servers with dialogs but without sprite
 * text (1.21.6 to 1.21.8) never load it.
 */
final class Sprites {

    private Sprites() {
    }

    /** The item's texture, from the "items" atlas (1.21.11+ clients) or the "blocks" atlas (1.21.9-1.21.10). */
    static Component item(String atlas, Material material) {
        return Component.object(ObjectContents.sprite(Key.key(Key.MINECRAFT_NAMESPACE, atlas),
                Key.key(Key.MINECRAFT_NAMESPACE, "item/" + material.getKey().getKey())));
    }
}
