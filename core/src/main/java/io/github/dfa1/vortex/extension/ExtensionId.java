package io.github.dfa1.vortex.extension;

import io.github.dfa1.vortex.core.VortexException;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Strongly-typed identifier for the spec-defined Vortex extension types.
/// Mirrors [io.github.dfa1.vortex.encoding.EncodingId] in shape so callers
/// reach for the same pattern across both registries.
///
/// Unknown wire ids (e.g. {@code "acme.geopoint"}) are not represented here —
/// they flow through the registry's unknown-passthrough path.
public enum ExtensionId {
    /// {@code vortex.date}.
    VORTEX_DATE("vortex.date"),
    /// {@code vortex.time}.
    VORTEX_TIME("vortex.time"),
    /// {@code vortex.timestamp}.
    VORTEX_TIMESTAMP("vortex.timestamp"),
    /// {@code vortex.uuid}.
    VORTEX_UUID("vortex.uuid");

    private static final Map<String, ExtensionId> LOOKUP = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ExtensionId::id, Function.identity()));

    private final String id;

    ExtensionId(String id) {
        this.id = id;
    }

    /// Returns the enum constant for the given raw extension id string.
    ///
    /// @param id raw extension id string (e.g. {@code "vortex.timestamp"})
    /// @return the matching {@link ExtensionId}
    /// @throws VortexException if the id is not a known spec-defined extension
    public static ExtensionId from(String id) {
        ExtensionId result = LOOKUP.get(id);
        if (result == null) {
            throw new VortexException("unknown extension id: " + id);
        }
        return result;
    }

    /// Non-throwing lookup for a raw extension id string.
    ///
    /// @param id raw extension id string
    /// @return matching constant, or {@code null} if not a known spec extension
    public static ExtensionId tryFrom(String id) {
        return LOOKUP.get(id);
    }

    /// Returns the canonical wire-format id string.
    ///
    /// @return canonical id (e.g. {@code "vortex.timestamp"})
    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return id;
    }
}
