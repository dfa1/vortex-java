package io.github.dfa1.vortex.core.model;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Identity of a layout node — either a spec [WellKnown] constant or a third-party [Custom] id.
///
/// Layout ids and array-encoding ids ([EncodingId]) are separate namespaces — the Vortex footer
/// carries two dictionary tables (`layout_specs` and `array_specs`) — even though some strings
/// (`vortex.chunked`, `vortex.struct`, `vortex.dict`) appear in both. Layouts are runtime-pluggable
/// upstream, so this type is open: [#parse(String)] maps any wire string to a typed value, and
/// [#id()] recovers the wire string from a typed value.
public sealed interface LayoutId permits LayoutId.WellKnown, LayoutId.Custom {

    /// Returns the wire string of this layout id (e.g. `"vortex.flat"`).
    ///
    /// @return the wire string of this layout id
    String id();

    /// Parses a wire string into its typed representation: the matching [WellKnown] constant,
    /// else a [Custom] wrapping the raw string. Total over every non-blank string; blank input
    /// is not a valid layout id and is rejected by the [Custom] constructor — callers parsing
    /// untrusted input must guard blank ids and raise their own domain error.
    ///
    /// @param raw the raw layout id string (e.g. `"vortex.flat"`)
    /// @return the matching [WellKnown] constant, or a [Custom] wrapping `raw` if none matches
    /// @throws NullPointerException if `raw` is `null`
    /// @throws IllegalArgumentException if `raw` is blank
    static LayoutId parse(String raw) {
        WellKnown known = WellKnown.byId(raw);
        return known != null ? known : new Custom(raw);
    }

    /// Layout ids defined by the Vortex specification and understood by this build.
    enum WellKnown implements LayoutId {
        /// Flat (leaf) layout — a single encoded segment (`vortex.flat`).
        FLAT("vortex.flat"),
        /// Chunked layout — a sequence of flat layouts (`vortex.chunked`).
        CHUNKED("vortex.chunked"),
        /// Struct layout — one child per column (`vortex.struct`).
        STRUCT("vortex.struct"),
        /// Zone-map layout — wraps a child with per-chunk stats for pruning; the current canonical
        /// wire id upstream (`vortex.zoned`).
        ZONED("vortex.zoned"),
        /// Legacy wire alias of the zoned layout (`vortex.stats`). Rust keeps a legacy vtable for
        /// it, and vortex-java currently writes it; readers must route it to the zoned handling.
        STATS("vortex.stats"),
        /// Dictionary layout for low-cardinality columns (`vortex.dict`).
        DICT("vortex.dict"),
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

    /// A third-party layout id whose wire string is not part of the [WellKnown] set.
    ///
    /// @param id the wire string of this layout id; must be non-blank and must not collide
    ///           with a [WellKnown] wire string
    record Custom(String id) implements LayoutId {

        /// Validates that `id` is a usable custom layout id.
        ///
        /// @param id the wire string of this layout id
        /// @throws NullPointerException     if `id` is `null`
        /// @throws IllegalArgumentException if `id` is blank or matches a [WellKnown] wire string
        public Custom {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("layout id must not be blank");
            }
            WellKnown wellKnown = WellKnown.byId(id);
            if (wellKnown != null) {
                throw new IllegalArgumentException(
                        "\"" + id + "\" is a well-known layout id; use LayoutId." + wellKnown.name() + " instead");
            }
        }

        @Override
        public String toString() {
            return id;
        }
    }

    // Re-export every WellKnown constant, typed as WellKnown, so `LayoutId.FLAT` call sites stay
    // usable wherever a WellKnown is required.

    /// Well-known `vortex.flat` id.
    WellKnown FLAT = WellKnown.FLAT;
    /// Well-known `vortex.chunked` id.
    WellKnown CHUNKED = WellKnown.CHUNKED;
    /// Well-known `vortex.struct` id.
    WellKnown STRUCT = WellKnown.STRUCT;
    /// Well-known `vortex.zoned` id.
    WellKnown ZONED = WellKnown.ZONED;
    /// Well-known `vortex.stats` id.
    WellKnown STATS = WellKnown.STATS;
    /// Well-known `vortex.dict` id.
    WellKnown DICT = WellKnown.DICT;
}
