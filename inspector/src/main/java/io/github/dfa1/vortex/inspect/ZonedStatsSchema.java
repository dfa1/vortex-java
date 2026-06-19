package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/// Reconstructs the per-zone statistics-table [DType] for a
/// `vortex.stats` (Zoned) layout.
///
/// The shape is sourced from the Rust reference implementation:
/// - Metadata format
///       (<a href="https://github.com/spiraldb/vortex/blob/develop/vortex-layout/src/layouts/zoned/mod.rs">
///       vortex-layout/src/layouts/zoned/mod.rs</a> — `ZonedMetadata`):
///       bytes [0..4) are the zone length as a little-endian `u32`;
///       remaining bytes form a `Stat` bitset (LSB-first per byte). Each
///       set bit at index `i` indicates that the {@link Stat} with that
///       ordinal is present in the auxiliary stats table.
/// - Schema construction
///       (<a href="https://github.com/spiraldb/vortex/blob/develop/vortex-layout/src/layouts/zoned/schema.rs">
///       vortex-layout/src/layouts/zoned/schema.rs</a> — `stats_table_dtype`):
///       for each present stat in ordinal order, append a struct field with the
///       stat's name and the stat's nullable dtype. `Max` and `Min`
///       each get an extra trailing field `max_is_truncated` /
///       `min_is_truncated` of type `Bool` (non-nullable).
///
/// `Sum` widening rules and Decimal handling are not yet implemented —
/// when the column dtype has no resolvable stat dtype the stat is skipped so
/// the inspector degrades to "no schema" rather than failing.
public final class ZonedStatsSchema {

    /// Ordinal positions of [Stat] in the Rust enum — kept stable across
    /// Vortex versions so the bitset can be decoded without per-version logic.
    public enum Stat {
        /// Stat ordinal 0.
        IS_CONSTANT("is_constant"),
        /// Stat ordinal 1.
        IS_SORTED("is_sorted"),
        /// Stat ordinal 2.
        IS_STRICT_SORTED("is_strict_sorted"),
        /// Stat ordinal 3.
        MAX("max"),
        /// Stat ordinal 4.
        MIN("min"),
        /// Stat ordinal 5.
        SUM("sum"),
        /// Stat ordinal 6.
        NULL_COUNT("null_count"),
        /// Stat ordinal 7.
        UNCOMPRESSED_SIZE_IN_BYTES("uncompressed_size_in_bytes"),
        /// Stat ordinal 8.
        NAN_COUNT("nan_count");

        /// Trailing struct-field name added next to [#MAX] for truncated max indicators.
        public static final String MAX_IS_TRUNCATED = "max_is_truncated";
        /// Trailing struct-field name added next to [#MIN] for truncated min indicators.
        public static final String MIN_IS_TRUNCATED = "min_is_truncated";

        private final String fieldName;

        Stat(String fieldName) {
            this.fieldName = fieldName;
        }

        /// Stat field name as written in the stats table.
        ///
        /// @return the canonical struct field name for this stat
        public String fieldName() {
            return fieldName;
        }
    }

    private ZonedStatsSchema() {
    }

    /// Returns the zone length declared in the layout metadata (logical rows per zone),
    /// or `0` if the metadata is too short to carry one.
    ///
    /// @param metadata raw `vortex.stats` layout metadata, possibly `null`
    /// @return decoded zone length, or `0` when absent
    public static long zoneLength(ByteBuffer metadata) {
        if (metadata == null || metadata.remaining() < 4) {
            return 0L;
        }
        ByteBuffer mb = metadata.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return Integer.toUnsignedLong(mb.getInt(mb.position()));
    }

    /// Returns the stats present in the layout metadata bitset, in ordinal order.
    ///
    /// Unknown bits (set at an index past [Stat#values()]'s length, which
    /// would mean a newer Vortex writer) are silently skipped — matching the Rust
    /// reader's forward-compatibility behaviour.
    ///
    /// @param metadata raw `vortex.stats` layout metadata, possibly `null`
    /// @return present stats in ascending ordinal order; empty if metadata carries no bitset
    public static List<Stat> presentStats(ByteBuffer metadata) {
        if (metadata == null || metadata.remaining() <= 4) {
            return List.of();
        }
        ByteBuffer mb = metadata.duplicate();
        mb.position(mb.position() + 4);
        Stat[] all = Stat.values();
        List<Stat> out = new ArrayList<>(all.length);
        int bitIndex = 0;
        while (mb.hasRemaining()) {
            byte b = mb.get();
            for (int bit = 0; bit < 8; bit++) {
                if (((b & 0xff) & (1 << bit)) != 0 && bitIndex < all.length) {
                    out.add(all[bitIndex]);
                }
                bitIndex++;
            }
        }
        return List.copyOf(out);
    }

