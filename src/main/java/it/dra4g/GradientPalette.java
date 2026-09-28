package it.dra4g;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/* Immutable palette with bounded, safely published derived plans. Instances
   can be shared by threads. No input text or returned component is retained. */
public final class GradientPalette {
    private static final VarHandle PLAN_ELEMENT = MethodHandles.arrayElementVarHandle(Plan[].class);
    private final int[] colors;
    private final Style firstStyle;
    private final Style flatStyle;
    private final Plan[] plans;

    GradientPalette(final int[] input) {
        if (input == null) throw new NullPointerException("colors");
        if (input.length < 2) throw new IllegalArgumentException("A gradient needs at least two colors");
        colors = input.clone();
        int invalid = 0;
        int different = 0;
        for (final int color : colors) {
            invalid |= color;
            different |= color ^ colors[0];
        }
        if ((invalid & 0xFF00_0000) != 0)
            throw new IllegalArgumentException("RGB must be between 0x000000 and 0xFFFFFF");

        firstStyle = Style.style(TextColor.color(colors[0]));
        flatStyle = different == 0
                ? firstStyle
                : null;
        plans = different == 0
                ? null
                : new Plan[8];
    }

    boolean matches(final int first, final int last, final int[] input) {
        return input == null
                ? colors.length == 2
                && colors[0] == first
                && colors[1] == last
                : Arrays.equals(colors, input);
    }

    public Component apply(final String text) {
        if (text == null
                || text.isEmpty())
            return Component.empty();
        /* Tiny flat path: reuse the Style, not just its TextColor. Only the
           returned text component is new; there is no scan or interpolation. */
        if (flatStyle != null) return Component.text(text, flatStyle);
        return render(text);
    }

    private Component render(final String text) {
        final int length = text.length();
        final int count = text.codePointCount(0, length);
        if (count == 1) return Component.text(text, firstStyle);
        if (count > 512) return CCGradient.direct(text, colors[0], colors[colors.length - 1], colors);

        /* 8 slots * at most 512 positions = 4096 retained positions/palette.
           Cache contains ONLY styles + exclusive code-point run ends. A new
           name of the same length reuses colors, never someone else's text.
           Misses may race and recompute; a release store publishes one complete
           immutable plan. Eviction does not mutate a plan already in use. */
        final int slot = (count ^ (count >>> 3)) & 7;
        Plan plan = (Plan) PLAN_ELEMENT.getAcquire(plans, slot);
        if (plan == null
                || plan.count != count) {
            plan = plan(count);
            PLAN_ELEMENT.setRelease(plans, slot, plan);
        }
        final int runs = plan.styles.length;
        if (runs == 1) return Component.text(text, plan.styles[0]);
        final List<Component> children = new ArrayList<>(runs);
        if (count == length) {
            int start = 0;
            for (int run = 0; run < runs; run++) {
                final int end = plan.ends[run];
                children.add(Component.text(
                        CCGradient.slice(
                                text,
                                start,
                                end),
                        plan.styles[run]));
                start = end;
            }
        } else {
            int start = 0;
            int point = 0;
            for (int run = 0; run < runs; run++) {
                int end = start;
                final int endPoint = plan.ends[run];
                while (point < endPoint) {
                    end += Character.charCount(text.codePointAt(end));
                    point++;
                }
                children.add(Component.text(
                        CCGradient.slice(
                                text,
                                start,
                                end),
                        plan.styles[run]));
                start = end;
            }
        }
        return Component.empty().children(children);
    }

    private Plan plan(final int count) {
        final int[] ends = new int[count];
        final Style[] styles = new Style[count];
        final int segments = colors.length - 1;
        final double scale = (double) segments / (count - 1);
        int segment = -1;
        int red = 0;
        int green = 0;
        int blue = 0;
        int redDelta = 0;
        int greenDelta = 0;
        int blueDelta = 0;
        int previous = colors[0];
        int runs = 0;
        Style style = firstStyle;
        for (int index = 1; index < count; index++) {
            final int rgb;
            if (index == count - 1) {
                rgb = colors[segments];
            } else {
                final double position = index * scale;
                final int next = (int) position;
                if (segment != next) {
                    segment = next;
                    final int from = colors[segment];
                    final int to = colors[segment + 1];
                    red = from >>> 16;
                    green = (from >>> 8) & 255;
                    blue = from & 255;
                    redDelta = (to >>> 16) - red;
                    greenDelta = ((to >>> 8) & 255) - green;
                    blueDelta = (to & 255) - blue;
                }
                /* Preserve MiniMessage's cast-before-subtraction rounding.
                   Never accumulate fractions: long gradients would drift. */
                final float fraction = Math.clamp((float) position - segment, 0.0f, 1.0f);
                rgb = (Math.round(red
                        + fraction
                        * redDelta) << 16)
                        | (Math.round(green
                        + fraction
                        * greenDelta) << 8)
                        | Math.round(blue
                        + fraction
                        * blueDelta);
            }
            if (rgb != previous) {
                ends[runs] = index;
                styles[runs++] = style;
                style = Style.style(TextColor.color(rgb));
                previous = rgb;
            }
        }
        ends[runs] = count;
        styles[runs++] = style;
        return new Plan(count, runs == count
                ? ends
                : Arrays.copyOf(ends, runs),
                runs == count
                        ? styles
                        : Arrays.copyOf(styles, runs));
    }

    private record Plan(int count,
                        int[] ends,
                        Style[] styles) {}
}
