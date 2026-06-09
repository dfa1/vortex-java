package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Strongly-typed encoding identifier used in place of raw strings.
public enum EncodingId {
    /// Canonical flat primitive encoding ({@code vortex.primitive}).
    VORTEX_PRIMITIVE("vortex.primitive"),
    /// Bit-packed boolean encoding ({@code vortex.bool}).
    VORTEX_BOOL("vortex.bool"),
    /// Dictionary encoding for low-cardinality columns ({@code vortex.dict}).
    VORTEX_DICT("vortex.dict"),
    /// Sparse encoding for columns with many nulls or zeros ({@code vortex.sparse}).
    VORTEX_SPARSE("vortex.sparse"),
    /// Sequence encoding ({@code vortex.sequence}).
    VORTEX_SEQUENCE("vortex.sequence"),
    /// Run-end encoding for sorted/repetitive columns ({@code vortex.runend}).
    VORTEX_RUNEND("vortex.runend"),
    /// Constant encoding — all elements share one value ({@code vortex.constant}).
    VORTEX_CONSTANT("vortex.constant"),
    /// ALP (Adaptive Lossless floating-Point) encoding for F32/F64 ({@code vortex.alp}).
    VORTEX_ALP("vortex.alp"),
    /// Variable-length binary encoding ({@code vortex.varbin}).
    VORTEX_VARBIN("vortex.varbin"),
    /// FSST compressed string encoding ({@code vortex.fsst}).
    VORTEX_FSST("vortex.fsst"),
    /// All-null encoding ({@code vortex.null}).
    VORTEX_NULL("vortex.null"),
    /// One-byte-per-boolean encoding ({@code vortex.bytebool}).
    VORTEX_BYTEBOOL("vortex.bytebool"),
    /// Zig-zag encoding for signed integers ({@code vortex.zigzag}).
    VORTEX_ZIGZAG("vortex.zigzag"),
    /// Extension type wrapper encoding ({@code vortex.ext}).
    VORTEX_EXT("vortex.ext"),
    /// Variable-length binary view encoding ({@code vortex.varbinview}).
    VORTEX_VARBINVIEW("vortex.varbinview"),
    /// pcodec (Pco) floating-point/integer encoding ({@code vortex.pco}).
    VORTEX_PCO("vortex.pco"),
    /// Canonical flat decimal storage ({@code vortex.decimal}).
    VORTEX_DECIMAL("vortex.decimal"),
    /// Decimal split into MSP + LSP children ({@code vortex.decimal_byte_parts}).
    VORTEX_DECIMAL_BYTE_PARTS("vortex.decimal_byte_parts"),
    /// Timestamp split into days/seconds/subseconds ({@code vortex.datetimeparts}).
    VORTEX_DATETIMEPARTS("vortex.datetimeparts"),
    /// Zstandard compressed encoding ({@code vortex.zstd}).
    VORTEX_ZSTD("vortex.zstd"),
    /// Fixed-size list encoding ({@code vortex.fixed_size_list}).
    VORTEX_FIXED_SIZE_LIST("vortex.fixed_size_list"),
    /// Variable-length list encoding ({@code vortex.list}).
    VORTEX_LIST("vortex.list"),
    /// List-view encoding ({@code vortex.listview}).
    VORTEX_LISTVIEW("vortex.listview"),
    /// ALP-RD (ALP with remainder dictionary) encoding ({@code vortex.alprd}).
    VORTEX_ALPRD("vortex.alprd"),

    // Layout encoding IDs included so parser/registry can represent them safely
    /// Chunked layout encoding ({@code vortex.chunked}).
    VORTEX_CHUNKED("vortex.chunked"),
    /// Struct layout encoding ({@code vortex.struct}).
    VORTEX_STRUCT("vortex.struct"),

    /// FastLanes bit-packed encoding ({@code fastlanes.bitpacked}).
    FASTLANES_BITPACKED("fastlanes.bitpacked"),
    /// FastLanes frame-of-reference encoding ({@code fastlanes.for}).
    FASTLANES_FOR("fastlanes.for"),
    /// FastLanes delta encoding ({@code fastlanes.delta}).
    FASTLANES_DELTA("fastlanes.delta"),
    /// FastLanes run-length encoding ({@code fastlanes.rle}).
    FASTLANES_RLE("fastlanes.rle"),

    // Known in Rust but not yet implemented; registered so EncodingId.from() doesn't throw
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

    /// Returns the enum constant for the given raw encoding id string.
    ///
    /// @param id raw encoding id string (e.g. {@code "vortex.primitive"})
    /// @return the matching {@link EncodingId}
    /// @throws io.github.dfa1.vortex.core.VortexException if the id is not recognised
    public static EncodingId from(String id) {
        EncodingId result = LOOKUP.get(id);
        if (result == null) {
            throw new VortexException("unknown encoding id: " + id);
        }
        return result;
    }

    /// Non-throwing lookup: returns the matching constant or `null` for ids not in this enum.
    /// Used by [Registry] to discriminate [KnownArrayNode] from [UnknownArrayNode].
    ///
    /// @param id raw encoding id string to look up
    /// @return the matching {@link EncodingId}, or {@code null} if not recognised
    public static EncodingId tryFrom(String id) {
        return LOOKUP.get(id);
    }

    /// Returns the raw encoding id string for this constant (e.g. {@code "vortex.primitive"}).
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
