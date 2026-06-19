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
            ProtoReader result = readerOver(bytes);

            // Then
            assertThat(result.readVarint64()).isEqualTo(value);
            assertThat(result.hasMore()).isFalse();
        }

        @Test
        void varintCanonicalEncodingOf150() throws IOException {
            // Given — proto3 spec example: 150 encodes as 0x96 0x01.
            ProtoWriter w = new ProtoWriter();
            w.writeVarint64(150L);

            // When
            byte[] result = w.toByteArray();

            // Then
            assertThat(result).containsExactly(0x96, 0x01);
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
            long result = readerOver(w.toByteArray()).readSint64();

            // Then
            assertThat(result).isEqualTo(value);
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
            ProtoReader result = readerOver(w.toByteArray());

            // Then
            assertThat(result.readFloat()).isEqualTo(3.14f);
            assertThat(result.readDouble()).isEqualTo(Math.PI);
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
            String result = readerOver(w.toByteArray()).readString();

            // Then
            assertThat(result).isEqualTo("hello 世界");
        }

        @Test
        void roundTripsBytes() throws IOException {
            // Given
            byte[] payload = {1, 2, 3, 4, 5};
            ProtoWriter w = new ProtoWriter();
            w.writeBytes(payload);

            // When
            byte[] result = readerOver(w.toByteArray()).readBytes();

            // Then
            assertThat(result).containsExactly(payload);
        }

        @Test
        void lenDelimSegmentIsZeroCopy() throws IOException {
            // Given
            byte[] payload = {10, 20, 30, 40};
            ProtoWriter w = new ProtoWriter();
            w.writeBytes(payload);
            MemorySegment src = MemorySegment.ofArray(w.toByteArray());

            // When
            MemorySegment result = new ProtoReader(src, 0, src.byteSize()).readLenDelimSegment();

            // Then — result points into the original segment, not a fresh copy.
            assertThat(result.byteSize()).isEqualTo(payload.length);
            assertThat(result.address()).isEqualTo(src.address() + 1); // skip 1-byte length varint
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
            int result = readerOver(w.toByteArray()).readVarint32();

            // Then
            assertThat(result >>> 3).isEqualTo(5);
            assertThat(result & 7).isEqualTo(WireType.LEN);
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
            long[] result = new long[3];
            int[] idx = {0};
            r.readPacked(packed.length, reader -> result[idx[0]++] = reader.readVarint64());

            // Then
            assertThat(result).containsExactly(1, 127, 128);
        }
    }

    @Nested
    class VarintOverflow {

        @Test
        void elevenContinuationBytesThrows() {
            // Given — 11 bytes with MSB set: exceeds the 10-byte varint64 limit.
            // The reader must throw "varint overflow" rather than silently truncating.
            byte[] bytes = new byte[11];
            for (int i = 0; i < 11; i++) {
                bytes[i] = (byte) 0xff;
            }
            MemorySegment seg = MemorySegment.ofArray(bytes);

            // When + Then
            assertThatThrownBy(() -> new ProtoReader(seg, 0, 11).readVarint64())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("varint overflow");
        }
    }

    @Nested
    class UnknownEnum {

        @Test
        void unknownPTypeValueIsCheckedIOException() {
            // Given — Primitive { type = 99 } where PType has no constant for 99.
            // Field tag 1, wire type VARINT (0): tag byte = (1 << 3) | 0 = 0x08, value 99 = 0x63.
            byte[] wire = new byte[]{0x08, 0x63};
            MemorySegment seg = MemorySegment.ofArray(wire);

            // When + Then — must be checked IOException (per SECURITY.md guarantee),
            // not the underlying IllegalArgumentException from Enum.fromValue.
            assertThatThrownBy(() -> Primitive.decode(seg, 0, wire.length))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("PType");
        }
    }

    @Nested
    class SingleNullEncode {

        @Test
        void singleStringNullEncodesEmpty() {
            // Given — Extension with all SINGLE fields null. Pre-fix this NPE'd on
            // String.isEmpty() / byte[].length. Default-value SINGLE fields must skip
            // emitting the tag entirely.
            Extension ext = new Extension(null, null, null);

            // When
            byte[] wire = ext.encode();

            // Then — no fields emitted, no NPE.
            assertThat(wire).isEmpty();
        }
    }

    @Nested
    class ByteArrayEquality {

        @Test
        void recordsWithEqualByteArraysAreEqual() {
            // Given — records auto-equals would do reference compare on byte[]. The
            // generator overrides equals/hashCode with Arrays.equals/Arrays.hashCode
            // so structurally equal records compare equal.
            ScalarValue a = ScalarValue.ofBytesValue(new byte[]{1, 2, 3});
            ScalarValue b = ScalarValue.ofBytesValue(new byte[]{1, 2, 3});

            // When + Then
            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void recordsWithDifferentByteArraysAreNotEqual() {
            // Given
            ScalarValue a = ScalarValue.ofBytesValue(new byte[]{1, 2, 3});
            ScalarValue b = ScalarValue.ofBytesValue(new byte[]{1, 2, 4});

            // When + Then
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    class Backpatch {

        @Test
        void shortPayloadCompactsLengthVarint() throws IOException {
            // Given — payload of 3 bytes fits in 1-byte varint length.
            // beginLenDelim reserves 5 bytes; endLenDelim shifts the payload left by 4.
            ProtoWriter w = new ProtoWriter();
            int mark = w.beginLenDelim();
            w.writeVarint64(1);
            w.writeVarint64(2);
            w.writeVarint64(3);
            w.endLenDelim(mark);

            // When
            byte[] bytes = w.toByteArray();

            // Then — len=3 (1 byte) + 3 payload bytes = 4 total, no wasted padding.
            assertThat(bytes).containsExactly(0x03, 0x01, 0x02, 0x03);
        }

        @Test
        void backpatchedMatchesLegacyEmbeddedPattern() throws IOException {
            // Given — same packed varint payload via backpatch vs. the legacy
            // "temp ProtoWriter + writeEmbedded" pattern. Output bytes must match exactly,
            // proving the backpatch refactor is wire-compatible.
            ProtoWriter backpatch = new ProtoWriter();
            int mark = backpatch.beginLenDelim();
            for (int i = 0; i < 50; i++) {
                backpatch.writeVarint64(i);
            }
            backpatch.endLenDelim(mark);

            ProtoWriter legacy = new ProtoWriter();
            ProtoWriter inner = new ProtoWriter();
            for (int i = 0; i < 50; i++) {
                inner.writeVarint64(i);
            }
            legacy.writeEmbedded(inner.toByteArray());

            // When + Then
            assertThat(backpatch.toByteArray()).containsExactly(legacy.toByteArray());
        }

        @Test
        void emptyPayloadProducesSingleZeroLength() {
            // Given — len-delim region with no payload.
            ProtoWriter w = new ProtoWriter();
            int mark = w.beginLenDelim();
            w.endLenDelim(mark);

            // When + Then — single 0x00 byte (length=0), 4 padding bytes shifted out.
            assertThat(w.toByteArray()).containsExactly(0x00);
        }

        @Test
        void largePayloadKeepsMultiByteLengthVarint() throws IOException {
            // Given — payload large enough to need a 2-byte length varint (>= 128 bytes).
            // Shift = 5 - 2 = 3 bytes leftward.
            ProtoWriter w = new ProtoWriter();
            int mark = w.beginLenDelim();
            byte[] payload = new byte[200];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i & 0xff);
            }
            for (byte b : payload) {
                w.writeFixed32(b & 0xff); // 4 bytes each — actually use raw write
            }
            w.endLenDelim(mark);

            // When — decode the length back, verify payload survives the shift.
            byte[] bytes = w.toByteArray();
            ProtoReader r = new ProtoReader(MemorySegment.ofArray(bytes), 0, bytes.length);
            int len = r.readVarint32();

            // Then — length matches 200 * 4 = 800; remaining bytes are exactly the payload.
            assertThat(len).isEqualTo(800);
            assertThat(bytes.length - varintBytes(len)).isEqualTo(800);
        }

        private int varintBytes(int v) {
            int n = 1;
            while ((v & ~0x7f) != 0) {
                v >>>= 7;
                n++;
            }
            return n;
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
