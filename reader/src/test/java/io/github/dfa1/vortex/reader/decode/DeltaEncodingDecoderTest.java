package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeltaEncodingDecoderTest {

    private static final DeltaEncodingDecoder SUT = new DeltaEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    private static final int FL_CHUNK_SIZE = 1024;

    @Test
    void encodingId_isFastlanesDelta() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.FASTLANES_DELTA);
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64"})
    void decode_nullMetadata_returnsEmptyArray(PType ptype) {
        // Given no metadata — the decoder defaults to deltas_len=0 and short-circuits
        // to an empty array of the right ptype (a path the encoder never emits, since it
        // always writes metadata)
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_DELTA, null, new ArrayNode[0], new int[0]);
        DecodeContext ctx = new DecodeContext(node, new DType.Primitive(ptype, false), 0,
                new MemorySegment[0], REGISTRY, Arena.ofAuto());

        // When
        Array result = SUT.decode(ctx);

        // Then
        assertThat(result.length()).isZero();
    }

    @Test
    void decode_constantChildren_broadcastsAcrossChunk() {
        // Given a single delta chunk (1024 rows) whose bases and deltas children each hold
        // a single element, as a ConstantEncoding child would. readLongs must broadcast the
        // lone value across the whole chunk (capacity < count). Zero bases + zero deltas
        // means every decoded row is zero.
        PType ptype = PType.I64;
        long deltasLen = FL_CHUNK_SIZE;
        MemorySegment meta = MemorySegment.ofArray(new ProtoDeltaMetadata(deltasLen, 0).encode());

        ArrayNode bases = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode deltas = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_DELTA, meta, new ArrayNode[]{bases, deltas}, new int[0]);

        // one element each → broadcast
        MemorySegment[] segs = {TestSegments.leLongs(0L), TestSegments.leLongs(0L)};
        DecodeContext ctx = new DecodeContext(node, new DType.Primitive(ptype, false), 4, segs, REGISTRY, Arena.ofAuto());

        // When
        LongArray result = (LongArray) SUT.decode(ctx);

        // Then
        assertThat(result.length()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertThat(result.getLong(i)).as("index %d", i).isZero();
        }
    }

    /// `deltas_len` and `offset` are untrusted metadata that together name the window of
    /// reconstructed elements the rows come from. A window running past that many elements
    /// reached a `System.arraycopy` as a raw ArrayIndexOutOfBoundsException, and a negative
    /// `deltas_len` sized a heap `long[]` — a NegativeArraySizeException. Both must be a
    /// VortexException (ADR 0003).
    @ParameterizedTest
    @CsvSource({
            "1024, 0, 2000",     // more rows than the chunks reconstruct
            "1024, 900, 200",    // window starts inside the chunk but runs off its end
            "1024, -1, 4",       // negative start position
            "-1024, 0, 4",       // negative element count
            "-9223372036854775808, 1, 4"  // negative enough that `deltasLen - offset` wraps positive
    })
    void decode_windowOutsideDeltas_throws(long deltasLen, int offset, long rowCount) {
        // Given — metadata whose row window is not covered by `deltasLen` elements
        DecodeContext ctx = deltaContext(deltasLen, offset, rowCount);

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("row window");
    }

    /// The other end of the same untrusted field: a `deltas_len` of `Long.MAX_VALUE` with a few
    /// rows asked for is a *legal* window — the rows are inside it — over an absurd declared
    /// length. It used to allocate `new long[(int) deltasLen]` twice before touching a buffer
    /// (OutOfMemoryError, or a silently truncated cast). Nothing is sized from `deltasLen` any
    /// more, and only the chunks the window touches are reconstructed, so this now decodes.
    @Test
    void decode_absurdDeltasLenButSmallWindow_decodesWithoutAllocatingForIt() {
        // Given
        DecodeContext ctx = deltaContext(Long.MAX_VALUE, 0, 4);

        // When
        LongArray result = (LongArray) SUT.decode(ctx);

        // Then — zero bases and zero deltas, so every row is zero
        assertThat(result.length()).isEqualTo(4);
        assertThat(result.getLong(0)).isZero();
        assertThat(result.getLong(3)).isZero();
    }

    @Test
    void decode_constantBases_nonZeroOffsetAndBase() {
        // Given a constant base of 5 broadcast across all 16 lanes with zero deltas:
        // every row decodes to the base value 5. Reads from an offset into the chunk.
        PType ptype = PType.I64;
        MemorySegment meta = MemorySegment.ofArray(new ProtoDeltaMetadata(FL_CHUNK_SIZE, 0).encode());

        ArrayNode bases = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode deltas = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_DELTA, meta, new ArrayNode[]{bases, deltas}, new int[0]);

        MemorySegment[] segs = {TestSegments.leLongs(5L), TestSegments.leLongs(0L)};
        DecodeContext ctx = new DecodeContext(node, new DType.Primitive(ptype, false), 3, segs, REGISTRY, Arena.ofAuto());

        // When
        LongArray result = (LongArray) SUT.decode(ctx);

        // Then prefix-sum of zero deltas over base 5 stays 5 on lane 0
        assertThat(result.getLong(0)).isEqualTo(5L);
        // sanity: materialized bytes are little-endian
        MemorySegment seg = result.materialize(Arena.ofAuto());
        assertThat(seg.get(VortexFormat.LE_LONG, 0)).isEqualTo(5L);
    }

    /// An I64 delta node over single-element (constant) bases and deltas children, so the
    /// metadata under test is the only variable.
    private static DecodeContext deltaContext(long deltasLen, int offset, long rowCount) {
        MemorySegment meta = MemorySegment.ofArray(new ProtoDeltaMetadata(deltasLen, offset).encode());
        ArrayNode bases = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{0});
        ArrayNode deltas = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{1});
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_DELTA, meta,
                new ArrayNode[]{bases, deltas}, new int[0]);
        MemorySegment[] segs = {TestSegments.leLongs(0L), TestSegments.leLongs(0L)};
        return new DecodeContext(node, new DType.Primitive(PType.I64, false), rowCount,
                segs, REGISTRY, Arena.ofAuto());
    }
}
