package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.writer.ColumnEncoding;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// F16 against vortex-jni (#515), in both directions. Java had no F16 case in the constant, dict,
/// rle and sparse encodings, and wrote F16 min/max statistics as `f32` scalars, which Rust rejects
/// ("expected F32 dtype for F32Value, got f16"), so no F16 file Java wrote could be read by Rust.
/// Every shape is compared as raw half-precision bits, so a NaN payload or a `-0.0` that went
/// through `float` fails.
class Float16InteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final Schema ARROW_SCHEMA = new Schema(List.of(
            Field.notNullable("v", new ArrowType.FloatingPoint(FloatingPointPrecision.HALF))));
    private static final ColumnName COLUMN = ColumnName.of("v");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COLUMN), List.of(DType.F16), false);
    private static final int ROWS = 3000;

    static {
        NativeLoader.loadJni();
    }

    private static Map<String, short[]> shapes() {
        Random random = new Random(515);
        Map<String, IntUnaryOperator> generators = new LinkedHashMap<>();
        generators.put("constant 1.5", i -> bits(1.5f));
        generators.put("constant NaN payload", i -> 0x7C01);
        generators.put("mostly one value", i -> i % 50 == 0 ? bits(random.nextInt(100)) : bits(1.5f));
        generators.put("runs", i -> bits((i / 50) % 7));
        generators.put("low cardinality", i -> bits(random.nextInt(5)));
        generators.put("signed zeros", i -> i % 2 == 0 ? 0x0000 : 0x8000);
        generators.put("ramp", i -> bits(i % 2048));
        generators.put("random", i -> bits(random.nextFloat() * 100));
        Map<String, short[]> result = new LinkedHashMap<>();
        generators.forEach((name, generator) -> {
            short[] data = new short[ROWS];
            for (int i = 0; i < ROWS; i++) {
                data[i] = (short) generator.applyAsInt(i);
            }
            result.put(name, data);
        });
        return result;
    }

    static Stream<Arguments> shapeArguments() {
        return shapes().entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    /// Each encoding forced onto the shape it exists for, plus the default cascade on every shape:
    /// the forced runs prove the file really holds that encoding, not a canonical fallback.
    static Stream<Arguments> javaWritesCases() {
        Map<String, short[]> shapes = shapes();
        List<Arguments> cases = new ArrayList<>();
        shapes.forEach((shape, data) -> cases.add(Arguments.of("default cascade / " + shape, WriteOptions.defaults(), null, data)));
        cases.add(forced(EncodingId.VORTEX_CONSTANT, "constant 1.5", shapes));
        cases.add(forced(EncodingId.VORTEX_CONSTANT, "constant NaN payload", shapes));
        cases.add(forced(EncodingId.VORTEX_DICT, "low cardinality", shapes));
        cases.add(forced(EncodingId.VORTEX_DICT, "runs", shapes));
        cases.add(forced(EncodingId.FASTLANES_RLE, "runs", shapes));
        cases.add(forced(EncodingId.FASTLANES_RLE, "signed zeros", shapes));
        cases.add(forced(EncodingId.VORTEX_SPARSE, "mostly one value", shapes));
        // Pco is the one encoding Rust runs on half-precision floats that Java lacked (compact preset)
        cases.add(forced(EncodingId.VORTEX_PCO, "random", shapes));
        cases.add(forced(EncodingId.VORTEX_PCO, "low cardinality", shapes));
        cases.add(forced(EncodingId.VORTEX_PCO, "signed zeros", shapes));
        cases.add(forced(EncodingId.VORTEX_PCO, "ramp", shapes));
        shapes.forEach((shape, data) -> cases.add(Arguments.of("compact cascade / " + shape,
                WriteOptions.defaults().withCompact(true), null, data)));
        return cases.stream();
    }

    private static Arguments forced(EncodingId id, String shape, Map<String, short[]> shapes) {
        WriteOptions options = WriteOptions.defaults().withoutEditions()
                .withColumnEncoding(COLUMN, ColumnEncoding.candidates(id));
        return Arguments.of(id + " / " + shape, options, id.toString(), shapes.get(shape));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaWritesCases")
    void javaWriter_jniReader_keepsEveryHalfBit(String label, WriteOptions options, String expectedEncoding,
                                               short[] data, @TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_f16.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = io.github.dfa1.vortex.writer.VortexWriter.create(ch, SCHEMA, options)) {
            sut.writeChunk(Map.of(COLUMN, data));
        }

        // When
        short[] result = readWithJni(file);

        // Then
        assertThat(result).as(label).containsExactly(data);
        if (expectedEncoding != null) {
            try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
                assertThat(VortexInspector.inspect(reader)).as("%s: the file must hold the forced encoding", label)
                        .contains(expectedEncoding);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapeArguments")
    void jniWriter_javaReader_keepsEveryHalfBit(String shape, short[] data, @TempDir Path tmp) throws IOException {
        // Given Rust's compressor picks whatever encoding it likes for the shape
        Path file = tmp.resolve("jni_f16.vtx");
        writeWithJni(file, data);

        // When
        short[] result = readWithJava(file);

        // Then
        assertThat(result).as(shape).containsExactly(data);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1023, 1024, 1025})
    void jniWriter_javaReader_constantColumnOfAnyLength(int rows, @TempDir Path tmp) throws IOException {
        // Given a constant column, which Rust writes as vortex.constant, at lengths around the
        // 1024-row FastLanes chunk
        short[] data = new short[rows];
        java.util.Arrays.fill(data, Float.floatToFloat16(1.5f));
        Path file = tmp.resolve("jni_f16_constant.vtx");
        writeWithJni(file, data);

        // When
        short[] result = readWithJava(file);

        // Then
        assertThat(result).containsExactly(data);
    }

    private static void writeWithJni(Path file, short[] data) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, ARROW_SCHEMA, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(ARROW_SCHEMA, ALLOCATOR)) {
            Float2Vector vec = (Float2Vector) root.getVector("v");
            vec.allocateNew(data.length);
            for (int i = 0; i < data.length; i++) {
                vec.setSafe(i, data[i]);
            }
            root.setRowCount(data.length);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    private static short[] readWithJava(Path file) throws IOException {
        List<Short> bits = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             Arena arena = Arena.ofConfined()) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                Float16Array column = chunk.column(COLUMN);
                MemorySegment segment = column.materialize(arena);
                for (long i = 0; i < column.length(); i++) {
                    bits.add(segment.getAtIndex(VortexFormat.LE_SHORT, i));
                }
            });
        }
        return toArray(bits);
    }

    private static short[] readWithJni(Path file) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions options = ScanOptions.builder()
                .projection(Expression.select(new String[]{"v"}, Expression.root()))
                .build();
        List<Short> bits = new ArrayList<>();
        DataSource source = DataSource.open(SESSION, uri);
        Scan scan = source.scan(options);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float2Vector vec = (Float2Vector) root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        bits.add(vec.get(i));
                    }
                }
            }
        }
        return toArray(bits);
    }

    private static short[] toArray(List<Short> bits) {
        short[] result = new short[bits.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = bits.get(i);
        }
        return result;
    }

    private static int bits(float value) {
        return Short.toUnsignedInt(Float.floatToFloat16(value));
    }
}
