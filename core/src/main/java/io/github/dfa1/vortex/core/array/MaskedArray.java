package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.MemorySegment;

/// Decoded {@code vortex.masked} array: a non-nullable child paired with an optional validity bitmap.
///
/// <p>Invariant: {@code child} has no actual nulls — nullability is expressed solely via
/// {@code validity}. When {@code validity} is {@code null} all positions are valid.
///
/// <p>{@link #buffer(int)} delegates to the child so that existing consumers that read raw
/// value buffers continue to work transparently. Use {@link #isValid(long)} to check
/// individual positions before trusting a value.
public final class MaskedArray implements Array {

    private final Array child;
    private final BoolArray validity;

    /// Creates a new {@code MaskedArray} wrapping a non-nullable child with an optional validity bitmap.
    ///
    /// @param child    non-nullable payload array
    /// @param validity validity bitmap, or {@code null} if all positions are valid
    public MaskedArray(Array child, BoolArray validity) {
        this.child = child;
        this.validity = validity;
    }

    @Override
    public DType dtype() {
        return child.dtype().withNullable(true);
    }

    @Override
    public long length() {
        return child.length();
    }

    @Override
    public MemorySegment buffer(int i) {
        return child.buffer(i);
    }

    @Override
    public Array child(int i) {
        return switch (i) {
            case 0 -> child;
            case 1 -> {
                if (validity == null) {
                    throw new VortexException("no validity child: masked array was encoded as AllValid");
                }
                yield validity;
            }
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /// Returns the validity bitmap, or {@code null} if all positions are valid.
    ///
    /// @return the validity bitmap, or {@code null} when all values are valid
    public BoolArray validity() {
        return validity;
    }

    /// Returns {@code true} if the position at index {@code i} is valid (not null).
    ///
    /// @param i zero-based logical index
    /// @return {@code true} if the value at {@code i} is valid
    public boolean isValid(long i) {
        return validity == null || validity.getBoolean(i);
    }
}
