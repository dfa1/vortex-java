package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.TimeDtype;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeExtensionEncoderTest {

    private static final TimeExtensionEncoder SUT = TimeExtensionEncoder.INSTANCE;
    private static final LocalTime T = LocalTime.of(1, 2, 3, 456_000_000); // 01:02:03.456

    @Test
    void extensionId_isVortexTime() {
        // Given / When / Then
        assertThat(SUT.extensionId()).isEqualTo(ExtensionId.VORTEX_TIME);
    }

    @Test
    void dtype_default_isMillisecondsOverI32() {
        // Given / When
        DType.Extension result = SUT.dtype(false);

        // Then
        assertThat(TimeDtype.readUnit(result)).isEqualTo(TimeUnit.Milliseconds);
        assertThat(result.storageDType()).isEqualTo(DType.I32);
    }

    @Test
    void dtype_seconds_usesI32() {
        // Given / When
        DType.Extension result = SUT.dtype(TimeUnit.Seconds, true);

        // Then
        assertThat(TimeDtype.readUnit(result)).isEqualTo(TimeUnit.Seconds);
        assertThat(result.storageDType()).isEqualTo(new DType.Primitive(PType.I32, true));
    }

    @Test
    void dtype_nanoseconds_usesI64() {
        // Given / When
        DType.Extension result = SUT.dtype(TimeUnit.Nanoseconds, false);

        // Then
        assertThat(TimeDtype.readUnit(result)).isEqualTo(TimeUnit.Nanoseconds);
        assertThat(result.storageDType()).isEqualTo(DType.I64);
    }

    @Test
    void encodeAll_seconds_returnsIntArray() {
        // Given seconds resolution truncates sub-second precision
        DType.Extension dtype = SUT.dtype(TimeUnit.Seconds, false);

        // When
        Object result = SUT.encodeAll(dtype, List.of(T));

        // Then
        assertThat(result).isInstanceOf(int[].class);
        assertThat((int[]) result).containsExactly(1 * 3600 + 2 * 60 + 3);
    }

    @Test
    void encodeAll_milliseconds_returnsIntArray() {
        // Given
        DType.Extension dtype = SUT.dtype(TimeUnit.Milliseconds, false);

        // When
        Object result = SUT.encodeAll(dtype, List.of(T));

        // Then
        long expectedMs = (1 * 3600 + 2 * 60 + 3) * 1000L + 456;
        assertThat(result).isInstanceOf(int[].class);
        assertThat((int[]) result).containsExactly((int) expectedMs);
    }

    @Test
    void encodeAll_microseconds_returnsLongArray() {
        // Given
        DType.Extension dtype = SUT.dtype(TimeUnit.Microseconds, false);

        // When
        Object result = SUT.encodeAll(dtype, List.of(T));

        // Then
        assertThat(result).isInstanceOf(long[].class);
        assertThat((long[]) result).containsExactly(T.toNanoOfDay() / 1_000L);
    }

    @Test
    void encodeAll_nanoseconds_returnsLongArray() {
        // Given
        DType.Extension dtype = SUT.dtype(TimeUnit.Nanoseconds, false);

        // When
        Object result = SUT.encodeAll(dtype, List.of(T));

        // Then
        assertThat(result).isInstanceOf(long[].class);
        assertThat((long[]) result).containsExactly(T.toNanoOfDay());
    }

    @Test
    void encodeAll_nullWithNullableInt_returnsNullableDataWithZeroPlaceholder() {
        // Given a null in a nullable column
        DType.Extension dtype = SUT.dtype(TimeUnit.Milliseconds, true);

        // When
        Object result = SUT.encodeAll(dtype, Arrays.asList(T, null));

        // Then storage carries a zero placeholder at the null position; validity marks it
        assertThat(result).isInstanceOf(NullableData.class);
        NullableData nd = (NullableData) result;
        int[] values = (int[]) nd.values();
        assertThat(values[1]).isZero();
        assertThat(nd.validity()).containsExactly(true, false);
    }

    @Test
    void encodeAll_nullWithNullableLong_returnsNullableData() {
        // Given a null in a nullable μs column (long storage)
        DType.Extension dtype = SUT.dtype(TimeUnit.Microseconds, true);

        // When
        Object result = SUT.encodeAll(dtype, Arrays.asList(null, T));

        // Then
        NullableData nd = (NullableData) result;
        assertThat(nd.values()).isInstanceOf(long[].class);
        assertThat(nd.validity()).containsExactly(false, true);
    }

    @Test
    void encodeAll_nullInNonNullableColumn_throws() {
        // Given a null in a non-nullable column
        DType.Extension dtype = SUT.dtype(TimeUnit.Milliseconds, false);
        List<LocalTime> values = Arrays.asList(T, null);

        // When / Then
        assertThatThrownBy(() -> SUT.encodeAll(dtype, values))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("non-nullable");
    }

    @Test
    void encodeAll_daysUnit_throws() {
        // Given a hand-built Days-tagged dtype (TimeDtype.of rejects Days, so build directly)
        MemorySegment meta = MemorySegment.ofArray(new byte[]{(byte) TimeUnit.Days.ordinal()});
        DType.Extension dtype = new DType.Extension(
                ExtensionId.VORTEX_TIME.id(), DType.I32, meta, false);
        List<LocalTime> values = List.of(T);

        // When / Then
        assertThatThrownBy(() -> SUT.encodeAll(dtype, values))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("Days");
    }
}
