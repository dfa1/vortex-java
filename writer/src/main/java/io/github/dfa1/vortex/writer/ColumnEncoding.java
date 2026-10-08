package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.writer.encode.EncodingEncoder;

import java.util.List;

/// How one column's encodings are chosen, overriding the default cascade for that column only —
/// set with [WriteOptions#withColumnEncoding(io.github.dfa1.vortex.core.model.ColumnName, ColumnEncoding)].
///
/// Use it when the data's shape is known in advance: a fixed-precision decimal is an ALP column, an
/// enum is a dict column. The column then cascades over the given encoders only, as Rust compresses
/// a field whose writer was built with a restricted scheme set
/// (`WriteStrategyBuilder::with_field_writer`): the list is the whole candidate set, children
/// included, and a value no listed encoder improves on stays in its canonical encoding
/// (`vortex.primitive`, `vortex.varbin`, …). Skipping candidates that cannot win is most of a
/// cascading write's cost: one million fixed-precision doubles write about 6x faster with
/// `candidates(ALP, FoR, Bitpacked)` than with the default cascade, to the same bytes.
///
/// The edition guard still applies (a listed encoding outside the targeted edition is never
/// emitted), and the column is left out of the global dictionary.
///
/// @param encoders the candidate encoders, at least one
public record ColumnEncoding(List<EncodingEncoder> encoders) {

    /// Copies `encoders` and rejects an empty set.
    ///
    /// @throws IllegalArgumentException if `encoders` is empty
    public ColumnEncoding {
        encoders = List.copyOf(encoders);
        if (encoders.isEmpty()) {
            throw new IllegalArgumentException("a column needs at least one candidate encoder");
        }
    }

    /// The column cascades over `encoders` only; see the type documentation.
    ///
    /// @param encoders the candidate encoders, at least one
    /// @return the column encoding
    public static ColumnEncoding candidates(EncodingEncoder... encoders) {
        return new ColumnEncoding(List.of(encoders));
    }
}
