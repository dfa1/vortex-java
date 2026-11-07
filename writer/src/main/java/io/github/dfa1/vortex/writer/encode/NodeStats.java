package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.proto.ProtoScalarValue;

/// The array stats Rust records on one node of a flat segment's `ArrayNode` tree. A `null` component
/// is a stat the node does not carry: Rust's flat layout retains only what the compressor had
/// computed on the array, and each encoding passes on only some of it.
///
/// Every stat written is exact.
///
/// @param min              exact minimum of the array's values, or `null`
/// @param max              exact maximum of the array's values, or `null`
/// @param isConstant       whether every value is equal, or `null`
/// @param isSorted         whether the values are sorted, or `null`
/// @param isStrictSorted   whether the values are strictly sorted, or `null`
/// @param nullCount        number of null rows, or `null`
/// @param uncompressedSize size of the canonical array in bytes, or `null`
/// @param nanCount         number of NaN values, or `null`
/// @param sum              exact sum of the values, or `null`
public record NodeStats(
        ProtoScalarValue min,
        ProtoScalarValue max,
        Boolean isConstant,
        Boolean isSorted,
        Boolean isStrictSorted,
        Long nullCount,
        Long uncompressedSize,
        Long nanCount,
        ProtoScalarValue sum) {

    /// Stats without a sum.
    ///
    /// @param min              exact minimum of the array's values, or `null`
    /// @param max              exact maximum of the array's values, or `null`
    /// @param isConstant       whether every value is equal, or `null`
    /// @param isSorted         whether the values are sorted, or `null`
    /// @param isStrictSorted   whether the values are strictly sorted, or `null`
    /// @param nullCount        number of null rows, or `null`
    /// @param uncompressedSize size of the canonical array in bytes, or `null`
    /// @param nanCount         number of NaN values, or `null`
    public NodeStats(ProtoScalarValue min, ProtoScalarValue max, Boolean isConstant, Boolean isSorted,
                     Boolean isStrictSorted, Long nullCount, Long uncompressedSize, Long nanCount) {
        this(min, max, isConstant, isSorted, isStrictSorted, nullCount, uncompressedSize, nanCount, null);
    }

    /// A node that carries no stats.
    public static final NodeStats NONE = new NodeStats(null, null, null, null, null, null, null, null, null);

    /// Whether this node carries no stat at all.
    ///
    /// @return `true` if every component is `null`
    public boolean isEmpty() {
        return min == null && max == null && isConstant == null && isSorted == null && isStrictSorted == null
                && nullCount == null && uncompressedSize == null && nanCount == null && sum == null;
    }
}
