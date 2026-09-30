package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;

/// Computes the `{min, max}` zone-map bounds for whatever shape an encoder was handed.
///
/// Every encoding that wins a column is responsible for reporting the column's bounds, or that
/// column silently loses zone-map pruning and `MIN`/`MAX` push-down. Five encodings shipped
/// without doing so before this existed, each found by eye on one dataset, because the bounds had
/// to be assembled differently for each input shape and passing `null` compiled just as well.
///
/// The shapes an encoder can receive:
/// - [NullableData] - null slots hold whatever the backing array happened to contain, so the
///   values are compacted against the validity mask first. Taking the raw array's minimum here
///   would report `0` for a column of positive values, which is worse than reporting nothing.
/// - a primitive array (`long[]`, `double[]`, …) matching the dtype's [io.github.dfa1.vortex.core.model.PType]
/// - `String[]`, compared lexicographically, skipping nulls
///
/// Anything else - binary blobs, nested types - has no useful ordering and yields `null`.
final class ZoneMapStats {

    private ZoneMapStats() {
    }

    /// The zone-map bounds for `data`, or `null` when the shape carries no useful ordering.
    ///
    /// @param dtype the column's dtype; a nullable one is read through to its non-nullable form
    /// @param data  the values being encoded
    /// @return a two-element `{min, max}` array of encoded scalars, or `null`
    static byte[][] of(DType dtype, Object data) {
        if (data instanceof NullableData(var values, var validity)) {
            DType nonNullable = dtype == null ? null : dtype.withNullable(false);
            if (nonNullable instanceof DType.Primitive p) {
                return PrimitiveEncodingEncoder.minMaxStats(p.ptype(),
                        PrimitiveArrays.compact(p.ptype(), values, validity));
            }
            // A String[] carries a real null at its invalid positions, so the lexicographic
            // null-skip is already correct and needs no compaction.
            return values instanceof String[] strings ? VarBinEncodingEncoder.minMaxStats(strings) : null;
        }
        if (data instanceof String[] strings) {
            return VarBinEncodingEncoder.minMaxStats(strings);
        }
        DType effective = dtype instanceof DType.Extension ext ? ext.storageDType() : dtype;
        if (effective instanceof DType.Primitive p && data != null && data.getClass().isArray()) {
            return PrimitiveEncodingEncoder.minMaxStats(p.ptype(), data);
        }
        return null;
    }

    /// The min half of an [#of(DType, Object)] result, or `null`.
    ///
    /// @param stats an [#of(DType, Object)] result, possibly `null`
    /// @return the serialized min scalar, or `null`
    static byte[] minOf(byte[][] stats) {
        return stats == null ? null : stats[0];
    }

    /// The max half of an [#of(DType, Object)] result, or `null`.
    ///
    /// @param stats an [#of(DType, Object)] result, possibly `null`
    /// @return the serialized max scalar, or `null`
    static byte[] maxOf(byte[][] stats) {
        return stats == null ? null : stats[1];
    }
}
