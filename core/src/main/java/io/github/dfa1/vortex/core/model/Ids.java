package io.github.dfa1.vortex.core.model;

import java.util.Objects;

/// The validation every custom id ([EncodingId], [ExtensionId], [LayoutId]) shares.
final class Ids {

    private Ids() {
    }

    /// Rejects a null or blank id, or one holding a control character.
    ///
    /// @param id   the id to check
    /// @param kind what the id names, for the message (`"encoding"`, `"layout"`, …)
    /// @throws IllegalArgumentException if `id` is blank or holds a control character
    static void requireValid(String id, String kind) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException(kind + " id must not be blank");
        }
        for (int i = 0; i < id.length(); i++) {
            if (Character.isISOControl(id.charAt(i))) {
                throw new IllegalArgumentException(
                        "%s id contains control character U+%04X".formatted(kind, (int) id.charAt(i)));
            }
        }
    }
}
