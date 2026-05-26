package io.github.dfa1.vortex.encoding;

import java.io.IOException;

/// Decodes one encoding type into a flat [Array].
///
/// Register implementations via `ServiceLoader` (META-INF/services) or
/// [DecoderRegistry#register(Decoder)].
public interface Decoder {
    /// Encoding ID this decoder handles, e.g. `"fastlanes.bitpacked"`.
    String encodingId();

    Array decode(DecodeContext ctx) ;
}
