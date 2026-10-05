package dev.m4sh3r.linear.menu.dialog;

import dev.m4sh3r.linear.Linear;
import dev.m4sh3r.linear.menu.MenuButton;
import dev.m4sh3r.linear.menu.MenuPage;
import dev.m4sh3r.linear.menu.MenuRenderer;
import dev.m4sh3r.linear.util.ClientVersion;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Linear's menu as a native Minecraft dialog (1.21.6+): an item header, the page's lines,
 * buttons in two columns with item icons, and a Close/Back button at the bottom.
 *
 * <p>This class is compiled against a newer Paper API than the rest of Linear and is only
 * loaded on servers that have dialogs.
 */
public final class DialogMenu implements MenuRenderer {

    private static final int BUTTON_WIDTH = 150;
    private static final int EXIT_WIDTH = 200;
    private static final int TEXT_WIDTH = 310;

    /** Sprite text exists from 1.21.9; older servers with dialogs show the buttons without icons. */
    private static final boolean SPRITES = classExists("net.kyori.adventure.text.object.ObjectContents");

    /** Icons whose item texture exists in every client from 1.21.9 to 26.3 (checked against the client jars). */
    private static final Set<Material> ICONS = EnumSet.of(Material.SPYGLASS, Material.REDSTONE, Material.WRITABLE_BOOK,
            Material.COMPARATOR, Material.BOOK, Material.TOTEM_OF_UNDYING, Material.BARRIER, Material.ARROW, Material.EMERALD,
            Material.WHEAT, Material.HOPPER, Material.EGG, Material.NETHER_STAR, Material.ENDER_PEARL, Material.HOPPER_MINECART,
            Material.REPEATER, Material.PAPER);

    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private final Linear plugin;

    public DialogMenu(Linear plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean supports(Player player) {
        return ClientVersion.of(player) >= ClientVersion.DIALOGS;
    }

    @Override
    public void show(Player player, MenuPage page) {
        int protocol = ClientVersion.of(player);
        boolean icons = SPRITES && plugin.settings().menu.icons && protocol >= ClientVersion.SPRITES;
        String atlas = protocol >= ClientVersion.ITEMS_ATLAS ? "items" : "blocks";

        List<DialogBody> body = new ArrayList<>();
        if (!page.header().isEmpty()) {
            Component header = lines(page.header());
            if (page.headerItem() != null) {
                body.add(DialogBody.item(page.headerItem())
                        .description(DialogBody.plainMessage(header, 220))
                        .showDecorations(false)
                        .showTooltip(false)
                        .build());
            } else {
                body.add(DialogBody.plainMessage(header, TEXT_WIDTH));
            }
        }
        if (!page.lines().isEmpty()) {
            body.add(DialogBody.plainMessage(lines(page.lines()), TEXT_WIDTH));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (MenuButton b : page.buttons()) {
            buttons.add(button(player, b, BUTTON_WIDTH, icons, atlas));
        }
        ActionButton exit = button(player, page.exit(), EXIT_WIDTH, icons, atlas);

        DialogType type = buttons.isEmpty()
                ? DialogType.notice(exit)
                : DialogType.multiAction(buttons).columns(2).exitAction(exit).build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(plugin.text(page.title()))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(type));
        player.showDialog(dialog);
    }

    @Override
    public void close(Player player) {
        player.closeDialog();
    }

    private ActionButton button(Player viewer, MenuButton b, int width, boolean icons, String atlas) {
        Component label = plugin.text(b.label());
        if (icons && b.icon() != null && ICONS.contains(b.icon())) {
            label = Component.text().append(Sprites.item(atlas, b.icon())).append(Component.space()).append(label).build();
        }
        ActionButton.Builder builder = ActionButton.builder(label).width(width);
        if (!b.tooltip().isEmpty()) {
            builder.tooltip(lines(b.tooltip()));
        }
        if (b.action() != null) {
            builder.action(DialogAction.customClick((response, audience) -> {
                if (audience instanceof Player player && player.getUniqueId().equals(viewer.getUniqueId())) {
                    // Callbacks may arrive on a network thread; actions run on the player's own thread.
                    player.getScheduler().run(plugin, task -> {
                        if (player.hasPermission("linear.admin")) {
                            b.action().accept(player);
                        }
                    }, null);
                }
            }, CALLBACK_OPTIONS));
        }
        return builder.build();
    }

    private Component lines(List<String> lines) {
        return plugin.text(String.join("<newline>", lines));
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
