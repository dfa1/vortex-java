/// Vortex reader module: memory-mapped file parsing, the layout/scan engine, decoded `Array`
/// types, and the encoding/extension decoders dispatched via [java.util.ServiceLoader].
///
/// `vortex.core` is re-exported (`requires transitive`) because decoded types surface core model
/// types (`DType`, `PType`, …) in the public API. The native zstd codec is optional
/// (`requires static`): the `vortex.zstd` decoder loads only when the `zstd` module is present.
// Lint suppressed: `module` for the group-derived terminal-digit name component `dfa1`;
// `requires-automatic` because the optional `zstd` codec ships as an automatic module (no
// module descriptor yet) and is pulled in via `requires static`.
@SuppressWarnings({"module", "requires-automatic"})
module io.github.dfa1.vortex.reader {
    requires transitive io.github.dfa1.vortex.core;
    requires static zstd;
    // transitive: VortexHttpReader.open exposes java.net.http.HttpClient in its public signature.
    requires transitive java.net.http;

    exports io.github.dfa1.vortex.reader;
    exports io.github.dfa1.vortex.reader.array;
    exports io.github.dfa1.vortex.reader.decode;
    exports io.github.dfa1.vortex.reader.extension;

    uses io.github.dfa1.vortex.reader.decode.EncodingDecoder;

    provides io.github.dfa1.vortex.reader.decode.EncodingDecoder with
            io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.AlpRdEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ByteBoolEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ChunkedEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.DateTimePartsEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.DecimalBytePartsEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.DecimalEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.DictEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ExtEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.FixedSizeListEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.FsstEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ListEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ListViewEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.MaskedEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.NullEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.PatchedEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.PcoEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.RleEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.RunEndEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.SequenceEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.SparseEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.StructEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.VarBinEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.VariantEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.VarBinViewEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder,
            io.github.dfa1.vortex.reader.decode.ZstdEncodingDecoder;
}
