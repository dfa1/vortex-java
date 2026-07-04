package io.github.dfa1.vortex.core.model;

import java.util.Objects;
import java.util.Optional;

/// A column (struct field) name that satisfies vortex-java's name policy: non-blank and free
/// of control characters.
///
/// The wire format itself accepts almost any UTF-8 string — including `""`, whitespace-only
/// names, and control characters — but those are footguns this implementation refuses on both
/// sides of the file boundary (the writer never emits them, the reader rejects files carrying
/// them). A `ColumnName` certifies at construction that a name passed that policy, so code
/// holding one never re-validates. Printable names of any shape remain legal: `"$$$$$"`,
/// names with interior spaces, and emoji all round-trip against the reference implementation.
///
/// The policy checks live in [#violation(String)] — the single source of truth used by the
/// schema builder, the writer, and the file parser (which wraps violations in its own
/// file-context error).
///
/// @param value the validated name; use it wherever a raw column-name string is required
public record ColumnName(String value) implements Comparable<ColumnName> {

    /// Validates the name against the policy.
    ///
    /// @param value the column name
    /// @throws NullPointerException     if `value` is `null`
    /// @throws IllegalArgumentException if the name violates the policy ([#violation(String)])
    public ColumnName {
        Objects.requireNonNull(value, "value");
        Optional<String> violation = violation(value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException(violation.get());
        }
    }

    /// Creates a validated column name.
    ///
    /// @param value the column name
    /// @return the validated [ColumnName]
    /// @throws NullPointerException     if `value` is `null`
    /// @throws IllegalArgumentException if the name violates the policy ([#violation(String)])
    public static ColumnName of(String value) {
        return new ColumnName(value);
    }

    /// Checks a raw name against the policy without constructing anything — the shared
    /// chokepoint for all boundary guards. Callers that need a domain-specific exception
    /// (e.g. the file parser's `VortexException` with file context) format the returned
    /// reason themselves.
    ///
    /// @param name the raw column name to check (must be non-`null`)
    /// @return the policy violation, or empty if `name` is a valid column name
    public static Optional<String> violation(String name) {
        if (name.isBlank()) {
            return Optional.of("blank field name (wire-legal, but a footgun vortex-java refuses to write)");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return Optional.of("field name contains control character U+%04X"
                        .formatted((int) name.charAt(i)));
            }
        }
        return Optional.empty();
    }

    /// Orders by the name string, so sorted collections of columns read naturally.
    ///
    /// @param other the name to compare against
    /// @return negative, zero, or positive per [String#compareTo(String)] on the values
    @Override
    public int compareTo(ColumnName other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
