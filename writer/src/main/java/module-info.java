/// Vortex writer module: the columnar file writer, encode pipeline, and the encoding/extension
/// encoders dispatched via [java.util.ServiceLoader].
///
/// `vortex.core` is re-exported (`requires transitive`) because the write API surfaces core model
/// types (`DType`, …). The writer never depends on the reader. The native zstd codec is optional
/// (`requires static`).
// Lint suppressed: `module` for the group-derived terminal-digit name component `dfa1`;
// `requires-automatic` because the optional `zstd` codec ships as an automatic module.
@SuppressWarnings({"module", "requires-automatic"})
module io.github.dfa1.vortex.writer {
    requires transitive io.github.dfa1.vortex.core;
    requires static zstd;

    exports io.github.dfa1.vortex.writer;
    exports io.github.dfa1.vortex.writer.encode;

    uses io.github.dfa1.vortex.writer.encode.EncodingEncoder;
    uses io.github.dfa1.vortex.writer.ExtensionEncoder;

    provides io.github.dfa1.vortex.writer.encode.EncodingEncoder with
            io.github.dfa1.vortex.writer.encode.AlpEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.AlpRdEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ByteBoolEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ChunkedEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.DateTimePartsEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.DecimalBytePartsEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.DecimalEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.DictEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ExtEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.FixedSizeListEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ListEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ListViewEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.NullEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.PatchedEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.PcoEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.RleEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.RunEndEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.SequenceEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.SparseEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.StructEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.VariantEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.VarBinViewEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ZigZagEncodingEncoder,
            io.github.dfa1.vortex.writer.encode.ZstdEncodingEncoder;

    provides io.github.dfa1.vortex.writer.ExtensionEncoder with
            io.github.dfa1.vortex.writer.encode.DateExtensionEncoder,
            io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder,
            io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder,
            io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;
}
