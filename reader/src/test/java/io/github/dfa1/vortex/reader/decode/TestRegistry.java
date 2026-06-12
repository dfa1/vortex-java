package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.ReadRegistry;

/// Static factories for {@link ReadRegistry} instances used in decode tests.
///
/// Public so writer/ test trees can reuse via the reader test-jar.
public final class TestRegistry {

    private TestRegistry() {
    }

    /// Builds a {@link ReadRegistry} containing only the supplied decoders.
    ///
    /// @param decoders decoders to register
    /// @return registry instance
    public static ReadRegistry ofDecoders(EncodingDecoder... decoders) {
        var b = ReadRegistry.builder();
        for (EncodingDecoder d : decoders) {
            b.register(d);
        }
        return b.build();
    }

    /// Builds a {@link ReadRegistry} containing the supplied decoder plus a
    /// {@link PrimitiveEncodingDecoder} fallback (so child decodes of primitive segments work).
    ///
    /// @param sut decoder under test
    /// @return registry instance
    public static ReadRegistry withPrimitive(EncodingDecoder sut) {
        var b = ReadRegistry.builder().register(sut);
        if (!(sut instanceof PrimitiveEncodingDecoder)) {
            b.register(new PrimitiveEncodingDecoder());
        }
        return b.build();
    }
}
