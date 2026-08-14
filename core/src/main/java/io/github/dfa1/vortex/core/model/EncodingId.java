package io.github.dfa1.vortex.core.model;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Identity of an array encoding — either a spec [WellKnown] constant or a third-party [Custom] id.
///
/// The wire representation is always a string (e.g. `"vortex.primitive"`); [#parse(String)] maps any
/// such string to a typed value, and [#id()] recovers the wire string from a typed value.
///
/// Extends [Serializable] so a [Custom] or [WellKnown] carried on a
/// [io.github.dfa1.vortex.core.error.VortexException] survives serialization, matching the prior
/// enum's implicit serializability.
public sealed interface EncodingId extends Serializable permits EncodingId.WellKnown, EncodingId.Custom {

    /// Returns the wire string of this encoding id (e.g. `"vortex.primitive"`).
    ///
    /// @return the wire string of this encoding id
    String id();

    /// Parses a wire string into its typed representation: the matching [WellKnown] constant,
    /// else a [Custom] wrapping the raw string. Total over every non-blank string; blank input
    /// is not a valid encoding id and is rejected by the [Custom] constructor — callers parsing
    /// untrusted input must guard blank ids and raise their own domain error.
    ///
    /// @param raw the raw encoding id string (e.g. `"vortex.primitive"`)
    /// @return the matching [WellKnown] constant, or a [Custom] wrapping `raw` if none matches
    /// @throws NullPointerException if `raw` is `null`
    /// @throws IllegalArgumentException if `raw` is blank
    static EncodingId parse(String raw) {
        WellKnown known = WellKnown.byId(raw);
        return known != null ? known : new Custom(raw);
    }

    /// Encoding ids defined by the Vortex specification and understood by this build.
    enum WellKnown implements EncodingId {
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
        /// Map encoding (`vortex.map`): a list-view of `{key, value}` entry structs.
        VORTEX_MAP("vortex.map"),

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

        /// Masked encoding (`vortex.masked`): payload child plus optional validity bitmap child.
        VORTEX_MASKED("vortex.masked"),
        /// Patched encoding (`vortex.patched`): base child with sparse positional patch overrides.
        VORTEX_PATCHED("vortex.patched"),
        /// Variant logical encoding: canonical container over `core_storage` plus an optional shredded child.
        VORTEX_VARIANT("vortex.variant"),
        ;

        // O(1) access to a WellKnown constant by its string representation
        private static final Map<String, WellKnown> LOOKUP = Stream.of(values())
                                                                  .collect(Collectors.toUnmodifiableMap(WellKnown::id, Function.identity()));
        private final String id;

        WellKnown(String id) {
            this.id = id;
        }

        /// Returns the well-known constant whose wire string is `id`, or `null` if none matches.
        ///
        /// @param id the wire string to look up (may be `null`)
        /// @return the matching constant, or `null` if unrecognized
        static WellKnown byId(String id) {
            return LOOKUP.get(id);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /// A third-party encoding id whose wire string is not part of the [WellKnown] set.
    ///
    /// @param id the wire string of this encoding id; must be non-blank, free of ISO control
    ///           characters, and must not collide with a [WellKnown] wire string
    record Custom(String id) implements EncodingId {

        /// Validates that `id` is a usable custom encoding id. A control character would write a
        /// file that [io.github.dfa1.vortex.core.error.VortexException]-raising parsers crash on,
        /// so the same policy as [ColumnName#violation(String)] applies here.
        ///
        /// @param id the wire string of this encoding id
        /// @throws NullPointerException     if `id` is `null`
        /// @throws IllegalArgumentException if `id` is blank, contains an ISO control character, or
        ///                                  matches a [WellKnown] wire string
        public Custom {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("encoding id must not be blank");
            }
            for (int i = 0; i < id.length(); i++) {
                if (Character.isISOControl(id.charAt(i))) {
                    throw new IllegalArgumentException(
                            "encoding id contains control character U+%04X".formatted((int) id.charAt(i)));
                }
            }
            WellKnown wellKnown = WellKnown.byId(id);
            if (wellKnown != null) {
                throw new IllegalArgumentException(
                        "\"" + id + "\" is a well-known encoding id; use EncodingId." + wellKnown.name() + " instead");
            }
        }

        @Override
        public String toString() {
            return id;
        }
    }

    // Re-export every WellKnown constant, typed as WellKnown, so existing `EncodingId.VORTEX_FOO`
    // call sites keep compiling and remain usable wherever a WellKnown is required.

