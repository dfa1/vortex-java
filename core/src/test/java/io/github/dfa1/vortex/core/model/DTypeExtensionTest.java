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
        MemorySegment meta = MemorySegment.ofArray(new byte[DType.Extension.MAX_METADATA_SIZE]);

        // When / Then
        assertThat(new DType.Extension("acme.big", I32, meta, false).metadata().byteSize())
                .isEqualTo(DType.Extension.MAX_METADATA_SIZE);
    }

    @Test
    void metadataOverCap_rejected() {
        // Given — one byte past the cap; defends parser against attacker-supplied
        // multi-megabyte metadata blobs that would inflate parse-time allocations
        MemorySegment meta = MemorySegment.ofArray(new byte[DType.Extension.MAX_METADATA_SIZE + 1]);

        // When / Then
        assertThatThrownBy(() -> new DType.Extension("acme.big", I32, meta, false))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("metadata too large");
    }

    @Test
    void nullMetadata_accepted() {
        // Given / When / Then — date/uuid extensions carry no metadata; null must remain valid
        assertThat(new DType.Extension("vortex.date", I32, null, false).metadata()).isNull();
    }
}
