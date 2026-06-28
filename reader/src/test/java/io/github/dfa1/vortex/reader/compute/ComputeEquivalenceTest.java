package io.github.dfa1.vortex.reader.compute;

import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedByteArray;
import io.github.dfa1.vortex.reader.array.MaterializedDoubleArray;
import io.github.dfa1.vortex.reader.array.MaterializedFloatArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import io.github.dfa1.vortex.reader.array.MaterializedShortArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Randomised oracle test: the type-specialised fast lane of [StreamingFilterKernel] /
/// [Reductions] must be bit-identical to the generic boxing path for every primitive input. For each
/// seeded scenario it builds a random primitive column (each integer width signed and unsigned,
/// `f32` / `f64`, with and without nulls), a random incoming mask, and a random predicate, then
/// asserts the specialised `filter` / `sum` / `count` / `min` / `max` results equal what the generic
/// path produces. Catching any divergence here guards against behavioural drift, the one thing the
/// fast lane must never introduce.
class ComputeEquivalenceTest {

    private static final Arena ARENA = Arena.ofAuto();

    private static final PType[] PTYPES = {
            PType.I8, PType.U8, PType.I16, PType.U16, PType.I32, PType.U32,
            PType.I64, PType.U64, PType.F32, PType.F64
    };

    private final StreamingFilterKernel filterKernel = new StreamingFilterKernel();

