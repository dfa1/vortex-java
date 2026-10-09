package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.EncodingId;

import java.util.List;

/// How one column's encodings are chosen, overriding the default cascade for that column only —
/// set with [WriteOptions#withColumnEncoding(io.github.dfa1.vortex.core.model.ColumnName, ColumnEncoding)].
///
/// Use it when the data's shape is known in advance: a fixed-precision decimal is an ALP column, an
/// enum is a dict column. Encodings are named by [EncodingId] — `EncodingId.VORTEX_ALP` for a
/// built-in, or a custom encoding's id registered on the writer's [WriteRegistry] — and resolved to
/// the writer's encoders when it is created, which fails for an id it cannot encode. The column
/// then cascades over the given encodings only, as Rust compresses
/// a field whose writer was built with a restricted scheme set
/// (`WriteStrategyBuilder::with_field_writer`): the list is the whole candidate set, children
/// included, and a value no listed encoder improves on stays in its canonical encoding
/// (`vortex.primitive`, `vortex.varbin`, …). Skipping candidates that cannot win is most of a
/// cascading write's cost: one million fixed-precision doubles write about 3.5x faster with
/// `candidates(VORTEX_ALP, FASTLANES_FOR, FASTLANES_BITPACKED)` than with the default cascade, to
/// the same bytes.
///
/// The edition guard still applies (a listed encoding outside the targeted edition is never
/// emitted), and the column is left out of the global dictionary.
///
/// @param encodings the candidate encodings, at least one
public record ColumnEncoding(List<EncodingId> encodings) {

    /// Copies `encodings` and rejects an empty set.
    ///
    /// @throws IllegalArgumentException if `encodings` is empty
    public ColumnEncoding {
        encodings = List.copyOf(encodings);
        if (encodings.isEmpty()) {
            throw new IllegalArgumentException("a column needs at least one candidate encoding");
        }
    }

    /// The column cascades over `encodings` only; see the type documentation.
    ///
    /// @param encodings the candidate encodings, at least one
    /// @return the column encoding
    public static ColumnEncoding candidates(EncodingId... encodings) {
        return new ColumnEncoding(List.of(encodings));
    }
}
