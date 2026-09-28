package it.dra4g;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Allocation-lean, branch-tight counterpart of clòssic CC.
 *
 * <p>The public contract intentionally mirrors {@code CC}; internals use primitive
 * state, exact token probes and direct array writes to keep the hot path small.</p>
 *
 * @author @Dra4g
 */
public final class CCPremium {
    public static final char COLOR_CHAR = '\u00a7';
    public static final char ALT_COLOR_CHAR = '&';
    public static final int HEX_TOKEN_LENGTH = 14;

    public static final int BLACK = 0x000000;
    public static final int DARK_BLUE = 0x0000AA;
    public static final int DARK_GREEN = 0x00AA00;
    public static final int DARK_AQUA = 0x00AAAA;
    public static final int DARK_RED = 0xAA0000;
    public static final int DARK_PURPLE = 0xAA00AA;
    public static final int GOLD = 0xFFAA00;
    public static final int GRAY = 0xAAAAAA;
    public static final int DARK_GRAY = 0x555555;
    public static final int BLUE = 0x5555FF;
    public static final int GREEN = 0x55FF55;
    public static final int AQUA = 0x55FFFF;
    public static final int RED = 0xFF5555;
    public static final int LIGHT_PURPLE = 0xFF55FF;
    public static final int YELLOW = 0xFFFF55;
    public static final int WHITE = 0xFFFFFF;

    private static final int[] R = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final char[] C = "0123456789abcdef".toCharArray();

    /*
     0 = unresolved,
     1 = RGB,
     -1 = legacy.
     VERY IMPORTANT! We MUST keep primitive to avoid Boolean unboxing.
     */
    private static volatile int H;

    private static final Unsafe HEX_UNSAFE;
    private static final Object HEX_BASE;
    private static final long HEX_OFFSET;

    static {
        Unsafe access = null;
        Object base = null;
        long offset = 0;
        try {
            final Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            final Unsafe candidate = (Unsafe) singleton.get(null);
            final Field state = CCPremium.class.getDeclaredField("H");
            final Object candidateBase = candidate.staticFieldBase(state);
            final long candidateOffset = candidate.staticFieldOffset(state);
            /* Probe the actual operation once: --sun-misc-unsafe-memory-access=deny
               must leave this class usable. Offsets come from the VM, NEVER
               from a guessed object layout. H remains the sole state variable. */
            candidate.getIntVolatile(candidateBase, candidateOffset);
            access = candidate;
            base = candidateBase;
            offset = candidateOffset;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            /* Unsupported/restricted runtime: keep the ordinary volatile read.
               No exception handling or reflective lookup on the hot path. */
        }
        HEX_UNSAFE = access;
        HEX_BASE = base;
        HEX_OFFSET = offset;
    }

    private CCPremium() {}

    /* Direct color parsing for Paper's Component APIs. The codec is loaded
       only by these new entry points; existing String hot paths stay separate. */
    public static Component component(final String text) {
        if (text == null) return Component.empty();
        /* Keep plain text outside the full parser. indexOf can use the JVM's
           string-search intrinsics for both Latin-1 and UTF-16; no text copy.
           BOTH markers matter: section-only input still needs decoding. */
        if (text.indexOf('&') < 0
                && text.indexOf('\u00a7') < 0) return Component.text(text);
        return CCComponents.parse(text);
    }

    /* Literal text: user-supplied '&' and section signs are NOT parsed. */
    public static Component text(final String text, final int rgb) {
        return CCComponents.literal(text, rgb);
    }

    /* Literal text, equally spaced RGB stops. The two-stop overload avoids a
       varargs array; reuse an int[] palette when rendering dynamic names. */
    public static Component gradient(final String text,
                                     final int first,
                                     final int last) {
        return CCGradient.create(
                text,
                first,
                last,
                null);
    }

    public static Component gradient(final String text,
                                     final int... colors) {
        if (colors == null) throw new NullPointerException("colors");
        if (colors.length < 2) throw new IllegalArgumentException("A gradient needs at least two colors");
        return CCGradient.create(
                text,
                colors[0],
                colors[colors.length - 1],
                colors);
    }

