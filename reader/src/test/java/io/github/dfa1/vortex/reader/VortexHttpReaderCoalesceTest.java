package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// How [VortexHttpReader#coalesce] groups the segments of one prefetch into requests: neighbors
/// within Rust's `CoalesceConfig::object_storage()` distance (1 MiB) and size (16 MiB) share a
/// request, so a screenful of columns costs about one round trip.
class VortexHttpReaderCoalesceTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void coalesce_gapOfExactlyTheDistance_merges() {
        // Given
        SegmentSpec first = spec(0, 100);
        SegmentSpec second = spec(100 + MIB, 100);

        // When
        var result = VortexHttpReader.coalesce(List.of(first, second));

        // Then
        assertThat(result).containsExactly(List.of(first, second));
    }

    @Test
    void coalesce_gapOneByteOverTheDistance_splits() {
        // Given
        SegmentSpec first = spec(0, 100);
        SegmentSpec second = spec(100 + MIB + 1, 100);

        // When
        var result = VortexHttpReader.coalesce(List.of(first, second));

        // Then
        assertThat(result).containsExactly(List.of(first), List.of(second));
    }

    @Test
    void coalesce_spanOfExactlyTheMaximum_staysOneRequest() {
        // Given — two segments filling exactly 16 MiB
        SegmentSpec first = spec(0, 8 * MIB);
        SegmentSpec second = spec(8 * MIB, 8 * MIB);

        // When
        var result = VortexHttpReader.coalesce(List.of(first, second));

        // Then
        assertThat(result).containsExactly(List.of(first, second));
    }

    @Test
    void coalesce_spanOneByteOverTheMaximum_splits() {
        // Given
        SegmentSpec first = spec(0, 8 * MIB);
        SegmentSpec second = spec(8 * MIB, 8 * MIB + 1);

        // When
        var result = VortexHttpReader.coalesce(List.of(first, second));

        // Then
        assertThat(result).containsExactly(List.of(first), List.of(second));
    }

    @Test
    void coalesce_unsortedDuplicateOverlappingAndEmptySpecs_areNormalized() {
        // Given — out of order, a duplicate, a segment inside another, and a zero-length one
        SegmentSpec outer = spec(100, 400);
        SegmentSpec inner = spec(200, 50);
        SegmentSpec last = spec(500, 10);
        SegmentSpec empty = spec(700, 0);

        // When
        var result = VortexHttpReader.coalesce(List.of(last, inner, outer, outer, empty));

        // Then
        assertThat(result).containsExactly(List.of(outer, inner, last));
    }

    @Test
    void coalesce_noSpecs_isEmpty() {
        // Given / When
        var result = VortexHttpReader.coalesce(List.of());

        // Then
        assertThat(result).isEmpty();
    }

    private static SegmentSpec spec(long offset, long length) {
        return new SegmentSpec(offset, length, (byte) 0, CompressionScheme.NONE);
    }
}
