package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.TimeUnit;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.time.LocalTime;
import java.util.List;

import static io.github.dfa1.vortex.extension.ExtensionTestSupport.I32;
import static io.github.dfa1.vortex.extension.ExtensionTestSupport.I64;
import static io.github.dfa1.vortex.extension.ExtensionTestSupport.ext;
import static io.github.dfa1.vortex.extension.ExtensionTestSupport.i32;
import static io.github.dfa1.vortex.extension.ExtensionTestSupport.i64;
import static io.github.dfa1.vortex.extension.ExtensionTestSupport.unitByte;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeExtensionTest {

    private final TimeExtension sut = TimeExtension.INSTANCE;

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
    void encodeAll_secondsReturnsIntArray() {
        // Given — Seconds resolution uses I32 storage; encodeAll must produce int[]
        // to match the writer's array-type switch
        Object packed = sut.encodeAll(List.of(LocalTime.of(1, 1, 1)), TimeUnit.Seconds);

        // Then — 01:01:01 = 3661 s
        assertThat(packed).isEqualTo(new int[]{3661});
    }

    @Test
    void encodeAll_nanosecondsReturnsLongArray() {
        // Given — nanos resolution needs I64; encodeAll switches return type to long[]
        Object packed = sut.encodeAll(List.of(LocalTime.ofNanoOfDay(42)), TimeUnit.Nanoseconds);

        // Then
        assertThat(packed).isEqualTo(new long[]{42L});
    }

    @Test
    void encodeAll_daysUnitThrows() {
        // Given — Days isn't sub-second; encodeAll fails with the same error decode does
        assertThatThrownBy(() -> sut.encodeAll(List.of(LocalTime.NOON), TimeUnit.Days))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("Days unit not valid");
    }
}