    /* Prepare once when the palette belongs to a menu/configuration. The
       snapshot owns its colors; apply() never retains player names or output. */
    public static GradientPalette gradientPalette(final int... colors) {
        return new GradientPalette(colors);
    }

    public static String plainText(final Component component) {
        return CCComponents.write(component, false);
    }

    /* Legacy output retains RGB and decorations. Events/fonts cannot be
       represented by legacy text; see the documented conversion contract. */
    public static String serialize(final Component component) {
        return CCComponents.write(component, true);
    }

    public static String translate(final String s) {
        if (s == null) return null;
        final int n;
        if ((n = s.length()) == 0) return "";

        final int a;
        if ((a = s.indexOf(ALT_COLOR_CHAR)) < 0) return s;

        /* Fast path above is intentional: most strings have no alternate color
          code, so avoid copying them just to discover that nothing changed.
           The scratch array below is reused as input/output space for expansion.
        */
        final int mode = H;
        /* buffer sizing scribble: a hex input token '&#RRGGBB' is 8 chars;
           output is 14 chars ('§x' + 6 * '§h'), so +6 per complete token.

             input length n   n>>>3 max tokens   extra chars   q
                   7                0                0        7
                   8                1                6       14
                  16                2               12       28

           q is an upper bound, not necessarily the final output length;
           when hex is unsupported, mode<0 takes q=n. */
        final int q = mode < 0
                ? n
                : n + ((n >>> 3) * 6);
        // noinspection ManualMinMaxCalculation
        final char[] o = new char[q < n ? n : q];
        final int base = o.length - n;
        s.getChars(0, n, o, base);
        if (a != 0) System.arraycopy(o, base, o, 0, a);

        int r = base + a;
        int w = a;
        int rgb = mode;
        final int end = o.length;

        while (r < end) {
            final char x = o[r];
            if (x != ALT_COLOR_CHAR || r + 1 >= end) {
                o[w++] = x;
                r++;
                continue;
            }
            final char y = o[r + 1];
            if (y == '#'
                    && r + 7 < end) {
                /* Hex expands much more than the two-character legacy codes.
                  Keep this branch together with its bounds check; a partial
                   token at the end must fall through as ordinary text.
                 */
                final char h0 = o[r + 2];
                final char h1 = o[r + 3];
                final char h2 = o[r + 4];
                final char h3 = o[r + 5];
                final char h4 = o[r + 6];
                final char h5 = o[r + 7];
                if (hx(h0)
                        && hx(h1)
                        && hx(h2)
                        && hx(h3)
                        && hx(h4)
                        && hx(h5)) {
                    if (rgb == 0) rgb = isHexSupported() ? 1 : -1;
                    if (rgb > 0) {
                        o[w] = COLOR_CHAR;
                        o[w + 1] = 'x';
                        o[w + 2] = COLOR_CHAR;
                        o[w + 3] = (char) (h0 | 32);
                        o[w + 4] = COLOR_CHAR;
                        o[w + 5] = (char) (h1 | 32);
                        o[w + 6] = COLOR_CHAR;
                        o[w + 7] = (char) (h2 | 32);
                        o[w + 8] = COLOR_CHAR;
                        o[w + 9] = (char) (h3 | 32);
                        o[w + 10] = COLOR_CHAR;
                        o[w + 11] = (char) (h4 | 32);
                        o[w + 12] = COLOR_CHAR;
                        o[w + 13] = (char) (h5 | 32);
                        w += HEX_TOKEN_LENGTH;
                    } else {
                        o[w++] = COLOR_CHAR;
                        int v = 0;
                        /*noinspection ConstantValue*/
                        v = (v << 4) | hv(h0);
                        v = (v << 4) | hv(h1);
                        v = (v << 4) | hv(h2);
                        v = (v << 4) | hv(h3);
                        v = (v << 4) | hv(h4);
                        v = (v << 4) | hv(h5);

                        final int rr = v >>> 16;
                        final int gg = (v >>> 8) & 255;
                        int bi = 15;
                        final int bb = v & 255;
                        long best = Long.MAX_VALUE;

                        for (int palette = 0;
                             palette < 16;
                             palette++) {
                            final int paletteColor = R[palette];
                            final int cr = paletteColor >>> 16;
                            final int dr = rr - cr;
                            final int dg = gg - ((paletteColor >>> 8) & 255);
                            final int db = bb - (paletteColor & 255);
                            final int sr = rr + cr;
                            final long d = (long) (1024 + sr)
                                    * dr
                                    * dr
                                    + 2048L
                                    * dg
                                    * dg
                                    + (long) (1534 - sr)
                                    * db
                                    * db;
                            if (d < best) {
                                best = d;
                                bi = palette;
                            }
                        }
                        o[w++] = C[bi];
                    }
                    r += 8;
                    continue;
                }
            }

            /*noinspection SuspiciousNameCombination*/
            final char z = lo(y);
            if (code(z)) {
                o[w++] = COLOR_CHAR;
                o[w++] = z;
                r += 2;
            } else {
                o[w++] = x;
                r++;
            }
        }

        return new String(o, 0, w);
    }

