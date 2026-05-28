package io.github.dfa1.vortex.encoding;

/// Decodes one encoding type into a flat [Array].
///
/// Register implementations via `ServiceLoader` (META-INF/services) or
/// [DecoderRegistry#register(Decoder)].
/// TODO: merge inside Codec
public interface Decoder {
    /// Encoding ID this decoder handles, e.g. `"fastlanes.bitpacked"`.
    String encodingId();

    Array decode(DecodeContext ctx) ;
}
