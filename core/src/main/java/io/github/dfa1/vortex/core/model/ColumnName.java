package io.github.dfa1.vortex.core.model;

import java.util.Objects;
import java.util.Optional;

/// A non-null typed wrapper around a column (struct field) name, free of control characters.
///
/// The wire format accepts almost any UTF-8 string — including `""`, whitespace-only names,
/// and control characters. vortex-java accepts blank names on both the read and write paths
/// (the Rust reference produces them and they are wire-legal), but still rejects control
/// characters because they silently break CSV, JSON, SQL identifiers, and other downstream
/// consumers. Printable names of any shape remain legal: `""`, `"$$$$$"`, names with interior
/// spaces, and emoji all round-trip against the reference implementation.
///
/// The policy check lives in [#violation(String)] — the single source of truth used by the
/// schema builder, the writer, and the file parser.
///
/// @param value the validated name; non-null and free of control characters
public record ColumnName(String value) implements Comparable<ColumnName> {

    /// Validates the name against the policy (non-null, no control characters).
    ///
    /// @param value the column name
    /// @throws NullPointerException     if `value` is `null`
    /// @throws IllegalArgumentException if the name contains a control character
    public ColumnName {
        Objects.requireNonNull(value, "value");
        Optional<String> violation = violation(value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException(violation.get());
        }
    }

    /// Creates a validated column name — identical to the compact constructor.
    ///
    /// @param value the column name
    /// @return the validated [ColumnName]
    /// @throws NullPointerException     if `value` is `null`
    /// @throws IllegalArgumentException if the name contains a control character
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
