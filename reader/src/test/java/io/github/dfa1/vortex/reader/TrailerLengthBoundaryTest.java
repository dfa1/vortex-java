package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.VortexFormat;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Boundary coverage for [Trailer#parse]'s postscript-length guard `postscriptLen > bodyBytes`.
/// The largest legal postscript fills the entire file body (`postscriptLen == bodyBytes`) and must
/// parse; one byte more overruns the body and must throw. Relaxing `>` to `>=` would reject the
/// exact-fill case — this pins that edge.
class TrailerLengthBoundaryTest {

    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /// Builds a well-formed 8-byte trailer (`version | postscriptLen | magic`) with the given
    /// postscript length, so only the length-vs-body check is under test.
    private static MemorySegment trailer(int postscriptLen) {
        MemorySegment seg = MemorySegment.ofArray(new byte[VortexFormat.TRAILER_SIZE]);
        seg.set(LE_SHORT, 0, (short) VortexFormat.VERSION);
        seg.set(LE_SHORT, 2, (short) postscriptLen);
        MemorySegment.copy(VortexFormat.MAGIC, 0, seg, 4, VortexFormat.MAGIC_SIZE);
        return seg;
    }

    @Test
    void parse_postscriptLengthEqualsBody_parses() {
        // Given — postscript exactly fills the body: postscriptLen == bodyBytes (the upper edge)
        MemorySegment sut = trailer(64);

        // When
        Trailer result = Trailer.parse(sut, 64);

        // Then — accepted, length preserved (kills `>` relaxed to `>=`)
        assertThat(result.postscriptLen()).isEqualTo(64);
    }

    @Test
    void parse_postscriptLengthOneOverBody_throws() {
        // Given — postscript one byte past the body
        MemorySegment sut = trailer(65);

        // When / Then
        assertThatThrownBy(() -> Trailer.parse(sut, 64))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("exceeds file body size");
    }

    @Test
    void parse_postscriptLengthWellWithinBody_parses() {
        // Given — a comfortably in-range postscript, as a sanity anchor for the boundary cases
        MemorySegment sut = trailer(10);

        // When / Then
        assertThatCode(() -> Trailer.parse(sut, 1_000)).doesNotThrowAnyException();
    }
}