    /// Reconstructs the per-zone stats-table dtype for the given column dtype
    /// and metadata.
    ///
    /// The result is a [io.github.dfa1.vortex.core.DType.Struct] mirroring
    /// the order produced by Rust's `stats_table_dtype`: for every present stat
    /// in ordinal order, append a `(name, nullable dtype)` field; Max/Min each
    /// add a trailing `_is_truncated` Bool (non-nullable) flag.
    ///
    /// If a stat has no resolvable dtype for the given column (e.g. `Sum`
    /// over an extension type without storage), it is omitted from the struct.
    ///
    /// @param columnDtype the column's logical dtype (the `data` child's dtype)
    /// @param metadata    raw `vortex.stats` layout metadata
    /// @return reconstructed non-nullable struct dtype, or empty struct when nothing is present
    public static DType.Struct statsTableDtype(DType columnDtype, ByteBuffer metadata) {
        return statsTableDtype(columnDtype, presentStats(metadata));
    }

    /// Reconstructs the per-zone stats-table dtype for the given column dtype
    /// and explicit stat list (already decoded from metadata).
    ///
    /// @param columnDtype the column's logical dtype
    /// @param present     stats present, in ordinal order
    /// @return reconstructed non-nullable struct dtype
    public static DType.Struct statsTableDtype(DType columnDtype, List<Stat> present) {
        List<String> names = new ArrayList<>(present.size() * 2);
        List<DType> types = new ArrayList<>(present.size() * 2);
        for (Stat stat : present) {
            DType stype = statDtype(stat, columnDtype);
            if (stype == null) {
                continue;
            }
            names.add(stat.fieldName());
            types.add(stype.withNullable(true));
            if (stat == Stat.MAX) {
                names.add(Stat.MAX_IS_TRUNCATED);
                types.add(new DType.Bool(false));
            } else if (stat == Stat.MIN) {
                names.add(Stat.MIN_IS_TRUNCATED);
                types.add(new DType.Bool(false));
            }
        }
        return new DType.Struct(List.copyOf(names), List.copyOf(types), false);
    }

    /// Returns the per-zone dtype the given stat resolves to for the given column
    /// dtype, or `null` if the stat has no defined dtype for that column
    /// (in which case it is dropped from the stats table).
    ///
    /// Mirrors Rust's `Stat::dtype(&DType)` plus the aggregate-function
    /// return-type rules (see
    /// <a href="https://github.com/spiraldb/vortex/blob/develop/vortex-array/src/aggregate_fn/fns">
    /// vortex-array/src/aggregate_fn/fns</a>):
    /// - min/max → same dtype as column (except DType.Null → null);
    /// - is_constant/is_sorted/is_strict_sorted → non-nullable Bool;
    /// - null_count → non-nullable U64;
    /// - uncompressed_size_in_bytes → non-nullable U64;
    /// - nan_count → non-nullable U64 (only for float columns);
    /// - sum → nullable U64 / I64 / F64 depending on column ptype; null for
    ///       unsupported column dtypes.
    ///
    /// For Extension column dtypes, the storage dtype is consulted as a backward-compat
    /// fallback (matching the `or_else` branch in Rust's
    /// `stats_table_dtype`).
    ///
    /// @param stat        the stat to resolve
    /// @param columnDtype the column's logical dtype
    /// @return the stat's stored dtype, or `null` if undefined for this column
    public static DType statDtype(Stat stat, DType columnDtype) {
        DType direct = statDtypeDirect(stat, columnDtype);
        if (direct != null) {
            return direct;
        }
        if (columnDtype instanceof DType.Extension ext) {
            return statDtypeDirect(stat, ext.storageDType());
        }
        return null;
    }

    private static DType statDtypeDirect(Stat stat, DType columnDtype) {
        return switch (stat) {
            case IS_CONSTANT, IS_SORTED, IS_STRICT_SORTED -> new DType.Bool(false);
            case MAX, MIN -> {
                if (columnDtype instanceof DType.Null) {
                    yield null;
                }
                yield columnDtype;
            }
            case NULL_COUNT -> new DType.Primitive(PType.U64, false);
            case UNCOMPRESSED_SIZE_IN_BYTES -> new DType.Primitive(PType.U64, false);
            case NAN_COUNT -> {
                if (columnDtype instanceof DType.Primitive p && p.ptype().isFloating()) {
                    yield new DType.Primitive(PType.U64, false);
                }
                yield null;
            }
            case SUM -> sumDtype(columnDtype);
        };
    }

    private static DType sumDtype(DType columnDtype) {
        if (columnDtype instanceof DType.Bool) {
            return new DType.Primitive(PType.U64, false);
        }
        if (columnDtype instanceof DType.Primitive p) {
            return switch (p.ptype()) {
                case U8, U16, U32, U64 -> new DType.Primitive(PType.U64, false);
                case I8, I16, I32, I64 -> new DType.Primitive(PType.I64, false);
                case F16, F32, F64 -> new DType.Primitive(PType.F64, false);
            };
        }
        // Decimal sum widening and other types are not handled — falls through to null
        // so the stat is dropped from the schema rather than causing a decode mismatch.
        return null;
    }
}
