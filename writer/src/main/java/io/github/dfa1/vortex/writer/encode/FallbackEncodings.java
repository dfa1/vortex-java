package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;

import java.util.List;
import java.util.Optional;

/// Picks the encoder for a child column from a container encoding's fixed fallback list: the
/// first one that accepts the child's dtype.
final class FallbackEncodings {

    private FallbackEncodings() {
    }

    /// The first of `candidates` that accepts `dtype`, if any.
    static Optional<EncodingEncoder> find(List<EncodingEncoder> candidates, DType dtype) {
        for (EncodingEncoder candidate : candidates) {
            if (candidate.accepts(dtype)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /// The first of `candidates` that accepts `dtype`.
    ///
    /// @throws UnsupportedOperationException if none does
    static EncodingEncoder require(List<EncodingEncoder> candidates, DType dtype) {
        return find(candidates, dtype)
                .orElseThrow(() -> new UnsupportedOperationException("no fallback encoding for dtype: " + dtype));
    }
}
