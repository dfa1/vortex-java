package io.github.dfa1.vortex.encoding;

/// Static factories for EncodingRegistry instances used in encoding tests.
final class TestRegistry {

    static EncodingRegistry of(Encoding... encodings) {
        EncodingRegistry r = EncodingRegistry.empty();
        for (Encoding e : encodings) {
            r.register(e);
        }
        return r;
    }

    static EncodingRegistry withPrimitive(Encoding sut) {
        return of(sut, new PrimitiveEncoding());
    }

    private TestRegistry() {}
}
