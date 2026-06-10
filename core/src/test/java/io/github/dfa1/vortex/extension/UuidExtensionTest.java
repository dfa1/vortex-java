package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.FixedSizeListArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
