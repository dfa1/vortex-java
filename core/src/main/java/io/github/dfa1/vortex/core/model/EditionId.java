package io.github.dfa1.vortex.core.model;

import java.time.YearMonth;
import java.util.Locale;
import java.util.Objects;

/// Identifier of a Vortex [edition](https://github.com/vortex-data/vortex/blob/develop/docs/specs/editions.md),
/// e.g. `core2026.07.0`.
///
/// `family` names an independently versioned, additive group of encodings (`core` is the set the
/// default writer emits); `cutMonth` records when the edition was frozen and orders editions
/// chronologically *within* a family — there is no ordering across families.
///
/// @param family   the edition family
/// @param cutMonth the year and month the edition was cut
/// @param version  distinguishes editions cut in the same month; normally `0`
public record EditionId(EditionFamily family, YearMonth cutMonth, int version) {

    /// @throws NullPointerException if `family` or `cutMonth` is `null`
    public EditionId {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(cutMonth, "cutMonth");
    }

    /// Returns `true` if `this` is the same edition as `other` or an earlier edition of the same
    /// family. Editions of different families are never ordered.
    ///
    /// @param other the edition id to compare against
    /// @return `true` if `this` is at or before `other` within the same family
    public boolean isAtOrBefore(EditionId other) {
        if (family != other.family) {
            return false;
        }
        int cmp = cutMonth.compareTo(other.cutMonth);
        return cmp != 0 ? cmp < 0 : version <= other.version;
    }

    @Override
    public String toString() {
        return "%s%d.%02d.%d".formatted(
                family.name().toLowerCase(Locale.ROOT), cutMonth.getYear(), cutMonth.getMonthValue(), version);
    }
}
