package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoRLEMetadata;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.LazyConstantDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyConstantFloatArray;
import io.github.dfa1.vortex.reader.array.LazyRleDoubleArray;
import io.github.dfa1.vortex.reader.array.LazyRleFloatArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Decoder-level tests for `fastlanes.rle`. The array records themselves are covered by
/// [io.github.dfa1.vortex.reader.array.LazyRleArrayTest]; here we drive
/// [RleEncodingDecoder#decode] with hand-assembled [ArrayNode]/[DecodeContext] fixtures so the
/// F32/F64 switch arms, the empty-array constant fallback, and the broadcast value helpers are
/// exercised at the seam that [io.github.dfa1.vortex.reader.array.LazyRleArrayTest] cannot reach.
///
/// Wire shape mirrors what the Python Vortex writer emits for F64/F32 weather columns with long
/// constant runs (issue #209): one FastLanes chunk (indices_len = 1024), a small distinct-values
/// pool, and a single per-chunk offset of 0.
class RleEncodingDecoderTest {

    private static final int FL_CHUNK_SIZE = 1024;

    private static final RleEncodingDecoder SUT = new RleEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(
            SUT, new PrimitiveEncodingDecoder());

    @Test
    void encodingId_isFastlanesRle() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.FASTLANES_RLE);
    }

    @Nested
    class DoubleDecode {

        @Test
        void decodesAcrossRunBoundary() {
            // Given — one chunk, pool [1.5, 2.5, 3.5], indices 0,0,1,2: rows 0-1 share value 1.5
            // then two distinct runs. Fractional values catch any accidental long-narrowing read.
            MemorySegment values = TestSegments.leDoubles(1.5, 2.5, 3.5);
            MemorySegment indices = u8Indices(FL_CHUNK_SIZE, 0, 0, 1, 2);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When
            Array result = decodeRle(DType.F64, 3, 4, indices, offsets, values, 0);

            // Then — the run boundary (row 1 -> row 2) resolves to the correct pool entries
            assertThat(result).isInstanceOf(LazyRleDoubleArray.class);
            LazyRleDoubleArray rle = (LazyRleDoubleArray) result;
            assertThat(rle.getDouble(0)).isEqualTo(1.5);
            assertThat(rle.getDouble(1)).isEqualTo(1.5);
            assertThat(rle.getDouble(2)).isEqualTo(2.5);
            assertThat(rle.getDouble(3)).isEqualTo(3.5);
        }

        @Test
        void u16IndexTable_decodesTheSameRuns() {
            // Given — the same pool and run pattern with a u16 index table, the width FastLanes
            // switches to past 256 runs per chunk. Both widths must resolve identically, and only
            // an end-to-end decode pins the metadata ptype to the record's wideIndices flag.
            MemorySegment values = TestSegments.leDoubles(1.5, 2.5, 3.5);
            MemorySegment indices = u16Indices(FL_CHUNK_SIZE, 0, 0, 1, 2);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When
            Array result = decodeRle(DType.F64, 3, 4, indices, ProtoPType.U16, offsets, values, 0);

            // Then
            assertThat(result).isInstanceOf(LazyRleDoubleArray.class);
            LazyRleDoubleArray rle = (LazyRleDoubleArray) result;
            assertThat(rle.wideIndices()).isTrue();
            assertThat(rle.getDouble(0)).isEqualTo(1.5);
            assertThat(rle.getDouble(1)).isEqualTo(1.5);
            assertThat(rle.getDouble(2)).isEqualTo(2.5);
            assertThat(rle.getDouble(3)).isEqualTo(3.5);
        }

        @Test
        void emptyIndices_yieldsConstantZeroArray() {
            // Given — indices_len = 0 short-circuits to emptyArray (L127): the all-null/no-run
            // column the writer emits for a fully-empty F64 chunk.
            MemorySegment values = TestSegments.leDoubles(9.5);
            MemorySegment indices = u8Indices(0);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When
            Array result = decodeRle(DType.F64, 1, 0, indices, offsets, values, 0);

            // Then — a length-0 constant array, not an RLE array
            assertThat(result).isInstanceOf(LazyConstantDoubleArray.class);
            assertThat(result.length()).isZero();
        }
    }

    @Nested
    class FloatDecode {

        @Test
        void decodesAcrossRunBoundary() {
            // Given — one chunk, pool [1.25f, 2.75f], indices 0,0,1: the 4-byte value read must not
            // widen to a double; fractional values chosen so a wrong width surfaces as a bad assertion.
            MemorySegment values = TestSegments.leFloats(1.25f, 2.75f);
            MemorySegment indices = u8Indices(FL_CHUNK_SIZE, 0, 0, 1);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When
            Array result = decodeRle(DType.F32, 2, 3, indices, offsets, values, 0);

            // Then
            assertThat(result).isInstanceOf(LazyRleFloatArray.class);
            LazyRleFloatArray rle = (LazyRleFloatArray) result;
            assertThat(rle.getFloat(0)).isEqualTo(1.25f);
            assertThat(rle.getFloat(1)).isEqualTo(1.25f);
            assertThat(rle.getFloat(2)).isEqualTo(2.75f);
        }

        @Test
        void emptyIndices_yieldsConstantZeroArray() {
            // Given — indices_len = 0 short-circuits to emptyArray (L128) for an empty F32 chunk
            MemorySegment values = TestSegments.leFloats(9.5f);
            MemorySegment indices = u8Indices(0);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When
            Array result = decodeRle(DType.F32, 1, 0, indices, offsets, values, 0);

            // Then
            assertThat(result).isInstanceOf(LazyConstantFloatArray.class);
            assertThat(result.length()).isZero();
        }
    }

    /// [RleEncodingDecoder#fitElements] is the one place a declared length meets an allocation,
    /// so it owns the guards. Its broadcast branch (a `ConstantEncoding` child storing one
    /// element for any declared length — the zip-bomb-defense shape [SegmentBroadcast] guards
    /// against) is reachable through `decode()`, and so is its rejection branch: nothing checks
    /// that a `vortex.primitive` child's buffer covers the length its parent declares. These
    /// tests drive the helper directly, since a hand-built node cannot express both shapes.
    @Nested
    class ValueBroadcast {

        @Test
        void doubles_broadcastLoneElementAcrossCount() {
            // Given — one physical double but a requested count of 4 (cap = 1 < count)
            MemorySegment buf = TestSegments.leDoubles(7.25);

            // When
            MemorySegment result;
            try (Arena arena = Arena.ofConfined()) {
                result = RleEncodingDecoder.fitElements(arena, buf, 4, 8);

                // Then — the lone value repeats for every logical index
                assertThat(result.byteSize()).isEqualTo(32);
                for (int i = 0; i < 4; i++) {
                    assertThat(result.getAtIndex(VortexFormat.LE_DOUBLE, i)).isEqualTo(7.25);
                }
            }
        }

        @Test
        void floats_broadcastLoneElementAcrossCount() {
            // Given — one physical float but a requested count of 3 (cap = 1 < count)
            MemorySegment buf = TestSegments.leFloats(-3.5f);

            // When
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = RleEncodingDecoder.fitElements(arena, buf, 3, 4);

                // Then
                assertThat(result.byteSize()).isEqualTo(12);
                for (int i = 0; i < 3; i++) {
                    assertThat(result.getAtIndex(VortexFormat.LE_FLOAT, i)).isEqualTo(-3.5f);
                }
            }
        }

        @Test
        void oversizedSegment_isSlicedNotCopied() {
            // Given — a values buffer holding more elements than the pool declares, the shape a
            // shared mmapped segment produces; the helper must hand back an exact-length view so
            // a run index past the pool cannot read a neighbor's bytes as a value.
            MemorySegment buf = TestSegments.leDoubles(1.5, 2.5, 3.5, 4.5);

            // When
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment result = RleEncodingDecoder.fitElements(arena, buf, 2, 8);

                // Then
                assertThat(result.byteSize()).isEqualTo(16);
                assertThat(result.getAtIndex(VortexFormat.LE_DOUBLE, 1)).isEqualTo(2.5);
            }
        }

        @Test
        void shortNonConstantSegment_failsAsVortexException() {
            // Given — a child holding 2 of the 4 elements it declares. Only a ConstantEncoding
            // child (exactly one element) may be broadcast; anything else is a shape mismatch,
            // and wrapping it around would fabricate values the file never stored.
            MemorySegment buf = TestSegments.leDoubles(1.5, 2.5);

            // When / Then
            try (Arena arena = Arena.ofConfined()) {
                assertThatThrownBy(() -> RleEncodingDecoder.fitElements(arena, buf, 4, 8))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("child holds 2 element(s)");
            }
        }

        @Test
        void emptySegment_failsAsVortexException() {
            // Given — a child that decoded to nothing at all; the old broadcast arithmetic
            // divided by its zero capacity and escaped as an ArithmeticException.
            MemorySegment buf = TestSegments.leDoubles();

            // When / Then
            try (Arena arena = Arena.ofConfined()) {
                assertThatThrownBy(() -> RleEncodingDecoder.fitElements(arena, buf, 2, 8))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("child holds 0 element(s)");
            }
        }

        @Test
        void negativeCount_failsAsVortexException() {
            // Given — a values_len a malformed file can declare; it must never reach an
            // allocation as a negative size (ADR 0003: no raw JDK exception escapes).
            MemorySegment buf = TestSegments.leDoubles(1.5);

            // When / Then
            try (Arena arena = Arena.ofConfined()) {
                assertThatThrownBy(() -> RleEncodingDecoder.fitElements(arena, buf, -1, 8))
                        .isInstanceOf(VortexException.class);
            }
        }

        @Test
        void absurdCount_failsAsVortexExceptionNotOutOfMemory() {
            // Given — a values_len of 2^40 against a one-element pool. The broadcast branch
            // would otherwise ask the arena for 8 TiB; ADR 0004's count guard rejects it first,
            // so the malformed file fails as VortexException instead of OutOfMemoryError.
            MemorySegment buf = TestSegments.leDoubles(1.5);

            // When / Then
            try (Arena arena = Arena.ofConfined()) {
                assertThatThrownBy(() -> RleEncodingDecoder.fitElements(arena, buf, 1L << 40, 8))
                        .isInstanceOf(VortexException.class)
                        .hasMessageContaining("2 GB");
            }
        }
    }

    /// Guards on the lengths and offsets a malformed file can declare. Every one must fail as
    /// [VortexException] — never an `OutOfMemoryError`, `NegativeArraySizeException`, or a raw
    /// `IndexOutOfBoundsException` from a later per-row read (CLAUDE.md security contract).
    @Nested
    class MalformedMetadata {

        @Test
        void valuesLenBeyondIndicesLen_isRejected() {
            // Given — a run-value pool larger than the index table that selects from it, which no
            // legitimate writer emits: 4096 values for 1024 rows of indices.
            MemorySegment values = TestSegments.leDoubles(1.5);
            MemorySegment indices = u8Indices(FL_CHUNK_SIZE, 0);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When / Then
            assertThatThrownBy(() -> decodeRle(DType.F64, 4096, 4, indices, offsets, values, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("exceeds indices_len");
        }

        @Test
        void absurdValuesLen_failsAsVortexExceptionNotOutOfMemory() {
            // Given — values_len = 2^40, the shape that reached `arena.allocate(8796093022208)`
            // before the ADR 0004 count guard was wired in.
            MemorySegment values = TestSegments.leDoubles(1.5);
            MemorySegment indices = u8Indices(FL_CHUNK_SIZE, 0);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When / Then
            assertThatThrownBy(() -> decodeRle(DType.F64, 1L << 40, 4, indices, offsets, values, 0))
                    .isInstanceOf(VortexException.class);
        }

        @Test
        void chunkOffsetPastValuesPool_isRejected() {
            // Given — a two-chunk index table whose second chunk starts past the end of a
            // three-value pool; without the offsets check the record would read off the pool.
            MemorySegment values = TestSegments.leDoubles(1.5, 2.5, 3.5);
            MemorySegment indices = u8Indices(2 * FL_CHUNK_SIZE, 0, 1, 2);
            MemorySegment offsets = TestSegments.leLongs(0, 99);

            // When / Then
            assertThatThrownBy(() -> decodeRle(DType.F64, 3, 1025, indices, offsets, values, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("values pool");
        }

        @Test
        void tooFewChunkOffsets_isRejected() {
            // Given — two chunks of indices but a single offset entry; the per-chunk lookup
            // would run off the offsets array on any row in chunk 1.
            MemorySegment values = TestSegments.leDoubles(1.5, 2.5);
            MemorySegment indices = u8Indices(2 * FL_CHUNK_SIZE, 0, 1);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When / Then
            assertThatThrownBy(() -> decodeRle(DType.F64, 2, 1025, indices, offsets, values, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("values_idx_offsets holds 1 entries");
        }

        @Test
        void unsupportedIndicesPtype_isRejected() {
            // Given — a u32 index table; FastLanes only ever emits u8 or u16, and the record's
            // two specialized read paths cover exactly those.
            MemorySegment values = TestSegments.leDoubles(1.5);
            MemorySegment indices = u8Indices(FL_CHUNK_SIZE, 0);
            MemorySegment offsets = TestSegments.leLongs(0);

            // When / Then
            assertThatThrownBy(() ->
                    decodeRle(DType.F64, 1, 4, indices, ProtoPType.U32, offsets, values, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported indices ptype");
        }
    }

    // ── decode harness ─────────────────────────────────────────────────────────

    /// Builds an RLE node with a `u8` index table: child[0] = values, child[1] = indices,
    /// child[2] = values-idx-offsets (U64), and drives [RleEncodingDecoder#decode].
    private static Array decodeRle(DType dtype, long valuesLen, long rowCount,
            MemorySegment indices, MemorySegment offsets, MemorySegment values, int offset) {
        return decodeRle(dtype, valuesLen, rowCount, indices, ProtoPType.U8, offsets, values, offset);
    }

    /// The same harness with an explicit index-table ptype, so both the `u8` and `u16` widths
    /// FastLanes emits — and a width it never emits — are drivable end to end.
    private static Array decodeRle(DType dtype, long valuesLen, long rowCount,
            MemorySegment indices, ProtoPType indicesPtype, MemorySegment offsets,
            MemorySegment values, int offset) {
        long indicesLen = indices.byteSize() / (indicesPtype == ProtoPType.U8 ? 1 : 2);
        long offsetsLen = offsets.byteSize() / 8;             // U64 offsets
        byte[] metaBytes = new ProtoRLEMetadata(
                valuesLen, indicesLen, indicesPtype, offsetsLen, ProtoPType.U64, offset).encode();
        MemorySegment meta = MemorySegment.ofArray(metaBytes);
        MemorySegment[] segs = {values, indices, offsets};
        ArrayNode node = new ArrayNode(EncodingId.FASTLANES_RLE, meta,
                new ArrayNode[]{primitiveNode(0), primitiveNode(1), primitiveNode(2)}, new int[]{});
        DecodeContext ctx = new DecodeContext(node, dtype, rowCount, segs, REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static ArrayNode primitiveNode(int bufferIndex) {
        return new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{bufferIndex});
    }

    /// A `u8` index buffer of `slots` entries with the leading entries set; `slots = 0` yields
    /// the length-0 buffer that drives the empty-array path.
    private static MemorySegment u8Indices(int slots, int... leading) {
        byte[] a = new byte[slots];
        for (int i = 0; i < leading.length; i++) {
            a[i] = (byte) leading[i];
        }
        return MemorySegment.ofArray(a);
    }

    /// The `u16` counterpart of [#u8Indices(int, int...)] — the width FastLanes switches to once
    /// a chunk holds more runs than a byte can address.
    private static MemorySegment u16Indices(int slots, int... leading) {
        MemorySegment seg = Arena.ofAuto().allocate(slots * 2L);
        for (int i = 0; i < leading.length; i++) {
            seg.setAtIndex(VortexFormat.LE_SHORT, i, (short) leading[i]);
        }
        return seg;
    }
}
