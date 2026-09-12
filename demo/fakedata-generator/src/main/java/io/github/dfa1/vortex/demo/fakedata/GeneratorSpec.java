package io.github.dfa1.vortex.demo.fakedata;

/// One column's value-generation rule, parsed from the `generator(args)` part of a column
/// descriptor (see [DescriptorParser]).
///
/// - [Series] mirrors PostgreSQL's `generate_series`: an arithmetic progression, `start + i *
///   step` for row `i`. Numeric columns only.
/// - [Range] draws uniformly at random from `[min, max]`. Numeric columns only.
/// - [Normal] draws from a Gaussian distribution with the given mean and standard deviation.
///   Numeric columns only.
/// - [EnumLabels] cycles uniformly at random through `count` labels `prefix0` … `prefix(count-1)`
///   (zero-padded to a common width). `Utf8` columns only. Combine with `--sort-by` on the CLI to
///   cluster same-label rows together (e.g. for zone-map-pruning-friendly data).
/// - [Constant] repeats one literal value for every row. Any column type.
/// - [RandomBool] draws uniformly at random between `true` and `false`. `Bool` columns only.
public sealed interface GeneratorSpec {

    /// @param start first value (row 0)
    /// @param step  increment per row
    record Series(double start, double step) implements GeneratorSpec {
    }

    /// @param min inclusive lower bound
    /// @param max inclusive upper bound
    record Range(double min, double max) implements GeneratorSpec {
    }

    /// @param mean   distribution mean
    /// @param stddev distribution standard deviation
    record Normal(double mean, double stddev) implements GeneratorSpec {
    }

    /// @param prefix label prefix
    /// @param count  number of distinct labels
    record EnumLabels(String prefix, int count) implements GeneratorSpec {
    }

    /// @param literal the value's literal text, parsed per the column's declared type
    record Constant(String literal) implements GeneratorSpec {
    }

    record RandomBool() implements GeneratorSpec {
    }
}
