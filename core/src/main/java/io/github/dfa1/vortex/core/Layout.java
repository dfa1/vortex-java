package io.github.dfa1.vortex.core;

import java.util.List;

/**
 * Node in the Vortex layout tree. Describes physical arrangement of data in the file.
 *
 * <p>Typical tree shape per column:
 * <pre>Struct → Zoned(Stats) → Chunked → [Flat, Flat, ...]</pre>
 */
public record Layout(
    String        encodingId,
    long          rowCount,
    byte[]        metadata,
    List<Layout>  children,
    List<Integer> segments
) {
    public static final String FLAT    = "vortex.flat";
    public static final String CHUNKED = "vortex.chunked";
    public static final String STRUCT  = "vortex.struct";
    public static final String ZONED   = "vortex.stats";
    public static final String DICT    = "vortex.dict";

    public boolean isFlat()    { return FLAT.equals(encodingId); }
    public boolean isChunked() { return CHUNKED.equals(encodingId); }
    public boolean isStruct()  { return STRUCT.equals(encodingId); }
    public boolean isZoned()   { return ZONED.equals(encodingId); }
}
