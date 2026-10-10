package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Float16Array;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #515: F16 had no case in the constant, dict, rle and sparse encodings, so some F16 columns
/// could not be written and others were written but could not be read back. Every shape below is
/// written with the default and compact cascades and with each of those encodings (and Pco) forced, then compared as raw
/// half-precision bits, so a NaN payload rounded through `float` or a `-0.0` turned into `0.0`
/// fails too.
class Float16EncodingsRoundTripTest {

    private static final ColumnName COLUMN = ColumnName.of("h");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COLUMN),
            List.of(new DType.Primitive(PType.F16, false)), false);
    private static final int ROWS = 3000;

    @TempDir
    Path tmp;

    static Stream<Arguments> optionsAndShapes() {
        Map<String, WriteOptions> options = new java.util.LinkedHashMap<>();
        options.put("default cascade", WriteOptions.defaults());
        options.put("compact cascade", WriteOptions.defaults().withCompact(true));
        for (EncodingId id : List.of(EncodingId.VORTEX_CONSTANT, EncodingId.VORTEX_DICT,
                EncodingId.FASTLANES_RLE, EncodingId.VORTEX_SPARSE, EncodingId.VORTEX_PCO)) {
            options.put(id.toString(), WriteOptions.defaults().withoutEditions()
                    .withColumnEncoding(COLUMN, ColumnEncoding.candidates(id)));
        }
        Random random = new Random(515);
        Map<String, IntUnaryOperator> shapes = new java.util.LinkedHashMap<>();
        shapes.put("constant 1.5", i -> bits(1.5f));
        shapes.put("constant NaN payload", i -> 0x7C01);
        shapes.put("mostly one value", i -> i % 50 == 0 ? bits(random.nextInt(100)) : bits(1.5f));
        shapes.put("runs", i -> bits((i / 50) % 7));
        shapes.put("low cardinality", i -> bits(random.nextInt(5)));
        shapes.put("signed zeros", i -> i % 2 == 0 ? 0x0000 : 0x8000);
        shapes.put("ramp", i -> bits(i % 2048));
        shapes.put("random", i -> bits(random.nextFloat() * 100));
        List<Arguments> cases = new ArrayList<>();
        options.forEach((optionsName, writeOptions) -> shapes.forEach((shapeName, shape) -> {
            short[] data = new short[ROWS];
            for (int i = 0; i < ROWS; i++) {
                data[i] = (short) shape.applyAsInt(i);
            }
            cases.add(Arguments.of(optionsName + " / " + shapeName, writeOptions, data));
        }));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionsAndShapes")
    void writeThenRead_keepsEveryHalfBit(String label, WriteOptions options, short[] data) throws IOException {
        // Given
        Path file = tmp.resolve("f16.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, options)) {
            sut.writeChunk(Map.of(COLUMN, data));
        }

        // When
        List<Short> result = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file);
             Arena arena = Arena.ofConfined()) {
            reader.scan(ScanOptions.all()).forEachRemaining(chunk -> {
                Float16Array column = chunk.column(COLUMN);
                MemorySegment bits = column.materialize(arena);
                for (long i = 0; i < column.length(); i++) {
                    result.add(bits.getAtIndex(VortexFormat.LE_SHORT, i));
                }
            });
        }

        // Then
        assertThat(result).as(label).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) {
            assertThat(result.get(i)).as("%s row %d", label, i).isEqualTo(data[i]);
        }
    }

    private static int bits(float value) {
        return Short.toUnsignedInt(Float.floatToFloat16(value));
    }
}
