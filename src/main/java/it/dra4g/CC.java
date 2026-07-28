package it.dra4g;

import org.bukkit.Bukkit;

/**
 * Legacy color utilities with RGB (hex) support.
 * <p>
 * Besides the classic {@code &<code>} codes, {@link #translate(String)} understands
 * {@code &#RRGGBB} and converts it to the legacy hex format {@code §x§R§R§G§G§B§B}
 * understood by 1.16+ clients. Gradients are simply a sequence of hex codes, one per
 * character, e.g.:
 * <pre>&amp;#00E0FF&amp;lP&amp;#33B3E9&amp;lR&amp;#6686D3&amp;lI&amp;#995ABD&amp;lS&amp;#CC2DA7&amp;lO&amp;#FF0091&amp;lN</pre>
 * On servers older than 1.16 every hex color is downsampled to the closest of the 16
 * legacy colors, so the same string keeps working everywhere.
 */
public final class CC {

    /**
     * Character used by the client to introduce a formatting code.
     */
    public static final char COLOR_CHAR = '§';

    /**
     * Character used inside configurations/messages to introduce a formatting code.
     */
    public static final char ALT_COLOR_CHAR = '&';

    /**
     * Length of a legacy hex token: {@code §x§R§R§G§G§B§B}.
     */
    public static final int HEX_TOKEN_LENGTH = 14;

    private static final String COLOR_CODES = "0123456789abcdef";
    private static final String DECORATION_CODES = "klmno";

    private static final char[] LEGACY_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private static volatile Boolean hexSupported;

    private CC() {
    }

    /**
     * Translate {@code &} color codes, including {@code &#RRGGBB}, to their client format.
     *
     * @param message raw message, may be null.
     * @return the translated message.
     */
    public static String translate(final String message) {
        if (message == null) return null;
        if (message.isEmpty()) return "";
        if (message.indexOf(ALT_COLOR_CHAR) < 0) return message;

        final int length = message.length();
        final StringBuilder builder = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            final char current = message.charAt(i);

            if (current != ALT_COLOR_CHAR || i + 1 >= length) {
                builder.append(current);
                continue;
            }

            final char next = message.charAt(i + 1);

            if (next == '#' && i + 7 < length && isHexSequence(message, i + 2)) {
                appendHex(builder, message, i + 2);
                i += 7;
                continue;
            }

            final char code = Character.toLowerCase(next);

            if (code == 'r' || COLOR_CODES.indexOf(code) > -1 || DECORATION_CODES.indexOf(code) > -1) {
                builder.append(COLOR_CHAR).append(code);
                i++;
                continue;
            }

            builder.append(current);
        }

