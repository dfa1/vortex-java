package io.github.dfa1.vortex.reader.compute;

import java.util.Objects;

/// A column predicate as pure data, the filter vocabulary for the compute kernels sketched in
/// ADR 0013 §4.
///
/// A predicate describes *what* to test, never *how*: it carries no column name and no evaluation
/// logic. A `FilterKernel` dispatches on the concrete variant via an exhaustive pattern switch and
/// lowers each leaf into an encoded-domain comparison for the array at hand (ALP, for instance,
/// encodes the scalar into its integer domain once, then runs a long compare). The same predicate
/// can instead be compiled against per-zone min/max statistics for chunk pruning — ADR 0013 §5
/// makes this the shared vocabulary that [io.github.dfa1.vortex.reader.RowFilter] will accept once
/// the two layers unify.
///
/// The leaf variants split by what they test:
/// - [Predicate.Eq] — equality against a value.
/// - [Predicate.Lt] / [Predicate.Gt] — strict ordering against a value.
/// - [Predicate.Between] — an inclusive range `[lo, hi]`.
/// - [Predicate.IsNull] / [Predicate.IsNotNull] — the dedicated null tests; a `null` value is never
///   passed to the comparison leaves.
///
/// The composite variants nest other predicates:
/// - [Predicate.And] / [Predicate.Or] — boolean composition of two sub-predicates, recursive so
///   arbitrary trees compose.
///
/// Predicates are immutable records: equality, hash code and string form follow component-wise from
/// the record contract, so two structurally identical trees are equal.
public sealed interface Predicate
        permits Predicate.Eq, Predicate.Lt, Predicate.Gt, Predicate.Between,
                Predicate.IsNull, Predicate.IsNotNull, Predicate.And, Predicate.Or {

    /// Matches rows whose value equals `value`.
    ///
    /// Equality is value-domain equality (the kernel decides how that maps onto the encoded form);
    /// the dedicated [Predicate.IsNull] covers the null test, so `value` here is never `null`.
    ///
    /// @param value the value to compare against, must be non-null
    record Eq(Object value) implements Predicate {

        /// Validates the value.
        ///
        /// @param value the value to compare against, must be non-null
        public Eq {
            Objects.requireNonNull(value, "value");
        }
    }

    /// Matches rows whose value is strictly less than `value`.
    ///
    /// @param value the exclusive upper bound, must be non-null and orderable
    record Lt(Comparable<?> value) implements Predicate {

        /// Validates the value.
        ///
        /// @param value the exclusive upper bound, must be non-null
        public Lt {
            Objects.requireNonNull(value, "value");
        }
    }

    /// Matches rows whose value is strictly greater than `value`.
    ///
    /// @param value the exclusive lower bound, must be non-null and orderable
    record Gt(Comparable<?> value) implements Predicate {

        /// Validates the value.
        ///
        /// @param value the exclusive lower bound, must be non-null
        public Gt {
            Objects.requireNonNull(value, "value");
        }
    }

    /// Matches rows whose value lies within the inclusive range `[lo, hi]`.
    ///
    /// @param lo the inclusive lower bound, must be non-null and orderable
    /// @param hi the inclusive upper bound, must be non-null and orderable
    record Between(Comparable<?> lo, Comparable<?> hi) implements Predicate {

        /// Validates both bounds.
        ///
        /// @param lo the inclusive lower bound, must be non-null
        /// @param hi the inclusive upper bound, must be non-null
        public Between {
            Objects.requireNonNull(lo, "lo");
            Objects.requireNonNull(hi, "hi");
        }
    }

    /// Matches rows whose value is null.
    record IsNull() implements Predicate {
    }

    /// Matches rows whose value is not null.
    ///
    /// The complement of [Predicate.IsNull]; present so the vocabulary is a superset of
    /// [io.github.dfa1.vortex.reader.RowFilter], which pushes both null tests into zone-map pruning.
    record IsNotNull() implements Predicate {
    }

    /// Matches rows that satisfy both sub-predicates.
    ///
    /// @param left  the first sub-predicate, must be non-null
    /// @param right the second sub-predicate, must be non-null
    record And(Predicate left, Predicate right) implements Predicate {

        /// Validates both operands.
        ///
        /// @param left  the first sub-predicate, must be non-null
        /// @param right the second sub-predicate, must be non-null
        public And {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /// Matches rows that satisfy either sub-predicate.
    ///
    /// @param left  the first sub-predicate, must be non-null
    /// @param right the second sub-predicate, must be non-null
    record Or(Predicate left, Predicate right) implements Predicate {

        /// Validates both operands.
        ///
        /// @param left  the first sub-predicate, must be non-null
        /// @param right the second sub-predicate, must be non-null
        public Or {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }
}
