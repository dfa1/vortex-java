package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.writer.encode.ComparableValues;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;

/// [ZoneMapStatCodec#columnMinMax] is the generic fallback [VortexWriter#writeSegment] calls
/// whenever the winning encoder's own [io.github.dfa1.vortex.writer.encode.EncodeResult] didn't
/// already supply stats -- the fix for the whole class of bug where an individual encoder simply
/// forgot to (#382/#384-#386 and ten more, ADR 0025). Since encoders are no longer *required* to
/// report their own stats, this is the actual place the guarantee now lives, so it gets direct
/// coverage over every dtype shape it dispatches on, independent of which specific encoder a
/// cascade might pick for any of them.
class ZoneMapStatCodecTest {

    @Nested
    class Primitive {

        @Test
        void signed_reportsRealMinMax() throws IOException {
            // Given
            long[] data = {10L, -5L, 3L, 7L};

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(DTypes.I64, data);

            // Then
            assertThat(scalar(stats[0]).int64_value()).isEqualTo(-5L);
            assertThat(scalar(stats[1]).int64_value()).isEqualTo(10L);
        }

        @Test
        void unsigned_usesUnsignedComparison() throws IOException {
            // Given -- -1's raw bits are the largest possible U32 magnitude, not the smallest
            int[] data = {1, -1, 5};

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(DTypes.U32, data);

            // Then
            assertThat(scalar(stats[0]).uint64_value()).isEqualTo(1L);
            assertThat(scalar(stats[1]).uint64_value()).isEqualTo(4294967295L);
        }

        @Test
        void nullable_compactsToValidElementsOnly() throws IOException {
            // Given -- the #381 bug this generalizes: invalid slots carry a placeholder (here 0),
            // which is not a min/max identity and must not corrupt the reported extremes
            long[] values = {0L, 100L, 0L, -50L};
            boolean[] validity = {false, true, false, true};
            NullableData data = new NullableData(values, validity);

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(DTypes.I64.asNullable(), data);

            // Then
            assertThat(scalar(stats[0]).int64_value()).isEqualTo(-50L);
            assertThat(scalar(stats[1]).int64_value()).isEqualTo(100L);
        }

        @Test
        void empty_returnsNull() {
            // Given
            long[] data = {};

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(DTypes.I64, data);

            // Then
            assertThat(stats).isNull();
        }
    }

    @Nested
    class ExtensionType {

        private static final DType TIMESTAMP_MS =
                new DType.Extension("vortex.timestamp", DType.I64, MemorySegment.ofArray(new byte[]{0, 0, 0}), false);

        @Test
        void plainPrimitiveStorage_unwrapsToStoragePrimitive() throws IOException {
            // Given -- an extension whose data is a bare primitive array, not a special carrier
            long[] data = {100L, 200L, 50L};

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(TIMESTAMP_MS, data);

            // Then
            assertThat(scalar(stats[0]).int64_value()).isEqualTo(50L);
            assertThat(scalar(stats[1]).int64_value()).isEqualTo(200L);
        }

        @Test
        void comparableValuesCarrier_usesItsOwnComparableForm() throws IOException {
            // Given -- a carrier whose literal shape isn't a plain primitive array (like
            // DateTimePartsData splitting a timestamp into day/second/subsecond parts); its
            // *comparable* form is what ComparableValues exposes, not the carrier itself
            record FakeCarrier(long[] comparable) implements ComparableValues {
                @Override
                public PType ptype() {
                    return PType.I64;
                }

                @Override
                public Object values() {
                    return comparable;
                }
            }
            FakeCarrier carrier = new FakeCarrier(new long[]{9L, 1L, 5L});

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(TIMESTAMP_MS, carrier);

            // Then
            assertThat(scalar(stats[0]).int64_value()).isEqualTo(1L);
            assertThat(scalar(stats[1]).int64_value()).isEqualTo(9L);
        }

        @Test
        void nullablePlainStorage_compactsToValidElementsOnly() throws IOException {
            // Given
            long[] values = {0L, 300L, 0L};
            boolean[] validity = {false, true, false};
            NullableData data = new NullableData(values, validity);

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(TIMESTAMP_MS, data);

            // Then
            assertThat(scalar(stats[0]).int64_value()).isEqualTo(300L);
            assertThat(scalar(stats[1]).int64_value()).isEqualTo(300L);
        }
    }

    @Nested
    class Utf8Type {

        @Test
        void reportsLexicographicMinMax() throws IOException {
            // Given
            String[] data = {"banana", "apple", "cherry"};

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(DTypes.UTF8, data);

            // Then
            assertThat(scalar(stats[0]).string_value()).isEqualTo("apple");
            assertThat(scalar(stats[1]).string_value()).isEqualTo("cherry");
        }

        @Test
        void nullable_skipsNullEntriesDirectly() throws IOException {
            // Given -- Utf8's NullableData carries actual null array entries, not a placeholder
            // scheme, so no compaction step is needed before the lexicographic scan
            String[] values = {null, "banana", null, "apple"};
            NullableData data = new NullableData(values, new boolean[]{false, true, false, true});

            // When
            byte[][] stats = ZoneMapStatCodec.columnMinMax(DTypes.UTF8.asNullable(), data);

            // Then
            assertThat(scalar(stats[0]).string_value()).isEqualTo("apple");
            assertThat(scalar(stats[1]).string_value()).isEqualTo("banana");
        }
    }

    @Nested
    class NotEligible {

        @Test
        void binary_returnsNull() {
            // Given -- min/max is lexicographic-string-only; a binary blob isn't zone-mapped
            byte[][] data = {{1, 2}, {3, 4}};

            // When / Then
            assertThat(ZoneMapStatCodec.columnMinMax(DTypes.BINARY, data)).isNull();
        }

        @Test
        void decimal_returnsNull() {
            // Given -- Decimal is not in ZoneMapStatCodec#zoneMinMaxDtype's supported set
            DType decimal = new DType.Decimal((byte) 10, (byte) 2, false);

            // When / Then
            assertThat(ZoneMapStatCodec.columnMinMax(decimal, new long[]{100L})).isNull();
        }

        @Test
        void bool_returnsNull() {
            // When / Then
            assertThat(ZoneMapStatCodec.columnMinMax(DTypes.BOOL, new boolean[]{true, false})).isNull();
        }

        @Test
        void structuralType_returnsNull() {
            // Given
            DType list = new DType.List(DTypes.I64, false);

            // When / Then
            assertThat(ZoneMapStatCodec.columnMinMax(list, new Object[0])).isNull();
        }
    }

    private static ProtoScalarValue scalar(byte[] bytes) throws IOException {
        MemorySegment seg = MemorySegment.ofArray(bytes);
        return ProtoScalarValue.decode(seg, 0, seg.byteSize());
    }
}
