package it.dra4g;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.ScoreComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.object.SpriteObjectContents;

import java.util.Arrays;
import java.util.List;

final class CCComponents {
    private static final char SECTION = '\u00a7';
    private static final String DIGITS = "0123456789abcdef";
    private static final int RGB_MASK = 0x00FF_FFFF;
    private static final int HAS_COLOR = 1 << 24;
    private static final int COLOR_MASK = RGB_MASK | HAS_COLOR;
    private static final int RESET = HAS_COLOR;
    private static final int DECORATION = 1 << 25;
    private static final int[] PALETTE = {
            CCPremium.BLACK, CCPremium.DARK_BLUE, CCPremium.DARK_GREEN, CCPremium.DARK_AQUA,
            CCPremium.DARK_RED, CCPremium.DARK_PURPLE, CCPremium.GOLD, CCPremium.GRAY,
            CCPremium.DARK_GRAY, CCPremium.BLUE, CCPremium.GREEN, CCPremium.AQUA,
            CCPremium.RED, CCPremium.LIGHT_PURPLE, CCPremium.YELLOW, CCPremium.WHITE
    };
    private static final TextDecoration[] DECORATIONS = {
            TextDecoration.OBFUSCATED, TextDecoration.BOLD, TextDecoration.STRIKETHROUGH,
            TextDecoration.UNDERLINED, TextDecoration.ITALIC
    };

    private CCComponents() {}

    static Component literal(final String text,
                             final int rgb) {
        if ((rgb & ~RGB_MASK) != 0) throw new IllegalArgumentException("RGB must be between 0x000000 and 0xFFFFFF");
        return Component.text(text == null
                        ? ""
                        : text,
                TextColor.color(rgb));
    }

    static Component parse(final String text) {
        if (text == null
                || text.isEmpty())
            return Component.empty();

        final int length = text.length();
        int start = 0;
        int state = 0;
        Component first = null;
        TextComponent.Builder root = null;
        for (int i = 0; i < length - 1;) {
            final char marker = text.charAt(i);
            if (marker != '&'
                    && marker != SECTION) {
                i++;
                continue;
            }
            final long token = token(text, i, marker);
            if (token < 0) {
                i++;
                continue;
            }
            if (i > start) {
                final Component part = segment(text.substring(start, i), state);
                if (first == null) {
                    first = part;
                } else {
                    if (root == null) root = Component
                            .text()
                            .append(first);
                    root.append(part);
                }
            }
            final int instruction;
            if ((instruction = (int) token) < RESET)
                state = HAS_COLOR | instruction;
            else if (instruction == RESET)
                state = 0;
            else
                state |= (instruction & 31) << 25;
            i += (int) (token >>> 32);
            start = i;
        }
        if (start < length) {
            final Component part = segment(start == 0
                    ? text
                    : text.substring(start), state);
            if (first == null) return part;
            if (root == null) root = Component
                    .text()
                    .append(first);
            root.append(part);
        }
        return root != null
                ? root.build()
                : first != null
                ? first
                : Component.empty();
    }

    private static long token(final String text,
                              final int index,
                              final char marker) {
        final char code;
        if ((code = Character
                .toLowerCase(text.charAt(index + 1)))
                == '#') {
            if (text.length() - index < 8) return -1L;
            int rgb = 0;
            for (int digit = 0; digit < 6; digit++) {
                final int value = digit(text.charAt(index + 2 + digit));
                if (value < 0) return -1L;
                rgb = (rgb << 4) | value;
            }
            return (8L << 32) | rgb;
        }
        if (code == 'x') {
            if (text.length() - index < 14) return -1L;
            int rgb = 0;
            for (int digit = 0; digit < 6; digit++) {
                final int offset = index + 2 + (digit << 1);
                if (text.charAt(offset) != marker) return -1L;
                final int value = digit(text.charAt(offset + 1));
                if (value < 0) return -1L;
                rgb = (rgb << 4) | value;
            }
            return (14L << 32) | rgb;
        }
        final int palette;
        if ((palette = digit(code)) >= 0)
            return (2L << 32) | PALETTE[palette];
        if (code >= 'k'
                && code <= 'o')
            return (2L << 32)
                    | DECORATION
                    | (1 << (code - 'k'));
        return code == 'r' ? (2L << 32) | RESET : -1L;
    }

    private static int digit(final char value) {
        final int decimal;
        if ((decimal = (value - '0'))
                >= 0
                && decimal < 10)
            return decimal;
        final int alpha = (value | 32) - 'a';
        return alpha >= 0
                && alpha < 6
                ? alpha + 10
                : -1;
    }

    private static Component segment(final String text,
                                     final int state) {
        if (state == 0) return Component.text(text);
        final int decorations;
        if ((decorations = state >>> 25) == 0) return
                Component.text(
                        text,
                        TextColor.color(state & RGB_MASK));
        final Style.Builder style = Style.style();
        if ((state & HAS_COLOR) != 0)
            style.color(TextColor.color(state & RGB_MASK));
        for (int bit = 0; bit < 5; bit++) {
            if ((decorations & (1 << bit)) != 0)
                style.decoration(DECORATIONS[bit], true);
        }
        return Component.text(text, style.build());
    }

