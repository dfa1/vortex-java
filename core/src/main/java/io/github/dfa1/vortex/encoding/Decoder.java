package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.Array;

/// Decodes one encoding type into a flat [Array].
///
/// Register implementations via `ServiceLoader` (META-INF/services) or
/// [DecoderRegistry#register(Decoder)].
public interface Decoder {
    /// Encoding ID this decoder handles, e.g. `"fastlanes.bitpacked"`.
    EncodingId encodingId();

    Array decode(DecodeContext ctx);
}