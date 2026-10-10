package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

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
        chunks.add(sut.finish());

        // Then — 2 full chunks, then the odd batch left over at the end, rows in order
        assertThat(chunks).extracting(c -> ((long[]) c).length).containsExactly(131_072, 131_072, 65_536);
        assertThat(concat(chunks)).containsExactly(sequence(0, 5 * 65_536));
    }

    @Test
    void largeBatch_splitsAtOneMegabyteOfWholeBlocks() {
        // Given — one 300 000-row batch: Rust slices it into 8192-row pieces and emits as soon as
        // 1 MB is pending, so a big batch never becomes one big chunk
        var sut = new Repartitioner(I64);

        // When
        List<Object> chunks = new ArrayList<>(sut.add(sequence(0, 300_000), 300_000));
        chunks.add(sut.finish());

        // Then
        assertThat(chunks).extracting(c -> ((long[]) c).length).containsExactly(131_072, 131_072, 37_856);
        assertThat(concat(chunks)).containsExactly(sequence(0, 300_000));
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
        chunks.add(sut.finish());

        // Then
        assertThat(chunks).extracting(c -> ((long[]) c).length)
                .allSatisfy(n -> assertThat(n).isPositive())
                .satisfies(ns -> assertThat(ns.subList(0, ns.size() - 1)).allSatisfy(n -> assertThat(n % 8192).isZero()));
        assertThat(concat(chunks)).containsExactly(sequence(0, 300_000));
    }

    @Test
    void nullableBatches_keepValidityAlignedWithValues() {
        // Given — a nullable batch mixed with a plain one: the coalesced chunk needs one validity
        var sut = new Repartitioner(new DType.Primitive(PType.I64, true));
        sut.add(new NullableData(new long[]{1, 0, 3}, new boolean[]{true, false, true}), 3);
        sut.add(new long[]{4, 5}, 2);

        // When
        Object result = sut.finish();

        // Then
        assertThat(result).isInstanceOfSatisfying(NullableData.class, nd -> {
            assertThat((long[]) nd.values()).containsExactly(1, 0, 3, 4, 5);
            assertThat(nd.validity()).containsExactly(true, false, true, true, true);
        });
    }

    @Test
    void finish_withNothingPending_isNull() {
        // Given
        var sut = new Repartitioner(I64);

        // When
        Object result = sut.finish();

        // Then
        assertThat(result).isNull();
    }

    private static long[] sequence(long from, int n) {
        return LongStream.range(from, from + n).toArray();
    }

    private static long[] concat(List<Object> chunks) {
        return chunks.stream().flatMapToLong(c -> LongStream.of((long[]) c)).toArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ascii", "caf\u00e9", "\u20ac uro", "\uD83D\uDE00 emoji", "lone \uD83D high", "lone \uDE00 low", "\uD83D", "\uDE00\uD83D"})
    void utf8LengthMatchesTheEncoder(String text) {
        // Given: ASCII, 2/3/4-byte characters and unpaired surrogates, which getBytes encodes as '?'
        int expected = text.getBytes(StandardCharsets.UTF_8).length;

        // When
        int result = Repartitioner.utf8Length(text);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void addWithPresizedPiecesCutsTheSameChunks() {
        // Given: strings long enough that a batch crosses the 1 MiB block target, so cuts depend on the sizes
        String[] batch = new String[50_000];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = "x".repeat(10 + i % 40) + (i % 7 == 0 ? "\u20ac" : "");
        }
        Repartitioner sizer = new Repartitioner(DType.UTF8);
        Repartitioner direct = new Repartitioner(DType.UTF8);
        Repartitioner presized = new Repartitioner(DType.UTF8);
        long[] sizes = sizer.sizePieces(batch, batch.length);

        // When
        List<Object> expected = direct.add(batch, batch.length);
        List<Object> result = presized.add(batch, batch.length, sizes);

        // Then
        assertThat(result).hasSameSizeAs(expected);
        for (int i = 0; i < result.size(); i++) {
            assertThat((String[]) result.get(i)).containsExactly((String[]) expected.get(i));
        }
        assertThat((String[]) presized.finish()).containsExactly((String[]) direct.finish());
    }
}
