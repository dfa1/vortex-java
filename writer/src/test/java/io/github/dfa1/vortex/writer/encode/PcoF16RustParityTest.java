package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.PcoEncodingDecoder;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// F16 against pco 1.0.1 (#515): `vortex.pco` had no F16 case, so Rust's compact preset could write
/// a half-precision column Java could neither read nor write. The fixtures under `pco/*-f16.*` are
/// pco 1.0.1's output for 9000 halves with Vortex's settings (level 8, 2^18-value chunks,
/// `EqualPagesUpTo(8192)`), with the raw input (`.raw`, little-endian halves) and the byte layout
/// (`.layout`: the chunk meta length, then each page's value count and length) next to them.
///
/// Encoding must match Rust byte for byte where Java makes the same choices (Classic). Decoding
/// must read every shape Rust writes, including FloatQuant, which Java never writes: the `quant`
/// shape is the half-precision case of that mode. The `ramp` shape (consecutive delta) is decoded
/// only: Java's 16-bit delta binning writes 2474 bytes where Rust writes 192, for `u16` data as
/// well, so it is not an F16 gap. FloatMult is not covered: pco 1.0.1 never chose it for any half
/// shape tried.
class PcoF16RustParityTest {

    private static final DType F16 = DType.F16;
    private final PcoEncodingEncoder encoder = new PcoEncodingEncoder();
    private final PcoEncodingDecoder decoder = new PcoEncodingDecoder();

    @ParameterizedTest
    @ValueSource(strings = {"random", "tenths", "lowcard"})
    void encodesTheBytesRustPcoWrites(String shape) throws IOException {
        // Given the Classic shapes without delta; Java matches Rust on these
        short[] values = raw(shape);
        byte[] expected = resource(shape + "-f16.pco");

        // When
        byte[] result;
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult encoded = encoder.encode(F16, values, EncodeContext.of(arena, WriteRegistry.loadAll()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (MemorySegment buffer : encoded.buffers()) {
                out.write(buffer.toArray(ValueLayout.JAVA_BYTE));
            }
            result = out.toByteArray();
        }

        // Then
        assertThat(result).containsExactly(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"random", "ramp", "tenths", "lowcard", "quant"})
    void decodesTheBytesRustPcoWrites(String shape) throws IOException {
        // Given Rust's chunk meta and pages for the shape, under the node and metadata Java's own
        // encoder writes for the same 4500 + 4500 page split
        short[] values = raw(shape);
        List<MemorySegment> rustBuffers = splitRustBuffers(shape);
        try (Arena arena = Arena.ofConfined()) {
            EncodeResult javaEncoded = encoder.encode(F16, values, EncodeContext.of(arena, WriteRegistry.loadAll()));
            ArrayNode root = new ArrayNode(javaEncoded.rootNode().encodingId(), javaEncoded.rootNode().metadata(),
                    new ArrayNode[0], javaEncoded.rootNode().bufferIndices());
            DecodeContext ctx = new DecodeContext(root, F16, values.length,
                    rustBuffers.toArray(new MemorySegment[0]), ReadRegistry.empty(), arena);

            // When
            Float16Array result = (Float16Array) decoder.decode(ctx);

            // Then every half matches, bit for bit
            MemorySegment bits = result.materialize(arena);
            assertThat(result.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(bits.getAtIndex(VortexFormat.LE_SHORT, i)).as("%s row %d", shape, i).isEqualTo(values[i]);
            }
        }
    }

    /// The fixture is all chunk metas, then all pages; the layout file gives the lengths.
    private static List<MemorySegment> splitRustBuffers(String shape) throws IOException {
        byte[] bytes = resource(shape + "-f16.pco");
        List<Integer> lengths = new ArrayList<>();
        List<Integer> pageLengths = new ArrayList<>();
        for (String line : new String(resource(shape + "-f16.layout"), StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts[0].equals("chunk")) {
                lengths.add(Integer.parseInt(parts[1]));
            } else if (parts[0].equals("page")) {
                pageLengths.add(Integer.parseInt(parts[2]));
            }
        }
        lengths.addAll(pageLengths);
        List<MemorySegment> buffers = new ArrayList<>();
        int at = 0;
        for (int length : lengths) {
            buffers.add(MemorySegment.ofArray(java.util.Arrays.copyOfRange(bytes, at, at + length)));
            at += length;
        }
        assertThat(at).as("layout covers the whole fixture").isEqualTo(bytes.length);
        return buffers;
    }

    private static short[] raw(String shape) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(resource(shape + "-f16.raw")).order(ByteOrder.LITTLE_ENDIAN);
        short[] values = new short[bytes.remaining() / 2];
        bytes.asShortBuffer().get(values);
        return values;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = PcoF16RustParityTest.class.getResourceAsStream("/pco/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return in.readAllBytes();
        }
    }
}