    /// Well-known `vortex.primitive` id.
    WellKnown VORTEX_PRIMITIVE = WellKnown.VORTEX_PRIMITIVE;
    /// Well-known `vortex.bool` id.
    WellKnown VORTEX_BOOL = WellKnown.VORTEX_BOOL;
    /// Well-known `vortex.dict` id.
    WellKnown VORTEX_DICT = WellKnown.VORTEX_DICT;
    /// Well-known `vortex.sparse` id.
    WellKnown VORTEX_SPARSE = WellKnown.VORTEX_SPARSE;
    /// Well-known `vortex.sequence` id.
    WellKnown VORTEX_SEQUENCE = WellKnown.VORTEX_SEQUENCE;
    /// Well-known `vortex.runend` id.
    WellKnown VORTEX_RUNEND = WellKnown.VORTEX_RUNEND;
    /// Well-known `vortex.constant` id.
    WellKnown VORTEX_CONSTANT = WellKnown.VORTEX_CONSTANT;
    /// Well-known `vortex.alp` id.
    WellKnown VORTEX_ALP = WellKnown.VORTEX_ALP;
    /// Well-known `vortex.varbin` id.
    WellKnown VORTEX_VARBIN = WellKnown.VORTEX_VARBIN;
    /// Well-known `vortex.fsst` id.
    WellKnown VORTEX_FSST = WellKnown.VORTEX_FSST;
    /// Well-known `vortex.null` id.
    WellKnown VORTEX_NULL = WellKnown.VORTEX_NULL;
    /// Well-known `vortex.bytebool` id.
    WellKnown VORTEX_BYTEBOOL = WellKnown.VORTEX_BYTEBOOL;
    /// Well-known `vortex.zigzag` id.
    WellKnown VORTEX_ZIGZAG = WellKnown.VORTEX_ZIGZAG;
    /// Well-known `vortex.ext` id.
    WellKnown VORTEX_EXT = WellKnown.VORTEX_EXT;
    /// Well-known `vortex.varbinview` id.
    WellKnown VORTEX_VARBINVIEW = WellKnown.VORTEX_VARBINVIEW;
    /// Well-known `vortex.pco` id.
    WellKnown VORTEX_PCO = WellKnown.VORTEX_PCO;
    /// Well-known `vortex.decimal` id.
    WellKnown VORTEX_DECIMAL = WellKnown.VORTEX_DECIMAL;
    /// Well-known `vortex.decimal_byte_parts` id.
    WellKnown VORTEX_DECIMAL_BYTE_PARTS = WellKnown.VORTEX_DECIMAL_BYTE_PARTS;
    /// Well-known `vortex.datetimeparts` id.
    WellKnown VORTEX_DATETIMEPARTS = WellKnown.VORTEX_DATETIMEPARTS;
    /// Well-known `vortex.zstd` id.
    WellKnown VORTEX_ZSTD = WellKnown.VORTEX_ZSTD;
    /// Well-known `vortex.fixed_size_list` id.
    WellKnown VORTEX_FIXED_SIZE_LIST = WellKnown.VORTEX_FIXED_SIZE_LIST;
    /// Well-known `vortex.list` id.
    WellKnown VORTEX_LIST = WellKnown.VORTEX_LIST;
    /// Well-known `vortex.listview` id.
    WellKnown VORTEX_LISTVIEW = WellKnown.VORTEX_LISTVIEW;
    /// Well-known `vortex.alprd` id.
    WellKnown VORTEX_ALPRD = WellKnown.VORTEX_ALPRD;
    /// Well-known `vortex.map` id.
    WellKnown VORTEX_MAP = WellKnown.VORTEX_MAP;
    /// Well-known `vortex.chunked` id.
    WellKnown VORTEX_CHUNKED = WellKnown.VORTEX_CHUNKED;
    /// Well-known `vortex.struct` id.
    WellKnown VORTEX_STRUCT = WellKnown.VORTEX_STRUCT;
    /// Well-known `fastlanes.bitpacked` id.
    WellKnown FASTLANES_BITPACKED = WellKnown.FASTLANES_BITPACKED;
    /// Well-known `fastlanes.for` id.
    WellKnown FASTLANES_FOR = WellKnown.FASTLANES_FOR;
    /// Well-known `fastlanes.delta` id.
    WellKnown FASTLANES_DELTA = WellKnown.FASTLANES_DELTA;
    /// Well-known `fastlanes.rle` id.
    WellKnown FASTLANES_RLE = WellKnown.FASTLANES_RLE;
    /// Well-known `vortex.masked` id.
    WellKnown VORTEX_MASKED = WellKnown.VORTEX_MASKED;
    /// Well-known `vortex.patched` id.
    WellKnown VORTEX_PATCHED = WellKnown.VORTEX_PATCHED;
    /// Well-known `vortex.variant` id.
    WellKnown VORTEX_VARIANT = WellKnown.VORTEX_VARIANT;
}
