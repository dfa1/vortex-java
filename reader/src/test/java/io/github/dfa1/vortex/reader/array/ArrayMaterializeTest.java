package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.PTypeIO;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.util.List;

import static io.github.dfa1.vortex.encoding.DTypes.F64;
import static io.github.dfa1.vortex.encoding.DTypes.I64;
import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.floats;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the [Array#materialize(java.lang.foreign.SegmentAllocator)] contract:
/// the zero-copy buffer return on segment-backed arrays, the scalar/bitmap-packing
/// fallbacks on the primitive interfaces, the inlined `Lazy*` decode formulas, the
/// composite concat/gather paths, and the explicit rejection on array families with
/// no primary segment.
class ArrayMaterializeTest {

    private final Arena arena = Arena.ofAuto();

    @Nested
    class ZeroCopy {

        @Test
        void materializedLongReturnsBackingBufferWithoutCopy() {
            // Given a buffer-backed long array
            MaterializedLongArray sut = (MaterializedLongArray) longs(1L, 2L, 3L);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then the exact backing segment is handed back — no allocation, no copy
            // (segmentIfPresent() exposes the same zero-copy buffer).
            assertThat(result).isSameAs(sut.segmentIfPresent().orElseThrow());
        }

        @Test
        void materializedBoolReturnsBackingBitmapWithoutCopy() {
            // Given a buffer-backed bool array (already an LSB-first bitmap)
            MaterializedBoolArray sut = (MaterializedBoolArray) bools(true, false, true);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then (segmentIfPresent() exposes the same zero-copy buffer)
            assertThat(result).isSameAs(sut.segmentIfPresent().orElseThrow());
        }
    }

    @Nested
    class ScalarFallback {

        @Test
        void longViewDecodesEveryElementThroughGetLong() {
            // Given an OffsetLongArray view (uses the LongArray default, not a buffer return)
            Array sut = longs(10L, 20L, 30L, 40L).limited(3);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then values come back little-endian in order
            assertThat(result.byteSize()).isEqualTo(3 * 8L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(10L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(30L);
        }

        @Test
        void boolViewPacksLsbFirstBitmap() {
            // Given a bool view — exercises the BoolArray packing default (lazy bool
            // previously had no materialize path at all). Pattern picks bits in two
            // different bytes to catch byte-index / shift mistakes.
            Array sut = bools(true, false, false, false, false, false, false, false, true).limited(9);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then 9 bits need 2 bytes; only positions 0 and 8 are set
            assertThat(result.byteSize()).isEqualTo(2L);
            assertThat(bit(result, 0)).isTrue();
            assertThat(bit(result, 1)).isFalse();
            assertThat(bit(result, 7)).isFalse();
            assertThat(bit(result, 8)).isTrue();
        }
    }

    @Nested
    class VectorizedLazy {

        @Test
        void frameOfReferenceAddsReference() {
            // Given encoded [1,2,3] with ref 100
            LazyForLongArray sut = new LazyForLongArray(I64, 3, encodedLongs(1L, 2L, 3L), 100L);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then each element is decoded + ref
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(101L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(103L);
        }

        @Test
        void zigzagDecodesSignedZigzagPattern() {
            // Given zigzag-encoded [0,1,2,3] -> decoded [0,-1,1,-2]
            LazyZigZagLongArray sut = new LazyZigZagLongArray(I64, 4, encodedLongs(0L, 1L, 2L, 3L));

            // When
            MemorySegment result = sut.materialize(arena);

            // Then
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(0L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 1)).isEqualTo(-1L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(1L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 3)).isEqualTo(-2L);
        }

        @Test
        void alpAppliesBothFactors() {
            // Given encoded i64 [1,2,3] with unit factors -> doubles [1.0,2.0,3.0]
            LazyAlpDoubleArray sut = new LazyAlpDoubleArray(F64, 3, encodedLongs(1L, 2L, 3L), 1.0, 1.0);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then
            assertThat(result.getAtIndex(PTypeIO.LE_DOUBLE, 0)).isEqualTo(1.0);
            assertThat(result.getAtIndex(PTypeIO.LE_DOUBLE, 2)).isEqualTo(3.0);
        }
    }

    @Nested
    class Composite {

        @Test
        void chunkedConcatenatesChildrenInOrder() {
            // Given two chunks [0,1,2][3,4]
            ChunkedLongArray sut = ChunkedLongArray.of(I64, 5,
                    List.of(longs(0L, 1L, 2L), longs(3L, 4L)));

            // When
            MemorySegment result = sut.materialize(arena);

            // Then one contiguous segment spanning both chunks
            assertThat(result.byteSize()).isEqualTo(5 * 8L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(0L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 3)).isEqualTo(3L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 4)).isEqualTo(4L);
        }

        @Test
        void dictGathersOneValuePerCode() {
            // Given dictionary [10,20] with byte codes [0,1,0]
            DictLongArray sut = DictLongArray.of(I64, 3, longs(10L, 20L), bytes((byte) 0, (byte) 1, (byte) 0));

            // When
            MemorySegment result = sut.materialize(arena);

            // Then each row resolves to values[code]
            assertThat(result.byteSize()).isEqualTo(3 * 8L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(10L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 1)).isEqualTo(20L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(10L);
        }
    }

    @Nested
    class Decimal {

        @Test
        void constantDecimalFillsValueEveryRow() {
            // Given the constant unscaled mantissa 12345 (scale 2) at 8-byte width over 3 rows
            DType.Decimal dtype = new DType.Decimal((byte) 10, (byte) 2, false);
            LazyConstantDecimalArray sut =
                    new LazyConstantDecimalArray(dtype, 3, new BigDecimal("123.45"), 8);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then every row holds the same little-endian mantissa
            assertThat(result.byteSize()).isEqualTo(3 * 8L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(12345L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(12345L);
        }
    }

    @Nested
    class Masked {

        @Test
        void delegatesToInnerDataIgnoringMask() {
            // Given a masked array whose inner payload is a plain long array
            MaskedArray sut = new MaskedArray(longs(7L, 8L, 9L), null);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then the inner data segment is returned (validity is not surfaced here)
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 0)).isEqualTo(7L);
            assertThat(result.getAtIndex(PTypeIO.LE_LONG, 2)).isEqualTo(9L);
        }
    }

    @Nested
    class Unsupported {

        @Test
        void nullArrayThrows() {
            // Given an all-null column (row count only, no data buffer)
            NullArray sut = new NullArray(new DType.Null(true), 3);

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void structArrayThrows() {
            // Given a two-field struct
            DType.Struct dtype = new DType.Struct(List.of("a", "b"), List.of(I64, I64), false);
            StructArray sut = new StructArray(dtype, 2, List.of(longs(1L, 2L), longs(3L, 4L)));

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void listArrayThrows() {
            // Given a list array (offsets + flat elements child)
            DType.List dtype = new DType.List(I64, false);
            ListArray sut = new ListArray(dtype, 2, longs(1L, 2L, 3L), longs(0L, 2L, 3L));

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void listViewArrayThrows() {
            // Given a list-view array (offsets + sizes + flat elements child)
            DType.List dtype = new DType.List(I64, false);
            ListViewArray sut = new ListViewArray(dtype, 2, longs(1L, 2L, 3L), longs(0L, 2L), longs(2L, 1L));

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void fixedSizeListArrayThrows() {
            // Given a fixed-size list (wraps a flat elements child)
            DType.FixedSizeList dtype = new DType.FixedSizeList(I64, 2, false);
            FixedSizeListArray sut = new FixedSizeListArray(dtype, 2, longs(1L, 2L, 3L, 4L));

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void variantArrayThrows() {
            // Given a variant array (core storage + optional shredded children)
            VariantArray sut = new VariantArray(I64, 2, longs(1L, 2L), null);

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void bytePartsDecimalThrows() {
            // Given the byte-parts decimal layout (reassembled from a child column on demand)
            DType.Decimal dtype = new DType.Decimal((byte) 10, (byte) 2, false);
            LazyDecimalBytePartsArray sut = new LazyDecimalBytePartsArray(dtype, 2, longs(1L, 2L));

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("no primary segment");
        }

        @Test
        void unknownArrayThrows() {
            // Given an undecoded foreign encoding
            UnknownArray sut = new UnknownArray("vortex.mystery", I64, 3, null,
                    new MemorySegment[0], new Array[0]);

            // When / Then
            assertThatThrownBy(() -> sut.materialize(arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vortex.mystery");
        }
    }

    /// The scalar/per-element `materialize` fallback on each primitive [Array] interface, reached
    /// through an `Offset*Array` view (which does not override `materialize`, unlike the buffer-backed
    /// `Materialized*` records). The Long and Bool defaults are covered above; this group fills in the
    /// remaining numeric interfaces.
    @Nested
    class PrimitiveScalarDefaults {

        @Test
        void floatViewDecodesEveryElementThroughGetFloat() {
            // Given an OffsetFloatArray view (FloatArray.materialize default, not a buffer return)
            Array sut = floats(1.5f, 2.5f, 3.5f, 4.5f).limited(3);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then the kept prefix is written as little-endian f32
            assertThat(result.byteSize()).isEqualTo(3 * 4L);
            assertThat(result.getAtIndex(PTypeIO.LE_FLOAT, 0)).isEqualTo(1.5f);
            assertThat(result.getAtIndex(PTypeIO.LE_FLOAT, 2)).isEqualTo(3.5f);
        }

        @Test
        void byteViewDecodesEveryElementThroughGetByte() {
            // Given an OffsetByteArray view (ByteArray.materialize default)
            Array sut = bytes((byte) 10, (byte) 20, (byte) 30, (byte) 40).limited(3);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then one byte per element
            assertThat(result.byteSize()).isEqualTo(3L);
            assertThat(result.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 10);
            assertThat(result.get(ValueLayout.JAVA_BYTE, 2)).isEqualTo((byte) 30);
        }

        @Test
        void shortViewDecodesEveryElementThroughGetShort() {
            // Given an OffsetShortArray view (ShortArray.materialize default)
            Array sut = shorts((short) 100, (short) 200, (short) 300, (short) 400).limited(3);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then little-endian i16
            assertThat(result.byteSize()).isEqualTo(3 * 2L);
            assertThat(result.getAtIndex(PTypeIO.LE_SHORT, 0)).isEqualTo((short) 100);
            assertThat(result.getAtIndex(PTypeIO.LE_SHORT, 2)).isEqualTo((short) 300);
        }

        @Test
        void intViewDecodesEveryElementThroughGetInt() {
            // Given an OffsetIntArray view (IntArray.materialize default)
            Array sut = ints(1, 2, 3, 4).limited(3);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then little-endian i32
            assertThat(result.byteSize()).isEqualTo(3 * 4L);
            assertThat(result.getAtIndex(PTypeIO.LE_INT, 0)).isEqualTo(1);
            assertThat(result.getAtIndex(PTypeIO.LE_INT, 2)).isEqualTo(3);
        }

        @Test
        void doubleViewDecodesEveryElementThroughGetDouble() {
            // Given an OffsetDoubleArray view (DoubleArray.materialize default)
            Array sut = doubles(1.5, 2.5, 3.5).limited(2);

            // When
            MemorySegment result = sut.materialize(arena);

            // Then little-endian f64
            assertThat(result.byteSize()).isEqualTo(2 * 8L);
            assertThat(result.getAtIndex(PTypeIO.LE_DOUBLE, 0)).isEqualTo(1.5);
            assertThat(result.getAtIndex(PTypeIO.LE_DOUBLE, 1)).isEqualTo(2.5);
        }
    }

    private MemorySegment encodedLongs(long... vs) {
        MemorySegment seg = arena.allocate(vs.length * 8L, 8);
        for (int i = 0; i < vs.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, vs[i]);
        }
        return seg;
    }

    private static boolean bit(MemorySegment seg, long i) {
        byte b = seg.get(ValueLayout.JAVA_BYTE, i >>> 3);
        return ((b & 0xff) & (1 << (i & 7))) != 0;
    }
}
