package io.github.dfa1.vortex.encoding;

/// Static factories for EncodingRegistry instances used in encoding tests.
final class TestRegistry {

    private TestRegistry() {
    }

    static EncodingRegistry of(Encoding... encodings) {
        EncodingRegistry r = EncodingRegistry.empty();
        for (Encoding e : encodings) {
            r.register(e);
        }
        return r;
    }

    static EncodingRegistry withPrimitive(Encoding sut) {
        EncodingRegistry r = of(sut);
        if (!(sut instanceof PrimitiveEncoding)) {
            r.register(new PrimitiveEncoding());
        }
        return r;
    }
}
