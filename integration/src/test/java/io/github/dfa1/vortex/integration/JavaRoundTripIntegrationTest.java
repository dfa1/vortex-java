package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.compute.FastLanes;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.WriteRegistry;
import io.github.dfa1.vortex.writer.encode.EncodeContext;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PatchedEncodingEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// Java writer → Java reader round-trips for encodings whose Java *decode* has no other end-to-end
/// cover. This is a real cross-module integration test either way: it drives the writer's encode,
/// the on-disk file format, and the reader's decode end to end.
///
/// Two reasons land a case here:
/// - the bundled `vortex-jni` build cannot read the encoding back, so there is no Java→Rust test.
///   `vortex.patched` is this case — the JNI reader rejects a standalone patched array with
///   "Unknown encoding: vortex.patched".
/// - Java→Rust cover exists but only exercises the *encoder*. `fastlanes.delta` is this case:
///   `JavaWritesRustReadsIntegrationTest#javaWriter_rustReader_delta_i64` proves what Java writes
///   is readable, and says nothing about `DeltaEncodingDecoder`.
class JavaRoundTripIntegrationTest {

    private static final DType.Struct I32_SCHEMA = new DType.Struct(
            List.of(ColumnName.of("v")),
            List.of(DType.I32),
            false);

    @Test
    void patched_i32_javaWriteJavaRead(@TempDir Path tmp) throws IOException {
        // Given — most values fit ~6 bits; four large outliers (< n/20) force the patch path
        // (base inner array + patch index / patch value children). Non-negative, so the bit width
        // is computed from the value itself rather than sign-extension.
        Path file = tmp.resolve("java_patched_i32.vtx");
        int[] data = new int[120];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 50;
        }
        data[7] = 5_000_000;
        data[23] = 6_000_000;
        data[61] = 7_000_000;
        data[88] = 8_000_000;
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults().withEdition(Editions.UNSTABLE_2026_04_0),
                     List.of(new PatchedEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("v"), data));
        }

        // Then — the Java reader reconstructs base values + patched outliers exactly
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    /// `fastlanes.delta` decode across every width it accepts, over three FastLanes chunks.
    ///
    /// The unit tests reach the decoder only with I64 and single-element (constant) children, so
    /// nothing covered the per-width read and write paths, and nothing covered more than one
    /// chunk — which is where the chunk-window arithmetic lives. Values are full-width random
    /// bit patterns, not a monotonic ramp: the high bit is exactly where a read that
    /// sign-extends and one that zero-extends diverge, and delta round-trips any values at all
    /// since encode and decode both wrap modulo the type width.
    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64"})
    void delta_javaWriteJavaRead(PType ptype, @TempDir Path tmp) throws IOException {
        // Given — 2500 rows is three 1024-element chunks, the last one padded.
        long mask = FastLanes.lowMask(ptype.bits());
        Random rng = new Random(338);
        long[] expected = new long[2500];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = rng.nextLong() & mask;
        }
        DType.Struct schema = new DType.Struct(List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(ptype, false)), false);
        Path file = tmp.resolve("java_delta_" + ptype + ".vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema,
                     WriteOptions.defaults().withEdition(Editions.UNSTABLE_2025_05_0),
                     List.of(new DeltaEncodingEncoder()))) {
            sut.writeChunk(Map.of(ColumnName.of("v"), narrow(expected, ptype)));
        }

        // Then — the encoding is asserted too, so a writer that quietly stopped choosing delta
        // would fail here rather than leave the decoder untested
        try (var reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(InspectorTree.build(reader).usedEncodings()).contains("fastlanes.delta");
        }
        // compared as stored bit patterns, so signed and unsigned widths assert alike
        assertThat(readColumnBits(file, "v", mask)).containsExactly(expected);
    }

    /// `fastlanes.delta`'s `offset` metadata — which makes a decode start partway into the
    /// reconstructed elements — has no round-trip cover, because the Java writer always emits 0;
    /// a non-zero offset only ever arrives on a Rust-written sliced array. So this drives the
    /// decoder directly over encoder-produced children instead of through a file, and asserts
    /// the window is exactly the corresponding slice of the full decode. The window arithmetic
    /// (which chunks to reconstruct, and where each lands in the output) is the part of decode
    /// that only a non-zero offset reaches.
    @Test
    void delta_offsetWindowIsTheSliceOfTheFullDecode() {
        // Given — 2500 rows, so the encoder pads to three chunks
        DType dtype = new DType.Primitive(PType.I64, false);
        Random rng = new Random(3381);
        long[] data = new long[2500];
        for (int i = 0; i < data.length; i++) {
            data[i] = rng.nextLong();
        }
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult encoded = new DeltaEncodingEncoder().encode(dtype, data,
                    EncodeContext.of(arena, WriteRegistry.builder().registerDefaults().build()));
            long padded = 3L * FastLanes.CHUNK;
            long[] full = decodeDelta(encoded, dtype, padded, 0, padded, arena);

            // When — a window opening inside chunk 0 and closing inside chunk 2
            long[] result = decodeDelta(encoded, dtype, padded, 700, 1500, arena);

            // Then
            assertThat(result).containsExactly(Arrays.copyOfRange(full, 700, 2200));
        }
    }

    /// Decodes `encoded` as a `fastlanes.delta` array over the given window, bypassing the file
    /// format so the `offset` the writer never emits can be set.
    ///
    /// @param encoded   the encoder's output (bases buffer, deltas buffer)
    /// @param dtype     logical element type
    /// @param deltasLen number of reconstructed elements the chunks cover
    /// @param offset    absolute index the first returned row maps to
    /// @param rowCount  number of rows to decode
    /// @param arena     allocator for the decoded segment
    /// @return the decoded values
    private static long[] decodeDelta(EncodeResult encoded, DType dtype, long deltasLen,
            int offset, long rowCount, Arena arena) {
        MemorySegment meta = MemorySegment.ofArray(new ProtoDeltaMetadata(deltasLen, offset).encode());
        ArrayNode bases = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode deltas = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_DELTA, meta,
                new ArrayNode[]{bases, deltas}, new int[0]);
        DecodeContext ctx = new DecodeContext(node, dtype, rowCount,
                encoded.buffers().toArray(new MemorySegment[0]), ReadRegistry.loadAll(), arena);
        LongArray decoded = (LongArray) new DeltaEncodingDecoder().decode(ctx);
        long[] out = new long[(int) decoded.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = decoded.getLong(i);
        }
        return out;
    }

    /// Narrows logical values to the Java array type the writer expects for `ptype`.
    private static Object narrow(long[] values, PType ptype) {
        return switch (ptype) {
            case I8, U8 -> {
                byte[] out = new byte[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (byte) values[i];
                }
                yield out;
            }
            case I16, U16 -> {
                short[] out = new short[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (short) values[i];
                }
                yield out;
            }
            case I32, U32 -> {
                int[] out = new int[values.length];
                for (int i = 0; i < values.length; i++) {
                    out[i] = (int) values[i];
                }
                yield out;
            }
            default -> values.clone();
        };
    }

    /// Reads a primitive column back as raw bit patterns, masked to the type's width so a
    /// sign-extending accessor and a zero-extending one compare equal.
    private static long[] readColumnBits(Path file, String column, long mask) throws IOException {
        var out = new ArrayList<Long>();
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.columns(column))) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(column);
                for (long i = 0; i < arr.length(); i++) {
                    out.add(switch (arr) {
                        case ByteArray a -> a.getByte(i) & mask;
                        case ShortArray a -> a.getShort(i) & mask;
                        case IntArray a -> a.getInt(i) & mask;
                        case LongArray a -> a.getLong(i) & mask;
                        default -> throw new IllegalStateException("unexpected array " + arr.getClass());
                    });
                }
            });
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    @SuppressWarnings("SameParameterValue")
    private static int[] readIntColumn(Path file, String column) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.columns(column))) {
            var ints = new ArrayList<Integer>();
            iter.forEachRemaining(c -> {
                IntArray arr = c.column(column);
                for (long i = 0; i < arr.length(); i++) {
                    ints.add(arr.getInt(i));
                }
            });
            return ints.stream().mapToInt(Integer::intValue).toArray();
        }
    }
}
