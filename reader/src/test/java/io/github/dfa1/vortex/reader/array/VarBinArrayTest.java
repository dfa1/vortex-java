package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarBinArrayTest {

    private static final DType UTF8 = DType.UTF8;

    private static VarBinOffsetArray of(String... values) {
        byte[] allBytes = String.join("", values).getBytes(StandardCharsets.UTF_8);
        MemorySegment bytes = MemorySegment.ofArray(allBytes);

        int[] offs = new int[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            offs[i + 1] = offs[i] + values[i].getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer bb = ByteBuffer.allocate(offs.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int o : offs) {
            bb.putInt(o);
        }
        MemorySegment offsetsSeg = MemorySegment.ofArray(bb.array());
        return new VarBinOffsetArray(UTF8, values.length, bytes, offsetsSeg, PType.I32);
    }

    @Nested
    class Regular {

        @Test
        void length_returnsElementCount() {
            // Given
            VarBinArray sut = of("a", "bb", "ccc");

            // When / Then
            assertThat(sut.length()).isEqualTo(3L);
        }

        @Test
        void dtype_returnsConstructedDtype() {
            // Given
            VarBinArray sut = of("x");

            // When / Then
            assertThat(sut.dtype()).isEqualTo(UTF8);
        }

        @Test
        void getBytes_returnsCorrectBytes() {
            // Given
            VarBinArray sut = of("hello", "world");

            // When / Then
            assertThat(sut.getString(0)).isEqualTo("hello");
            assertThat(sut.getString(1)).isEqualTo("world");
        }

        @Test
        void getByteLength_returnsCorrectLengths() {
            // Given
            VarBinArray sut = of("hi", "there", "!");

            // When / Then
            assertThat(sut.getByteLength(0)).isEqualTo(2);
            assertThat(sut.getByteLength(1)).isEqualTo(5);
            assertThat(sut.getByteLength(2)).isEqualTo(1);
        }

        @Test
        void forEachByteLength_visitsAllLengths() {
            // Given
            VarBinArray sut = of("ab", "c", "def");
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(2, 1, 3);
        }

        @Test
        void getBytes_emptyString_returnsEmptyArray() {
            // Given
            VarBinArray sut = of("", "x", "");

            // When / Then
            assertThat(sut.getBytes(0)).isEmpty();
            assertThat(sut.getByteLength(0)).isZero();
            assertThat(sut.getByteLength(2)).isZero();
        }

        @Test
        void empty_zeroElements() {
            // Given
            VarBinArray sut = of();

            // When / Then
            assertThat(sut.length()).isZero();
        }

        @Test
        void offsetsPtype_returnsOffsetType() {
            // Given
            VarBinOffsetArray sut = of("a");

            // When / Then
            assertThat(sut.offsetsPtype()).isNotNull();
        }
    }

    @Nested
    class Dict {

        private static VarBinArray ofDict(String[] dictValues, int[] codes) {
            byte[] allBytes = String.join("", dictValues).getBytes(StandardCharsets.UTF_8);
            MemorySegment dictValBytes = MemorySegment.ofArray(allBytes);

            int[] offs = new int[dictValues.length + 1];
            for (int i = 0; i < dictValues.length; i++) {
                offs[i + 1] = offs[i] + dictValues[i].getBytes(StandardCharsets.UTF_8).length;
            }
            MemorySegment dictValOffsets = leInts(offs);

            MemorySegment dictCodes = leInts(codes);

            return VarBinArray.ofDict(UTF8, codes.length,
                    dictValBytes, dictValOffsets, PType.I32,
                    dictCodes, PType.I32);
        }

        private static MemorySegment leInts(int[] values) {
            ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int v : values) {
                bb.putInt(v);
            }
            return MemorySegment.ofArray(bb.array());
        }

        @Test
        void getBytes_resolvesViaDict() {
            // Given — dict=["foo","bar"], codes=[1,0,1]
            VarBinArray sut = ofDict(new String[]{"foo", "bar"}, new int[]{1, 0, 1});

            // When / Then
            assertThat(sut.getString(0)).isEqualTo("bar");
            assertThat(sut.getString(1)).isEqualTo("foo");
            assertThat(sut.getString(2)).isEqualTo("bar");
        }

        @Test
        void getByteLength_resolvesViaDict() {
            // Given — dict=["hi","there"], codes=[0,1,0]
            VarBinArray sut = ofDict(new String[]{"hi", "there"}, new int[]{0, 1, 0});

            // When / Then
            assertThat(sut.getByteLength(0)).isEqualTo(2);
            assertThat(sut.getByteLength(1)).isEqualTo(5);
            assertThat(sut.getByteLength(2)).isEqualTo(2);
        }

        @Test
        void forEachByteLength_resolvesViaDict() {
            // Given
            VarBinArray sut = ofDict(new String[]{"a", "bbb"}, new int[]{1, 0, 1});
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(3, 1, 3);
        }

        /// Exercises the I32-offset fast path of `forEachByteLength` (#243) for each
        /// integer codes ptype: the code-ptype switch in `forEachI32OffsetByteLength` must
        /// handle U8, U16, U32, I32, I64, and U64 codes without regressing to the per-row
        /// general path. Catches regressions where a new codes ptype is added to the decoder
        /// but not to the fast-path switch.
        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"U8", "U16", "U32", "I32", "I64", "U64"})
        void forEachByteLength_i32Offsets_codesAtNativeWidth(PType codesPtype) {
            // Given — dict=["foo","bar"] with I32 offsets, codes=[1,0,1] at the given ptype
            MemorySegment dictValBytes = MemorySegment.ofArray("foobar".getBytes(StandardCharsets.UTF_8));
            MemorySegment offsets = packOffsets(new int[]{0, 3, 6}, PType.I32);
            MemorySegment codes = packCodes(new long[]{1, 0, 1}, codesPtype);
            VarBinArray sut = VarBinArray.ofDict(UTF8, 3, dictValBytes, offsets, PType.I32, codes, codesPtype);
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(3, 3, 3);
        }

        /// Unsupported codes ptype (e.g. F32) inside the I32-offset fast path must throw
        /// [VortexException] rather than produce corrupt output or an unchecked exception.
        @Test
        void forEachByteLength_i32Offsets_unsupportedCodesPtype_throws() {
            // Given — codes declared as F32 (not a valid dict-codes ptype)
            MemorySegment dictValBytes = MemorySegment.ofArray("abc".getBytes(StandardCharsets.UTF_8));
            MemorySegment offsets = packOffsets(new int[]{0, 3}, PType.I32);
            MemorySegment codes = MemorySegment.ofArray(new byte[4]);
            VarBinArray sut = VarBinArray.ofDict(UTF8, 1, dictValBytes, offsets, PType.I32, codes, PType.F32);

            // When / Then
            assertThatThrownBy(() -> sut.forEachByteLength(l -> {}))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported codes ptype");
        }

        /// Exercises the wide-offset general path of `forEachByteLength` (#243): 64-bit value
        /// offsets take the per-row `dictReadOff` branch rather than the I32 fast path.
        @Test
        void forEachByteLength_i64Offsets_resolvesViaDict() {
            // Given — dict=["hi","there"] with I64 offsets, codes=[0,1,0,1]
            VarBinArray sut = ofDictWithOffsets(new String[]{"hi", "there"}, PType.I64,
                    new int[]{0, 1, 0, 1});
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(2, 5, 2, 5);
        }

        /// The dict-value offsets can arrive at any integer width: FSST-decompressed
        /// values carry I32 offsets, legacy dicts use I64, and narrow sequence-encoded
        /// offsets keep their U8/U16 ptype. `dictReadOff` must read each at its true
        /// stride — reading a 4-byte-stride buffer as 8 bytes was the #215 crash.
        @ParameterizedTest
        @EnumSource(value = PType.class, names = {"U8", "I8", "U16", "I16", "I32", "U32", "I64", "U64"})
        void getString_resolvesOffsetsAtTrueWidth(PType offsetsPtype) {
            // Given — dict=["foo","bar"] with offsets [0,3,6] packed at this ptype's width,
            // codes=[1,0,1]. Small offsets fit every width including U8/I8, exercising the
            // exact narrow-ptype shape from uci-magic-gamma-telescope's FSST dict.
            VarBinArray sut = ofDictWithOffsets(new String[]{"foo", "bar"}, offsetsPtype, new int[]{1, 0, 1});

            // When
            String[] result = {sut.getString(0), sut.getString(1), sut.getString(2)};

            // Then
            assertThat(result).containsExactly("bar", "foo", "bar");
            assertThat(sut.getByteLength(0)).isEqualTo(3);
        }

        /// The #215 shape exactly: an I32-width offsets buffer (4-byte stride) mislabeled
        /// with an 8-byte ptype. The read must fail loudly with a helpful [VortexException],
        /// never a bare `IndexOutOfBoundsException` (ADR 0003 bounds discipline).
        @Test
        void getString_widthOvershootsBuffer_throwsVortexException() {
            // Given — 3 offsets packed as I32 (12-byte buffer) but the carrier claims I64.
            // Reading index 1 as 8 bytes needs offset 8..16 against a 12-byte segment.
            MemorySegment dictValBytes = MemorySegment.ofArray("foobar".getBytes(StandardCharsets.UTF_8));
            MemorySegment offsetsI32Width = leInts(new int[]{0, 3, 6});
            MemorySegment codes = leInts(new int[]{1});
            VarBinArray sut = VarBinArray.ofDict(UTF8, 1,
                    dictValBytes, offsetsI32Width, PType.I64, codes, PType.I32);

            // When / Then
            assertThatThrownBy(() -> sut.getString(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("out of range");
        }

        private static VarBinArray ofDictWithOffsets(String[] dictValues, PType offsetsPtype, int[] codes) {
            byte[] allBytes = String.join("", dictValues).getBytes(StandardCharsets.UTF_8);
            MemorySegment dictValBytes = MemorySegment.ofArray(allBytes);

            int[] offs = new int[dictValues.length + 1];
            for (int i = 0; i < dictValues.length; i++) {
                offs[i + 1] = offs[i] + dictValues[i].getBytes(StandardCharsets.UTF_8).length;
            }
            MemorySegment dictValOffsets = packOffsets(offs, offsetsPtype);
            MemorySegment dictCodes = leInts(codes);
            return VarBinArray.ofDict(UTF8, codes.length,
                    dictValBytes, dictValOffsets, offsetsPtype, dictCodes, PType.I32);
        }

        private static MemorySegment packCodes(long[] codes, PType ptype) {
            int width = ptype.byteSize();
            ByteBuffer bb = ByteBuffer.allocate(codes.length * width).order(ByteOrder.LITTLE_ENDIAN);
            for (long c : codes) {
                switch (width) {
                    case 1 -> bb.put((byte) c);
                    case 2 -> bb.putShort((short) c);
                    case 4 -> bb.putInt((int) c);
                    default -> bb.putLong(c);
                }
            }
            return MemorySegment.ofArray(bb.array());
        }

        private static MemorySegment packOffsets(int[] offs, PType ptype) {
            int width = ptype.byteSize();
            ByteBuffer bb = ByteBuffer.allocate(offs.length * width).order(ByteOrder.LITTLE_ENDIAN);
            for (int o : offs) {
                switch (width) {
                    case 1 -> bb.put((byte) o);
                    case 2 -> bb.putShort((short) o);
                    case 4 -> bb.putInt(o);
                    default -> bb.putLong(o);
                }
            }
            return MemorySegment.ofArray(bb.array());
        }

    }

    @Nested
    class Constant {

        @Test
        void getString_returnsSameValueForEveryRow() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 5, "hi".getBytes(StandardCharsets.UTF_8));

            // When / Then
            assertThat(sut.getString(0)).isEqualTo("hi");
            assertThat(sut.getString(4)).isEqualTo("hi");
        }

        @Test
        void getByteLength_returnsConstantLength() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 3, "abc".getBytes(StandardCharsets.UTF_8));

            // When / Then
            assertThat(sut.getByteLength(0)).isEqualTo(3);
            assertThat(sut.getByteLength(2)).isEqualTo(3);
        }

        @Test
        void forEachByteLength_visitsLengthOncePerRow() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 4, "xy".getBytes(StandardCharsets.UTF_8));
            List<Integer> lengths = new ArrayList<>();

            // When
            sut.forEachByteLength(lengths::add);

            // Then
            assertThat(lengths).containsExactly(2, 2, 2, 2);
        }

        /// [VarBinArray#getBytes(long)]'s contract is a copy per call; a shared backing array
        /// broadcast across every row must not let one row's mutation leak into another's.
        @Test
        void getBytes_returnsIndependentCopyEachCall() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 2, "z".getBytes(StandardCharsets.UTF_8));

            // When
            byte[] first = sut.getBytes(0);
            first[0] = (byte) 'Q';

            // Then — mutating the returned copy must not corrupt subsequent reads
            assertThat(sut.getBytes(1)).isEqualTo("z".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void getString_outOfBoundsIndex_throws() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 2, "v".getBytes(StandardCharsets.UTF_8));

            // When / Then
            assertThatThrownBy(() -> sut.getString(2)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        /// The zero-length constant is the shape `vortex.sparse` produces for a patch-free range
        /// (#340) — `n` empty strings. Every accessor must handle an empty backing array rather
        /// than assume at least one byte.
        @Test
        void emptyValue_readsAsEmptyStringOnEveryRow() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 3, new byte[0]);

            // When / Then
            assertThat(sut.getString(0)).isEmpty();
            assertThat(sut.getBytes(2)).isEmpty();
            assertThat(sut.getByteLength(1)).isZero();
        }

        @Test
        void limited_returnsShorterConstant() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 10, "k".getBytes(StandardCharsets.UTF_8));

            // When
            VarBinArray result = sut.limited(3);

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.getString(2)).isEqualTo("k");
        }

        @Test
        void limited_rowsAtOrAboveLength_returnsSameInstance() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 5, "m".getBytes(StandardCharsets.UTF_8));

            // When
            VarBinArray result = sut.limited(5);

            // Then
            assertThat(result).isSameAs(sut);
        }

        @Test
        void bytesSegment_isNull_noContiguousBufferBacksABroadcast() {
            // Given
            VarBinConstantArray sut = new VarBinConstantArray(UTF8, 2, "n".getBytes(StandardCharsets.UTF_8));

            // When / Then
            assertThat(sut.bytesSegment()).isSameAs(MemorySegment.NULL);
            assertThat(sut.segmentIfPresent()).isEmpty();
        }

        /// The general "any VarBinArray -> VarBinOffsetArray" path other decoders (RunEnd's string
        /// expansion, dict/sparse child normalization) rely on must still work for a broadcast
        /// constant, walking it via the typed accessors rather than `bytesSegment()`.
        @Test
        void toOffsetMode_materializesBroadcastIntoRealOffsets() {
            // Given
            VarBinArray sut = new VarBinConstantArray(UTF8, 3, "hey".getBytes(StandardCharsets.UTF_8));

            // When
            VarBinOffsetArray result = VarBinArray.toOffsetMode(sut, Arena.ofAuto());

            // Then
            assertThat(result.length()).isEqualTo(3);
            assertThat(result.getString(0)).isEqualTo("hey");
            assertThat(result.getString(1)).isEqualTo("hey");
            assertThat(result.getString(2)).isEqualTo("hey");
        }
    }
}
