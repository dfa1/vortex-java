package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoOnPairMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.OnPairEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OnPairEncodingEncoderTest {

    private static final OnPairEncodingEncoder SUT = new OnPairEncodingEncoder();
    private static final OnPairEncodingDecoder DECODER = new OnPairEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    static Stream<Arguments> corpora() {
        // Seeded corpora: repetitive URL-like strings (merges must form), random bytes as Utf8-safe
        // ASCII (few merges), and edge rows (empty, a single byte, longer than the 16-byte token cap,
        // multi-byte UTF-8, all-empty column).
        Random random = new Random(7);
        String[] urls = IntStream.range(0, 2000)
                .mapToObj(i -> "https://example.com/item/" + random.nextInt(50) + "?ref=home")
                .toArray(String[]::new);
        String[] noise = IntStream.range(0, 500)
                .mapToObj(i -> random.ints(random.nextInt(30), 33, 127)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString())
                .toArray(String[]::new);
        String[] edges = {"", "a", "", "abcdefghijklmnopqrstuvwxyz0123456789", "città", "日本語テキスト", "a"};
        return Stream.of(
                Arguments.of("urls", urls),
                Arguments.of("noise", noise),
                Arguments.of("edges", edges),
                Arguments.of("allEmpty", new String[]{"", "", ""}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    void encode_roundTripsThroughTheDecoder(String name, String[] data) {
        // Given
        EncodeContext ctx = EncodeContext.ofDepth(0, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = SUT.encode(DType.UTF8, data, ctx);

        // Then
        VarBinArray decoded = (VarBinArray) decode(result, data.length);
        String[] actual = new String[data.length];
        for (int i = 0; i < data.length; i++) {
            actual[i] = decoded.getString(i);
        }
        assertThat(actual).containsExactly(data);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpora")
    void encode_dictionarySatisfiesRustInvariants(String name, String[] data) throws IOException {
        // Given — Rust's validate_safety rejects a dictionary that is unpadded or has empty/oversized
        // tokens; its search kernels additionally need sorted, unique, complete (all 256 bytes).
        EncodeContext ctx = EncodeContext.ofDepth(0, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = SUT.encode(DType.UTF8, data, ctx);

        // Then
        MemorySegment metaSeg = result.rootNode().metadata();
        var meta = ProtoOnPairMetadata.decode(metaSeg, 0, metaSeg.byteSize());
        byte[] dict = result.buffers().get(0).toArray(ValueLayout.JAVA_BYTE);
        PType offPType = PType.fromOrdinal(meta.dict_offsets_ptype().value());
        MemorySegment offSeg = result.buffers().get(1);
        int numTokens = meta.dict_size();
        long[] off = new long[numTokens + 1];
        for (int t = 0; t <= numTokens; t++) {
            off[t] = io.github.dfa1.vortex.core.compute.PrimitiveArrays.readLong(
                    offSeg, (long) t * offPType.byteSize(), offPType, EncodingId.VORTEX_ONPAIR);
        }
        assertThat(off[0]).isZero();
        assertThat(dict.length).isGreaterThanOrEqualTo((int) off[numTokens - 1] + 16);
        int singleBytes = 0;
        for (int t = 0; t < numTokens; t++) {
            byte[] token = Arrays.copyOfRange(dict, (int) off[t], (int) off[t + 1]);
            assertThat(token.length).as("token %d length", t).isBetween(1, 16);
            if (token.length == 1) {
                singleBytes++;
            }
            if (t > 0) {
                byte[] previous = Arrays.copyOfRange(dict, (int) off[t - 1], (int) off[t]);
                assertThat(Arrays.compareUnsigned(previous, token)).as("sorted+unique at %d", t).isNegative();
            }
        }
        assertThat(singleBytes).isEqualTo(256);
    }

    @Test
    void encode_repetitiveStrings_mergesTokens() {
        // Given — the same 22-byte string 1000 times: training must promote pairs, otherwise every
        // byte stays its own code and OnPair can never beat plain varbin.
        String[] data = new String[1000];
        Arrays.fill(data, "status=ok;region=eu-1;");
        EncodeContext ctx = EncodeContext.ofDepth(0, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = SUT.encode(DType.UTF8, data, ctx);

        // Then
        MemorySegment metaSeg = result.rootNode().metadata();
        long codes = decodeMeta(metaSeg).codes_len();
        assertThat(codes).isLessThan(data.length * 22L / 4);
    }

    private static ProtoOnPairMetadata decodeMeta(MemorySegment seg) {
        try {
            return ProtoOnPairMetadata.decode(seg, 0, seg.byteSize());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static io.github.dfa1.vortex.reader.array.Array decode(EncodeResult result, int n) {
        EncodeNode root = result.rootNode();
        ArrayNode[] children = Arrays.stream(root.children())
                .map(c -> new ArrayNode(c.encodingId(), c.metadata(), new ArrayNode[0], c.bufferIndices()))
                .toArray(ArrayNode[]::new);
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ONPAIR, root.metadata(), children, root.bufferIndices());
        MemorySegment[] segments = result.buffers().toArray(new MemorySegment[0]);
        return DECODER.decode(new DecodeContext(node, DType.UTF8, n, segments, REGISTRY, Arena.ofAuto()));
    }
}
