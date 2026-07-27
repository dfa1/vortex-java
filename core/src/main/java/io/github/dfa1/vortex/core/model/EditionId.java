package io.github.dfa1.vortex.core.model;

import java.util.Locale;

/// Identifier of a Vortex [edition](https://github.com/vortex-data/vortex/blob/develop/docs/specs/editions.md),
/// e.g. `core2026.07.0`.
///
/// `family` names an independently versioned, additive group of encodings (`core` is the set the
/// default writer emits); the date components record when the edition was frozen and order
/// editions chronologically *within* a family — there is no ordering across families.
///
/// @param family  the edition family
/// @param year    the four-digit year the edition was cut
/// @param month   the month the edition was cut (1-12)
/// @param version distinguishes editions cut in the same month; normally `0`
public record EditionId(EditionFamily family, int year, int month, int version) {

    /// Validates the identifier's form: a four-digit year and a month in 1-12 (`family` needs no
    /// validation of its own — [EditionFamily] is a closed enum). Editions are a client-side
    /// write/read policy, never derived from untrusted file bytes, so a malformed value here is a
    /// programmer error — hence [IllegalArgumentException], not
    /// [io.github.dfa1.vortex.core.error.VortexException] (which is reserved for untrusted-input
    /// parsing failures).
    ///
    /// @throws IllegalArgumentException if `year` or `month` is malformed
    public EditionId {
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("edition year must be four digits, got: " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("edition month must be in 1-12, got: " + month);
        }
    }

    /// Returns `true` if `this` is the same edition as `other` or an earlier edition of the same
    /// family. Editions of different families are never ordered.
    ///
    /// @param other the edition id to compare against
    /// @return `true` if `this` is at or before `other` within the same family
    public boolean isAtOrBefore(EditionId other) {
        if (!family.equals(other.family)) {
            return false;
        }
        if (year != other.year) {
            return year < other.year;
        }
        if (month != other.month) {
            return month < other.month;
        }
        return version <= other.version;
    }

    @Override
    public String toString() {
        return "%s%d.%02d.%d".formatted(family.name().toLowerCase(Locale.ROOT), year, month, version);
    }
}
