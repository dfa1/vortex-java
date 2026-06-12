package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.encoding.TimeUnit;
import io.github.dfa1.vortex.extension.ExtensionId;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.LocalTime;
import java.util.List;

import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.I32;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.I64;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.ext;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.i32;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.i64;
import static io.github.dfa1.vortex.reader.extension.ExtensionTestSupport.unitByte;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeExtensionDecoderTest {

    private final TimeExtensionDecoder sut = TimeExtensionDecoder.INSTANCE;

    @Test
    void identity() {
        assertThat(sut.extensionId()).isSameAs(ExtensionId.VORTEX_TIME);
    }

    @Test
    void dtype_defaultsToMillisecondsI32() {
        // Given — default factory must pick the most common resolution
        DType.Extension dtype = sut.dtype(true);

        // Then — storage width is I32 for s/ms, metadata byte 0 = TimeUnit.Milliseconds.ordinal()
        assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I32, true));
        assertThat(dtype.metadata().get(0)).isEqualTo((byte) TimeUnit.Milliseconds.ordinal());
    }

    @Test
    void dtype_nanosecondsForceI64() {
        // Given — nanos overflow I32; the factory must promote storage to I64
        DType.Extension dtype = sut.dtype(TimeUnit.Nanoseconds, false);

        // Then
        assertThat(dtype.storageDType()).isEqualTo(new DType.Primitive(PType.I64, false));
        assertThat(dtype.metadata().get(0)).isEqualTo((byte) TimeUnit.Nanoseconds.ordinal());
    }

    @Test
    void decode_eachUnit() {
        // Given — round-trip a known time-of-day through every TimeUnit
        try (Arena arena = Arena.ofConfined()) {
            // Seconds: 3661 s = 01:01:01
            assertThat(sut.decode(ext("vortex.time", I32, unitByte((byte) 3)),
                    i32(arena, 3661), 0))
                    .isEqualTo(LocalTime.of(1, 1, 1));
            // Milliseconds: 3_661_500 = 01:01:01.500
            assertThat(sut.decode(ext("vortex.time", I32, unitByte((byte) 2)),
                    i32(arena, 3_661_500), 0))
                    .isEqualTo(LocalTime.of(1, 1, 1, 500_000_000));
            // Microseconds: 1_000_001 = 00:00:01.000001
            assertThat(sut.decode(ext("vortex.time", I64, unitByte((byte) 1)),
                    i64(arena, 1_000_001L), 0))
                    .isEqualTo(LocalTime.of(0, 0, 1, 1_000));
            // Nanoseconds: 42 ns past midnight
            assertThat(sut.decode(ext("vortex.time", I64, unitByte((byte) 0)),
                    i64(arena, 42L), 0))
                    .isEqualTo(LocalTime.ofNanoOfDay(42));
        }
    }

    @Test
    void decode_daysUnitThrows() {
        // Given — Days isn't a sub-second unit
        try (Arena arena = Arena.ofConfined()) {
            DType.Extension dtype = ext("vortex.time", I32, unitByte((byte) 4));

            // When / Then
            assertThatThrownBy(() -> sut.decode(dtype, i32(arena, 0), 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("Days unit not valid");
        }
    }

    @Test
    void decodeAll_maskedArray_yieldsNullAtInvalidPositions() {
        // Given — Milliseconds (I32 storage) + validity at position 1 = null
        try (Arena arena = Arena.ofConfined()) {
            int millisNoon = (int) (LocalTime.NOON.toNanoOfDay() / 1_000_000L);
            MemorySegment buf = arena.allocate(8);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 0, millisNoon);
            buf.set(ValueLayout.JAVA_INT_UNALIGNED, 4, 0);
            IntArray inner = new IntArray(I32, 2, buf);
            MemorySegment validityBuf = arena.allocate(1);
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0001);
            BoolArray validity = new BoolArray(new DType.Bool(false), 2, validityBuf);

            // When
            List<LocalTime> out = sut.decodeAll(
                    sut.dtype(TimeUnit.Milliseconds, true),
                    new MaskedArray(inner, validity));

            // Then
            assertThat(out).containsExactly(LocalTime.NOON, null);
        }
    }
}
