package it.dra4g.benchmark;

import it.dra4g.CC;
import it.dra4g.CCPremium;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end latency suite for the current CC API.
 *
 * <p>Run the full suite with {@code gradlew jmh}; use
 * {@code gradlew jmh -PjmhArgs=--quick} only for a short smoke measurement.</p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@Threads(1)
public class ColorBenchmark {
    private static final LegacyComponentSerializer KYORI_AMP =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();
    private static final LegacyComponentSerializer KYORI_SECTION =
            LegacyComponentSerializer.builder()
                    .character(CC.COLOR_CHAR)
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();
    private static final PlainTextComponentSerializer KYORI_PLAIN =
            PlainTextComponentSerializer.plainText();

    private String raw;
    private String translated;
    private String lastColors;
    private String startsWith;
    private String split;
    private String strip;
    private int splitAt;

    @Setup(Level.Trial)
    public void setup() {
        forceModernRgb();

        raw = "&#00E0FF&lP&#24C7F4&lR&#489FE9&lE&#6C77DE&lM"
                + "&#904FD3&lI&#B427C8&lU&#D800BD&lM &r&aLobby &nEU-1";
        translated = CC.translate(raw);
        startsWith = "\u00a7aPremium";

        final StringBuilder history = new StringBuilder(1536);
        for (int i = 0; i < 96; i++) {
            history.append("\u00a7").append("0123456789abcdef".charAt(i & 15))
                    .append((char) ('A' + i % 26)).append('\u00a7').append((char) ('k' + i % 5));
        }
        history.append("\u00a7x\u00a70\u00a70\u00a7E\u00a70\u00a7F\u00a7F\u00a7l\u00a7nEND");
        lastColors = history.toString();

        split = "0123456789abcdef".repeat(32)
                + "\u00a7x\u00a70\u00a70\u00a7E\u00a70\u00a7F\u00a7F"
                + "\ud83d\udc31-tail";
        splitAt = 518;
        strip = (translated + " plain \u00a7kinvisible\u00a7r ").repeat(8);

        verifyEquivalent();
    }

    @Benchmark
    public String translate__kyori() {
        return KYORI_SECTION.serialize(KYORI_AMP.deserialize(raw));
    }

    @Benchmark
    public String translate__classic() {
        return CC.translate(raw);
    }

    @Benchmark
    public String translate__premium() {
        return CCPremium.translate(raw);
    }

    @Benchmark
    public boolean hexSupport__classic() {
        return CC.isHexSupported();
    }

    @Benchmark
    public boolean hexSupport__premium() {
        return CCPremium.isHexSupported();
    }

    @Benchmark
    public String lastColors__classic() {
        return CC.getLastColors(lastColors);
    }

    @Benchmark
    public String lastColors__premium() {
        return CCPremium.getLastColors(lastColors);
    }

    @Benchmark
    public boolean startsWithColor__classic() {
        return CC.startsWithColor(startsWith);
    }

    @Benchmark
    public boolean startsWithColor__premium() {
        return CCPremium.startsWithColor(startsWith);
    }

    @Benchmark
    public int safeSplitIndex__classic() {
        return CC.safeSplitIndex(split, splitAt);
    }

    @Benchmark
    public int safeSplitIndex__premium() {
        return CCPremium.safeSplitIndex(split, splitAt);
    }

    @Benchmark
    public String truncate__classic() {
        return CC.truncate(split, splitAt);
    }

    @Benchmark
    public String truncate__premium() {
        return CCPremium.truncate(split, splitAt);
    }

    @Benchmark
    public String stripColor__kyori() {
        return KYORI_PLAIN.serialize(KYORI_SECTION.deserialize(strip));
    }

    @Benchmark
    public String stripColor__classic() {
        return CC.stripColor(strip);
    }

    @Benchmark
    public String stripColor__premium() {
        return CCPremium.stripColor(strip);
    }

    public static void main(final String[] args) throws Exception {
        final boolean quick = has(args, "--quick");
        final Path reportDir = Path.of("build", "reports", "jmh", quick ? "quick" : "full");
        Files.createDirectories(reportDir);

        final Options options = new OptionsBuilder()
                .include("^" + ColorBenchmark.class.getName() + "\\..*$")
                .mode(Mode.AverageTime)
                .timeUnit(TimeUnit.NANOSECONDS)
                .warmupIterations(quick ? 2 : 4)
                .warmupTime(TimeValue.milliseconds(quick ? 150 : 400))
                .measurementIterations(quick ? 3 : 5)
                .measurementTime(TimeValue.milliseconds(quick ? 150 : 400))
                .forks(quick ? 1 : 2)
                .threads(1)
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(reportDir.resolve("results.json").toString())
                .build();

        final Collection<RunResult> results = new Runner(options).run();
        printReport(results, reportDir, quick);
    }

    private void verifyEquivalent() {
        same(CC.translate(raw), CCPremium.translate(raw), "translate");
        same(CC.getLastColors(lastColors), CCPremium.getLastColors(lastColors), "getLastColors");
        same(CC.startsWithColor(startsWith), CCPremium.startsWithColor(startsWith), "startsWithColor");
        same(CC.safeSplitIndex(split, splitAt), CCPremium.safeSplitIndex(split, splitAt), "safeSplitIndex");
        same(CC.truncate(split, splitAt), CCPremium.truncate(split, splitAt), "truncate");
        same(CC.stripColor(strip), CCPremium.stripColor(strip), "stripColor");
        same(CC.isHexSupported(), CCPremium.isHexSupported(), "isHexSupported");
    }

