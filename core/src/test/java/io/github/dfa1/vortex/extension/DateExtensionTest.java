package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalDate;
import java.util.List;

import static io.github.dfa1.vortex.extension.ExtensionTestSupport.I32;
import static org.assertj.core.api.Assertions.assertThat;

class DateExtensionTest {

    private final DateExtension sut = DateExtension.INSTANCE;

    @Test
    void identity() {
        // Given / When / Then — singleton + canonical id; pattern-match keys depend on it
        assertThat(sut.extensionId()).isSameAs(ExtensionId.VORTEX_DATE);
    }

    @Test
    void dtype_isI32StorageNoMetadata() {
        // Given / When — Arrow's canonical Date type carries no metadata; days fit in I32 through year 5_881_580
        DType.Extension dtype = sut.dtype(false);

        // Then
        assertThat(dtype.extensionId()).isEqualTo("vortex.date");
        assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I32, false));
        assertThat(dtype.metadata()).isNull();
    }

    @Test
    void decode_tpchSample() {
        // Given — anchor against known TPC-H value 9538 = 1996-02-12
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
            IntArray storage = new IntArray(I32, 1, buf);

            // When / Then
            assertThat(sut.decode(storage, 0)).isEqualTo(LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void decode_negativeDays_returnsPreEpoch() {
        // Given — defensive: signed storage, pre-1970 must work
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, -1);
            IntArray storage = new IntArray(I32, 1, buf);

            // When / Then
            assertThat(sut.decode(storage, 0)).isEqualTo(LocalDate.of(1969, 12, 31));
        }
    }

    @Test
    void encode_singleDate_returnsEpochDay() {
        // Given / When / Then — 1996-02-12 anchored against the same TPC-H sample as decode
        assertThat(sut.encode(LocalDate.of(1996, 2, 12))).isEqualTo(9538);
    }

    @Test
    void decodeAll_maskedArray_yieldsNullAtInvalidPositions() {
        // Given — MaskedArray wrapping IntArray: 3 rows, row 1 null. epochInteger
        // would throw on the null cell; decodeAll must short-circuit via isValid
        // and emit a null entry instead.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(12);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, sut.encode(LocalDate.of(2026, 1, 1)));
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 0);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 8, sut.encode(LocalDate.of(2026, 1, 3)));
            IntArray inner = new IntArray(I32, 3, buf);
            MemorySegment validityBuf = arena.allocate(1);
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0101);
            BoolArray validity = new BoolArray(new DType.Bool(false), 3, validityBuf);

            // When
            List<LocalDate> out = sut.decodeAll(new MaskedArray(inner, validity));

            // Then
            assertThat(out).containsExactly(
                    LocalDate.of(2026, 1, 1),
                    null,
                    LocalDate.of(2026, 1, 3));
        }
    }

}
