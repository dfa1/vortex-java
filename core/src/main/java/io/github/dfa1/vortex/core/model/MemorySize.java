package io.github.dfa1.vortex.core.model;

/// A validated, non-negative byte count.
///
/// Byte-size quantities (cache budgets, metadata caps, target file sizes) are otherwise scattered
/// through the codebase as raw `long`/`int` fields computed with hand-written `* 1024` arithmetic
/// — a missing multiplier, a KiB/MiB/GiB mix-up, or a negative value all silently compile and only
/// surface as a wrong runtime number. Once constructed, a `MemorySize` is guaranteed non-negative;
/// call sites that must hand a primitive to a JDK/native API (`Arena#allocate`, etc.) read it back
/// via [#bytes()]. Mirrors `io.github.dfa1.zstd.ZstdByteSize` from the sibling zstd-java project.
///
/// @param bytes the byte count; never negative
public record MemorySize(long bytes) {

    /// Rejects a negative size at construction, so no caller downstream has to re-check one.
    ///
    /// @throws IllegalArgumentException if `bytes` is negative
    public MemorySize {
        if (bytes < 0) {
            throw new IllegalArgumentException("byte count must be non-negative: " + bytes);
        }
    }

    /// Creates a [MemorySize] from a count of kibibytes (1 KiB = 1024 bytes).
    ///
    /// @param kib the count of kibibytes; must be non-negative
    /// @return the equivalent [MemorySize]
    /// @throws IllegalArgumentException if `kib` is negative
    public static MemorySize ofKiB(long kib) {
        return new MemorySize(kib * 1024);
    }

    /// Creates a [MemorySize] from a count of mebibytes (1 MiB = 1024 * 1024 bytes).
    ///
    /// @param mib the count of mebibytes; must be non-negative
    /// @return the equivalent [MemorySize]
    /// @throws IllegalArgumentException if `mib` is negative
    public static MemorySize ofMiB(long mib) {
        return new MemorySize(mib * 1024 * 1024);
    }

    /// Creates a [MemorySize] from a count of gibibytes (1 GiB = 1024 * 1024 * 1024 bytes).
    ///
    /// @param gib the count of gibibytes; must be non-negative
    /// @return the equivalent [MemorySize]
    /// @throws IllegalArgumentException if `gib` is negative
    public static MemorySize ofGiB(long gib) {
        return new MemorySize(gib * 1024 * 1024 * 1024);
    }

    /// Returns this size in gibibytes (1 GiB = 1024 * 1024 * 1024 bytes) as a fractional value,
    /// for human-readable display (e.g. `"%.2f GB".formatted(size.toGiB())`) — the domain-primitive
    /// replacement for hand-written `bytes / (double) (1L << 30)` at call sites.
    ///
    /// @return `bytes` divided by 1024 * 1024 * 1024
    public double toGiB() {
        return bytes / (double) (1024L * 1024 * 1024);
    }
}
