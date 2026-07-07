package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_INT;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/// Reconstructs the per-zone statistics-table [DType] for a
/// `vortex.stats` (Zoned) layout.
///
/// The shape is sourced from the Rust reference implementation:
/// - Metadata format
///       ([vortex-layout/src/layouts/zoned/mod.rs](https://github.com/spiraldb/vortex/blob/develop/vortex-layout/src/layouts/zoned/mod.rs)
///       — `ZonedMetadata`):
///       bytes [0..4) are the zone length as a little-endian `u32`;
///       remaining bytes form a `Stat` bitset (LSB-first per byte). Each
///       set bit at index `i` indicates that the [Stat] with that
///       ordinal is present in the auxiliary stats table.
/// - Schema construction
///       ([vortex-layout/src/layouts/zoned/schema.rs](https://github.com/spiraldb/vortex/blob/develop/vortex-layout/src/layouts/zoned/schema.rs)
///       — `stats_table_dtype`):
///       for each present stat in ordinal order, append a struct field with the
///       stat's name and the stat's nullable dtype. `Max` and `Min`
///       each get an extra trailing field `max_is_truncated` /
///       `min_is_truncated` of type `Bool` (non-nullable).
///
/// `Sum` widening for Decimal columns is not yet modeled. The legacy bitset path
/// ([#statsTableDtype(DType, List)]) skips any stat with no resolvable dtype so the inspector
/// degrades to "no schema" rather than failing. The aggregate-spec path
/// ([#aggregateStatsTableDtype(DType, MemorySegment)]) is positional, so it only skips fields Rust
/// also omits and otherwise returns `null` (fall back to per-chunk stats) rather than emit a
/// misaligned struct.
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
    public static long zoneLength(MemorySegment metadata) {
        if (metadata == null || metadata.byteSize() < 4) {
            return 0L;
        }
        return Integer.toUnsignedLong(metadata.get(LE_INT, 0));
    }

    /// Returns the stats present in the layout metadata bitset, in ordinal order.
    ///
    /// Unknown bits (set at an index past [Stat#values()]'s length, which
    /// would mean a newer Vortex writer) are silently skipped — matching the Rust
    /// reader's forward-compatibility behavior.
    ///
    /// @param metadata raw `vortex.stats` layout metadata, possibly `null`
    /// @return present stats in ascending ordinal order; empty if metadata carries no bitset
    public static List<Stat> presentStats(MemorySegment metadata) {
        if (metadata == null || metadata.byteSize() <= 4) {
            return List.of();
        }
        Stat[] all = Stat.values();
        List<Stat> out = new ArrayList<>(all.length);
        int bitIndex = 0;
        for (long pos = 4; pos < metadata.byteSize(); pos++) {
            int b = metadata.get(ValueLayout.JAVA_BYTE, pos) & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((b & (1 << bit)) != 0 && bitIndex < all.length) {
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
    /// The result is a [io.github.dfa1.vortex.core.model.DType.Struct] mirroring
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
    public static DType.Struct statsTableDtype(DType columnDtype, MemorySegment metadata) {
        return statsTableDtype(columnDtype, presentStats(metadata));
    }

    /// Reconstructs the per-zone stats-table dtype for a newer `vortex.zoned` layout, whose
    /// metadata carries an ordered list of aggregate-function specs instead of the legacy
    /// [Stat] bitset.
    ///
    /// The shape mirrors Rust's `aggregate_stats_table_dtype`
    /// ([vortex-layout/src/layouts/zoned/schema.rs](https://github.com/spiraldb/vortex/blob/develop/vortex-layout/src/layouts/zoned/schema.rs)):
    /// for every aggregate whose state dtype is defined for the column, append one
    /// `(name, nullable state-dtype)` field, in spec order — no `_is_truncated` flags. Field
    /// names are the aggregate's canonical [Stat#fieldName()] so the reader can look them up by
    /// stat; the struct decode itself is positional, so alignment with the encoded table is what
    /// matters.
    ///
    /// Returns `null` when the metadata is not a decodable aggregate-spec blob or names an
    /// aggregate this reader cannot map to a [Stat] (e.g. `vortex.bounded_max`, whose state is a
    /// nested struct). Bailing keeps the positional schema faithful: the caller falls back to
    /// per-chunk stats rather than decoding a misaligned table.
    ///
    /// @param columnDtype the column's logical dtype (the `data` child's dtype)
    /// @param metadata    raw `vortex.zoned` layout metadata
    /// @return reconstructed non-nullable struct dtype, or `null` when it cannot be reconstructed
    public static DType.Struct aggregateStatsTableDtype(DType columnDtype, MemorySegment metadata) {
        List<String> aggregateIds = aggregateIds(metadata);
        if (aggregateIds == null) {
            return null;
        }
        List<ColumnName> names = new ArrayList<>(aggregateIds.size());
        List<DType> types = new ArrayList<>(aggregateIds.size());
        for (String aggregateId : aggregateIds) {
            Stat stat = statForAggregate(aggregateId);
            if (stat == null) {
                // Unknown aggregate — the encoded table has a field here we cannot describe, so
                // any reconstruction would be positionally misaligned. Bail to the fallback path.
                return null;
            }
            DType stype = statDtype(stat, columnDtype);
            if (stype == null) {
                if (rustAlsoOmits(stat, columnDtype)) {
                    // Rust's aggregate_stats_table_dtype also drops this field (its aggregate's
                    // state_dtype is None for this column), so skipping stays aligned with the
                    // encoded table.
                    continue;
                }
                // Rust keeps a field here that we cannot map — notably Sum over a Decimal column,
                // whose state widens to Decimal(precision + 10) (writer.rs default_zoned_aggregate_fns
                // only emits Sum when Sum.return_dtype is Some, which it is for Decimal). Dropping it
                // would misalign the positional decode, so bail to per-chunk stats.
                return null;
            }
            names.add(ColumnName.of(stat.fieldName()));
            types.add(stype.withNullable(true));
        }
        return new DType.Struct(List.copyOf(names), List.copyOf(types), false);
    }

    /// Reports whether Rust's `aggregate_stats_table_dtype` also omits this aggregate for the given
    /// column — i.e. the aggregate's `state_dtype` is `None` there, so dropping it keeps our
    /// positional schema aligned with the encoded table. Any other null resolution means Rust keeps
    /// a field we cannot describe, and the caller must bail instead.
    ///
    /// The two legitimate drops mirror the Rust reference
    /// ([vortex-array/src/aggregate_fn/fns](https://github.com/spiraldb/vortex/tree/develop/vortex-array/src/aggregate_fn/fns)):
    /// - `nan_count` returns `None` for non-float columns (`nan_count/mod.rs`);
    /// - `min`/`max` return `None` when `minmax_supported_dtype` is false — for us that is only
    ///       `DType.Null`, the sole column for which the min/max resolver yields null.
    private static boolean rustAlsoOmits(Stat stat, DType columnDtype) {
        return switch (stat) {
            case NAN_COUNT -> !isFloatingColumn(columnDtype);
            case MAX, MIN -> true;
            default -> false;
        };
    }

    private static boolean isFloatingColumn(DType columnDtype) {
        if (columnDtype instanceof DType.Primitive p) {
            return p.ptype().isFloating();
        }
        if (columnDtype instanceof DType.Extension ext) {
            return isFloatingColumn(ext.storageDType());
        }
        return false;
    }

    /// Reconstructs the per-zone stats-table dtype for the given column dtype
    /// and explicit stat list (already decoded from metadata).
    ///
    /// @param columnDtype the column's logical dtype
    /// @param present     stats present, in ordinal order
    /// @return reconstructed non-nullable struct dtype
    public static DType.Struct statsTableDtype(DType columnDtype, List<Stat> present) {
        List<ColumnName> names = new ArrayList<>(present.size() * 2);
        List<DType> types = new ArrayList<>(present.size() * 2);
        for (Stat stat : present) {
            DType stype = statDtype(stat, columnDtype);
            if (stype == null) {
                continue;
            }
            names.add(ColumnName.of(stat.fieldName()));
            types.add(stype.withNullable(true));
            if (stat == Stat.MAX) {
                names.add(ColumnName.of(Stat.MAX_IS_TRUNCATED));
                types.add(DType.BOOL);
            } else if (stat == Stat.MIN) {
                names.add(ColumnName.of(Stat.MIN_IS_TRUNCATED));
                types.add(DType.BOOL);
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
    /// [vortex-array/src/aggregate_fn/fns](https://github.com/spiraldb/vortex/blob/develop/vortex-array/src/aggregate_fn/fns)):
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
            case IS_CONSTANT, IS_SORTED, IS_STRICT_SORTED -> DType.BOOL;
            case MAX, MIN -> {
                if (columnDtype instanceof DType.Null) {
                    yield null;
                }
                yield columnDtype;
            }
            case NULL_COUNT -> DType.U64;
            case UNCOMPRESSED_SIZE_IN_BYTES -> DType.U64;
            case NAN_COUNT -> {
                if (columnDtype instanceof DType.Primitive p && p.ptype().isFloating()) {
                    yield DType.U64;
                }
                yield null;
            }
            case SUM -> sumDtype(columnDtype);
        };
    }

    private static DType sumDtype(DType columnDtype) {
        if (columnDtype instanceof DType.Bool) {
            return DType.U64;
        }
        if (columnDtype instanceof DType.Primitive p) {
            return switch (p.ptype()) {
                case U8, U16, U32, U64 -> DType.U64;
                case I8, I16, I32, I64 -> DType.I64;
                case F16, F32, F64 -> DType.F64;
            };
        }
        // Decimal sum widening and other types are not handled — falls through to null
        // so the stat is dropped from the schema rather than causing a decode mismatch.
        return null;
    }

    /// First byte of `vortex.zoned` metadata: the protobuf envelope version Rust writes.
    private static final int AGGREGATE_METADATA_VERSION = 1;

    /// Maps a well-known aggregate-function id (Rust `AggregateFnId`) to the [Stat] whose stored
    /// dtype and canonical field name it uses in the zone-map table, or `null` when the reader
    /// has no faithful mapping (nested-state aggregates like `vortex.bounded_max`, or functions
    /// not stored as a scalar stat).
    private static Stat statForAggregate(String aggregateId) {
        return switch (aggregateId) {
            case "vortex.max" -> Stat.MAX;
            case "vortex.min" -> Stat.MIN;
            case "vortex.sum" -> Stat.SUM;
            case "vortex.null_count" -> Stat.NULL_COUNT;
            case "vortex.nan_count" -> Stat.NAN_COUNT;
            case "vortex.is_constant" -> Stat.IS_CONSTANT;
            case "vortex.is_sorted" -> Stat.IS_SORTED;
            case "vortex.is_strict_sorted" -> Stat.IS_STRICT_SORTED;
            case "vortex.uncompressed_size_in_bytes" -> Stat.UNCOMPRESSED_SIZE_IN_BYTES;
            default -> null;
        };
    }

    /// Extracts the ordered aggregate-function ids from `vortex.zoned` metadata.
    ///
    /// The metadata is a version byte (value [#AGGREGATE_METADATA_VERSION]) followed by a
    /// protobuf `ZonedMetadataProto { uint32 zone_len = 1; repeated AggregateSpecProto
    /// aggregate_specs = 2; }`, where `AggregateSpecProto { string id = 1; bytes options = 2; }`.
    /// Only the `id` of each spec is needed to reconstruct the table schema.
    ///
    /// Returns `null` when the blob is empty, carries an unsupported version, or is not
    /// well-formed protobuf — signalling the caller to fall back rather than trust a partial parse.
    private static List<String> aggregateIds(MemorySegment metadata) {
        if (metadata == null || metadata.byteSize() < 1) {
            return null;
        }
        if ((metadata.get(ValueLayout.JAVA_BYTE, 0) & 0xff) != AGGREGATE_METADATA_VERSION) {
            return null;
        }
        ProtoCursor cursor = new ProtoCursor(metadata, 1, metadata.byteSize());
        List<String> ids = new ArrayList<>();
        while (cursor.hasRemaining()) {
            long tag = cursor.readVarint();
            if (tag < 0) {
                return null;
            }
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            if (fieldNumber == 2 && wireType == ProtoCursor.WIRE_LEN) {
                String id = cursor.readAggregateSpecId();
                if (id == null) {
                    return null;
                }
                ids.add(id);
            } else if (!cursor.skipField(wireType)) {
                return null;
            }
        }
        return List.copyOf(ids);
    }

    /// Minimal forward cursor over a protobuf byte range within a [MemorySegment]. Reads only the
    /// varints and length-delimited fields the zoned metadata uses; any malformed or unexpected
    /// wire shape is reported as a failure so the caller can bail rather than misread.
    private static final class ProtoCursor {
        static final int WIRE_VARINT = 0;
        static final int WIRE_I64 = 1;
        static final int WIRE_LEN = 2;
        static final int WIRE_I32 = 5;

        private final MemorySegment segment;
        private long pos;
        private final long end;

        ProtoCursor(MemorySegment segment, long start, long end) {
            this.segment = segment;
            this.pos = start;
            this.end = end;
        }

        boolean hasRemaining() {
            return pos < end;
        }

        /// Reads a base-128 varint, or `-1` when it runs past the end or exceeds 64 bits.
        long readVarint() {
            long result = 0;
            int shift = 0;
            while (pos < end) {
                int b = segment.get(ValueLayout.JAVA_BYTE, pos) & 0xff;
                pos++;
                if (shift < 64) {
                    result |= (long) (b & 0x7f) << shift;
                }
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    return -1;
                }
            }
            return -1;
        }

        /// Skips a field of the given wire type, returning `false` on an unknown type or overrun.
        boolean skipField(int wireType) {
            return switch (wireType) {
                case WIRE_VARINT -> readVarint() >= 0;
                case WIRE_I64 -> advance(8);
                case WIRE_I32 -> advance(4);
                case WIRE_LEN -> {
                    long len = readVarint();
                    yield len >= 0 && advance(len);
                }
                default -> false;
            };
        }

        /// Reads an `AggregateSpecProto` length-delimited message and returns its `id` (field 1),
        /// or `null` if the message is malformed or carries no id.
        String readAggregateSpecId() {
            long len = readVarint();
            // Overflow-safe bound: pos <= end holds throughout, so end - pos never overflows; a
            // varint-derived len up to Long.MAX_VALUE would overflow pos + len to negative and slip
            // past a pos + len > end guard, then advance pos past the segment.
            if (len < 0 || len > end - pos) {
                return null;
            }
            long messageEnd = pos + len;
            String id = null;
            while (pos < messageEnd) {
                long tag = readVarint();
                if (tag < 0) {
                    return null;
                }
                int fieldNumber = (int) (tag >>> 3);
                int wireType = (int) (tag & 0x7);
                if (fieldNumber == 1 && wireType == WIRE_LEN) {
                    id = readString(messageEnd);
                    if (id == null) {
                        return null;
                    }
                } else if (!skipField(wireType)) {
                    return null;
                }
            }
            return id;
        }

        private String readString(long limit) {
            long len = readVarint();
            // Overflow-safe bound (see readAggregateSpecId): compare against limit - pos, never
            // pos + len. If a prior varint pushed pos past limit, limit - pos is negative and any
            // non-negative len is rejected, so we never size new byte[(int) len] from an
            // attacker-controlled, overflowed length.
            if (len < 0 || len > limit - pos) {
                return null;
            }
            byte[] bytes = new byte[(int) len];
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, pos, bytes, 0, (int) len);
            pos += len;
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        private boolean advance(long count) {
            // Overflow-safe bound: pos <= end holds throughout, so end - pos never overflows,
            // whereas pos + count could overflow to negative for a varint-derived count near
            // Long.MAX_VALUE and slip past a pos + count > end guard.
            if (count < 0 || count > end - pos) {
                return false;
            }
            pos += count;
            return true;
        }
    }
}
