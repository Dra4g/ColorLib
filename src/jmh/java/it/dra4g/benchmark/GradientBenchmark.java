package it.dra4g.benchmark;

import it.dra4g.CCPremium;
import it.dra4g.GradientPalette;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(2)
@Threads(1)
public class GradientBenchmark {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Param({"two", "multi", "unicode", "long", "flat"})
    public String scenario;
    private String text;
    private int[] colors;
    private TextColor[] kyoriColors;
    private String template;
    private boolean flat;
    private GradientPalette prepared;

    public static void main(final String[] args) throws Exception {
        ComponentBenchmark.run(args, true);
    }

    @Setup(Level.Trial)
    public void setup() {
        text = switch (scenario) {
            case "two", "multi", "flat" -> "Hello Player";
            case "unicode" -> "Hello 猫\ud83d\udc31 Player";
            case "long" -> "Dynamic gradient message ".repeat(16);
            default -> throw new IllegalArgumentException(scenario);
        };
        colors = switch (scenario) {
            case "two" -> new int[]{0x00AAFF, 0xAA00FF};
            case "flat" -> new int[]{0x123456, 0x123456, 0x123456, 0x123456, 0x123456};
            default -> new int[]{0xE482F0, 0x952294, 0x7B0275, 0x8601B3, 0x8B00D2};
        };
        kyoriColors = new TextColor[colors.length];
        flat = true;
        final StringBuilder markup = new StringBuilder("<gradient");
        for (int i = 0; i < colors.length; i++) {
            kyoriColors[i] = TextColor.color(colors[i]);
            flat &= colors[i] == colors[0];
            markup.append(':').append(kyoriColors[i].asHexString());
        }
        template = markup.append("><value></gradient>").toString();
        prepared = CCPremium.gradientPalette(colors);

        /* Only correctness here, no precomputed output in the timed methods.
           All three paths must produce the same text and effective colors. */
        final Component premium = gradient__premium();
        final Component kyori = gradient__kyori();
        final Component mini = gradient__miniMessage();
        final Component ready = gradient__prepared();
        final Component cold = gradient__coldPalette();
        if (!CCPremium.plainText(premium)
                .equals(text)
                || !CCPremium.plainText(kyori)
                .equals(text)
                || !CCPremium.plainText(mini)
                .equals(text)
                || !CCPremium.serialize(premium)
                .equals(CCPremium.serialize(kyori))
                || !CCPremium.serialize(premium)
                .equals(CCPremium.serialize(mini))
                || !CCPremium.serialize(premium)
                .equals(CCPremium.serialize(ready))
                || !CCPremium.serialize(premium)
                .equals(CCPremium.serialize(cold))) {
            throw new IllegalStateException("Gradient output mismatch: " + scenario);
        }
    }

    @Benchmark
    public Component gradient__premium() {
        return colors.length == 2
                ? CCPremium.gradient(text, colors[0], colors[1])
                : CCPremium.gradient(text, colors);
    }

    @Benchmark
    public Component gradient__prepared() {
        return prepared.apply(text);
    }

    @Benchmark
    public Component gradient__coldPalette() {
        /* Include snapshot validation and plan compilation on EVERY call.
           This deliberately cannot hit a palette's previously compiled plan. */
        return CCPremium.gradientPalette(colors).apply(text);
    }

    @Benchmark
    public Component gradient__kyori() {
        if (flat) return Component.text(text, kyoriColors[0]);
        final int count = text.codePointCount(0, text.length());
        final int segments = kyoriColors.length - 1;
        final double scale = (double) segments / (count - 1);
        final TextComponent.Builder output = Component.text();
        for (int index = 0, offset = 0; index < count; index++) {
            final double position = index * scale;
            final int segment = Math.min((int) position, segments - 1);
            final TextColor color = TextColor.lerp((float) position - segment,
                    kyoriColors[segment], kyoriColors[segment + 1]);
            final int next = offset + Character.charCount(text.codePointAt(offset));
            output.append(Component.text(text.substring(offset, next), color));
            offset = next;
        }
        return output.build();
    }

    @Benchmark
    public Component gradient__miniMessage() {
        return MINI.deserialize(template, Placeholder.unparsed("value", text));
    }
}
