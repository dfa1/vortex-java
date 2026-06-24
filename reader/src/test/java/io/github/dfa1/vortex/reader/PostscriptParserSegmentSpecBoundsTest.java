package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/// Exact-boundary coverage for [PostscriptParser#validateSegmentSpecs(List, long)], the guard
/// that rejects a footer `SegmentSpec` whose declared byte range escapes the mapped file.
///
/// The guard predicate is `offset < 0 || length < 0 || offset > fileSize || length > fileSize -
/// offset`. Each comparison is one off-by-one away from a bug: relax any `<`/`>` to `<=`/`>=`, or
/// swap the `fileSize - offset` subtraction for addition, and a slice that overruns the mapping
/// slips through to a raw `IndexOutOfBoundsException` at scan time — breaking the
/// "malformed input → VortexException" contract. These cases pin every edge of that predicate:
/// the largest range that must still pass, and the smallest overrun that must be rejected.
class PostscriptParserSegmentSpecBoundsTest {

    private static SegmentSpec spec(long offset, long length) {
        return new SegmentSpec(offset, length, (byte) 0, CompressionScheme.NONE);
    }

    static Stream<Arguments> inBoundsRanges() {
        long fileSize = 100;
        return Stream.of(
                // offset 0 / length 0: the lower edge — must pass, else `offset < 0` / `length < 0`
                // were relaxed to `<= 0`.
                Arguments.of("empty at start", spec(0, 0), fileSize),
                // offset == fileSize with an empty segment: the range [100,100) touches EOF but reads
                // nothing — valid. Catches `offset > fileSize` relaxed to `>=`.
                Arguments.of("empty at EOF", spec(fileSize, 0), fileSize),
                // length == fileSize - offset: the segment ends exactly at EOF — the largest range
                // that still fits. Catches `length > fileSize - offset` relaxed to `>=`.
                Arguments.of("exact fit to EOF", spec(10, 90), fileSize),
                // whole file in one segment — same exact-fit edge from offset 0.
                Arguments.of("whole file", spec(0, fileSize), fileSize));
    }

    static Stream<Arguments> outOfBoundsRanges() {
        long fileSize = 100;
        return Stream.of(
                Arguments.of("negative offset", spec(-1, 0), fileSize),
                Arguments.of("negative length", spec(0, -1), fileSize),
                Arguments.of("offset past EOF", spec(fileSize + 1, 0), fileSize),
                // length one byte past the exact fit: overruns EOF by 1. This also kills the
                // `fileSize - offset` → `fileSize + offset` math mutant — 91 > 90 rejects, but
                // 91 > 110 (the mutated bound) would wrongly accept.
                Arguments.of("overrun by one byte", spec(10, 91), fileSize));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inBoundsRanges")
    void validateSegmentSpecs_accepts_inBoundsRange(String name, SegmentSpec s, long fileSize) {
        // Given — a spec whose range sits exactly on the in-bounds edge (see provider)

        // When / Then — the largest legal range must not be rejected
        assertThatCode(() -> PostscriptParser.validateSegmentSpecs(List.of(s), fileSize))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfBoundsRanges")
    void validateSegmentSpecs_rejects_outOfBoundsRange(String name, SegmentSpec s, long fileSize) {
        // Given — a spec one step past a bound (see provider)

        // When / Then — the smallest overrun must be rejected as a VortexException, not leak an IOOBE
        assertThatThrownBy(() -> PostscriptParser.validateSegmentSpecs(List.of(s), fileSize))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void validateSegmentSpecs_acceptsEmptyList() {
        // Given — no segments at all

        // When / Then — nothing to validate, nothing thrown
        assertDoesNotThrow(() -> PostscriptParser.validateSegmentSpecs(List.of(), 100));
    }
}
