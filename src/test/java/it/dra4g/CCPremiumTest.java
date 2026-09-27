package it.dra4g;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CCPremiumTest {
    private static final char[] ALPHABET = (
            "abcXYZ 019&\u00a7#klmnorABCDEFxyz!?" +
                    "\ud83d\ude80\ud83d\udc31\ud800\udc00"
    ).toCharArray();

    @AfterEach
    void restoreModernMode() throws ReflectiveOperationException {
        setHexMode(true);
    }

    @Test
    void hexSupportReadsBothCachedModes() throws ReflectiveOperationException {
        setHexMode(false);
        assertFalse(CCPremium.isHexSupported());
        setHexMode(true);
        assertTrue(CCPremium.isHexSupported());
        setHexMode(false);
        assertFalse(CCPremium.isHexSupported());
    }

    @Test
    void unresolvedHexSupportPublishesTheResolvedMode() throws ReflectiveOperationException {
        final Field state = CCPremium.class.getDeclaredField("H");
        state.setAccessible(true);
        state.setInt(null, 0);
        /* No Bukkit server in these unit tests: existing contract assumes RGB
           and caches that decision. Both read paths must see the same field. */
        assertTrue(CCPremium.isHexSupported());
        assertEquals(1, state.getInt(null));
        assertTrue(CCPremium.isHexSupported());
    }

    @Test
    void knownCasesMatchClassic() throws ReflectiveOperationException {
        setHexMode(true);
        final String[] cases = {
                null,
                "",
                "plain",
                "&aGreen &Lbold &rreset",
                "invalid &z and dangling &",
                "&#00E0FF&lP&#33B3E9&lR&#6686D3&lI",
                "\u00a7x\u00a70\u00a70\u00a7E\u00a70\u00a7F\u00a7F\u00a7lHello",
                "\u00a7aA\ud83d\udc31B\u00a7lC",
                "\u00a7x\u00a71\u00a72broken",
                "\u00a7\u00a7x\u00a70\u00a70\u00a7E\u00a70\u00a7F\u00a7Foverlap",
                "\u00a7\u00a7\u00a7aodd-run"
        };

        for (String value : cases) {
            compareEveryOperation(value);
        }
    }

    @Test
    void randomizedDifferentialContract() throws ReflectiveOperationException {
        setHexMode(true);
        final Random random = new Random(0xCCF00D);

        for (int sample = 0; sample < 20_000; sample++) {
            final int size = random.nextInt(96);
            final char[] data = new char[size];
            for (int i = 0; i < size; i++) {
                data[i] = ALPHABET[random.nextInt(ALPHABET.length)];
            }
            compareEveryOperation(new String(data));
        }
    }

    @Test
    void legacyRgbDownsamplingMatchesClassic() throws ReflectiveOperationException {
        setHexMode(false);
        final Random random = new Random(0x16C010);
        for (int i = 0; i < 10_000; i++) {
            final String value = String.format("x&#%06Xy", random.nextInt(0x1000000));
            assertEquals(CC.translate(value), CCPremium.translate(value), value);
        }
    }

    @Test
    void inPlaceRgbExpansionDoesNotOverwriteUnreadInput() throws ReflectiveOperationException {
        setHexMode(true);

        for (int tokens = 1; tokens <= 128; tokens++) {
            final StringBuilder value = new StringBuilder((tokens * 8) + 32);
            value.append("p".repeat(tokens & 15));
            for (int token = 0; token < tokens; token++) {
                value.append(token % 2 == 0 ? "&#ABCDEF" : "&#012345");
            }
            value.append("&lTAIL").append(tokens);

            assertEquals(CC.translate(value.toString()), CCPremium.translate(value.toString()),
                    "tokens=" + tokens);
        }
    }

    @Test
    void inPlaceStripCompactionHandlesDenseAndUnicodeContent() {
        final String value = ("\u00a7x\u00a7A\u00a7B\u00a7C\u00a7D\u00a7E\u00a7F"
                + "\u00a7l\ud83d\udc31text\u00a7r\u00a7aX").repeat(128);

        assertEquals(CC.stripColor(value), CCPremium.stripColor(value));
    }

    @Test
    void everyStartingCodeMatchesClassic() {
        final char[] value = {CC.COLOR_CHAR, 0};
        for (int code = Character.MIN_VALUE;
             code <= Character.MAX_VALUE;
            code++) {
            value[1] = (char) code;
            final String input = new String(value);
            assertEquals(CC.startsWithColor(input), CCPremium.startsWithColor(input), "code=" + code);
        }
    }

    @Test
    void mixedTokensMatchClassicAtEveryBoundary() throws ReflectiveOperationException {
        final String[] pieces = {
                "plain", "\u732b\ud83d\udc31", "\ud800", "\udc00",
                "&a", "&L", "&#ABCDEF", "&#012345", "&#GG0000", "&#123",
                "\u00a7a", "\u00a7L", "\u00a7r", "\u00a7n", "\u00a7z", "\u00a7",
                "\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76",
                "\u00a7X\u00a7A\u00a7B\u00a7C\u00a7D\u00a7E\u00a7F",
                "\u00a7x\u00a71\u00a72\u00a7G\u00a74\u00a75\u00a76"
        };
        final Random random = new Random(0xC010B0);
        for (final boolean modern : new boolean[]{true, false}) {
            setHexMode(modern);
            for (int sample = 0; sample < 2_000; sample++) {
                final StringBuilder value = new StringBuilder();
                final int count = 1 + random.nextInt(12);
                for (int piece = 0; piece < count; piece++) {
                    value.append(pieces[random.nextInt(pieces.length)]);
                }
                compareEveryOperation(value.toString());
            }
        }
    }

    @Test
    void backwardRgbOwnerSurvivesOffsetsAndMalformedPrefixes() {
        final String rgb = "\u00a7x\u00a71\u00a72\u00a7A\u00a7b\u00a75\u00a76";
        for (int prefix = 0; prefix < 16; prefix++) {
            for (int signs = 0; signs < 8; signs++) {
                for (final String tail : new String[]{"", "\u00a7l\u00a7nEND", "\u00a7", "\u00a7z", "\u00a7r", "\u00a7a"}) {
                    final String value = "p".repeat(prefix) + "\u00a7".repeat(signs) + rgb + tail;
                    assertEquals(CC.getLastColors(value), CCPremium.getLastColors(value), value);
                }
            }
        }
    }

    @Test
    void stripPreservesLiteralSpansAndNoOpIdentity() {
        final String literal = "\u732b\u00a7z plain \u00a7x\u00a7G trailing \u00a7";
        assertSame(literal, CCPremium.stripColor(literal));
        for (final String token : new String[]{
                "\u00a7a",
                "\u00a7L",
                "\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76"}) {
            assertEquals("", CCPremium.stripColor(token.repeat(128)));
            final String value = literal + token + "\ud800middle\udc00" + token + literal;
            assertEquals(CC.stripColor(value), CCPremium.stripColor(value));
        }
    }

    private static void compareEveryOperation(final String value) {
        assertEquals(CC.translate(value), CCPremium.translate(value), "translate: " + value);
        assertEquals(CC.getLastColors(value), CCPremium.getLastColors(value), "last: " + value);
        assertEquals(CC.startsWithColor(value), CCPremium.startsWithColor(value), "starts: " + value);
        assertEquals(CC.stripColor(value), CCPremium.stripColor(value), "strip: " + value);

        if (value != null) {
            for (int max = -2; max <= value.length() + 2; max++) {
                assertEquals(
                        CC.safeSplitIndex(value, max),
                        CCPremium.safeSplitIndex(value, max),
                        "split max="
                                + max
                                + ": "
                                + value);
                assertEquals(
                        CC.truncate(value, max),
                        CCPremium.truncate(value, max),
                        "truncate max="
                                + max
                                + ": "
                                + value);
            }
        }
    }

    private static void setHexMode(final boolean enabled) throws ReflectiveOperationException {
        final Field classic = CC.class.getDeclaredField("hexSupported");
        classic.setAccessible(true);
        classic.set(null, enabled);

        final Field premium = CCPremium.class.getDeclaredField("H");
        premium.setAccessible(true);
        premium.setInt(null, enabled ? 1 : -1);
    }
}
