package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchBuffersTest {

    /// Each chunk's offset counts the patches before it: a patch on the last row of a chunk
    /// belongs to that chunk, one on its first row to the next (Rust's `gather_patches`).
    @Test
    void of_patchesOnChunkBoundaries_countTowardsTheirOwnChunk() {
        // Given
        long[] positions = {1023, 1024, 2047, 2048};

        // When
        PatchBuffers sut = PatchBuffers.of(positions, 3000, EncodeTestHelper.testCtx());

        // Then
        assertThat(sut.chunkCount()).isEqualTo(3);
        assertThat(sut.offsetType()).isEqualTo(PType.U8);
        assertThat(sut.offsets().toArray(ValueLayout.JAVA_BYTE)).containsExactly(0, 1, 3);
        assertThat(sut.indexType()).isEqualTo(PType.U16);
        assertThat(sut.indices().toArray(VortexFormat.LE_SHORT)).containsExactly((short) 1023, (short) 1024,
                (short) 2047, (short) 2048);
    }

    @Test
    void of_lengthNotAMultipleOfTheChunk_hasAPartialLastChunk() {
        // Given
        long[] positions = {0};

        // When
        PatchBuffers sut = PatchBuffers.of(positions, 1025, EncodeTestHelper.testCtx());

        // Then
        assertThat(sut.chunkCount()).isEqualTo(2);
        assertThat(sut.offsets().toArray(ValueLayout.JAVA_BYTE)).containsExactly(0, 1);
    }

    @Test
    void of_noPositions_fails() {
        // Given
        long[] positions = {};

        // When / Then
        assertThatThrownBy(() -> PatchBuffers.of(positions, 10, EncodeTestHelper.testCtx()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0, U8", "255, U8", "256, U16", "65535, U16", "65536, U32", "4294967295, U32", "4294967296, U64",
            "-1, U64"})
    void narrowestUnsigned_picksTheSmallestTypeHoldingTheValue(long max, PType expected) {
        // When
        PType result = PatchBuffers.narrowestUnsigned(max);

        // Then
        assertThat(result).isEqualTo(expected);
    }
}
