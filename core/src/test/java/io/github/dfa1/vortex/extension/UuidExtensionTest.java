package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.BoolArray;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import io.github.dfa1.vortex.core.array.MaskedArray;
import io.github.dfa1.vortex.core.array.NullableData;
import io.github.dfa1.vortex.encoding.FixedSizeListData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static io.github.dfa1.vortex.extension.ExtensionTestSupport.U8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidExtensionTest {

    private final UuidExtension sut = UuidExtension.INSTANCE;

    @Test
    void identity() {
        assertThat(sut.extensionId()).isSameAs(ExtensionId.VORTEX_UUID);
    }

    @Test
    void dtype_isFixedSizeListOf16U8() {
        // Given / When — UUID storage is canonically FixedSizeList<U8>(16); no extension metadata
        DType.Extension dtype = sut.dtype(true);

        // Then
        DType.Primitive u8 = new DType.Primitive(PType.U8, false);
        assertThat(dtype.storageDType()).isEqualTo(new DType.FixedSizeList(u8, 16, true));
        assertThat(dtype.metadata()).isNull();
    }

    @Test
    void decode_roundTripsKnownValue() {
        // Given — RFC 9562 example
        UUID expected = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            long msb = expected.getMostSignificantBits();
            long lsb = expected.getLeastSignificantBits();
            for (int k = 0; k < 8; k++) {
                buf.set(ValueLayout.JAVA_BYTE, k, (byte) ((msb >> (56 - 8 * k)) & 0xff));
                buf.set(ValueLayout.JAVA_BYTE, 8 + k, (byte) ((lsb >> (56 - 8 * k)) & 0xff));
            }
            ByteArray inner = new ByteArray(U8, 16, buf);
            FixedSizeListArray storage = new FixedSizeListArray(
                    new DType.FixedSizeList(U8, 16, false), 1, inner);

            // When / Then
            assertThat(sut.decode(storage, 0)).isEqualTo(expected);
        }
    }

    @Test
    void decode_allOnes_noSignExtension() {
        // Given — 0xff in every byte trips sign-extension bugs in the mask
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(16);
            for (int k = 0; k < 16; k++) {
                buf.set(ValueLayout.JAVA_BYTE, k, (byte) 0xff);
            }
            ByteArray inner = new ByteArray(U8, 16, buf);
            FixedSizeListArray storage = new FixedSizeListArray(
                    new DType.FixedSizeList(U8, 16, false), 1, inner);

            // When / Then
            assertThat(sut.decode(storage, 0)).isEqualTo(new UUID(-1L, -1L));
        }
    }

    @Test
    void encode_thenDecodeAll_roundTrips() {
        // Given — pair of UUIDs covers both halves of the packed buffer + sign-extension edge
        List<UUID> ids = List.of(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new UUID(-1L, -1L));

        // When — encodeAll produces a flat byte[] sized 16*N
        byte[] packed = sut.encodeAll(ids);
        assertThat(packed).hasSize(32);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(packed.length);
            for (int i = 0; i < packed.length; i++) {
                buf.set(ValueLayout.JAVA_BYTE, i, packed[i]);
            }
            ByteArray inner = new ByteArray(U8, packed.length, buf);
            FixedSizeListArray storage = new FixedSizeListArray(
                    new DType.FixedSizeList(U8, 16, false), ids.size(), inner);

            // Then
            assertThat(sut.decodeAll(storage)).isEqualTo(ids);
        }
    }

    @Nested
    class PolymorphicEncodeAll {

        @Test
        void noNulls_returnsFixedSizeListData() {
            // Given — non-null path keeps the FixedSizeListData return shape so
            // FixedSizeListEncoding picks it up without a NullableData detour
            DType.Extension dtype = sut.dtype(false);
            UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

            // When
            Object result = sut.encodeAll(dtype, List.of(id));

            // Then
            assertThat(result).isInstanceOf(FixedSizeListData.class);
            FixedSizeListData fsl = (FixedSizeListData) result;
            assertThat(fsl.outerLen()).isEqualTo(1L);
            assertThat(((byte[]) fsl.elements())).hasSize(16);
        }

        @Test
        void withNulls_nullableDtype_zeroPlaceholderStride16() {
            // Given — null entry must produce 16 zero bytes so the masked child stays free
            // of semantic nulls; row count == validity length, not bytes/16
            DType.Extension dtype = sut.dtype(true);
            UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
            List<UUID> ids = Arrays.asList(id, null, id);

            // When
            Object result = sut.encodeAll(dtype, ids);

            // Then
            assertThat(result).isInstanceOf(NullableData.class);
            NullableData nd = (NullableData) result;
            assertThat(nd.values()).isInstanceOf(FixedSizeListData.class);
            FixedSizeListData fsl = (FixedSizeListData) nd.values();
            assertThat(fsl.outerLen()).isEqualTo(3L);
            byte[] flat = (byte[]) fsl.elements();
            assertThat(flat).hasSize(48);
            // Null row is bytes 16..31 — must be zero so MaskedArray invariant holds
            for (int i = 16; i < 32; i++) {
                assertThat(flat[i]).isZero();
            }
            assertThat(nd.validity()).containsExactly(true, false, true);
        }

        @Test
        void withNulls_nonNullableDtype_throws() {
            // Given — NOT NULL UUID column rejects null elements rather than zero-rounding
            DType.Extension dtype = sut.dtype(false);

            // When / Then
            assertThatThrownBy(() -> sut.encodeAll(dtype,
                    Arrays.asList(UUID.fromString("00000000-0000-0000-0000-000000000001"), null)))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("vortex.uuid");
        }
    }

    @Test
    void decodeAll_maskedArray_yieldsNullAtInvalidPositions() {
        // Given — 2 UUIDs in flat byte storage, validity says row 1 = null
        java.util.UUID u1 = java.util.UUID.fromString("12345678-1234-5678-9abc-def012345678");
        try (Arena arena = Arena.ofConfined()) {
            byte[] flat = sut.encodeAll(java.util.List.of(u1, new java.util.UUID(0L, 0L)));
            MemorySegment buf = arena.allocate(flat.length);
            for (int i = 0; i < flat.length; i++) {
                buf.set(ValueLayout.JAVA_BYTE, i, flat[i]);
            }
            ByteArray innerBytes = new ByteArray(U8, flat.length, buf);
            FixedSizeListArray innerFsl = new FixedSizeListArray(
                    new DType.FixedSizeList(U8, 16, false), 2, innerBytes);
            MemorySegment validityBuf = arena.allocate(1);
            validityBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_0001);
            BoolArray validity = new BoolArray(new DType.Bool(false), 2, validityBuf);

            // When
            java.util.List<java.util.UUID> out = sut.decodeAll(new MaskedArray(innerFsl, validity));

            // Then
            assertThat(out).containsExactly(u1, null);
        }
    }

    @Test
    void decode_wrongFixedSize_throws() {
        // Given — 8 != 16; reject up front
        try (Arena arena = Arena.ofConfined()) {
            ByteArray inner = new ByteArray(U8, 8, arena.allocate(8));
            FixedSizeListArray storage = new FixedSizeListArray(
                    new DType.FixedSizeList(U8, 8, false), 1, inner);

            // When / Then
            assertThatThrownBy(() -> sut.decode(storage, 0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("fixedSize 16");
        }
    }
}
