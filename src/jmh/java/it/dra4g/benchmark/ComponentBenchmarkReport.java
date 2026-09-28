package it.dra4g.benchmark;

import it.dra4g.CCPremium;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ComponentBenchmarkReport {
    private static final String RESET = "\u001b[0m";
    private static final String GRAY = "\u001b[38;2;145;155;170m";
    private static final String CYAN = "\u001b[38;2;96;225;255m";
    private static final String GREEN = "\u001b[38;2;91;255;142m";
    private static final String YELLOW = "\u001b[38;2;255;191;71m";
    private static final String RED = "\u001b[38;2;255;100;100m";
    private static final String RULE = "+----------------------+------------+-----------------------+-----------------------+-----------+-----------+";
    private static final String ROW = "| %-20s | %-10s | %21s | %21s | %9s | %9s |";
    private static final String[] SCENARIOS = {"plain", "gradient"};
    private static final String[] OPERATIONS = {"component", "plainText", "serialize"};

    private ComponentBenchmarkReport() {}

    record Score(double ns,
                 double error) {}
    record Row(String method,
               String scenario,
               Score reference,
               Score premium) {}
    private record Key(String method,
                       String scenario) {}

    static List<Row> rows(final Collection<RunResult> results,
                          final boolean gradients) {
        final Map<Key, Score> scores = new HashMap<>();
        final String prefix = (gradients
                ? GradientBenchmark.class.getName()
                : ComponentBenchmark.class.getName()) + '.';
        for (final RunResult result : results) {
            final String benchmark;
            if (!(benchmark = result
                    .getParams()
                    .getBenchmark())
                    .startsWith(prefix)) continue;
            if (result
                    .getParams()
                    .getMode() != Mode.AverageTime
                    || !result
                    .getPrimaryResult()
                    .getScoreUnit()
                    .equals("ns/op")) {
                throw new IllegalArgumentException("Cannot compare results with different modes or units");
            }
            scores.put(new Key(benchmark
                            .substring(prefix.length()),
                            result.getParams().getParam("scenario")),
                    new Score(result
                            .getPrimaryResult()
                            .getScore(),
                            result.getPrimaryResult()
                                    .getScoreError()));
        }
        final String[] scenarios = gradients ? new String[]{
                "two",
                "multi",
                "unicode",
                "long",
                "flat"} : SCENARIOS;
        final List<Row> rows = new ArrayList<>(
                scenarios.length
                        * (gradients
                        ? 5
                        : OPERATIONS.length));
        for (final String scenario : scenarios) {
            if (gradients) {
                final Score premium = scores.get(new Key("gradient__premium", scenario));
                for (final String reference : new String[]{"kyori", "miniMessage"}) {
                    final Score baseline = scores.get(new Key("gradient__" + reference, scenario));
                    if (baseline != null
                            || premium != null) rows.add(new Row(reference.equals("kyori")
                            ? "vs Kyori lerp"
                            : "vs MiniMessage",
                            scenario, baseline, premium));
                }
                final Score prepared = scores.get(
                        new Key("gradient__prepared",
                        scenario));
                if (prepared != null) {
                    rows.add(new Row("prepared/Kyori", scenario,
                            scores.get(new Key("gradient__kyori", scenario)),
                            prepared));
                    rows.add(new Row("prepared/MiniMessage", scenario,
                            scores.get(new Key("gradient__miniMessage", scenario)),
                            prepared));
                }
                final Score cold = scores.get(new Key("gradient__coldPalette", scenario));
                if (cold != null) rows.add(new Row("cold palette", scenario, null, cold));
                continue;
            }
            for (final String op : OPERATIONS) {
                final String reference = op.equals("component")
                        ? "previousPipeline"
                        : "kyori";
                final Score baseline;
                final Score premium = scores.get(new Key(op + "__premium", scenario));
                /* Filtered JMH runs may contain only one side. Never invent a
                   ratio or a PASS from a missing result. */
                if ((baseline = scores
                        .get(new Key(op
                                + "__"
                                + reference,
                                scenario)))
                        != null
                        || premium != null)
                    rows.add(new Row(
                            op,
                            scenario,
                            baseline,
                            premium));
            }
        }
        return rows;
    }

    static String render(final List<Row> rows,
                         final String label,
                         final boolean ansi,
                         final boolean gradients) {
        final StringBuilder output = new StringBuilder(6_144);
        line(output, " CC "
                + (gradients
                ? "GRADIENT"
                : "COMPONENT")
                + " NANOSECOND ARENA  ["
                + label
                + "]",
                CYAN,
                ansi);
        output.append('\n');
        line(output, " RGB GRADIENT OUTPUT COMPARISON", GRAY, ansi);
        appendGradients(output, ansi, gradients);
        output.append('\n');
        line(output, RULE, GRAY, ansi);
        line(output,
                String.format(
                        Locale.ROOT,
                        ROW,
                        "method",
                        "scenario",
                        "Paper/Kyori*",
                        "CC-Premium",
                        "speedup",
                        "2x target"),
                CYAN,
                ansi);
        line(output, RULE, GRAY, ansi);
        String previousScenario = null;
        for (final Row row : rows) {
            if (previousScenario != null
                    && !previousScenario
                    .equals(row.scenario))
                line(output, RULE, GRAY, ansi);
            previousScenario = row.scenario;
            final boolean comparable = row.reference != null
                    && row.premium != null
                    && Double.isFinite(row.reference.ns)
                    && Double.isFinite(row.premium.ns)
                    && row.reference.ns > 0
                    && row.premium.ns > 0;
            final double speedup = comparable
                    ? row.reference.ns / row.premium.ns
                    : Double.NaN;
            line(output, String.format(Locale.ROOT,
                    ROW,
                    row.method,
                    row.scenario,
                    cell(row.reference),
                    cell(row.premium),
                    comparable
                            ? String.format(
                                    Locale.ROOT,
                            "%.2fx",
                            speedup)
                            : "n/a",
                            (comparable
                                    ? speedup >= 2.0
                                    ? "PASS"
                                    : "MISS"
                                    : "n/a")),
                    (!comparable
                            ? GRAY
                            : speedup >= 2.0
                            ? GREEN
                            : speedup >= 1.0
                            ? YELLOW
                            : RED),
                    ansi);
        }
        line(output, RULE, GRAY, ansi);
        output.append('\n');
        line(output, "ns/op = average time per operation. LOWER IS BETTER. +/- = JMH score error.", GRAY, ansi);
        line(output, "Speedup = reference time / CC-Premium time. 2.00x means half the time; below 1.00x means slower.", GRAY, ansi);
        line(output, "Green: >= 2x faster. Yellow: 1x to < 2x. Red: slower than the reference.", GRAY, ansi);
        if (gradients) {
            line(output, "* vs Kyori lerp: direct TextColor.lerp loop, with prebuilt colors and a flat-palette shortcut.", GRAY, ansi);
            line(output, "* vs MiniMessage: gradient tag with a literal text placeholder; template prepared outside measurement.", GRAY, ansi);
            line(output, "* vs rows: existing gradient(text, colors) API with bounded palette/length plan reuse after warmup.", GRAY, ansi);
            line(output, "* prepared rows: reusable GradientPalette.apply(text); palette creation excluded, plans warmed up.", GRAY, ansi);
            line(output, "* cold palette: new palette + plan + output every call, shown separately without a speedup target.", GRAY, ansi);
            line(output, "* Plans retain only colors/styles and run ends. No names or output components are cached.", GRAY, ansi);
        } else {
            line(output, "* component: previous pipeline (CCPremium.translate + Kyori deserialize).", GRAY, ansi);
            line(output, "* plainText / serialize: direct comparison against the Kyori serializer.", GRAY, ansi);
        }
        line(output, "PASS/MISS uses the mean speedup against the 2x target, not a statistical significance test.", GRAY, ansi);
        return output.toString();
    }

    private static String cell(final Score score) {
        return score == null
                ? "n/a"
                : String.format(
                        Locale.ROOT,
                "%7.2f +/- %-5.2f",
                score.ns,
                score.error);
    }

    private static void line(final StringBuilder output,
                             final String text,
                             final String color,
                             final boolean ansi) {
        if (ansi) output.append(color);
        output.append(text);
        if (ansi) output.append(RESET);
        output.append('\n');
    }

    private static void appendGradients(final StringBuilder output,
                                        final boolean ansi,
                                        final boolean generated) {
        if (generated) {
            final String text = "|".repeat(96);
            final int[] colors = {
                    0xFF0000,
                    0xFFFF00,
                    0x00FF00,
                    0x00FFFF,
                    0x0000FF,
                    0xFF00FF,
                    0xFF0000};
            gradient(output, "MiniMessage", CCPremium.serialize(MiniMessage.miniMessage().deserialize(
                    "<gradient:#ff0000:#ffff00:#00ff00:#00ffff:#0000ff:#ff00ff:#ff0000>" + text + "</gradient>")), ansi);
            gradient(output, "CC-Premium", CCPremium.serialize(CCPremium.gradient(text, colors)), ansi);
            return;
        }
        final StringBuilder raw = new StringBuilder(480);
        for (int i = 0; i < 48; i++) {
            final int sector = i / 8;
            final int up = (int) Math.round((i % 8) * 255.0 / 8.0);
            final int down = 255 - up;
            final int rgb = switch (sector) {
                case 0 -> (255 << 16) | (up << 8);
                case 1 -> (down << 16) | (255 << 8);
                case 2 -> (255 << 8) | up;
                case 3 -> (down << 8) | 255;
                case 4 -> (up << 16) | 255;
                default -> (255 << 16) | down;
            };
            raw.append(String.format(Locale.ROOT, "&#%06x||", rgb));
        }
        /* Render actual outputs through both paths. This visual comparison is
           outside measurement; it is not a hard-coded rainbow for each label. */
        final LegacyComponentSerializer kyori = LegacyComponentSerializer
                .builder()
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
        final String input = raw.toString();
        gradient(output,
                "Paper/Kyori",
                kyori.serialize(kyori.deserialize(CCPremium.translate(input))), ansi);
        gradient(output,
                "CC-Premium",
                CCPremium.serialize(CCPremium.component(input)), ansi);
    }

    private static void gradient(final StringBuilder output,
                                 final String name,
                                 final String text,
                                 final boolean ansi) {
        output.append(String.format(Locale.ROOT, "%-13s : ", name));
        final int[] palette = {0x000000,
                0x0000AA,
                0x00AA00,
                0x00AAAA,
                0xAA0000,
                0xAA00AA,
                0xFFAA00,
                0xAAAAAA,
                0x555555,
                0x5555FF,
                0x55FF55,
                0x55FFFF,
                0xFF5555,
                0xFF55FF,
                0xFFFF55,
                0xFFFFFF};
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) != '\u00a7') {
                output.append(text.charAt(i++));
                continue;
            }
            final char code = text.charAt(i + 1);
            final int rgb;
            if (code == 'x') {
                int value = 0;
                for (int digit = 0; digit < 6; digit++)
                    value = (value << 4) | Character.digit(
                            text.charAt(i + 3 + digit * 2),
                            16);
                rgb = value;
                i += 14;
            } else {
                rgb = palette[Character.digit(code, 16)];
                i += 2;
            }
            if (ansi) output
                    .append("\u001b[38;2;")
                    .append(rgb >>> 16)
                    .append(';')
                    .append((rgb >>> 8) & 255)
                    .append(';')
                    .append(rgb & 255)
                    .append('m');
        }
        if (ansi) output.append(RESET);
        output.append('\n');
    }
}
