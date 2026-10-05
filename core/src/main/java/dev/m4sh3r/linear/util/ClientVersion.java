package dev.m4sh3r.linear.util;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The protocol version of a player's client. Without ViaVersion every client speaks the
 * server's protocol; with ViaVersion (looked up by reflection, no dependency) older or newer
 * clients can join, and menus must not send them screens they can't draw.
 */
public final class ClientVersion {

    /** Native dialog screens. */
    public static final int DIALOGS = 771; // 1.21.6
    /** Item sprites inside text. */
    public static final int SPRITES = 773; // 1.21.9
    /** Item textures moved from the "blocks" atlas to their own "items" atlas. */
    public static final int ITEMS_ATLAS = 774; // 1.21.11

    private static volatile Method viaApi;
    private static volatile Method viaPlayerVersion;
    private static volatile boolean viaChecked;

    private ClientVersion() {
    }

    @SuppressWarnings("deprecation") // the only cross-version way to read the server protocol
    public static int server() {
        return Bukkit.getUnsafe().getProtocolVersion();
    }

    public static int of(Player player) {
        int server = server();
        if (!viaChecked) {
            lookUpVia();
        }
        if (viaApi == null) {
            return server;
        }
        try {
            Object api = viaApi.invoke(null);
            Object version = viaPlayerVersion.invoke(api, player.getUniqueId());
            int protocol = version instanceof Integer i ? i : -1;
            return protocol > 0 ? protocol : server;
        } catch (Throwable t) {
            return server;
        }
    }

    private static synchronized void lookUpVia() {
        if (viaChecked) {
            return;
        }
        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                Method getApi = via.getMethod("getAPI");
                Method playerVersion = getApi.getReturnType().getMethod("getPlayerVersion", UUID.class);
                viaPlayerVersion = playerVersion;
                viaApi = getApi;
            }
        } catch (Throwable ignored) {
            // ViaVersion missing or changed: assume every client matches the server
        }
        viaChecked = true;
    }
}
