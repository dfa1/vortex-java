package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.MaterializedBoolArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalDate;
import java.util.List;

import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.I32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateExtensionDecoderTest {

    private static final DType.Extension DAYS = DateExtensionDecoder.INSTANCE.dtype(false);
    private static final DType.Extension MILLIS = dateDtype(TimeUnit.Milliseconds, DType.I64);

    private final DateExtensionDecoder sut = DateExtensionDecoder.INSTANCE;

    @Test
    void identity() {
        // Given / When / Then — singleton + canonical id; pattern-match keys depend on it
        assertThat(sut.extensionId()).isSameAs(ExtensionId.VORTEX_DATE);
    }

    @Test
    void dtype_isI32StorageWithDaysMetadata() {
        // Given / When — vortex.date requires a 1-byte TimeUnit tag (Days = 4) so the Rust reader
        // can call deserialize_metadata without hitting its "metadata must not be empty" guard
        DType.Extension dtype = sut.dtype(false);

        // Then
        assertThat(dtype.extensionId()).isEqualTo("vortex.date");
        assertThat(dtype.storageDType()).isEqualTo(DType.I32);
        assertThat(dtype.metadata()).isNotNull();
        assertThat(dtype.metadata().byteSize()).isEqualTo(1);
        assertThat(dtype.metadata().get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 4); // TimeUnit.Days ordinal
    }

    @Test
    void decode_tpchSample() {
        // Given — anchor against known TPC-H value 9538 = 1996-02-12
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, 9538);
            IntArray storage = new MaterializedIntArray(I32, 1, buf);

            // When / Then
            assertThat(sut.decode(DAYS, storage, 0)).isEqualTo(LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void decode_negativeDays_returnsPreEpoch() {
        // Given — defensive: signed storage, pre-1970 must work
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, -1);
            IntArray storage = new MaterializedIntArray(I32, 1, buf);

            // When / Then
            assertThat(sut.decode(DAYS, storage, 0)).isEqualTo(LocalDate.of(1969, 12, 31));
        }
    }

    @Test
    void decodeAll_maskedArray_yieldsNullAtInvalidPositions() {
        // Given — MaskedArray wrapping IntArray: 3 rows, row 1 null. epochInteger
        // would throw on the null cell; decodeAll must short-circuit via isValid
        // and emit a null entry instead.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(12);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) LocalDate.of(2026, 1, 1).toEpochDay());
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 0);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 8, (int) LocalDate.of(2026, 1, 3).toEpochDay());
            IntArray inner = new MaterializedIntArray(I32, 3, buf);
            MemorySegment validityBuf = arena.allocate(1);
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0101);
            BoolArray validity = new MaterializedBoolArray(DType.BOOL, 3, validityBuf);

            // When
            List<LocalDate> out = sut.decodeAll(DAYS, new MaskedArray(inner, validity));

            // Then
            assertThat(out).containsExactly(
                    LocalDate.of(2026, 1, 1),
                    null,
                    LocalDate.of(2026, 1, 3));
        }
    }

    @ParameterizedTest
    @CsvSource({
            // Rust stores an Arrow Date64 as vortex.date(Milliseconds) over I64. The decoder used
            // to read the count as days, so these came back ~86 million times too far from 1970.
            "0, 1970-01-01",
            "86400000, 1970-01-02",
            "824083200000, 1996-02-12",
            // before the epoch a millisecond count floors to the earlier day, not toward zero
            "-1, 1969-12-31",
            "-86400000, 1969-12-31",
            "-86400001, 1969-12-30"
    })
    void decode_milliseconds_floorsToTheDay(long millis, LocalDate expected) {
        // Given
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, millis);
            LongArray storage = new MaterializedLongArray(DType.I64, 1, buf);

            // When
            LocalDate result = sut.decode(MILLIS, storage, 0);

            // Then
            assertThat(result).isEqualTo(expected);
        }
    }

    @Test
    void decodeAll_milliseconds_usesTheUnit() {
        // Given — the same unit must reach decodeAll, the path Chunk.as takes
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0L);
            buf.set(ValueLayout.JAVA_LONG_UNALIGNED, 8, 824_083_200_000L);
            LongArray storage = new MaterializedLongArray(DType.I64, 2, buf);

            // When
            List<LocalDate> result = sut.decodeAll(MILLIS, storage);

            // Then
            assertThat(result).containsExactly(LocalDate.EPOCH, LocalDate.of(1996, 2, 12));
        }
    }

    @Test
    void date_unsupportedUnit_throws() {
        // Given — Rust's Date supports only Days and Milliseconds
        DType.Extension seconds = dateDtype(TimeUnit.Seconds, DType.I64);

        // When / Then
        assertThatThrownBy(() -> sut.date(seconds, 1L))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("does not support time unit Seconds");
    }

    private static DType.Extension dateDtype(TimeUnit unit, DType storage) {
        return new DType.Extension("vortex.date", storage,
                MemorySegment.ofArray(new byte[]{(byte) unit.ordinal()}), false);
    }
}
