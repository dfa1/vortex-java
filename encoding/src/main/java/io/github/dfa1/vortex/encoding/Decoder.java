package io.github.dfa1.vortex.encoding;

import java.io.IOException;

/**
 * Decodes one encoding type into a flat {@link Array}.
 *
 * <p>Register implementations via {@code ServiceLoader} (META-INF/services) or
 * {@link DecoderRegistry#register}.
 */
public interface Decoder {
    /** Encoding ID this decoder handles, e.g. {@code "fastlanes.bitpacked"}. */
    String encodingId();

    Array decode(DecodeContext ctx) throws IOException;
}
