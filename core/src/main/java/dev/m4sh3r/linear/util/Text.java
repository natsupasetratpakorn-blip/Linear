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
 * {@code <bad>}, {@code <warn>}, {@code <link>}, {@code <violet>}, {@code <teal>} and {@code <rose>}.
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
    public static final TextColor VIOLET = TextColor.fromHexString("#c4b5fd");
    public static final TextColor TEAL = TextColor.fromHexString("#5eead4");
    public static final TextColor ROSE = TextColor.fromHexString("#fda4af");

    private static final TagResolver THEME = TagResolver.resolver(
            Placeholder.styling("accent", ACCENT),
            Placeholder.styling("value", VALUE),
            Placeholder.styling("muted", MUTED),
            Placeholder.styling("dim", DIM),
            Placeholder.styling("good", GOOD),
            Placeholder.styling("bad", BAD),
            Placeholder.styling("warn", WARN),
            Placeholder.styling("link", LINK, TextDecoration.UNDERLINED),
            Placeholder.styling("violet", VIOLET),
            Placeholder.styling("teal", TEAL),
            Placeholder.styling("rose", ROSE));

    private static final MiniMessage MINI = MiniMessage.builder()
            .tags(TagResolver.resolver(TagResolver.standard(), THEME))
            .build();

    /** a-z mapped to their small-caps forms (x has no small-caps letter and stays as it is). */
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private Text() {
    }

    /** Text between these markers is never converted to small caps (setting names, file names, URLs). */
    public static final String VERBATIM_OPEN = "<verbatim>";
    public static final String VERBATIM_CLOSE = "</verbatim>";

    public static Component parse(String miniMessage, boolean smallCaps) {
        return MINI.deserialize(convert(miniMessage, smallCaps));
    }

    /** Wraps text so it keeps the normal font. */
    public static String verbatim(String text) {
        return VERBATIM_OPEN + text + VERBATIM_CLOSE;
    }

    public static String smallCaps(String miniMessage) {
        return convert(miniMessage, true);
    }

    /**
     * Converts the visible letters of a MiniMessage string to small caps. Anything inside a
     * tag ({@code <...>}) or between verbatim markers is left alone, so colours, click
     * commands, hover text and exact names keep working. The verbatim markers are removed.
     */
    private static String convert(String miniMessage, boolean smallCaps) {
        StringBuilder out = new StringBuilder(miniMessage.length());
        boolean inTag = false;
        boolean verbatim = false;
        for (int i = 0; i < miniMessage.length(); i++) {
            if (miniMessage.startsWith(VERBATIM_OPEN, i)) {
                verbatim = true;
                i += VERBATIM_OPEN.length() - 1;
                continue;
            }
            if (miniMessage.startsWith(VERBATIM_CLOSE, i)) {
                verbatim = false;
                i += VERBATIM_CLOSE.length() - 1;
                continue;
            }
            char c = miniMessage.charAt(i);
            if (c == '\\' && i + 1 < miniMessage.length()) {
                out.append(c).append(miniMessage.charAt(++i)); // escaped character
                continue;
            }
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (smallCaps && !inTag && !verbatim) {
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
