package dev.m4sh3r.linear.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Linear's chat look: a small-caps font and one colour theme, used by every in-game message.
 *
 * <p>Messages are written in MiniMessage with semantic theme tags instead of raw colours:
 * {@code <accent>}, {@code <value>}, {@code <muted>}, {@code <dim>}, {@code <good>},
 * {@code <bad>}, {@code <warn>} and {@code <link>}.
 */
public final class Text {

    // A cool sky-to-indigo theme that stays readable on the dark chat background.
    public static final TextColor ACCENT = TextColor.fromHexString("#7dd3fc");
    public static final TextColor VALUE = TextColor.fromHexString("#e2e8f0");
    public static final TextColor MUTED = TextColor.fromHexString("#94a3b8");
    public static final TextColor DIM = TextColor.fromHexString("#475569");
    public static final TextColor GOOD = TextColor.fromHexString("#4ade80");
    public static final TextColor BAD = TextColor.fromHexString("#f87171");
    public static final TextColor WARN = TextColor.fromHexString("#fbbf24");
    public static final TextColor LINK = TextColor.fromHexString("#a5b4fc");

    private static final TagResolver THEME = TagResolver.resolver(
            Placeholder.styling("accent", ACCENT),
            Placeholder.styling("value", VALUE),
            Placeholder.styling("muted", MUTED),
            Placeholder.styling("dim", DIM),
            Placeholder.styling("good", GOOD),
            Placeholder.styling("bad", BAD),
            Placeholder.styling("warn", WARN),
            Placeholder.styling("link", LINK, TextDecoration.UNDERLINED));

    private static final MiniMessage MINI = MiniMessage.builder()
            .tags(TagResolver.resolver(TagResolver.standard(), THEME))
            .build();

    /** a-z mapped to their small-caps forms (x has no small-caps letter and stays as it is). */
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private Text() {
    }

    public static Component parse(String miniMessage, boolean smallCaps) {
        return MINI.deserialize(smallCaps ? smallCaps(miniMessage) : miniMessage);
    }

    /**
     * Converts the visible letters of a MiniMessage string to small caps. Anything inside a
     * tag ({@code <...>}) is left alone, so colours, click commands and hover text keep working.
     */
    public static String smallCaps(String miniMessage) {
        StringBuilder out = new StringBuilder(miniMessage.length());
        boolean inTag = false;
        for (int i = 0; i < miniMessage.length(); i++) {
            char c = miniMessage.charAt(i);
            if (c == '\\' && i + 1 < miniMessage.length()) {
                out.append(c).append(miniMessage.charAt(++i)); // escaped character
                continue;
            }
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                char lower = Character.toLowerCase(c);
                if (lower >= 'a' && lower <= 'z') {
                    c = SMALL_CAPS.charAt(lower - 'a');
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
