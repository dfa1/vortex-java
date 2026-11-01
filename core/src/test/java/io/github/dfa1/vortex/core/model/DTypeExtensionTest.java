package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DTypeExtensionTest {

    private static final DType.Primitive I32 = DTypes.I32;

    @Nested
    class Metadata {

        @Test
        void nullMetadata_rejected() {
            // Given / When / Then — no metadata is OpaqueMetadata.EMPTY, so a dtype always has one
            assertThatThrownBy(() -> new DType.Extension(ExtensionId.VORTEX_DATE, I32, null, false))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void nonRecordMetadata_rejected() {
            // Given metadata whose equality is identity: a dtype holding it would not compare by
            // value, so two reads of one file would give unequal schemas
            ExtensionMetadata identity = new ExtensionMetadata() {
            };

            // When / Then
            assertThatThrownBy(() -> new DType.Extension(ExtensionId.parse("acme.custom"), I32, identity, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be a record");
        }

        @Test
        void sameOpaqueBytes_equalDtypes() {
            // Given the same bytes from a heap array and from off-heap memory, as a dtype built in
            // code and one read back from a file hold them
            MemorySegment offHeap = Arena.ofAuto().allocate(2);
            offHeap.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
            offHeap.set(ValueLayout.JAVA_BYTE, 1, (byte) 2);
            var built = new DType.Extension(ExtensionId.parse("acme.custom"), I32, new OpaqueMetadata(MemorySegment.ofArray(new byte[]{1, 2})), false);

            // When
            var result = new DType.Extension(ExtensionId.parse("acme.custom"), I32, new OpaqueMetadata(offHeap), false);

            // Then
            assertThat(result).isEqualTo(built).hasSameHashCodeAs(built);
        }
    }

    @Nested
    class Opaque {

        @Test
        void atCap_accepted() {
            // Given — exact boundary must pass so legitimate large-but-bounded metadata
            // is not falsely rejected
            MemorySegment bytes = MemorySegment.ofArray(new byte[(int) OpaqueMetadata.MAX_SIZE.bytes()]);

            // When
            OpaqueMetadata result = new OpaqueMetadata(bytes);

            // Then
            assertThat(result.bytes().byteSize()).isEqualTo(OpaqueMetadata.MAX_SIZE.bytes());
        }

        @Test
        void overCap_rejected() {
            // Given — one byte past the cap; defends parser against attacker-supplied
            // multi-megabyte metadata blobs that would inflate parse-time allocations
            MemorySegment bytes = MemorySegment.ofArray(new byte[(int) OpaqueMetadata.MAX_SIZE.bytes() + 1]);

            // When / Then
            assertThatThrownBy(() -> new OpaqueMetadata(bytes))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("metadata too large");
        }

        @Test
        void nativeBytes_outliveTheirArena() {
            // Given bytes sliced from off-heap memory, as a reader parses them out of the file mapping
            OpaqueMetadata result;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment bytes = arena.allocate(2);
                bytes.set(ValueLayout.JAVA_BYTE, 0, (byte) 3);

                // When
                result = new OpaqueMetadata(bytes);
            }

            // Then — still readable after the arena (the reader) is closed
            assertThat(result.bytes().get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 3);
        }

        @Test
        void callerArray_changedLater_doesNotChangeTheValue() {
            // Given metadata built from a caller's array
            byte[] caller = {1};
            OpaqueMetadata sut = new OpaqueMetadata(MemorySegment.ofArray(caller));
            int hash = sut.hashCode();

            // When the caller reuses its array
            caller[0] = 2;

            // Then — a dtype holding it keeps its equality and hash, so a map holding it still finds it
            assertThat(sut).isEqualTo(new OpaqueMetadata(MemorySegment.ofArray(new byte[]{1})));
            assertThat(sut.hashCode()).isEqualTo(hash);
        }

        @Test
        void toString_ofLargeMetadata_showsSizeAndAPrefixOnly() {
            // Given metadata at the 64 KiB cap, which ends up in exception messages via toString
            OpaqueMetadata sut = new OpaqueMetadata(MemorySegment.ofArray(new byte[(int) OpaqueMetadata.MAX_SIZE.bytes()]));

            // When
            String result = sut.toString();

            // Then — not a 128 K-character hex dump
            assertThat(result).isEqualTo("OpaqueMetadata[65536 bytes: 00000000000000000000000000000000…]");
        }

        @Test
        void toString_ofSmallMetadata_showsEveryByte() {
            // Given
            OpaqueMetadata sut = new OpaqueMetadata(MemorySegment.ofArray(new byte[]{'C', 'R', 'S'}));

            // When
            String result = sut.toString();

            // Then
            assertThat(result).isEqualTo("OpaqueMetadata[3 bytes: 435253]");
        }

        @Test
        void hashCode_isTheArraysHashOfTheBytes() {
            // Given — read in place rather than copied out, it must still match the array hash
            byte[] bytes = {1, -2, 3, 127, -128};

            // When
            int result = new OpaqueMetadata(MemorySegment.ofArray(bytes)).hashCode();

            // Then
            assertThat(result).isEqualTo(java.util.Arrays.hashCode(bytes));
        }

        @Test
        void bytes_areReadOnly() {
            // Given
            OpaqueMetadata sut = new OpaqueMetadata(MemorySegment.ofArray(new byte[]{1}));

            // When
            boolean result = sut.bytes().isReadOnly();

            // Then — a caller cannot change a dtype through the segment it reads back
            assertThat(result).isTrue();
        }
    }
}
