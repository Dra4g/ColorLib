package it.dra4g;

import org.bukkit.Bukkit;

/**
 * Allocation-lean, branch-tight counterpart of {@link CC}.
 *
 * <p>The public contract intentionally mirrors {@code CC}; internals use primitive
 * state, exact token probes and direct array writes to keep the hot path small.</p>
 */
public final class CCPremium {
    public static final char COLOR_CHAR = '\u00a7';
    public static final char ALT_COLOR_CHAR = '&';
    public static final int HEX_TOKEN_LENGTH = 14;

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

    private CCPremium() {}

    public static String translate(final String s) {
        if (s == null) return null;
        final int n;
        if ((n = s.length()) == 0) return "";

        final int a;
        if ((a = s.indexOf(ALT_COLOR_CHAR)) < 0) return s;

        final int mode = H;
        final int q = mode < 0
                ? n
                : n + ((n >>> 3) * 6);
        //noinspection ManualMinMaxCalculation
        final char[] o = new char[q < n ? n : q];
        final int base = o.length - n;
        s.getChars(0, n, o, base);
        if (a != 0) System.arraycopy(o, base, o, 0, a);

        int i = a;
        int r = base + a;
        int w = a;
        int rgb = mode;

        while (i < n) {
            final char x = o[r];
            if (x != ALT_COLOR_CHAR
                    || i + 1
                    >= n) {
                o[w++] = x;
                i++;
                r++;
                continue;
            }

            final char y = o[r + 1];
            if (y == '#'
                    && i + 7 < n) {
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
                        o[w++] = near(h0, h1, h2, h3, h4, h5);
                    }
                    i += 8;
                    r += 8;
                    continue;
                }
            }

            //noinspection SuspiciousNameCombination
            final char z = lo(y);
            if (code(z)) {
                o[w++] = COLOR_CHAR;
                o[w++] = z;
                i += 2;
                r += 2;
            } else {
                o[w++] = x;
                i++;
                r++;
            }
        }

        return new String(o, 0, w);
    }

    public static boolean isHexSupported() {
        final int h;
        if ((h = H) != 0) return h > 0;

        final int r;
        if ((r = probe())
                != 0)
            H = r;
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
                final int hp;
                if ((hp = owner(s, i)) >= 0) {
                    p = hp;
                    l = HEX_TOKEN_LENGTH;
                } else {
                    p = i;
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
            if ((b & (1 << x))
                    != 0) {
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
                                     final int max) {
        if (s == null
                || max
                <= 0)
            return 0;

        final int n;
        if ((n = s.length())
                <= max)
            return n;

        final int from;
        from = Math.max(0, max - 13);
        for (int i = from;
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
                && Character.isLowSurrogate(b))
            return max - 1;
        return max;
    }

    public static String truncate(final String s, final int max) {
        if (s == null) return null;
        if (s.length()
                <= max)
            return s;
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
                final char z = lo(s.charAt(k + 1));
                if ((z == 'x' && hexTail(s, k))
                        || code(z))
                    break;
            }
            k++;
        }

        if (k == n) return s;

        // The input copy is also the output buffer. Once a token is skipped,
        // retained characters are compacted in place without a second array.
        final char[] o = s.toCharArray();
        int w = k;
        i = k;

        while (i < n) {
            final char x = o[i];
            if (x == COLOR_CHAR
                    && i + 1 < n) {
                final char z = lo(o[i + 1]);
                if (z == 'x'
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

    private static int owner(final String s,
                             final int i) {
        if (hex(s, i)) return i;

        final int q;
        q = Math.max(0, i - 12);
        for (int p = q;
             p + 2 <= i;
             p += 2) {
            if (s.charAt(p) == COLOR_CHAR
                    && lo(s.charAt(p + 1)) == 'x'
                    && active(s, p)
                    && hex(s, p))
                return p;
        }
        return -1;
    }

    private static boolean active(final String s,
                                  final int i) {
        int p = i;
        while (p > 0
                && s
                .charAt(p - 1)
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
    private static char near(final char h0,
                             final char h1,
                             final char h2,
                             final char h3,
                             final char h4,
                             final char h5) {
        int v = 0;
        //noinspection ConstantValue
        v = (v << 4) | hv(h0);
        v = (v << 4) | hv(h1);
        v = (v << 4) | hv(h2);
        v = (v << 4) | hv(h3);
        v = (v << 4) | hv(h4);
        v = (v << 4) | hv(h5);

        final int rr = v >>> 16;
        final int gg = (v >>> 8) & 255;
        int bi = getBi(v, rr, gg);

        return C[bi];
    }

    private static int getBi(int v,
                             int rr,
                             int gg) {
        final int bb = v & 255;
        long best = Long.MAX_VALUE;
        int bi = 15;

        for (int i = 0;
             i < 16;
             i++) {
            final int q = R[i];
            final int cr = q >>> 16;
            final int dr = rr - cr;
            final int dg = gg - ((q >>> 8) & 255);
            final int db = bb - (q & 255);
            final int sr = rr + cr;
            final long d = (long) (1024 + sr) * dr * dr
                    + 2048L * dg * dg
                    + (long) (1534 - sr) * db * db;
            if (d < best) {
                best = d;
                bi = i;
            }
        }
        return bi;
    }

    /*
     Parse only major/minor directly from Bukkit's version string.
     A zero result is NEVER cached because the server may not exist yet.
     */
    private static int probe() {
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
                            || c
                            > '9')
                        break;
                    minor = minor * 10 + c - '0';
                    i++;
                }
            }

            return major > 1
                    || minor >= 16
                    ? 1
                    : -1;
        } catch (final Throwable _) {
            return 1;
        }
    }
}
