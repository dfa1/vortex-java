package io.github.dfa1.vortex.integration;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

final class RandomArrays {

    private static final long SEED = 0xCAFEBABEL;

    private RandomArrays() {
    }

    static Stream<long[]> i64Arrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int size = rng.nextInt(10_001);
            long[] arr = new long[size];
            for (int j = 0; j < size; j++) {
                arr[j] = rng.nextLong();
            }
            return arr;
        });
    }

    static Stream<double[]> f64Arrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int size = rng.nextInt(5_001);
            double[] arr = new double[size];
            for (int j = 0; j < size; j++) {
                double d;
                do {
                    d = Double.longBitsToDouble(rng.nextLong());
                } while (!Double.isFinite(d));
                arr[j] = d;
            }
            return arr;
        });
    }

    static Stream<float[]> f32Arrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int size = rng.nextInt(5_001);
            float[] arr = new float[size];
            for (int j = 0; j < size; j++) {
                float f;
                do {
                    f = Float.intBitsToFloat(rng.nextInt());
                } while (!Float.isFinite(f));
                arr[j] = f;
            }
            return arr;
        });
    }

    static Stream<String[]> asciiStringArrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int size = rng.nextInt(5_001);
            String[] arr = new String[size];
            for (int j = 0; j < size; j++) {
                arr[j] = randomAlphaString(rng, 0, 100);
            }
            return arr;
        });
    }

    static Stream<String[]> varBinViewStringArrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int size = rng.nextInt(1_001);
            String[] arr = new String[size];
            for (int j = 0; j < size; j++) {
                arr[j] = randomAlphaString(rng, 0, 30);
            }
            return arr;
        });
    }

    static Stream<String[]> u16DictStringArrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int targetSize = 257 + rng.nextInt(5_000 - 257 + 1);
            Set<String> unique = new LinkedHashSet<>();
            while (unique.size() < targetSize) {
                unique.add(randomAlphaString(rng, 3, 20));
            }
            return unique.toArray(String[]::new);
        });
    }

    static Stream<String[]> unicodeStringArrays(int count) {
        Random rng = new Random(SEED);
        return IntStream.range(0, count).mapToObj(_ -> {
            int size = rng.nextInt(1_001);
            StringBuilder sb = new StringBuilder();
            String[] arr = new String[size];
            for (int j = 0; j < size; j++) {
                arr[j] = randomCjkString(rng, 0, 50, sb);
            }
            return arr;
        });
    }

    private static String randomAlphaString(Random rng, int minLen, int maxLen) {
        int len = minLen + (maxLen > minLen ? rng.nextInt(maxLen - minLen + 1) : 0);
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) ('a' + rng.nextInt(26));
        }
        return new String(chars);
    }

    private static String randomCjkString(Random rng, int minLen, int maxLen, StringBuilder sb) {
        int len = minLen + (maxLen > minLen ? rng.nextInt(maxLen - minLen + 1) : 0);
        sb.setLength(0);
        for (int i = 0; i < len; i++) {
            sb.appendCodePoint('一' + rng.nextInt('퟿' - '一' + 1));
        }
        return sb.toString();
    }
}
