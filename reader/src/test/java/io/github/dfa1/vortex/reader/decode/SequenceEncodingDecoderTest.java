package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoSequenceMetadata;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.Float16Array;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazySequenceByteArray;
import io.github.dfa1.vortex.reader.array.LazySequenceDoubleArray;
import io.github.dfa1.vortex.reader.array.LazySequenceFloat16Array;
import io.github.dfa1.vortex.reader.array.LazySequenceFloatArray;
import io.github.dfa1.vortex.reader.array.LazySequenceIntArray;
import io.github.dfa1.vortex.reader.array.LazySequenceLongArray;
import io.github.dfa1.vortex.reader.array.LazySequenceShortArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequenceEncodingDecoderTest {

    private static final SequenceEncodingDecoder SUT = new SequenceEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT);

    @Test
    void encodingId_isVortexSequence() {
        // Given / When / Then
        assertThat(SUT.encodingId()).isEqualTo(EncodingId.VORTEX_SEQUENCE);
    }

    /// `vortex.sequence` is metadata-only: no buffers, no children. Decoding it must therefore
    /// allocate nothing at all, whatever the row count — the eager decode allocated
    /// `n * elemBytes` for a formula computable in O(1), and since no buffer bounds `n`, a few
    /// bytes of metadata could name an arbitrary allocation (#335). Asserting the concrete lazy
    /// type is the point: correct values alone would also pass on the eager path.
    @ParameterizedTest
    @EnumSource(value = PType.class, names = {"I8", "U8", "I16", "U16", "I32", "U32", "I64", "U64"})
    void integerSequence_decodesLazily(PType ptype) {
        // Given — base 10, step 3 over a row count far larger than any buffer in the file
        Array result = decodeInteger(ptype, 10, 3, 1_000_000);

        // Then
        assertThat(result).isInstanceOf(expectedLazyType(ptype));
        assertThat(result.length()).isEqualTo(1_000_000L);
        assertThat(readAsLong(result, 0)).isEqualTo(10L);
        assertThat(readAsLong(result, 1)).isEqualTo(13L);
        assertThat(readAsLong(result, 2)).isEqualTo(16L);
    }

    /// Narrow types wrap rather than saturate, exactly as the eager decode did: it computed the
    /// step in `long` and narrowed on store. Row 100 of `base=0, step=3` is 300, which does not
    /// fit in a byte.
    @Test
    void byteSequence_wrapsOnOverflowLikeTheEagerDecode() {
        // Given
        Array result = decodeInteger(PType.I8, 0, 3, 200);

        // When
        byte value = ((ByteArray) result).getByte(100);

        // Then — (byte) 300 == 44
        assertThat(value).isEqualTo((byte) 44);
    }

    @Test
    void f64Sequence_decodesLazily() {
        // Given
        Array result = decode(PType.F64,
                ProtoScalarValue.ofF64Value(1.5), ProtoScalarValue.ofF64Value(0.25), 100);

        // Then
        assertThat(result).isInstanceOf(LazySequenceDoubleArray.class);
        assertThat(((DoubleArray) result).getDouble(0)).isEqualTo(1.5);
        assertThat(((DoubleArray) result).getDouble(4)).isEqualTo(2.5);
    }

    @Test
    void f32Sequence_decodesLazily() {
        // Given
        Array result = decode(PType.F32,
                ProtoScalarValue.ofF32Value(1.5f), ProtoScalarValue.ofF32Value(0.25f), 100);

        // Then
        assertThat(result).isInstanceOf(LazySequenceFloatArray.class);
        assertThat(((FloatArray) result).getFloat(0)).isEqualTo(1.5f);
        assertThat(((FloatArray) result).getFloat(4)).isEqualTo(2.5f);
    }

    /// F16 values round-trip through half precision on every read, matching the eager decode,
    /// which stored `floatToFloat16` into the buffer and widened again on access. Both base and
    /// step are exactly representable here, so the arithmetic is not what is under test — the
    /// narrowing is.
    @Test
    void f16Sequence_decodesLazilyAndRoundTripsThroughHalfPrecision() {
        // Given
        long baseBits = Short.toUnsignedLong(Float.floatToFloat16(1.5f));
        long stepBits = Short.toUnsignedLong(Float.floatToFloat16(0.25f));
        Array result = decode(PType.F16,
                ProtoScalarValue.ofF16Value(baseBits), ProtoScalarValue.ofF16Value(stepBits), 100);

        // Then
        assertThat(result).isInstanceOf(LazySequenceFloat16Array.class);
        assertThat(((Float16Array) result).getFloat(0)).isEqualTo(1.5f);
        assertThat(((Float16Array) result).getFloat(4)).isEqualTo(2.5f);
    }

    @Test
    void missingMetadata_throwsVortexException() {
        // Given
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_SEQUENCE, null, new ArrayNode[0], new int[]{});
        DecodeContext ctx = new DecodeContext(node, DType.I32, 5, new MemorySegment[0], REGISTRY, Arena.ofAuto());

        // When / Then
        assertThatThrownBy(() -> SUT.decode(ctx))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("missing metadata");
    }

    @Test
    void nonPrimitiveDtype_throwsVortexException() {
        // Given / When / Then
        assertThatThrownBy(() -> decode(DType.UTF8,
                ProtoScalarValue.ofInt64Value(0L), ProtoScalarValue.ofInt64Value(1L), 5))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("expected primitive dtype");
    }

    private static Array decodeInteger(PType ptype, long base, long step, long n) {
        boolean unsigned = switch (ptype) {
            case U8, U16, U32, U64 -> true;
            default -> false;
        };
        ProtoScalarValue b = unsigned ? ProtoScalarValue.ofUint64Value(base) : ProtoScalarValue.ofInt64Value(base);
        ProtoScalarValue m = unsigned ? ProtoScalarValue.ofUint64Value(step) : ProtoScalarValue.ofInt64Value(step);
        return decode(ptype, b, m, n);
    }

    private static Array decode(PType ptype, ProtoScalarValue base, ProtoScalarValue mul, long n) {
        return decode(new DType.Primitive(ptype, false), base, mul, n);
    }

    private static Array decode(DType dtype, ProtoScalarValue base, ProtoScalarValue mul, long n) {
        MemorySegment meta = MemorySegment.ofArray(new ProtoSequenceMetadata(base, mul).encode());
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_SEQUENCE, meta, new ArrayNode[0], new int[]{});
        DecodeContext ctx = new DecodeContext(node, dtype, n, new MemorySegment[0], REGISTRY, Arena.ofAuto());
        return SUT.decode(ctx);
    }

    private static Class<?> expectedLazyType(PType ptype) {
        return switch (ptype) {
            case I8, U8 -> LazySequenceByteArray.class;
            case I16, U16 -> LazySequenceShortArray.class;
            case I32, U32 -> LazySequenceIntArray.class;
            default -> LazySequenceLongArray.class;
        };
    }

    private static long readAsLong(Array array, long i) {
        return switch (array) {
            case ByteArray ba -> ba.getByte(i);
            case ShortArray sa -> sa.getShort(i);
            case IntArray ia -> ia.getInt(i);
            case LongArray la -> la.getLong(i);
            default -> throw new IllegalStateException("unexpected array type " + array.getClass());
        };
    }
}
