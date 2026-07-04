package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.encoding.TestSegments;
import io.github.dfa1.vortex.core.proto.ProtoDeltaMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(seg.get(PTypeIO.LE_LONG, 0)).isEqualTo(5L);
    }
}