    public static boolean isHexSupported() {
        final int h = hexMode();
        return h == 0 ? resolveHexSupport() : h > 0;
    }

    private static int hexMode() {
        /* Same volatile semantics as translate's H read. A plain Unsafe getInt
           could be hoisted out of a caller's loop; that is not a valid speedup.
           Static-final access metadata lets the JIT specialize this choice. */
        return HEX_UNSAFE == null ? H : HEX_UNSAFE.getIntVolatile(HEX_BASE, HEX_OFFSET);
    }

    private static boolean resolveHexSupport() {
        int r;
        try {
            final String v = Bukkit.getServer().getBukkitVersion();
            final int n = v.length();
            int i = 0;
            int major = 0;

            if (n == 0) throw new NumberFormatException("empty version");
            while (i < n) {
                final char c = v.charAt(i);
                if (c == '.'
                        || c == '-')
                    break;
                if (c < '0'
                        || c > '9')
                    throw new NumberFormatException(v);
                major = major * 10 + c - '0';
                i++;
            }

            int minor = 0;
            if (i < n
                    && v
                    .charAt(i++)
                    == '.') {
                if (i == n
                        || v.charAt(i) < '0'
                        || v.charAt(i) > '9')
                    throw new NumberFormatException(v);
                while (i < n) {
                    final char c = v.charAt(i);
                    if (c < '0'
                            || c > '9')
                        break;
                    minor = minor * 10 + c - '0';
                    i++;
                }
            }

            r = major > 1
                    || minor >= 16
                    ? 1
                    : -1;
        } catch (final Throwable _) {
            r = 1;
        }
        if (r != 0) H = r;
        return r >= 0;
    }