    private static void forceModernRgb() {
        try {
            final Field classic = CC.class.getDeclaredField("hexSupported");
            classic.setAccessible(true);
            classic.set(null, Boolean.TRUE);

            final Field premium = CCPremium.class.getDeclaredField("H");
            premium.setAccessible(true);
            premium.setInt(null, 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot initialize deterministic benchmark state", e);
        }
    }

    private static void same(final Object expected, final Object actual, final String operation) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(operation + " changed its observable result");
        }
    }

    private static boolean has(final String[] args, final String expected) {
        for (String arg : args) {
            if (expected.equalsIgnoreCase(arg)) return true;
        }
        return false;
    }

    private static void printReport(
            final Collection<RunResult> results,
            final Path reportDir,
            final boolean quick
    ) throws IOException {
        final Map<String, Score> scores = new HashMap<>();
        for (RunResult result : results) {
            final String full = result.getParams().getBenchmark();
            final String name = full.substring(full.lastIndexOf('.') + 1);
            scores.put(name, new Score(
                    result.getPrimaryResult().getScore(),
                    result.getPrimaryResult().getScoreError()
            ));
        }

        final String[] operations = {
                "translate", "hexSupport", "lastColors", "startsWithColor",
                "safeSplitIndex", "truncate", "stripColor"
        };
        final StringBuilder plain = new StringBuilder(2048);

        System.out.println(rgb(96, 225, 255) + " CC NANOSECOND ARENA"
                + reset() + (quick ? "  [QUICK / NON-CERTIFYING]" : "  [FULL]"));
        System.out.println();
        System.out.println(rgb(145, 155, 170) + " RGB GRADIENT OUTPUT COMPARISON" + reset());
        rainbow("Paper/Kyori");
        rainbow("CC Classic");
        rainbow("CC-Premium");
        System.out.println();
        final String rule =
                "+----------------------+-----------------------+-----------------------+"
                        + "-----------------------+-----------+-----------+";
        final String heading = String.format(Locale.ROOT,
                "| %-20s | %21s | %21s | %21s | %9s | %9s |",
                "method", "Paper/Kyori", "CC Classic", "CC-Premium", "speedup", "2x target");
        System.out.println(rule);
        System.out.println(heading);
        System.out.println(rule);
        plain.append("CC nanosecond benchmark")
                .append(quick ? " [QUICK / NON-CERTIFYING]" : " [FULL]")
                .append(System.lineSeparator())
                .append(rule).append(System.lineSeparator())
                .append(heading).append(System.lineSeparator())
                .append(rule).append(System.lineSeparator());

        for (String operation : operations) {
            final Score kyori = scores.get(operation + "__kyori");
            final Score classic = required(scores, operation + "__classic");
            final Score premium = required(scores, operation + "__premium");
            final double speedup = classic.ns / premium.ns;
            final boolean pass = speedup >= 2.0;
            final String status = pass ? "PASS" : "MISS";
            final String speedupCell = String.format(Locale.ROOT, "%.2fx", speedup);
            final String row = String.format(Locale.ROOT,
                    "| %-20s | %21s | %21s | %21s | %9s | %9s |",
                    operation,
                    cell(kyori),
                    cell(classic),
                    cell(premium),
                    speedupCell,
                    status);
            System.out.println((pass ? rgb(91, 255, 142) : rgb(255, 191, 71)) + row + reset());
            plain.append(row).append(System.lineSeparator());
        }
        System.out.println(rule);
        plain.append(rule).append(System.lineSeparator());

        System.out.println();
        System.out.println(rgb(145, 155, 170)
                + "ns/op = average latency; +/- is JMH score error. "
                + "Kyori is shown only where Adventure exposes an equivalent serializer operation."
                + reset());
        System.out.println(rgb(145, 155, 170)
                + "JSON: " + reportDir.resolve("results.json").toAbsolutePath()
                + reset());

        plain.append(System.lineSeparator())
                .append("ns/op = average latency; +/- is JMH score error.")
                .append(System.lineSeparator())
                .append("Paper/Kyori is N/A where Adventure has no equivalent public operation.")
                .append(System.lineSeparator());
        Files.writeString(
                reportDir.resolve("summary.txt"),
                plain.toString(),
                StandardCharsets.UTF_8
        );
    }

    private static Score required(final Map<String, Score> scores, final String name) {
        final Score score = scores.get(name);
        if (score == null) throw new IllegalStateException("Missing JMH result: " + name);
        return score;
    }

    private static String cell(final Score score) {
        return score == null
                ? "n/a"
                : String.format(Locale.ROOT, "%7.2f +/- %-4.2f", score.ns, score.error);
    }

    private static void rainbow(final String implementation) {
        final StringBuilder line = new StringBuilder(implementation.length() + (48 * 24) + 20);
        line.append(rgb(220, 225, 235))
                .append(String.format(Locale.ROOT, "%-13s", implementation))
                .append(reset())
                .append(" : ");
        for (int i = 0; i < 48; i++) {
            final int[] c = hsv(i / 48.0);
            line.append(rgb(c[0], c[1], c[2])).append("||");
        }
        line.append(reset());
        System.out.println(line);
    }

    private static int[] hsv(final double hue) {
        final double h = hue * 6.0;
        final int sector = (int) h;
        final double f = h - sector;
        final int up = (int) Math.round(255.0 * f);
        final int down = 255 - up;
        return switch (sector % 6) {
            case 0 -> new int[]{255, up, 0};
            case 1 -> new int[]{down, 255, 0};
            case 2 -> new int[]{0, 255, up};
            case 3 -> new int[]{0, down, 255};
            case 4 -> new int[]{up, 0, 255};
            default -> new int[]{255, 0, down};
        };
    }

    private static String rgb(final int red, final int green, final int blue) {
        return "\u001b[38;2;" + red + ';' + green + ';' + blue + 'm';
    }

    private static String reset() {
        return "\u001b[0m";
    }

    private record Score(double ns, double error) {
    }
}