    static String write(final Component input,
                        final boolean legacy) {
        if (input == null) return "";
        if (!legacy
                && input instanceof final TextComponent text
                && input
                .children()
                .isEmpty()) return text.content();

        final StringBuilder output = new StringBuilder();
        Component[] pending = new Component[16];
        int[] inherited = legacy
                ? new int[16]
                : null;
        pending[0] = input;
        int size = 1;
        int emitted = 0;
        while (size > 0) {
            final int top = --size;
            final Component component = pending[top];
            pending[top] = null;
            int state = 0;
            if (legacy) {
                /* Packed traversal state (30 bits, top two remain zero):
                     0..23 RGB, 24 color-present, 25..29 decorations k/l/m/n/o.
                   Black=0x01000000, no color=0. Bold=1<<(25+1)=0x04000000.
                   FALSE clears an inherited flag; NOT_SET leaves it alone. */
                final Style style = component.style();
                final int parent = inherited[top];
                final TextColor color = style.color();
                state = color == null
                        ? parent
                        : (parent & ~COLOR_MASK)
                        | HAS_COLOR
                        | color.value();
                for (int bit = 0; bit < 5; bit++) {
                    final TextDecoration.State decoration = style.decoration(DECORATIONS[bit]);
                    final int mask = 1 << (25 + bit);
                    if (decoration == TextDecoration
                            .State
                            .TRUE) state |= mask;
                    else if (decoration == TextDecoration
                            .State
                            .FALSE) state &= ~mask;
                }
            }
            final String content;
            resolveContent: {
                if (component instanceof final TextComponent text) {
                    content = text.content();
                    break resolveContent;
                }
                if (component instanceof final TranslatableComponent translated) {
                    final String fallback = translated.fallback();
                    content = fallback == null
                            ? translated.key()
                            : fallback;
                    break resolveContent;
                }
                if (component instanceof final KeybindComponent keybind) {
                    content = keybind.keybind();
                    break resolveContent;
                }
                if (component instanceof final SelectorComponent selector) {
                    content = selector.pattern();
                    break resolveContent;
                }
                if (component instanceof final ScoreComponent score) {
                    final String value = score.value();
                    content = value == null
                            ? ""
                            : value;
                    break resolveContent;
                }
                if (component instanceof final ObjectComponent object) {
                    final ObjectContents contents;
                    if ((contents = object
                            .contents()) instanceof final SpriteObjectContents sprite) {
                        content = "[" + sprite
                                .sprite()
                                .asMinimalString()
                                + (sprite
                                .atlas()
                                .equals(SpriteObjectContents
                                        .DEFAULT_ATLAS)
                                ? ""
                                : "@" + sprite
                                .atlas()
                                .asMinimalString())
                                + "]";
                        break resolveContent;
                    }
                    if (contents instanceof final PlayerHeadObjectContents head) {
                        content = "[" + (head.name() == null
                                ? "unknown player"
                                : head.name())
                                + " head]";
                        break resolveContent;
                    }
                }
                throw new UnsupportedOperationException("Cannot flatten component type: " + component.getClass().getName());
            }
            if (!content.isEmpty()) {
                if (legacy) {
                    final int nextColor = state & COLOR_MASK;
                    int activeDecorations = emitted >>> 25;
                    final int nextDecorations = state >>> 25;
                    if ((emitted & COLOR_MASK) != nextColor
                            || (activeDecorations & ~nextDecorations)
                            != 0) {
                        if (nextColor == 0) output.append(SECTION).append('r');
                        else writeColor: {
                            for (int i = 0; i < PALETTE.length; i++) {
                                if (PALETTE[i] == (nextColor & RGB_MASK)) {
                                    output.append(SECTION)
                                            .append(DIGITS.charAt(i));
                                    /* This replaces appendColor's old return:
                                       a palette match must NOT also emit RGB. */
                                    break writeColor;
                                }
                            }
                            output.append(SECTION).append('x');
                            for (int shift = 20; shift >= 0; shift -= 4) {
                                output.append(SECTION)
                                        .append(DIGITS
                                                .charAt(((nextColor & RGB_MASK)
                                                        >>> shift) & 15));
                            }
                        }
                        activeDecorations = 0;
                    }
                    final int added = nextDecorations & ~activeDecorations;
                    for (int bit = 0; bit < 5; bit++) {
                        if ((added & (1 << bit)) != 0)
                            output.append(SECTION)
                                    .append((char) ('k' + bit));
                    }
                    emitted = state;
                }
                output.append(content);
            }
            final List<Component> children = component.children();
            final int count = children.size();
            final int required;
            if ((required = Math
                    .addExact(size, count))
                    > pending.length) {
                //noinspection MathClampMigration
                final int capacity = (int) Math.min(
                        Integer.MAX_VALUE,
                        Math.max((long) required,
                                pending.length * 2L));
                pending = Arrays.copyOf(pending, capacity);
                if (legacy) inherited = Arrays.copyOf(inherited, capacity);
            }
            /* Reverse push -> left-to-right output. Each sibling gets its
               PARENT state, never the preceding sibling's resolved style.
               No recursive Java calls and no frame object per component. */
            for (int i = count - 1; i >= 0; i--) {
                pending[size] = children.get(i);
                if (legacy) inherited[size] = state;
                size++;
            }
        }
        return output.toString();
    }
}
