package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DTypeExtensionTest {

    private static final DType.Primitive I32 = DType.I32;

    @Test
    void metadataAtCap_accepted() {
        // Given — exact boundary must pass so legitimate large-but-bounded metadata
        // is not falsely rejected
        MemorySegment meta = MemorySegment.ofArray(new byte[(int) DType.Extension.MAX_METADATA_SIZE.bytes()]);

        // When / Then
        assertThat(new DType.Extension("acme.big", I32, meta, false).metadata().byteSize())
                .isEqualTo(DType.Extension.MAX_METADATA_SIZE.bytes());
    }

    @Test
    void metadataOverCap_rejected() {
        // Given — one byte past the cap; defends parser against attacker-supplied
        // multi-megabyte metadata blobs that would inflate parse-time allocations
        MemorySegment meta = MemorySegment.ofArray(new byte[(int) DType.Extension.MAX_METADATA_SIZE.bytes() + 1]);

        // When / Then
        assertThatThrownBy(() -> new DType.Extension("acme.big", I32, meta, false))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("metadata too large");
    }

    @Test
    void nativeMetadata_outlivesItsArena() {
        // Given metadata sliced from off-heap memory, as a reader parses it out of the file mapping
        DType.Extension result;
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment meta = arena.allocate(2);
            meta.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 3);

            // When
            result = new DType.Extension("vortex.timestamp", DType.I64, meta, false);
        }

        // Then — the dtype is still readable after the arena (the reader) is closed
        assertThat(result.metadata().get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 3);
    }

    @Test
    void nullMetadata_accepted() {
        // Given / When / Then — date/uuid extensions carry no metadata; null must remain valid
        assertThat(new DType.Extension("vortex.date", I32, null, false).metadata()).isNull();
    }
}
