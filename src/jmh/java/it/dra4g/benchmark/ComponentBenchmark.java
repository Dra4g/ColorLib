package it.dra4g.benchmark;

import it.dra4g.CCPremium;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@Threads(1)
public class ComponentBenchmark {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer
            .builder()
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Param({"plain", "gradient"})
    public String scenario;
    private String raw;
    private Component component;

    static void main(final String[] args) throws Exception {
        run(args, false);
    }

    static void run(final String[] args,
                    final boolean gradients) throws Exception {
        boolean quick = false;
        final List<String> forwarded = new ArrayList<>(args.length);
        for (final String argument : args) {
            if (argument.equals("--quick")) quick = true;
            else forwarded.add(argument);
        }
        final CommandLineOptions cli;
        if ((cli = new CommandLineOptions(forwarded
                .toArray(String[]::new)))
                .shouldHelp()) {
            cli.showHelp();
            return;
        }
        if (cli.shouldListProfilers()) {
            cli.listProfilers();
            return;
        }
        if (cli.shouldListResultFormats()) {
            cli.listResultFormats();
            return;
        }
        if ((!cli
                .getBenchModes()
                .isEmpty()
                && (cli
                .getBenchModes()
                .size() != 1
                || !cli
                .getBenchModes()
                .contains(Mode.AverageTime)))
                || cli
                .getTimeUnit()
                .orElse(TimeUnit.NANOSECONDS) != TimeUnit.NANOSECONDS
                || cli
                .getResultFormat()
                .orElse(ResultFormatType.JSON) != ResultFormatType.JSON) {
            throw new IllegalArgumentException("This report requires average time, ns/op and JSON results.");
        }
        final String label = quick
                ? "QUICK / NON-CERTIFYING"
                : forwarded.isEmpty()
                ? "FULL"
                : "CUSTOM";
        final Path reportDir = Path.of(
                "build",
                "reports",
                "jmh",
                gradients ? "gradients" : "components",
                quick
                ? "quick"
                : forwarded.isEmpty()
                ? "full"
                : "custom");
        final Path json = Path.of(cli
                .getResult()
                .orElse(reportDir
                        .resolve("results.json")
                        .toString()));
        final OptionsBuilder builder = new OptionsBuilder();
        builder.parent(cli)
                .mode(Mode.AverageTime)
                .timeUnit(TimeUnit.NANOSECONDS)
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(json.toString());
        if (cli.getIncludes()
                .isEmpty())
            builder.include(gradients ? GradientBenchmark.class.getName() : ComponentBenchmark.class.getName());
        if (quick) {
            builder.warmupIterations(cli
                            .getWarmupIterations()
                            .orElse(2))
                    .warmupTime(cli
                            .getWarmupTime()
                            .orElse(TimeValue.milliseconds(150)))
                    .measurementIterations(cli
                            .getMeasurementIterations()
                            .orElse(3))
                    .measurementTime(cli
                            .getMeasurementTime()
                            .orElse(TimeValue.milliseconds(150)))
                    .forks(cli
                            .getForkCount()
                            .orElse(1));
        }
        final Runner runner = new Runner(builder.build());
        if (cli.shouldList()) {
            runner.list();
            return;
        }
        if (cli.shouldListWithParams()) {
            /* Unlike list()/run(), JMH's parameter listing reads this CLI
               directly instead of the builder's options. Keep its filter too. */
            if (cli.getIncludes()
                    .isEmpty()) {
                final List<String> listing = new ArrayList<>(forwarded);
                listing.add(gradients
                        ? GradientBenchmark.class.getName()
                        : ComponentBenchmark.class.getName());
                runner.listWithParams(new CommandLineOptions(listing.toArray(String[]::new)));
            } else {
                runner.listWithParams(cli);
            }
            return;
        }
        Files.createDirectories(reportDir);
        Files.createDirectories(json.toAbsolutePath().getParent());
        final Collection<RunResult> results = runner.run();
        final List<ComponentBenchmarkReport.Row> rows = ComponentBenchmarkReport.rows(results, gradients);
        System.out.print(ComponentBenchmarkReport.render(
                rows,
                label,
                true,
                gradients));
        final Path summary = reportDir.resolve("summary.txt");
        Files.writeString(summary,
                ComponentBenchmarkReport.render(
                        rows,
                        label,
                        false,
                        gradients),
                StandardCharsets.UTF_8);
        System.out.println("JSON: " + json.toAbsolutePath());
        System.out.println("Summary: " + summary.toAbsolutePath());
    }

    @Setup(Level.Trial)
    public void setup() {
        raw = switch (scenario) {
            case "plain" -> "Example message: sample string 猫 \ud83d\udc31";
            case "gradient" -> "&#00E0FF&lP&#24C7F4&lR&#489FE9&lE&#6C77DE&lM"
                    + "&#904FD3&lI&#B427C8&lU&#D800BD&lM";
            default -> throw new IllegalArgumentException(scenario);
        };
        component = LEGACY.deserialize(CCPremium.translate(raw));
        final Component parsed = CCPremium.component(raw);
        if (!LEGACY
                .serialize(component)
                .equals(LEGACY.serialize(parsed)))
            throw new IllegalStateException("Parser mismatch");
        if (!PLAIN
                .serialize(component)
                .equals(CCPremium.plainText(component)))
            throw new IllegalStateException("Plain text mismatch");
        if (!LEGACY
                .serialize(component)
                .equals(LEGACY
                        .serialize(LEGACY
                        .deserialize(CCPremium.serialize(component))))) {
            throw new IllegalStateException("Legacy writer mismatch");
        }
    }

    @Benchmark
    public Component component__previousPipeline() {
        return LEGACY.deserialize(CCPremium.translate(raw));
    }

    @Benchmark
    public Component component__premium() {
        return CCPremium.component(raw);
    }

    @Benchmark
    public String plainText__kyori() {
        return PLAIN.serialize(component);
    }

    @Benchmark
    public String plainText__premium() {
        return CCPremium.plainText(component);
    }

    @Benchmark
    public String serialize__kyori() {
        return LEGACY.serialize(component);
    }

    @Benchmark
    public String serialize__premium() {
        return CCPremium.serialize(component);
    }
}