    /*
     Scan BACKWARDS and stop as soon as the effective color is known.
     VERY IMPORTANT! Decorations found after that color MUST be preserved.
     */
    public static String getLastColors(final String s) {
        if (s == null) return "";

        final int n;
        if ((n = s.length())
                < 2)
            return "";

        int p = -1;
        int l = 0;
        int b = 0;
        char lc = 0;
        int i;
        i = s.lastIndexOf(COLOR_CHAR);

        while (i >= 0) {
            if (i + 1
                    >= n) {
                i = s.lastIndexOf(COLOR_CHAR, i - 1);
                continue;
            }

            if (!active(s, i)) {
                i = s.lastIndexOf(COLOR_CHAR, i - 1);
                continue;
            }

            final char z;
            if ((z = lo(s
                    .charAt(i + 1)))
                    >= 'k'
                    && z
                    <= 'o')
                b |= 1 << (z - 'k');
            else if (z == 'r') {
                p = -1;
                l = 0;
                break;
            } else if (color(z)) {
                p = i;
                /* Backward scan meets the LAST digit of a complete RGB token
                   first. Its owner can only be i - 12:
                     header  digit 1  digit 2  ...  digit 6
                       0        2        4           12
                   No need to probe all six possible owners. Keep active():
                   getLastColors consumes even invalid section-sign pairs. */
                final int candidate = i - 12;
                if (candidate >= 0
                        && hex(s, candidate)
                        && active(s, candidate)) {
                    p = candidate;
                    l = HEX_TOKEN_LENGTH;
                } else {
                    l = 2;
                    lc = z;
                }
                break;
            } else if (z == 'x'
                    && hex(s, i)) {
                p = i;
                l = HEX_TOKEN_LENGTH;
                break;
            }
            i = s.lastIndexOf(COLOR_CHAR, i - 1);
        }

        final int d;
        d = bitCount(b);
        if (p < 0
                && d
                == 0)
            return "";
        if (d == 0)
            return l == HEX_TOKEN_LENGTH
                    ? s.substring(p, p + HEX_TOKEN_LENGTH)
                    : new String(new char[]{COLOR_CHAR, lc});

        final char[] o = new char[l + (d << 1)];
        int w = 0;
        if (p >= 0) {
            if (l == HEX_TOKEN_LENGTH) {
                s.getChars(p, p + HEX_TOKEN_LENGTH, o, 0);
                w = HEX_TOKEN_LENGTH;
            } else {
                o[0] = COLOR_CHAR;
                o[1] = lc;
                w = 2;
            }
        }
        for (int x = 0;
             x < 5;
             x++) {
            if ((b & (1 << x)) != 0) {
                o[w++] = COLOR_CHAR;
                o[w++] = (char) ('k' + x);
            }
        }
        return new String(o);
    }

    public static int bitCount(int i) {
        return Integer.bitCount(i);
    }

    /*
     This is intentionally a two-character fast path.
     NEVER deserialize or allocate just to inspect the first color.
     */
    public static boolean startsWithColor(final String s) {
        if (s == null
                || s.length() < 2
                || s.charAt(0) != COLOR_CHAR)
            return false;

        final char x;
        x = s.charAt(1);
        final char z;
        z = (char) (x | 32);
        return (x >= '0'
                && x <= '9')
                || (z >= 'a'
                && z <= 'f')
                || z == 'r'
                || (z == 'x'
                && hex(s, 0));
    }

    /*
     Only the 13 characters before the boundary can belong to a RGB token.
     DO NOT turn this constant-time boundary check back into a full scan.
     */
    public static int safeSplitIndex(final String s,
                                     /* callers use this at a visible-text boundary;
                                       splitting inside a color token corrupts it.
                                     */
                                     final int max) {
        if (s == null
                || max <= 0)
            return 0;

        final int n;
        if ((n = s.length())
                <= max)
            return n;

        for (int i = Math.max(0, max - 13);
             i < max;
             i++) {
            if (s.charAt(i) == COLOR_CHAR
                    && i + HEX_TOKEN_LENGTH > max
                    && hex(s, i))
                return i;
        }

        final char a = s.charAt(max - 1);
        final char b = s.charAt(max);
        if (a == COLOR_CHAR
                && code(lo(b)))
            return max - 1;
        if (Character.isHighSurrogate(a)
                && Character
                .isLowSurrogate(b))
            return max - 1;
        return max;
    }