        return builder.toString();
    }

    /**
     * @return true if the running server understands the {@code §x} hex format (1.16+).
     */
    public static boolean isHexSupported() {
        final Boolean cached = hexSupported;

        if (cached != null) return cached;

        final Boolean resolved = resolveHexSupport();

        // The server may not be up yet: in that case keep retrying instead of
        // caching a guess for the whole runtime.
        if (resolved != null) {
            hexSupported = resolved;
            return resolved;
        }

        return true;
    }

    /**
     * Get the formatting still active at the end of the given translated text.
     * Unlike {@code ChatColor#getLastColors(String)} this understands hex colors.
     *
     * @param input translated text (using {@link #COLOR_CHAR}).
     * @return the color followed by the decorations applied after it.
     */
    public static String getLastColors(final String input) {
        if (input == null || input.length() < 2) return "";

        String color = "";
        int decorations = 0;
        int i = 0;

        while (i < input.length() - 1) {
            if (input.charAt(i) != COLOR_CHAR) {
                i++;
                continue;
            }

            if (isHexToken(input, i)) {
                color = input.substring(i, i + HEX_TOKEN_LENGTH);
                decorations = 0;
                i += HEX_TOKEN_LENGTH;
                continue;
            }

            final char code = Character.toLowerCase(input.charAt(i + 1));
            final int decoration = DECORATION_CODES.indexOf(code);

            if (decoration > -1) {
                decorations |= 1 << decoration;
            } else if (code == 'r') {
                color = "";
                decorations = 0;
            } else if (COLOR_CODES.indexOf(code) > -1) {
                color = String.valueOf(new char[]{COLOR_CHAR, code});
                decorations = 0;
            }

            i += 2;
        }

        if (decorations == 0) return color;

        final StringBuilder builder = new StringBuilder(color);

        for (int decoration = 0; decoration < DECORATION_CODES.length(); decoration++) {
            if ((decorations & (1 << decoration)) != 0) {
                builder.append(COLOR_CHAR).append(DECORATION_CODES.charAt(decoration));
            }
        }

        return builder.toString();
    }

    /**
     * @return true if the translated text opens with a color (hex, legacy or reset),
     * meaning any formatting inherited from a previous chunk would be discarded anyway.
     */
    public static boolean startsWithColor(final String input) {
        if (input == null || input.length() < 2 || input.charAt(0) != COLOR_CHAR) return false;
        if (isHexToken(input, 0)) return true;

        final char code = Character.toLowerCase(input.charAt(1));
        return code == 'r' || COLOR_CODES.indexOf(code) > -1;
    }

    /**
     * Find the highest index not greater than {@code max} where the text can be cut
     * without breaking a color code (hex included) or a surrogate pair.
     *
     * @param input translated text.
     * @param max   maximum amount of characters.
     * @return a safe cut index.
     */
    public static int safeSplitIndex(final String input, final int max) {
        if (input == null) return 0;
        if (input.length() <= max) return input.length();

        int index = 0;

        while (index < input.length()) {
            final int tokenLength = tokenLength(input, index);

            if (index + tokenLength > max) break;

            index += tokenLength;
        }

        return index;
    }

    /**
     * Cut the text to {@code max} characters without breaking a color code.
     *
     * @param input translated text, may be null.
     * @param max   maximum amount of characters.
     * @return the truncated text.
     */
    public static String truncate(final String input, final int max) {
        if (input == null) return null;
        if (input.length() <= max) return input;

        return input.substring(0, safeSplitIndex(input, max));
    }

    /**
     * Strip every color code, hex included.
     *
     * @param input translated text, may be null.
     * @return the text without formatting.
     */
    public static String stripColor(final String input) {
        if (input == null) return null;
        if (input.indexOf(COLOR_CHAR) < 0) return input;

        final StringBuilder builder = new StringBuilder(input.length());
        int index = 0;

        while (index < input.length()) {
            final int tokenLength = tokenLength(input, index);

            if (input.charAt(index) != COLOR_CHAR || tokenLength == 1) {
                builder.append(input, index, index + tokenLength);
            }

            index += tokenLength;
        }

        return builder.toString();
    }

    private static int tokenLength(final String input, final int index) {
        if (input.charAt(index) == COLOR_CHAR) {
            if (isHexToken(input, index)) return HEX_TOKEN_LENGTH;

            if (index + 1 < input.length()) {
                final char code = Character.toLowerCase(input.charAt(index + 1));

                if (code == 'r' || COLOR_CODES.indexOf(code) > -1 || DECORATION_CODES.indexOf(code) > -1) {
                    return 2;
                }
            }

            return 1;
        }

        if (Character.isHighSurrogate(input.charAt(index)) && index + 1 < input.length()
                && Character.isLowSurrogate(input.charAt(index + 1))) {
            return 2;
        }

        return 1;
    }

    private static boolean isHexToken(final String input, final int index) {
        if (index + HEX_TOKEN_LENGTH > input.length()) return false;
        if (input.charAt(index) != COLOR_CHAR) return false;
        if (Character.toLowerCase(input.charAt(index + 1)) != 'x') return false;

        for (int digit = 0; digit < 6; digit++) {
            final int position = index + 2 + (digit * 2);

            if (input.charAt(position) != COLOR_CHAR) return false;
            if (!isHexDigit(input.charAt(position + 1))) return false;
        }

        return true;
    }

    private static boolean isHexSequence(final String input, final int start) {
        for (int offset = 0; offset < 6; offset++) {
            if (!isHexDigit(input.charAt(start + offset))) return false;
        }

        return true;
    }

    private static boolean isHexDigit(final char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static void appendHex(final StringBuilder builder, final String source, final int start) {
        if (!isHexSupported()) {
            builder.append(COLOR_CHAR).append(nearestLegacy(source, start));
            return;
        }

        builder.append(COLOR_CHAR).append('x');

        for (int digit = 0; digit < 6; digit++) {
            builder.append(COLOR_CHAR).append(Character.toLowerCase(source.charAt(start + digit)));
        }
    }

    private static char nearestLegacy(final String source, final int start) {
        final int rgb = Integer.parseInt(source.substring(start, start + 6), 16);
        final int red = (rgb >> 16) & 0xFF;
        final int green = (rgb >> 8) & 0xFF;
        final int blue = rgb & 0xFF;

        char closest = 'f';
        double closestDistance = Double.MAX_VALUE;

        for (int i = 0; i < LEGACY_RGB.length; i++) {
            final int candidate = LEGACY_RGB[i];
            final int candidateRed = (candidate >> 16) & 0xFF;
            final int candidateGreen = (candidate >> 8) & 0xFF;
            final int candidateBlue = candidate & 0xFF;

            // "Redmean" approximation: cheap but noticeably closer to human perception
            // than a plain euclidean distance in RGB space.
            final double meanRed = (red + candidateRed) / 2.0;
            final double deltaRed = red - candidateRed;
            final double deltaGreen = green - candidateGreen;
            final double deltaBlue = blue - candidateBlue;

            final double distance = (2 + (meanRed / 256)) * deltaRed * deltaRed
                    + 4 * deltaGreen * deltaGreen
                    + (2 + ((255 - meanRed) / 256)) * deltaBlue * deltaBlue;

            if (distance < closestDistance) {
                closestDistance = distance;
                closest = LEGACY_CHARS[i];
            }
        }

        return closest;
    }

    private static Boolean resolveHexSupport() {
        try {
            final String version = Bukkit.getBukkitVersion();
            final int separator = version.indexOf('-');
            final String[] parts = (separator > 0 ? version.substring(0, separator) : version).split("\\.");

            final int major = Integer.parseInt(parts[0]);
            final int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

            return major > 1 || minor >= 16;
        } catch (Throwable ignored) {
            // Unknown version scheme: assume a modern server.
            return Boolean.TRUE;
        }
    }
}