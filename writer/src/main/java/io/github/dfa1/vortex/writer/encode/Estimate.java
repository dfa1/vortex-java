package io.github.dfa1.vortex.writer.encode;

/// Compression estimate returned by an [EncodingEncoder] given pre-computed [ArrayStats].
///
/// Two terminal verdicts:
/// - [Skip] — encoder is incompatible with this input; do not consider.
/// - [AlwaysUse] — encoder is provably the best choice; pick immediately.
///
/// Encoders that are neither incompatible nor provably-best return `null` so the
/// [CascadingCompressor] competes them by real sample-encode size rather than an
/// up-front ratio estimate.
public sealed interface Estimate {

    /// Skip this encoder for this input.
    record Skip() implements Estimate {
    }

    /// Pick this encoder immediately; short-circuit the cascade.
    record AlwaysUse() implements Estimate {
    }

    Estimate SKIP = new Skip();
    Estimate ALWAYS_USE = new AlwaysUse();

    /// @return the [Skip] verdict singleton
    static Estimate skip() {
        return SKIP;
    }

    /// @return the [AlwaysUse] verdict singleton
    static Estimate alwaysUse() {
        return ALWAYS_USE;
    }
}
