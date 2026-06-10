package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.NullableData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static io.github.dfa1.vortex.extension.ExtensionTestSupport.I32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Nested
    class PolymorphicEncodeAll {

        @Test
        void noNulls_returnsPlainIntArray() {
            // Given — non-null inputs must keep the historical int[] return shape so
            // existing callers (non-nullable schemas) stay zero-overhead
            DType.Extension dtype = sut.dtype(false);

            // When
            Object result = sut.encodeAll(dtype, List.of(LocalDate.of(2026, 1, 1)));

            // Then
            assertThat(result).isInstanceOf(int[].class);
        }

        @Test
        void withNulls_nullableDtype_returnsNullableDataWithZeroPlaceholder() {
            // Given — mixed null run; nullable dtype declares the column nullable so writers
            // can wrap the storage in vortex.masked. Placeholder must be 0 so the masked
            // child stays free of semantic nulls (matches the MaskedArray invariant).
            DType.Extension dtype = sut.dtype(true);
            List<LocalDate> dates = Arrays.asList(
                    LocalDate.of(2026, 1, 1),
                    null,
                    LocalDate.of(2026, 1, 3));

            // When
            Object result = sut.encodeAll(dtype, dates);

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData nd = (NullableData) result;
            int[] storage = (int[]) nd.values();
            assertThat(storage).hasSize(3);
            assertThat(storage[0]).isEqualTo(sut.encode(LocalDate.of(2026, 1, 1)));
            assertThat(storage[1]).isZero();
            assertThat(storage[2]).isEqualTo(sut.encode(LocalDate.of(2026, 1, 3)));
            assertThat(nd.validity()).containsExactly(true, false, true);
        }

        @Test
        void withNulls_nonNullableDtype_throws() {
            // Given — schema declared NOT NULL but caller passed nulls; writer must refuse loudly
            // rather than silently writing zeros
            DType.Extension dtype = sut.dtype(false);

            // When / Then
            assertThatThrownBy(() -> sut.encodeAll(dtype, Arrays.asList(LocalDate.of(2026, 1, 1), null)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vortex.date");
        }
    }

    @Test
    void encodeAll_thenDecodeAll_roundTrips() {
        // Given — a small set covers ordering + a pre-epoch row to exercise sign handling
        List<LocalDate> dates = List.of(
                LocalDate.of(1969, 12, 31),
                LocalDate.of(1970, 1, 1),
                LocalDate.of(1996, 2, 12),
                LocalDate.of(2026, 6, 9));

        // When — encode -> storage Array -> decodeAll
        int[] packed = sut.encodeAll(dates);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(packed.length * 4L);
            for (int i = 0; i < packed.length; i++) {
                buf.set(ValueLayout.JAVA_INT_UNALIGNED, i * 4L, packed[i]);
            }
            IntArray storage = new IntArray(I32, packed.length, buf);

            // Then — order + values preserved end-to-end
            assertThat(sut.decodeAll(storage)).isEqualTo(dates);
        }
    }
}