    private static Stream<Integer> seeds() {
        return IntStream.range(0, 400).boxed();
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void specialisedMatchesGeneric(int seed) {
        // Given a randomly shaped primitive column, incoming mask and predicate for this seed
        Random random = new Random(seed);
        Array array = randomColumn(random);
        Mask incoming = randomMask(random, array.length());
        Predicate predicate = randomPredicate(random, array, 0);

        // When the specialised fast lane and the generic oracle both run
        // Then filter and every reduction agree, position by position and value for value
        assertFilterAgrees(array, incoming, predicate);
        assertReductionsAgree(array, incoming);
    }

    @Test
    void boundaryConstantsMatchGeneric() {
        // Given signed columns whose predicate constants hit the flipped-range edge cases (Lt of the
        // minimum and Gt of the maximum must select nothing) and an empty column
        Array signed = longColumn(DType.I64, new long[]{Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE}, null);
        Array unsigned = longColumn(DType.U64, new long[]{0, 1, Long.MIN_VALUE, -1}, null);
        Array empty = longColumn(DType.I64, new long[]{}, null);
        Mask allSigned = Mask.allTrue(signed.length());
        Mask allUnsigned = Mask.allTrue(unsigned.length());
        Mask allEmpty = Mask.allTrue(0);

        // When/Then the edge predicates and empty input never diverge from the generic path
        assertFilterAgrees(signed, allSigned, new Predicate.Lt(Long.MIN_VALUE));
        assertFilterAgrees(signed, allSigned, new Predicate.Gt(Long.MAX_VALUE));
        assertFilterAgrees(unsigned, allUnsigned, new Predicate.Lt(0L));
        assertFilterAgrees(unsigned, allUnsigned, new Predicate.Gt(-1L));
        assertFilterAgrees(empty, allEmpty, new Predicate.Eq(1L));
        assertReductionsAgree(empty, allEmpty);
    }

    @Test
    void nanAndInfinitiesMatchGeneric() {
        // Given a double column carrying NaN, both signed zeros and the infinities — the values whose
        // ordering the fast lane must defer to Double.compare exactly like the generic path
        double[] values = {Double.NaN, 0.0, -0.0, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 1.5, -1.5};
        Array array = doubleColumn(DType.F64, values, null);
        Mask all = Mask.allTrue(array.length());

        // When/Then the comparisons and extremes agree with the generic Double.compare path
        assertFilterAgrees(array, all, new Predicate.Gt(0.0));
        assertFilterAgrees(array, all, new Predicate.Lt(0.0));
        assertFilterAgrees(array, all, new Predicate.Between(-1.0, 2.0));
        assertReductionsAgree(array, all);
    }

    private void assertFilterAgrees(Array array, Mask incoming, Predicate predicate) {
        Mask specialised = filterKernel.apply(array, incoming, predicate, ARENA);
        Mask generic = filterKernel.applyGeneric(array, incoming, predicate, ARENA);
        long n = array.length();
        for (long i = 0; i < n; i++) {
            assertThat(specialised.get(i))
                    .as("position %d for predicate %s on %s", i, predicate, array.dtype())
                    .isEqualTo(generic.get(i));
        }
        assertThat(specialised.trueCount()).isEqualTo(generic.trueCount());
    }

    private void assertReductionsAgree(Array array, Mask mask) {
        assertThat(Reductions.SUM.apply(array, mask))
                .as("sum on %s", array.dtype())
                .isEqualTo(Reductions.sumGeneric(array, mask));
        assertThat(Reductions.COUNT.apply(array, mask))
                .as("count on %s", array.dtype())
                .isEqualTo(Reductions.countGeneric(array, mask));
        assertThat(Reductions.MIN.apply(array, mask))
                .as("min on %s", array.dtype())
                .isEqualTo(Reductions.extremumGeneric(array, mask, true));
        assertThat(Reductions.MAX.apply(array, mask))
                .as("max on %s", array.dtype())
                .isEqualTo(Reductions.extremumGeneric(array, mask, false));
    }

    private static Array randomColumn(Random random) {
        PType ptype = PTYPES[random.nextInt(PTYPES.length)];
        int n = random.nextInt(40);
        boolean nullable = random.nextBoolean();
        boolean[] valid = nullable ? randomValidity(random, n) : null;
        DType dtype = new DType.Primitive(ptype, nullable);
        if (ptype.isFloating()) {
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = randomDouble(random);
            }
            return doubleColumn(dtype, values, valid);
        }
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = random.nextLong();
        }
        return longColumn(dtype, values, valid);
    }

    private static boolean[] randomValidity(Random random, int n) {
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            valid[i] = random.nextInt(4) != 0;
        }
        return valid;
    }

    private static double randomDouble(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> Double.NaN;
            case 1 -> Double.POSITIVE_INFINITY;
            case 2 -> Double.NEGATIVE_INFINITY;
            case 3 -> 0.0;
            case 4 -> -0.0;
            default -> (random.nextDouble() - 0.5) * 1.0e6;
        };
    }

    private static Mask randomMask(Random random, long n) {
        return switch (random.nextInt(5)) {
            case 0 -> Mask.allTrue(n);
            case 1 -> n == 0 ? Mask.allTrue(0) : Mask.allFalse(n);
            case 2 -> randomRange(random, n);
            default -> randomBitmap(random, n);
        };
    }

    private static Mask randomRange(Random random, long n) {
        if (n == 0) {
            return Mask.allTrue(0);
        }
        long start = random.nextInt((int) n);
        long end = start + random.nextInt((int) (n - start) + 1);
        return new Mask.RangeMask(start, end, n);
    }

    private static Mask randomBitmap(Random random, long n) {
        long bytes = (n + 7) >>> 3;
        MemorySegment bits = ARENA.allocate(Math.max(1, bytes));
        for (long i = 0; i < n; i++) {
            if (random.nextBoolean()) {
                long byteIndex = i >>> 3;
                int existing = bits.get(ValueLayout.JAVA_BYTE, byteIndex) & 0xff;
                bits.set(ValueLayout.JAVA_BYTE, byteIndex, (byte) (existing | (1 << (i & 7))));
            }
        }
        return new Mask.BitmapMask(bits, n);
    }

    private static Predicate randomPredicate(Random random, Array array, int depth) {
        boolean floating = array.dtype() instanceof DType.Primitive prim && prim.ptype().isFloating();
        int choice = depth >= 2 ? random.nextInt(6) : random.nextInt(8);
        return switch (choice) {
            case 0 -> new Predicate.Eq(constant(random, floating));
            case 1 -> new Predicate.Lt(constant(random, floating));
            case 2 -> new Predicate.Gt(constant(random, floating));
            case 3 -> new Predicate.Between(constant(random, floating), constant(random, floating));
            case 4 -> new Predicate.IsNull();
            case 5 -> new Predicate.IsNotNull();
            case 6 -> new Predicate.And(randomPredicate(random, array, depth + 1),
                    randomPredicate(random, array, depth + 1));
            default -> new Predicate.Or(randomPredicate(random, array, depth + 1),
                    randomPredicate(random, array, depth + 1));
        };
    }

    private static Comparable<?> constant(Random random, boolean floating) {
        if (floating) {
            return randomDouble(random);
        }
        return switch (random.nextInt(6)) {
            case 0 -> 0L;
            case 1 -> Long.MIN_VALUE;
            case 2 -> Long.MAX_VALUE;
            case 3 -> -1L;
            default -> (long) (random.nextInt(2000) - 1000);
        };
    }

    private static Array longColumn(DType dtype, long[] values, boolean[] valid) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        int n = values.length;
        Array child = switch (ptype) {
            case I8, U8 -> {
                MemorySegment seg = ARENA.allocate(Math.max(1, n));
                for (int i = 0; i < n; i++) {
                    seg.set(ValueLayout.JAVA_BYTE, i, (byte) values[i]);
                }
                yield new MaterializedByteArray(dtype.withNullable(false), n, seg);
            }
            case I16, U16 -> {
                MemorySegment seg = ARENA.allocate(Math.max(2L, n * 2L), 2);
                for (int i = 0; i < n; i++) {
                    seg.setAtIndex(PTypeIO.LE_SHORT, i, (short) values[i]);
                }
                yield new MaterializedShortArray(dtype.withNullable(false), n, seg);
            }
            case I32, U32 -> {
                MemorySegment seg = ARENA.allocate(Math.max(4L, n * 4L), 4);
                for (int i = 0; i < n; i++) {
                    seg.setAtIndex(PTypeIO.LE_INT, i, (int) values[i]);
                }
                yield new MaterializedIntArray(dtype.withNullable(false), n, seg);
            }
            default -> {
                MemorySegment seg = ARENA.allocate(Math.max(8L, n * 8L), 8);
                for (int i = 0; i < n; i++) {
                    seg.setAtIndex(PTypeIO.LE_LONG, i, values[i]);
                }
                yield new MaterializedLongArray(dtype.withNullable(false), n, seg);
            }
        };
        return wrap(child, valid);
    }

    private static Array doubleColumn(DType dtype, double[] values, boolean[] valid) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        int n = values.length;
        Array child;
        if (ptype == PType.F32) {
            MemorySegment seg = ARENA.allocate(Math.max(4L, n * 4L), 4);
            for (int i = 0; i < n; i++) {
                seg.setAtIndex(PTypeIO.LE_FLOAT, i, (float) values[i]);
            }
            child = new MaterializedFloatArray(dtype.withNullable(false), n, seg);
        } else {
            MemorySegment seg = ARENA.allocate(Math.max(8L, n * 8L), 8);
            for (int i = 0; i < n; i++) {
                seg.setAtIndex(PTypeIO.LE_DOUBLE, i, values[i]);
            }
            child = new MaterializedDoubleArray(dtype.withNullable(false), n, seg);
        }
        return wrap(child, valid);
    }

    private static Array wrap(Array child, boolean[] valid) {
        if (valid == null) {
            return child;
        }
        return new MaskedArray(child, ComputeArrays.boolArray(ARENA, valid));
    }
}
