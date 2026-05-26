package io.github.dfa1.vortex.encoding;

import java.nio.ByteBuffer;

/// Output of encoding an array to bytes for a single flat segment.
public record EncodeResult(
    ByteBuffer data,
    byte[]     statsMin,
    byte[]     statsMax
) {
    public boolean hasStats() {
        return statsMin != null && statsMax != null;
    }
}