    public static String truncate(final String s,
                                  final int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, safeSplitIndex(s, max));
    }

    /*
     Copy directly into one primitive array and skip recognized tokens in place.
     VERY IMPORTANT! Invalid section signs MUST survive unchanged.
     */
    public static String stripColor(final String s) {
        if (s == null) return null;

        final int n;
        n = s.length();
        int i;
        if ((i = s.indexOf(COLOR_CHAR)) < 0) return s;

        int k = i;
        while (k < n) {
            if (s.charAt(k) == COLOR_CHAR
                    && k + 1 < n) {
                final char z;
                if (((z = lo(s
                        .charAt(k + 1))) == 'x'
                        && hexTail(s, k))
                        || code(z))
                    break;
            }
            k++;
        }

        if (k == n) return s;

        /* The input copy is also the output buffer. Once a token is skipped,
           retained characters are compacted in place without a second array. */
        final char[] o = s.toCharArray();
        int w = k;
        i = k;

        while (i < n) {
            final char x = o[i];
            if (x == COLOR_CHAR
                    && i + 1 < n) {
                final char z;
                if ((z = lo(o[i + 1]))
                        == 'x'
                        && hexTail(o, i, n)) {
                    i += HEX_TOKEN_LENGTH;
                    continue;
                }
                if (code(z)) {
                    i += 2;
                    continue;
                }
            }
            o[w++] = x;
            i++;
        }
        return new String(o, 0, w);
    }

    private static boolean hex(final String s,
                               final int i) {
        if (i + HEX_TOKEN_LENGTH
                > s.length()
                || s.charAt(i)
                != COLOR_CHAR
                || lo(s.charAt(i + 1))
                != 'x')
            return false;

        return filter(s, i);
    }

    private static boolean filter(final String s,
                                  final int i) {
        return ((s.charAt(i + 2) ^ COLOR_CHAR)
                | (s.charAt(i + 4) ^ COLOR_CHAR)
                | (s.charAt(i + 6) ^ COLOR_CHAR)
                | (s.charAt(i + 8) ^ COLOR_CHAR)
                | (s.charAt(i + 10) ^ COLOR_CHAR)
                | (s.charAt(i + 12) ^ COLOR_CHAR))
                == 0
                && hx(s.charAt(i + 3))
                && hx(s.charAt(i + 5))
                && hx(s.charAt(i + 7))
                && hx(s.charAt(i + 9))
                && hx(s.charAt(i + 11))
                && hx(s.charAt(i + 13));
    }

    private static boolean hexTail(final String s,
                                   final int i) {
        if (i + HEX_TOKEN_LENGTH > s.length()) return false;

        return filter(s, i);
    }

    private static boolean hexTail(final char[] s,
                                   final int i,
                                   final int n) {
        if (i + HEX_TOKEN_LENGTH > n) return false;

        return ((s[i + 2] ^ COLOR_CHAR)
                | (s[i + 4] ^ COLOR_CHAR)
                | (s[i + 6] ^ COLOR_CHAR)
                | (s[i + 8] ^ COLOR_CHAR)
                | (s[i + 10] ^ COLOR_CHAR)
                | (s[i + 12] ^ COLOR_CHAR))
                == 0
                && hx(s[i + 3])
                && hx(s[i + 5])
                && hx(s[i + 7])
                && hx(s[i + 9])
                && hx(s[i + 11])
                && hx(s[i + 13]);
    }

    private static boolean active(final String s,
                                  final int i) {
        int p = i;
        while (p > 0
                && s.charAt(p - 1)
                == COLOR_CHAR)
            p--;
        return ((i - p) & 1) == 0;
    }

    private static boolean hx(final char x) {
        final int d;
        if ((d = x - '0') >= 0
                && d < 10)
            return true;

        final int a;
        return (a = (x
                | 32) - 'a')
                >= 0
                &&
                a < 6;
    }

    private static int hv(final char x) {
        final int d;
        d = x - '0';
        return d >= 0
                && d < 10
                ? d
                : (x | 32)
                - 'a'
                + 10;
    }

    private static char lo(final char x) {
        return x >= 'A'
                && x <= 'Z'
                ? (char) (x | 32)
                : x;
    }

    private static boolean color(final char x) {
        return (x >= '0'
                && x
                <= '9')
                || (x
                >= 'a'
                && x
                <= 'f');
    }

    private static boolean code(final char x) {
        return color(x)
                || (x >= 'k'
                && x <= 'o')
                || x == 'r';
    }

}
