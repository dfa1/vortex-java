package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.writer.encode.AlpEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ColumnEncodingTest {

    private static final ColumnName PRICE = ColumnName.of("price");

    private static final ColumnEncoding ALP_CASCADE = ColumnEncoding.candidates(
            new AlpEncodingEncoder(), new FrameOfReferenceEncodingEncoder(), new BitpackedEncodingEncoder());

    @TempDir
    Path tmp;

    @Test
    void candidatesAreTheColumnsWholeEncodingSet() throws IOException {
        // Given fixed-precision prices: the default cascade would also try dict, ALP-RD, RLE, …;
        // with the override the column's tree may only use the candidates plus canonical storage
        double[] prices = prices(20_000, 7);
        WriteOptions options = WriteOptions.defaults().withZoneMaps(false).withColumnEncoding(PRICE, ALP_CASCADE);

        // When
        Path file = write(DType.F64, prices, options);

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(vf.footer().arraySpecs()).contains(EncodingId.VORTEX_ALP)
                    .isSubsetOf(EncodingId.VORTEX_ALP, EncodingId.FASTLANES_FOR, EncodingId.FASTLANES_BITPACKED,
                            EncodingId.VORTEX_PRIMITIVE, EncodingId.VORTEX_STRUCT);
            assertThat(readDoubles(vf)).containsExactly(prices);
        }
    }

    @Test
    void nullableColumnCascadesItsValuesOverTheCandidates() throws IOException {
        // Given a nullable column: its values cascade inside vortex.masked, which must see the
        // column's candidates rather than the writer's defaults
        double[] values = prices(1_000, 11);
        boolean[] validity = new boolean[values.length];
        for (int i = 0; i < validity.length; i++) {
            validity[i] = i % 3 != 0;
        }
        WriteOptions options = WriteOptions.defaults().withZoneMaps(false).withColumnEncoding(PRICE, ALP_CASCADE);

        // When
        Path file = write(DType.F64.withNullable(true), new NullableData(values, validity), options);

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(vf.footer().arraySpecs()).contains(EncodingId.VORTEX_ALP, EncodingId.VORTEX_MASKED);
            try (var iter = vf.scan(ScanOptions.all()); Chunk chunk = iter.next()) {
                assertThat(chunk.rowCount()).isEqualTo(values.length);
            }
        }
    }

    @Test
    void overriddenColumnIsLeftOutOfTheGlobalDictionary() throws IOException {
        // Given a low-cardinality column the global dictionary would claim by default
        long[] codes = new long[10_000];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = i % 4;
        }
        WriteOptions options = WriteOptions.defaults().withColumnEncoding(PRICE,
                ColumnEncoding.candidates(new BitpackedEncodingEncoder()));

        // When
        Path file = write(DType.I64, codes, options);

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(vf.footer().arraySpecs()).doesNotContain(EncodingId.VORTEX_DICT);
            assertThat(vf.layout().children().getFirst().toString()).doesNotContain("vortex.dict");
            List<Long> result = new ArrayList<>();
            try (var iter = vf.scan(ScanOptions.all())) {
                iter.forEachRemaining(c -> {
                    LongArray a = c.column("price");
                    for (long i = 0; i < a.length(); i++) {
                        result.add(a.getLong(i));
                    }
                });
            }
            assertThat(result).containsExactlyElementsOf(java.util.Arrays.stream(codes).boxed().toList());
        }
    }

    @Test
    void unknownColumnIsRejectedAtCreate() {
        // Given an override naming a column the schema does not have — a typo would otherwise
        // silently leave the real column on the default cascade
        WriteOptions options = WriteOptions.defaults().withColumnEncoding(ColumnName.of("prcie"), ALP_CASCADE);
        var schema = new DType.Struct(List.of(PRICE), List.of(DType.F64), false);

        // When / Then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> VortexWriter.create(FileChannel.open(tmp.resolve("x.vtx"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE), schema, options))
                .withMessageContaining("prcie");
    }

    @Test
    void emptyCandidateSetIsRejected() {
        // Given / When / Then
        assertThatIllegalArgumentException().isThrownBy(ColumnEncoding::candidates);
    }

    @Test
    void copyMethodsKeepTheOverride() {
        // Given
        WriteOptions base = WriteOptions.defaults().withColumnEncoding(PRICE, ALP_CASCADE);

        // When
        WriteOptions result = base.withZoneMaps(false).withGlobalDict(false).withoutEditions()
                .withGlobalDictMaxRetainedBytes(new io.github.dfa1.vortex.core.model.MemorySize(1));

        // Then
        assertThat(result.columnEncodings()).containsEntry(PRICE, ALP_CASCADE);
    }

    private Path write(DType dtype, Object data, WriteOptions options) {
        Path file = tmp.resolve("col.vtx");
        var schema = new DType.Struct(List.of(PRICE), List.of(dtype), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, options)) {
            sut.writeChunk(Map.of(PRICE, data));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return file;
    }

    private static double[] readDoubles(VortexReader vf) throws IOException {
        List<Double> out = new ArrayList<>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                DoubleArray a = c.column("price");
                for (long i = 0; i < a.length(); i++) {
                    out.add(a.getDouble(i));
                }
            });
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] prices(int n, long seed) {
        Random rng = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.round((50 + rng.nextDouble() * 100) * 100) / 100.0;
        }
        return out;
    }
}
