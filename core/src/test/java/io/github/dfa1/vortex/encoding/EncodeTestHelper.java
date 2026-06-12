package io.github.dfa1.vortex.encoding;

import java.lang.foreign.Arena;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/// Encode-side test utilities.
///
/// Public so reader/ and writer/ test trees can reuse it via the core test-jar.
public final class EncodeTestHelper {

    private EncodeTestHelper() {
        // no instances
    }

    /// Creates a non-cascading [EncodeContext] using a GC-managed arena and all
    /// service-loaded {@link EncodingEncoder}s.
    ///
    /// @return a test-suitable {@link EncodeContext}
    public static EncodeContext testCtx() {
        Map<EncodingId, EncodingEncoder> encoders = new HashMap<>();
        for (EncodingEncoder enc : ServiceLoader.load(EncodingEncoder.class)) {
            encoders.put(enc.encodingId(), enc);
        }
        return EncodeContext.of(Arena.ofAuto(), Map.copyOf(encoders));
    }
}
