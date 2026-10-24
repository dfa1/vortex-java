package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RepartitionerTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);

    @Test
    void smallBatches_coalesceIntoOneMegabyteChunks() {
        // Given — 65 536-row I64 batches (512 KiB each), the taxi writer's batch size. Rust emits once
        // 1 MB is pending: two batches, 131 072 rows, which is what vortex-jni's taxi chunks hold.
        var sut = new Repartitioner(I64);
        List<Object> chunks = new ArrayList<>();

        // When
        for (int b = 0; b < 5; b++) {
            chunks.addAll(sut.add(sequence(b * 65_536L, 65_536), 65_536));
        }
        chunks.addAll(sut.finish());

        // Then — 2 full chunks, then the odd batch left over at the end, rows in order
        assertThat(chunks).extracting(c -> ((long[]) c).length).containsExactly(131_072, 131_072, 65_536);
        assertThat(concat(chunks)).isEqualTo(sequence(0, 5 * 65_536));
    }

    @Test
    void largeBatch_splitsAtOneMegabyteOfWholeBlocks() {
        // Given — one 300 000-row batch: Rust slices it into 8192-row pieces and emits as soon as
        // 1 MB is pending, so a big batch never becomes one big chunk
        var sut = new Repartitioner(I64);

        // When
        List<Object> chunks = new ArrayList<>(sut.add(sequence(0, 300_000), 300_000));
        chunks.addAll(sut.finish());

        // Then
        assertThat(chunks).extracting(c -> ((long[]) c).length).containsExactly(131_072, 131_072, 37_856);
        assertThat(concat(chunks)).isEqualTo(sequence(0, 300_000));
    }

    @Test
    void unalignedBatches_splitThePieceStraddlingTheBlockBoundary() {
        // Given — 100 000-row batches leave a partial 8192 piece at each batch end; a chunk must
        // still be a whole number of blocks, the straddling piece's tail staying pending
        var sut = new Repartitioner(I64);
        List<Object> chunks = new ArrayList<>();

        // When
        for (int b = 0; b < 3; b++) {
            chunks.addAll(sut.add(sequence(b * 100_000L, 100_000), 100_000));
        }
        chunks.addAll(sut.finish());

        // Then
        assertThat(chunks).extracting(c -> ((long[]) c).length)
                .allSatisfy(n -> assertThat(n).isPositive())
                .satisfies(ns -> assertThat(ns.subList(0, ns.size() - 1)).allSatisfy(n -> assertThat(n % 8192).isZero()));
        assertThat(concat(chunks)).isEqualTo(sequence(0, 300_000));
    }

    @Test
    void nullableBatches_keepValidityAlignedWithValues() {
        // Given — a nullable batch mixed with a plain one: the coalesced chunk needs one validity
        var sut = new Repartitioner(new DType.Primitive(PType.I64, true));
        sut.add(new NullableData(new long[]{1, 0, 3}, new boolean[]{true, false, true}), 3);
        sut.add(new long[]{4, 5}, 2);

        // When
        Object result = sut.finish().getFirst();

        // Then
        assertThat(result).isInstanceOfSatisfying(NullableData.class, nd -> {
            assertThat((long[]) nd.values()).containsExactly(1, 0, 3, 4, 5);
            assertThat(nd.validity()).containsExactly(true, false, true, true, true);
        });
    }

    // Each batch's first chunk is the fewest 8192-row blocks reaching 1 MiB by Rust 0.89's exact size
    // (vortex-data/vortex#10445). The inlined, all-valid and all-null cases cut elsewhere under the
    // rule before it, which counted every string byte and a bitmap on every nullable block.
    static Stream<Arguments> exactSizeCases() {
        String[] inlined = new String[600_000];
        Arrays.fill(inlined, "twelve bytes");
        String[] outOfLine = new String[600_000];
        Arrays.fill(outOfLine, "thirteen byte");
        boolean[] allValid = new boolean[600_000];
        Arrays.fill(allValid, true);
        boolean[] nullPerBlock = allValid.clone();
        for (int i = 0; i < nullPerBlock.length; i += 8192) {
            nullPerBlock[i] = false;
        }
        byte[][] inlinedBinary = new byte[600_000][];
        Arrays.fill(inlinedBinary, new byte[12]);
        byte[][] outOfLineBinary = new byte[600_000][];
        Arrays.fill(outOfLineBinary, new byte[13]);
        DType nullableI16 = new DType.Primitive(PType.I16, true);
        short[] i16 = new short[600_000];
        // 1024 B of bool values a block: the validity's cost decides the cut. All null at 2 B a
        // block needs 1023 blocks; 0 B would need 1024, a bitmap 512.
        boolean[] bools = new boolean[1024 * 8192 + 1];
        return Stream.of(
                // a value of at most 12 bytes lives in its 16-byte view: 16 B/row
                Arguments.of("inlined strings", DType.UTF8, inlined, 65_536),
                // 16 + 13 = 29 B/row: 5 blocks are 1 187 840 B, 4 fall short
                Arguments.of("out-of-line strings", DType.UTF8, outOfLine, 40_960),
                Arguments.of("inlined binaries", new DType.Binary(false), inlinedBinary, 65_536),
                Arguments.of("out-of-line binaries", new DType.Binary(false), outOfLineBinary, 40_960),
                // an all-valid block has no bitmap: 2 B/row
                Arguments.of("all valid", nullableI16, new NullableData(i16, allValid), 524_288),
                // a block with a null keeps its bitmap: 2.125 B/row, 61 blocks
                Arguments.of("a null per block", nullableI16, new NullableData(i16, nullPerBlock), 499_712),
                // an all-null block costs 2 bytes, a constant false's scalar
                Arguments.of("all null", new DType.Bool(true), new NullableData(bools, bools), 1023 * 8192));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exactSizeCases")
    void blocksAreMeasuredByRustsExactSize(String name, DType dtype, Object batch, int expectedRows) {
        // Given
        var sut = new Repartitioner(dtype);

        // When
        List<Object> result = sut.add(batch, rows(batch));

        // Then
        assertThat(result).first().satisfies(chunk -> assertThat(rows(chunk)).isEqualTo(expectedRows));
    }

    @Test
    void finish_withNothingPending_isEmpty() {
        // Given
        var sut = new Repartitioner(I64);

        // When
        List<Object> result = sut.finish();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void finish_partialBlockToppingUpToOneMegabyte_isItsOwnChunk() {
        // Given — 3 whole blocks of inlined strings pending (384 KiB) and 100 rows of 10 000-byte
        // strings: the partial block tips the total over 1 MiB, so Rust's coalescer emits the whole
        // blocks, then flushes the rest on its own
        var sut = new Repartitioner(DType.UTF8);
        String[] blocks = new String[3 * 8192];
        Arrays.fill(blocks, "short");
        String[] tail = new String[100];
        Arrays.fill(tail, "x".repeat(10_000));
        sut.add(blocks, blocks.length);
        sut.add(tail, tail.length);

        // When
        List<Object> result = sut.finish();

        // Then
        assertThat(result).extracting(RepartitionerTest::rows).containsExactly(3 * 8192, 100);
    }

    @Test
    void blocksAlignToTheColumnsFirstRow_notToEachBatch() {
        // Given — 5000-row batches, alternately all null and all valid. Rust re-cuts the stream
        // into 8192-row blocks before measuring them, so every block mixes both and carries a
        // bitmap (2.125 B/row: 61 blocks reach 1 MiB). Measured per batch, the all-null and
        // all-valid pieces would carry none, and 64 blocks would be needed.
        var sut = new Repartitioner(new DType.Primitive(PType.I16, true));
        boolean[] allNull = new boolean[5000];
        boolean[] allValid = new boolean[5000];
        Arrays.fill(allValid, true);
        List<Object> result = new ArrayList<>();

        // When
        for (int b = 0; b < 120; b++) {
            result.addAll(sut.add(new NullableData(new short[5000], b % 2 == 0 ? allNull : allValid), 5000));
        }

        // Then
        assertThat(result).first().satisfies(chunk -> assertThat(rows(chunk)).isEqualTo(61 * 8192));
    }

    private static long[] sequence(long from, int n) {
        return LongStream.range(from, from + n).toArray();
    }

    private static int rows(Object chunk) {
        Object values = chunk instanceof NullableData nd ? nd.values() : chunk;
        return java.lang.reflect.Array.getLength(values);
    }

    private static long[] concat(List<Object> chunks) {
        return chunks.stream().flatMapToLong(c -> LongStream.of((long[]) c)).toArray();
    }

    @Test
    void addWithPresizedPiecesCutsTheSameChunks() {
        // Given: strings long enough that a batch crosses the 1 MiB block target, so cuts depend on the sizes
        String[] batch = new String[50_000];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = "x".repeat(10 + i % 40) + (i % 7 == 0 ? "\u20ac" : "");
        }
        // and a 3000-row batch ahead of it, so its pieces start mid-block
        String[] head = new String[3000];
        Arrays.fill(head, "a string longer than twelve bytes");
        Repartitioner direct = new Repartitioner(DType.UTF8);
        Repartitioner presized = new Repartitioner(DType.UTF8);
        direct.add(head, head.length);
        presized.add(head, head.length);
        long[] sizes = Repartitioner.sizePieces(batch, batch.length, presized.rows());
        // the first piece only fills the head's block: rows 0..5191
        long firstPiece = 0;
        for (int i = 0; i < 8192 - head.length; i++) {
            int length = batch[i].getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            firstPiece += length > 12 ? length : 0;
        }
        assertThat(sizes[0]).isEqualTo(firstPiece);

        // When
        List<Object> expected = direct.add(batch, batch.length);
        List<Object> result = presized.add(batch, batch.length, sizes);

        // Then
        assertThat(result).hasSameSizeAs(expected);
        for (int i = 0; i < result.size(); i++) {
            assertThat((String[]) result.get(i)).isEqualTo((String[]) expected.get(i));
        }
        assertThat(presized.finish().getFirst()).isEqualTo(direct.finish().getFirst());
    }
}
