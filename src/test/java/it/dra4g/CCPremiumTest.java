package it.dra4g;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static void compareEveryOperation(final String value) {
        assertEquals(CC.translate(value), CCPremium.translate(value), "translate: " + value);
        assertEquals(CC.getLastColors(value), CCPremium.getLastColors(value), "last: " + value);
        assertEquals(CC.startsWithColor(value), CCPremium.startsWithColor(value), "starts: " + value);
        assertEquals(CC.stripColor(value), CCPremium.stripColor(value), "strip: " + value);

        if (value != null) {
            for (int max = -2; max <= value.length() + 2; max++) {
                assertEquals(CC.safeSplitIndex(value, max), CCPremium.safeSplitIndex(value, max),
                        "split max=" + max + ": " + value);
                assertEquals(CC.truncate(value, max), CCPremium.truncate(value, max),
                        "truncate max=" + max + ": " + value);
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
