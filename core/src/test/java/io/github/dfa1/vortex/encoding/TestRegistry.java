package io.github.dfa1.vortex.encoding;

/// Static factories for Registry instances used in encoding tests.
final class TestRegistry {

    private TestRegistry() {
    }

    static Registry of(Encoding... encodings) {
        var b = Registry.builder();
        for (Encoding e : encodings) {
            b.register(e);
        }
        return b.build();
    }

    static Registry withPrimitive(Encoding sut) {
        var b = Registry.builder().register(sut);
        if (!(sut instanceof PrimitiveEncoding)) {
            b.register(new PrimitiveEncoding());
        }
        return b.build();
    }
}
