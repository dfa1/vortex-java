module io.github.dfa1.vortex.core {
    requires com.google.protobuf;
    requires flatbuffers.java;
    requires aircompressor;

    exports io.github.dfa1.vortex.core;
    exports io.github.dfa1.vortex.core.array;
    exports io.github.dfa1.vortex.encoding;
    exports io.github.dfa1.vortex.fbs;
    exports io.github.dfa1.vortex.proto;

    uses io.github.dfa1.vortex.encoding.Encoding;

    provides io.github.dfa1.vortex.encoding.Encoding with
        io.github.dfa1.vortex.encoding.AlpEncoding,
        io.github.dfa1.vortex.encoding.BitpackedEncoding,
        io.github.dfa1.vortex.encoding.BoolEncoding,
        io.github.dfa1.vortex.encoding.ByteBoolEncoding,
        io.github.dfa1.vortex.encoding.ConstantEncoding,
        io.github.dfa1.vortex.encoding.DeltaEncoding,
        io.github.dfa1.vortex.encoding.DictEncoding,
        io.github.dfa1.vortex.encoding.ExtEncoding,
        io.github.dfa1.vortex.encoding.FrameOfReferenceEncoding,
        io.github.dfa1.vortex.encoding.FsstEncoding,
        io.github.dfa1.vortex.encoding.NullEncoding,
        io.github.dfa1.vortex.encoding.PcoEncoding,
        io.github.dfa1.vortex.encoding.PrimitiveEncoding,
        io.github.dfa1.vortex.encoding.RunEndEncoding,
        io.github.dfa1.vortex.encoding.SequenceEncoding,
        io.github.dfa1.vortex.encoding.SparseEncoding,
        io.github.dfa1.vortex.encoding.StructEncoding,
        io.github.dfa1.vortex.encoding.VarBinEncoding,
        io.github.dfa1.vortex.encoding.VarBinViewEncoding,
        io.github.dfa1.vortex.encoding.ZigZagEncoding,
        io.github.dfa1.vortex.encoding.ZstdEncoding;
}
