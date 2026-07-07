package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.proto.ProtoRLEMetadata;
import io.github.dfa1.vortex.encoding.TestSegments;
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
            MemorySegment indices = u8Indices(0, 0, 1, 2);
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
        void emptyIndices_yieldsConstantZeroArray() {
            // Given — indices_len = 0 short-circuits to emptyArray (L127): the all-null/no-run
            // column the writer emits for a fully-empty F64 chunk.
            MemorySegment values = TestSegments.leDoubles(9.5);
            MemorySegment indices = u8Indices();
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
            MemorySegment indices = u8Indices(0, 0, 1);
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
            MemorySegment indices = u8Indices();
            MemorySegment offsets = TestSegments.leLongs(0);

            // When
            Array result = decodeRle(DType.F32, 1, 0, indices, offsets, values, 0);

            // Then
            assertThat(result).isInstanceOf(LazyConstantFloatArray.class);
            assertThat(result.length()).isZero();
        }
    }

    /// The broadcast branch of `readDoubles`/`readFloats` (cap < count) is unreachable through
    /// `decode()` — a ConstantEncoding values child always materializes to `count` full elements,
    /// so cap == count. These tests exercise the helper directly on a single-element pool, the
    /// exact zip-bomb-defense shape [SegmentBroadcast] guards against.
    @Nested
    class ValueBroadcast {

        @Test
        void readDoubles_broadcastsLoneElementAcrossCount() {
            // Given — one physical double but a requested count of 4 (cap = 1 < count)
            MemorySegment buf = TestSegments.leDoubles(7.25);

            // When
            double[] result = RleEncodingDecoder.readDoubles(buf, 4);

            // Then — the lone value repeats for every logical index
            assertThat(result).containsExactly(7.25, 7.25, 7.25, 7.25);
        }

        @Test
        void readFloats_broadcastsLoneElementAcrossCount() {
            // Given — one physical float but a requested count of 3 (cap = 1 < count)
            MemorySegment buf = TestSegments.leFloats(-3.5f);

            // When
            float[] result = RleEncodingDecoder.readFloats(buf, 3);

            // Then
            assertThat(result).containsExactly(-3.5f, -3.5f, -3.5f);
        }
    }

    // ── decode harness ─────────────────────────────────────────────────────────

    /// Builds a single-chunk RLE node: child[0] = values, child[1] = indices (U8),
    /// child[2] = values-idx-offsets (U64), and drives [RleEncodingDecoder#decode].
    private static Array decodeRle(DType dtype, int valuesLen, long rowCount,
            MemorySegment indices, MemorySegment offsets, MemorySegment values, int offset) {
        long indicesLen = indices.byteSize();                 // U8 indices: 1 byte per element
        long offsetsLen = offsets.byteSize() / 8;             // U64 offsets
        byte[] metaBytes = new ProtoRLEMetadata(
                valuesLen, indicesLen, ProtoPType.U8, offsetsLen, ProtoPType.U64, offset).encode();
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

    /// A full 1024-byte (one FastLanes chunk) U8 index buffer with the leading entries set;
    /// an empty varargs list yields the length-0 buffer that drives the empty-array path.
    private static MemorySegment u8Indices(int... leading) {
        int len = leading.length == 0 ? 0 : FL_CHUNK_SIZE;
        byte[] a = new byte[len];
        for (int i = 0; i < leading.length; i++) {
            a[i] = (byte) leading[i];
        }
        return MemorySegment.ofArray(a);
    }
}
