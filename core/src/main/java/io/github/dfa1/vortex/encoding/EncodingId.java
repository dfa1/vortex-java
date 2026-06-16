package io.github.dfa1.vortex.encoding;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Strongly-typed encoding identifier used in place of raw strings.
public enum EncodingId {
    /// Canonical flat primitive encoding (`vortex.primitive`).
    VORTEX_PRIMITIVE("vortex.primitive"),
    /// Bit-packed boolean encoding (`vortex.bool`).
    VORTEX_BOOL("vortex.bool"),
    /// Dictionary encoding for low-cardinality columns (`vortex.dict`).
    VORTEX_DICT("vortex.dict"),
    /// Sparse encoding for columns with many nulls or zeros (`vortex.sparse`).
    VORTEX_SPARSE("vortex.sparse"),
    /// Sequence encoding (`vortex.sequence`).
    VORTEX_SEQUENCE("vortex.sequence"),
    /// Run-end encoding for sorted/repetitive columns (`vortex.runend`).
    VORTEX_RUNEND("vortex.runend"),
    /// Constant encoding — all elements share one value (`vortex.constant`).
    VORTEX_CONSTANT("vortex.constant"),
    /// ALP (Adaptive Lossless floating-Point) encoding for F32/F64 (`vortex.alp`).
    VORTEX_ALP("vortex.alp"),
    /// Variable-length binary encoding (`vortex.varbin`).
    VORTEX_VARBIN("vortex.varbin"),
    /// FSST compressed string encoding (`vortex.fsst`).
    VORTEX_FSST("vortex.fsst"),
    /// All-null encoding (`vortex.null`).
    VORTEX_NULL("vortex.null"),
    /// One-byte-per-boolean encoding (`vortex.bytebool`).
    VORTEX_BYTEBOOL("vortex.bytebool"),
    /// Zig-zag encoding for signed integers (`vortex.zigzag`).
    VORTEX_ZIGZAG("vortex.zigzag"),
    /// Extension type wrapper encoding (`vortex.ext`).
    VORTEX_EXT("vortex.ext"),
    /// Variable-length binary view encoding (`vortex.varbinview`).
    VORTEX_VARBINVIEW("vortex.varbinview"),
    /// pcodec (Pco) floating-point/integer encoding (`vortex.pco`).
    VORTEX_PCO("vortex.pco"),
    /// Canonical flat decimal storage (`vortex.decimal`).
    VORTEX_DECIMAL("vortex.decimal"),
    /// Decimal split into MSP + LSP children (`vortex.decimal_byte_parts`).
    VORTEX_DECIMAL_BYTE_PARTS("vortex.decimal_byte_parts"),
    /// Timestamp split into days/seconds/subseconds (`vortex.datetimeparts`).
    VORTEX_DATETIMEPARTS("vortex.datetimeparts"),
    /// Zstandard compressed encoding (`vortex.zstd`).
    VORTEX_ZSTD("vortex.zstd"),
    /// Fixed-size list encoding (`vortex.fixed_size_list`).
    VORTEX_FIXED_SIZE_LIST("vortex.fixed_size_list"),
    /// Variable-length list encoding (`vortex.list`).
    VORTEX_LIST("vortex.list"),
    /// List-view encoding (`vortex.listview`).
    VORTEX_LISTVIEW("vortex.listview"),
    /// ALP-RD (ALP with remainder dictionary) encoding (`vortex.alprd`).
    VORTEX_ALPRD("vortex.alprd"),

    // Layout encoding IDs included so parser/registry can represent them safely
    /// Chunked layout encoding (`vortex.chunked`).
    VORTEX_CHUNKED("vortex.chunked"),
    /// Struct layout encoding (`vortex.struct`).
    VORTEX_STRUCT("vortex.struct"),

    /// FastLanes bit-packed encoding (`fastlanes.bitpacked`).
    FASTLANES_BITPACKED("fastlanes.bitpacked"),
    /// FastLanes frame-of-reference encoding (`fastlanes.for`).
    FASTLANES_FOR("fastlanes.for"),
    /// FastLanes delta encoding (`fastlanes.delta`).
    FASTLANES_DELTA("fastlanes.delta"),
    /// FastLanes run-length encoding (`fastlanes.rle`).
    FASTLANES_RLE("fastlanes.rle"),

    // Known in Rust but not yet implemented; registered so EncodingId.parse() resolves
    /// Masked encoding (not yet implemented; registered to prevent parse errors).
    VORTEX_MASKED("vortex.masked"),
    /// Patched encoding (not yet implemented; registered to prevent parse errors).
    VORTEX_PATCHED("vortex.patched"),
    /// Variant encoding (not yet implemented; registered to prevent parse errors).
    VORTEX_VARIANT("vortex.variant"),
    ;

    // O(1) access to EncodingId by its string representation
    private static final Map<String, EncodingId> LOOKUP = Stream.of(EncodingId.values())
                                                                  .collect(Collectors.toUnmodifiableMap(EncodingId::id, Function.identity()));
    private final String id;

    EncodingId(String id) {
        this.id = id;
    }

    /// Parses a raw encoding id string into the matching constant.
    /// Used by `ReadRegistry` to discriminate `KnownArrayNode` from `UnknownArrayNode`;
    /// callers that demand a known id chain `.orElseThrow(...)`.
    ///
    /// @param id raw encoding id string (e.g. `"vortex.primitive"`)
    /// @return matching constant, or empty if not recognised
    public static Optional<EncodingId> parse(String id) {
        return Optional.ofNullable(LOOKUP.get(id));
    }

    /// Returns the raw encoding id string for this constant (e.g. `"vortex.primitive"`).
    ///
    /// @return the raw string encoding id
    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return id;
    }
}
