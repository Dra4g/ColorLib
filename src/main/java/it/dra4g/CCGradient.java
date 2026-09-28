package it.dra4g;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class CCGradient {
    private static final GradientPalette[] PALETTES = new GradientPalette[8];
    private static final VarHandle PALETTE_ELEMENT = MethodHandles.arrayElementVarHandle(GradientPalette[].class);
    private static final String[] LATIN = new String[256];
    private static volatile GradientPalette recent;

    static {
        for (int i = 0; i < LATIN.length; i++) LATIN[i] = String.valueOf((char) i);
    }

    private CCGradient() {}

    static Component create(final String text,
                            final int first,
                            final int last,
                            final int[] colors) {
        final GradientPalette cached = recent;
        if (cached != null
                && cached.matches(first, last, colors)) return cached.apply(text);
        return resolve(first, last, colors).apply(text);
    }

    /* Keep lookup/replacement outside create's small repeated-palette path. */
    private static GradientPalette resolve(final int first,
                                           final int last,
                                           final int[] colors) {
        /* Eight slots plus the recent owner, keyed by VALUE, never identity.
           Interior stops are checked by Arrays.equals even on a hash hit.
           Limit retained palettes to 32 stops; collisions only evict work.
           Release/acquire publishes complete immutable snapshots and plans. */
        final int count = colors == null
                ? 2
                : colors.length;
        final int hash = (first * 31 + last) * 31 + count;
        final int slot = (hash ^ (hash >>> 16)) & 7;
        GradientPalette palette = (GradientPalette) PALETTE_ELEMENT.getAcquire(PALETTES, slot);
        if (palette == null
                || !palette.matches(first, last, colors)) {
            palette = new GradientPalette(colors == null
                    ? new int[]{first, last}
                    : colors);
            if (count <= 32) PALETTE_ELEMENT.setRelease(PALETTES, slot, palette);
        }
        if (count <= 32) recent = palette;
        return palette;
    }

    static String slice(final String text,
                        final int start,
                        final int end) {
        final char value;
        if (end - start == 1
                && (value = text
                .charAt(start))
                < 256)
            return LATIN[value];
        return text.substring(start, end);
    }

    /* Large inputs bypass plan caching and keep the streaming renderer, so a
       one-off book cannot retain arrays/styles proportional to its length. */
    static Component direct(final String text,
                            final int first,
                            final int last,
                            final int[] colors) {
        final int length = text.length();
        final int count = text.codePointCount(0, length);
        if (count == 1) return Component.text(text, TextColor.color(first));
        /* Stops sit at 0, 1, ... segments; characters sample that interval.
           5 characters + 3 stops => positions 0, 0.5, 1, 1.5, 2.
           One division per call, no accumulated step error. Extract channel
           bases/deltas only when crossing a stop. Math.round keeps the same
           channel rounding as Adventure's direct TextColor.lerp operation. */
        final int segments = colors == null
                ? 1
                : colors.length - 1;
        final double scale = (double) segments / (count - 1);
        final boolean bmp = count == length;
        int segment = -1;
        int red = 0;
        int green = 0;
        int blue = 0;
        int redDelta = 0;
        int greenDelta = 0;
        int blueDelta = 0;
        int runColor = first;
        int runStart = 0;
        int offset = bmp
                ? 1
                : Character.charCount(text.codePointAt(0));
        TextComponent.Builder output = null;
        for (int index = 1; index < count; index++) {
            final int rgb;
            if (index == count - 1)
                rgb = last;
            else {
                final double position = index * scale;
                final int nextSegment;
                if ((nextSegment = (int) position) != segment) {
                    segment = nextSegment;
                    final int from = colors == null
                            ? first
                            : colors[segment];
                    final int to = colors == null
                            ? last
                            : colors[segment + 1];
                    red = from >>> 16;
                    green = (from >>> 8) & 255;
                    blue = from & 255;
                    redDelta = (to >>> 16) - red;
                    greenDelta = ((to >>> 8) & 255) - green;
                    blueDelta = (to & 255) - blue;
                }
                /* Cast BEFORE subtracting to match MiniMessage's rounding.
                   Clamp also covers float precision with very large palettes. */
                final float fraction = Math.clamp((float) position - segment, 0.0f, 1.0f);
                rgb = (Math.round(red + fraction * redDelta) << 16)
                        | (Math.round(green + fraction * greenDelta) << 8)
                        | Math.round(blue + fraction * blueDelta);
            }
            if (rgb != runColor) {
                /* Emit a whole equal-color run. Flat palettes never allocate a
                   builder; tiny channel ranges do not need a leaf per letter. */
                if (output == null) output = Component.text();
                output.append(Component.text(
                        slice(text, runStart, offset),
                        TextColor.color(runColor)));
                runStart = offset;
                runColor = rgb;
            }
            offset += bmp
                    ? 1
                    : Character.charCount(text.codePointAt(offset));
        }
        final Component tail = Component
                .text(runStart == 0
                                ? text
                                : slice(text, runStart, length),
                        TextColor.color(runColor));
        return output == null
                ? tail
                : output.append(tail).build();
    }
}
