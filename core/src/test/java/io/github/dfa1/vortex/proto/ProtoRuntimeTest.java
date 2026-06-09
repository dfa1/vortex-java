package io.github.dfa1.vortex.proto;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoRuntimeTest {

    @Nested
    class Varint {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, 127L, 128L, 150L, 16383L, 16384L, 0x7fffffffL, Long.MAX_VALUE, -1L, Long.MIN_VALUE})
        void roundTripsVarint64(long value) throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeVarint64(value);
            byte[] bytes = w.toByteArray();

            // When
            ProtoReader r = readerOver(bytes);

            // Then
            assertThat(r.readVarint64()).isEqualTo(value);
            assertThat(r.hasMore()).isFalse();
        }

        @Test
        void varintCanonicalEncodingOf150() throws IOException {
            // Given — proto3 spec example: 150 encodes as 0x96 0x01.
            ProtoWriter w = new ProtoWriter();
            w.writeVarint64(150L);

            // When
            byte[] bytes = w.toByteArray();

            // Then
            assertThat(bytes).containsExactly(0x96, 0x01);
        }

        @Test
        void truncatedVarintThrows() {
            // Given — MSB set on last byte means more bytes expected.
            MemorySegment seg = MemorySegment.ofArray(new byte[]{(byte) 0x80});

            // When + Then
            assertThatThrownBy(() -> new ProtoReader(seg, 0, 1).readVarint64())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated varint");
        }
    }

    @Nested
    class Sint {

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L, 1L, -2L, 2L, Long.MIN_VALUE, Long.MAX_VALUE})
        void roundTripsZigzag(long value) throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeSint64(value);

            // When
            long decoded = readerOver(w.toByteArray()).readSint64();

            // Then
            assertThat(decoded).isEqualTo(value);
        }
    }

    @Nested
    class Fixed {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x12345678})
        void roundTripsFixed32(int value) throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeFixed32(value);

            // When + Then
            assertThat(readerOver(w.toByteArray()).readFixed32()).isEqualTo(value);
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789abcdef0L})
        void roundTripsFixed64(long value) throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeFixed64(value);

            // When + Then
            assertThat(readerOver(w.toByteArray()).readFixed64()).isEqualTo(value);
        }

        @Test
        void roundTripsFloatAndDouble() throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeFloat(3.14f);
            w.writeDouble(Math.PI);

            // When
            ProtoReader r = readerOver(w.toByteArray());

            // Then
            assertThat(r.readFloat()).isEqualTo(3.14f);
            assertThat(r.readDouble()).isEqualTo(Math.PI);
        }
    }

    @Nested
    class LengthDelimited {

        @Test
        void roundTripsString() throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeString("hello 世界");

            // When
            String s = readerOver(w.toByteArray()).readString();

            // Then
            assertThat(s).isEqualTo("hello 世界");
        }

        @Test
        void roundTripsBytes() throws IOException {
            // Given
            byte[] payload = {1, 2, 3, 4, 5};
            ProtoWriter w = new ProtoWriter();
            w.writeBytes(payload);

            // When
            byte[] decoded = readerOver(w.toByteArray()).readBytes();

            // Then
            assertThat(decoded).containsExactly(payload);
        }

        @Test
        void lenDelimSegmentIsZeroCopy() throws IOException {
            // Given
            byte[] payload = {10, 20, 30, 40};
            ProtoWriter w = new ProtoWriter();
            w.writeBytes(payload);
            MemorySegment src = MemorySegment.ofArray(w.toByteArray());

            // When
            MemorySegment slice = new ProtoReader(src, 0, src.byteSize()).readLenDelimSegment();

            // Then — slice points into the original segment, not a fresh copy.
            assertThat(slice.byteSize()).isEqualTo(payload.length);
            assertThat(slice.address()).isEqualTo(src.address() + 1); // skip 1-byte length varint
        }

        @Test
        void truncatedLenDelimThrows() {
            // Given — length=10 but only 2 bytes follow.
            MemorySegment seg = MemorySegment.ofArray(new byte[]{10, 1, 2});

            // When + Then
            assertThatThrownBy(() -> new ProtoReader(seg, 0, 3).readBytes())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("truncated");
        }
    }

    @Nested
    class Tag {

        @Test
        void tagRoundTrip() throws IOException {
            // Given
            ProtoWriter w = new ProtoWriter();
            w.writeTag(5, WireType.LEN);

            // When
            int tag = readerOver(w.toByteArray()).readVarint32();

            // Then
            assertThat(tag >>> 3).isEqualTo(5);
            assertThat(tag & 7).isEqualTo(WireType.LEN);
        }
    }

    @Nested
    class Skip {

        @Test
        void skipsAllFourWireTypes() throws IOException {
            // Given — one of each wire type.
            ProtoWriter w = new ProtoWriter();
            w.writeTag(1, WireType.VARINT);
            w.writeVarint64(150);
            w.writeTag(2, WireType.FIXED32);
            w.writeFixed32(42);
            w.writeTag(3, WireType.FIXED64);
            w.writeFixed64(42L);
            w.writeTag(4, WireType.LEN);
            w.writeBytes(new byte[]{1, 2, 3});

            ProtoReader r = readerOver(w.toByteArray());

            // When — skip each field after reading its tag.
            for (int i = 0; i < 4; i++) {
                int tag = r.readVarint32();
                r.skipField(tag & 7);
            }

            // Then
            assertThat(r.hasMore()).isFalse();
        }
    }

    @Nested
    class PackedRepeated {

        @Test
        void readsPackedVarintRegion() throws IOException {
            // Given — 3 varints packed inside a length-delim region: lengths 1+1+2 = 4 bytes.
            ProtoWriter inner = new ProtoWriter();
            inner.writeVarint64(1);
            inner.writeVarint64(127);
            inner.writeVarint64(128);
            byte[] packed = inner.toByteArray();

            // Outer wrapper: just the payload (no tag prefix), simulating the LEN body.
            MemorySegment seg = MemorySegment.ofArray(packed);
            ProtoReader r = new ProtoReader(seg, 0, packed.length);

            // When
            long[] out = new long[3];
            int[] idx = {0};
            r.readPacked(packed.length, reader -> out[idx[0]++] = reader.readVarint64());

            // Then
            assertThat(out).containsExactly(1, 127, 128);
        }
    }

    @Nested
    class Bounds {

        @Test
        void constructorRejectsOutOfRangeOffset() {
            // Given
            MemorySegment seg = MemorySegment.ofArray(new byte[10]);

            // When + Then
            assertThatThrownBy(() -> new ProtoReader(seg, 5, 10))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        void constructorRejectsNegativeLength() {
            // Given
            MemorySegment seg = MemorySegment.ofArray(new byte[10]);

            // When + Then
            assertThatThrownBy(() -> new ProtoReader(seg, 0, -1))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    private static ProtoReader readerOver(byte[] bytes) {
        MemorySegment seg = MemorySegment.ofArray(bytes);
        return new ProtoReader(seg, 0, seg.byteSize());
    }
}
