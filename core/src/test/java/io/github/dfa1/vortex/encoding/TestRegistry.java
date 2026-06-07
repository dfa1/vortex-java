package io.github.dfa1.vortex.encoding;

/// Static factories for EncodingRegistry instances used in encoding tests.
final class TestRegistry {

    private TestRegistry() {
    }

    static EncodingRegistry of(Encoding... encodings) {
        var b = EncodingRegistry.builder();
        for (Encoding e : encodings) {
            b.register(e);
        }
        return b.build();
    }

    static EncodingRegistry withPrimitive(Encoding sut) {
        var b = EncodingRegistry.builder().register(sut);
        if (!(sut instanceof PrimitiveEncoding)) {
            b.register(new PrimitiveEncoding());
        }
        return b.build();
    }
}
