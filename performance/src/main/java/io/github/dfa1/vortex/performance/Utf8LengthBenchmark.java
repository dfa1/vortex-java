package io.github.dfa1.vortex.performance;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// The UTF-8 length of 100k strings (#508), four ways: `getBytes`, the branching loop, an ASCII
/// pre-pass, and the branch-free count of
/// [Lemire's Latin-1 trick](https://lemire.me/blog/2023/02/16/computing-the-utf-8-size-of-a-latin-1-string-quickly-avx-edition/)
/// extended to UTF-16 (one add per character, no branch, so the JIT can vectorize it). Run
/// `./bench Utf8LengthBenchmark.branchFree`, `-f 3` for numbers worth recording.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class Utf8LengthBenchmark {

    private static final int STRINGS = 100_000;

    @Param({"ascii", "latin1", "cjk"})
    public String content;

    private String[] strings;

    @Setup
    public void setup() {
        Random random = new Random(42);
        strings = new String[STRINGS];
        for (int i = 0; i < STRINGS; i++) {
            StringBuilder sb = new StringBuilder();
            int length = 10 + random.nextInt(60);
            for (int j = 0; j < length; j++) {
                sb.append(switch (content) {
                    case "ascii" -> (char) ('a' + random.nextInt(26));
                    case "latin1" -> random.nextInt(20) == 0 ? 'é' : (char) ('a' + random.nextInt(26));
                    default -> random.nextInt(4) == 0 ? (char) ('a' + random.nextInt(26)) : (char) (0x4E00 + random.nextInt(1000));
                });
            }
            strings[i] = sb.toString();
        }
    }

    @Benchmark
    public long getBytes() {
        long total = 0;
        for (String s : strings) {
            total += s.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    @Benchmark
    public long branching() {
        long total = 0;
        for (String s : strings) {
            total += branching(s);
        }
        return total;
    }

    @Benchmark
    public long asciiPrePass() {
        long total = 0;
        for (String s : strings) {
            int seen = 0;
            int n = s.length();
            for (int i = 0; i < n; i++) {
                seen |= s.charAt(i);
            }
            total += seen < 0x80 ? n : branching(s);
        }
        return total;
    }

    @Benchmark
    public long branchFree() {
        long total = 0;
        for (String s : strings) {
            int n = s.length();
            int bytes = n;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                // +1 from 0x80, +1 more from 0x800; a surrogate (0xD800-0xDFFF) is 2 here, so a pair is 4
                bytes += (c >= 0x80 ? 1 : 0) + (c >= 0x800 ? 1 : 0) - (c >>> 11 == 0x1B ? 1 : 0);
            }
            total += bytes;
        }
        return total;
    }

    private static int branching(String s) {
        int n = s.length();
        int bytes = n;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x800) {
                if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                    bytes += 2;
                    i++;
                } else if (!Character.isSurrogate(c)) {
                    bytes += 2;
                }
            } else if (c >= 0x80) {
                bytes++;
            }
        }
        return bytes;
    }
}
