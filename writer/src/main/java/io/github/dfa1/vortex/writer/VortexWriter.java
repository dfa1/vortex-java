package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/// Writes a Vortex file.
///
/// Usage:
/// ```java
/// var schema = new DType.Struct(List.of("id", "value"),
///                               List.of(new DType.Primitive(PType.I64, false),
///                                       new DType.Primitive(PType.F64, false)), false);
/// try (var writer = VortexWriter.create(out, schema, WriteOptions.defaults())) {
///     writer.writeChunk(Map.of("id", idArray, "value", valueArray));
/// }
/// ```
public final class VortexWriter implements Closeable {

    private final OutputStream  out;
    private final DType.Struct  schema;
    private final WriteOptions  options;

    private VortexWriter(OutputStream out, DType.Struct schema, WriteOptions options) {
        this.out     = out;
        this.schema  = schema;
        this.options = options;
    }

    public static VortexWriter create(OutputStream out, DType.Struct schema, WriteOptions options) {
        return new VortexWriter(out, schema, options);
    }

    /// Write one chunk. Encoding selected per-column per-chunk:
    /// - integers → FoR+BitPacking or Pcodec → Primitive fallback
    /// - floats → Pcodec → Primitive
    /// - strings → Dict(VarBinView) → VarBinView
    /// - bools → Bool (bit-packed)
    public void writeChunk(Map<String, Object> columns) throws IOException {
        throw new UnsupportedOperationException("VortexWriter not yet implemented");
    }

    @Override
    public void close() throws IOException {
        // TODO: flush zone-map stats, build layout tree, write footer + postscript
    }
}
