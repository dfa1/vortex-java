package io.github.dfa1.vortex.core.model;

import io.github.dfa1.vortex.core.error.VortexException;

import java.io.Serializable;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Identity of an extension type — either a [WellKnown] constant or a third-party [Custom] id.
///
/// [WellKnown] holds every id the vortex-jni reference session registers by default: the
/// temporal types and `vortex.uuid`, plus `vortex.json` and the `vortex.st.*` geometry types. A
/// file with one of them opens without a registered decoder, the column reading as its storage.
/// Mirrors [EncodingId] in shape so callers reach for the same pattern across both registries.
///
/// The wire representation is always a string (e.g. `"vortex.timestamp"`); [#parse(String)] maps any
/// such string to a typed value, and [#id()] recovers the wire string from a typed value.
public sealed interface ExtensionId extends Serializable permits ExtensionId.WellKnown, ExtensionId.Custom {

    /// Returns the wire string of this extension id (e.g. `"vortex.timestamp"`).
    ///
    /// @return the wire string of this extension id
    String id();

    /// Parses a wire string into its typed representation: the matching [WellKnown] constant,
    /// else a [Custom] wrapping the raw string. Total over every valid id; callers parsing
    /// untrusted input must guard invalid ids and raise their own domain error.
    ///
    /// @param raw the raw extension id string (e.g. `"vortex.timestamp"`)
    /// @return the matching [WellKnown] constant, or a [Custom] wrapping `raw` if none matches
    /// @throws NullPointerException if `raw` is `null`
    /// @throws IllegalArgumentException if `raw` is blank or contains an ISO control character
    static ExtensionId parse(String raw) {
        WellKnown known = WellKnown.byId(raw);
        return known != null ? known : new Custom(raw);
    }

    /// Extension ids the vortex-jni reference session registers by default.
    enum WellKnown implements ExtensionId {
        /// `vortex.date`.
        VORTEX_DATE("vortex.date"),
        /// `vortex.time`.
        VORTEX_TIME("vortex.time"),
        /// `vortex.timestamp`.
        VORTEX_TIMESTAMP("vortex.timestamp"),
        /// `vortex.uuid`.
        VORTEX_UUID("vortex.uuid"),
        /// `vortex.json`: a JSON document per row over `Utf8` storage (Rust's `vortex-json`).
        VORTEX_JSON("vortex.json"),
        /// `vortex.st.wkb`: a Well-Known Binary geometry per row over `Binary` storage (Rust's `vortex-spatial`).
        VORTEX_ST_WKB("vortex.st.wkb"),
        /// `vortex.st.point` (Rust's `vortex-spatial`).
        VORTEX_ST_POINT("vortex.st.point"),
        /// `vortex.st.linestring` (Rust's `vortex-spatial`).
        VORTEX_ST_LINESTRING("vortex.st.linestring"),
        /// `vortex.st.polygon` (Rust's `vortex-spatial`).
        VORTEX_ST_POLYGON("vortex.st.polygon"),
        /// `vortex.st.multipoint` (Rust's `vortex-spatial`).
        VORTEX_ST_MULTIPOINT("vortex.st.multipoint"),
        /// `vortex.st.multilinestring` (Rust's `vortex-spatial`).
        VORTEX_ST_MULTILINESTRING("vortex.st.multilinestring"),
        /// `vortex.st.multipolygon` (Rust's `vortex-spatial`).
        VORTEX_ST_MULTIPOLYGON("vortex.st.multipolygon"),
        /// `vortex.st.box` (Rust's `vortex-spatial`).
        VORTEX_ST_BOX("vortex.st.box");

        // GeoArrow field names per dimension (vortex-spatial coordinate.rs and rect.rs)
        private static final java.util.List<java.util.List<String>> COORDINATE_FIELDS = java.util.List.of(
                java.util.List.of("x", "y"), java.util.List.of("x", "y", "z"),
                java.util.List.of("x", "y", "m"), java.util.List.of("x", "y", "z", "m"));
        private static final java.util.List<java.util.List<String>> BOX_FIELDS = java.util.List.of(
                java.util.List.of("xmin", "ymin", "xmax", "ymax"),
                java.util.List.of("xmin", "ymin", "zmin", "xmax", "ymax", "zmax"),
                java.util.List.of("xmin", "ymin", "mmin", "xmax", "ymax", "mmax"),
                java.util.List.of("xmin", "ymin", "zmin", "mmin", "xmax", "ymax", "zmax", "mmax"));

        private static final Map<String, WellKnown> LOOKUP = Stream.of(values())
                .collect(Collectors.toUnmodifiableMap(WellKnown::id, Function.identity()));

        private final String id;

        WellKnown(String id) {
            this.id = id;
        }

        /// Returns the well-known constant whose wire string is `id`, or `null` if none matches.
        ///
        /// @param id the wire string to look up (may be `null`)
        /// @return the matching constant, or `null` if unrecognized
        static WellKnown byId(String id) {
            return LOOKUP.get(id);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }

        /// Rejects a dtype this extension cannot represent, with the rules of Rust's
        /// `deserialize_metadata` and `validate_dtype` for it: the time-unit byte and storage ptype
        /// of `vortex.date`/`vortex.time`, the metadata layout and `I64` storage of
        /// `vortex.timestamp`, the version byte and `FixedSizeList<U8, 16>` storage of `vortex.uuid`,
        /// empty metadata over `Utf8` for `vortex.json`, `Binary` storage for `vortex.st.wkb`, and
        /// the GeoArrow coordinate-struct storage of the other `vortex.st.*` geometries.
        ///
        /// @param dtype an extension dtype with this id
        /// @throws VortexException if the metadata or storage dtype is invalid for this extension
        public void validate(DType.Extension dtype) {
            byte[] meta = dtype.metadata() == null ? new byte[0] : dtype.metadata().toArray(ValueLayout.JAVA_BYTE);
            DType storage = dtype.storageDType();
            switch (this) {
                case VORTEX_DATE -> {
                    TimeUnit unit = unit(meta);
                    PType expected = switch (unit) {
                        case Days -> PType.I32;
                        case Milliseconds -> PType.I64;
                        case Nanoseconds, Microseconds, Seconds -> throw new VortexException(
                                id + " does not support time unit " + unit);
                    };
                    requirePtype(storage, expected, unit);
                }
                case VORTEX_TIME -> {
                    TimeUnit unit = unit(meta);
                    PType expected = switch (unit) {
                        case Seconds, Milliseconds -> PType.I32;
                        case Microseconds, Nanoseconds -> PType.I64;
                        case Days -> throw new VortexException(id + " does not support time unit " + unit);
                    };
                    requirePtype(storage, expected, unit);
                }
                case VORTEX_TIMESTAMP -> {
                    if (meta.length < 3) {
                        throw new VortexException(id + " metadata must have at least 3 bytes, got " + meta.length);
                    }
                    unit(meta);
                    int tzLen = Byte.toUnsignedInt(meta[1]) | Byte.toUnsignedInt(meta[2]) << 8;
                    if (meta.length < 3 + tzLen) {
                        throw new VortexException(id + " metadata is truncated: declared timezone length "
                                + tzLen + " but only " + (meta.length - 3) + " bytes available");
                    }
                    try {
                        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(meta, 3, tzLen));
                    } catch (CharacterCodingException e) {
                        throw new VortexException(id + " timezone is not valid UTF-8", e);
                    }
                    if (!(storage instanceof DType.Primitive p && p.ptype() == PType.I64)) {
                        throw new VortexException(id + " storage dtype must be I64, got " + storage);
                    }
                }
                case VORTEX_UUID -> {
                    if (meta.length > 1) {
                        throw new VortexException(id + " metadata must be 0 or 1 bytes, got " + meta.length);
                    }
                    // uuid crate versions 0-8, plus Max as 0x0f or the older 0xff
                    if (meta.length == 1 && !(meta[0] >= 0 && meta[0] <= 8 || meta[0] == 0x0f || meta[0] == (byte) 0xff)) {
                        throw new VortexException("unknown UUID version discriminant: " + Byte.toUnsignedInt(meta[0]));
                    }
                    if (!(storage instanceof DType.FixedSizeList list && list.fixedSize() == 16
                            && list.elementType() instanceof DType.Primitive p && p.ptype() == PType.U8 && !p.nullable())) {
                        throw new VortexException(id + " storage dtype must be FixedSizeList<non-null U8, 16>, got " + storage);
                    }
                }
                case VORTEX_JSON -> {
                    if (meta.length != 0) {
                        throw new VortexException(id + " metadata must be empty");
                    }
                    if (!(storage instanceof DType.Utf8)) {
                        throw new VortexException(id + " storage dtype must be Utf8, got " + storage);
                    }
                }
                // ponytail: the spatial metadata (a SpatialMetadata protobuf: CRS, edges) is not decoded,
                // so malformed metadata Rust refuses still opens here; decode it when Java reads geometries
                case VORTEX_ST_WKB -> {
                    if (!(storage instanceof DType.Binary)) {
                        throw new VortexException(id + " storage dtype must be Binary, got " + storage);
                    }
                }
                case VORTEX_ST_POINT -> requireF64Struct(storage, COORDINATE_FIELDS);
                case VORTEX_ST_LINESTRING, VORTEX_ST_MULTIPOINT -> requireF64Struct(listElement(storage, 1), COORDINATE_FIELDS);
                case VORTEX_ST_POLYGON, VORTEX_ST_MULTILINESTRING -> requireF64Struct(listElement(storage, 2), COORDINATE_FIELDS);
                case VORTEX_ST_MULTIPOLYGON -> requireF64Struct(listElement(storage, 3), COORDINATE_FIELDS);
                case VORTEX_ST_BOX -> requireF64Struct(storage, BOX_FIELDS);
            }
        }

        /// Unwraps `depth` levels of `List`: a linestring is a list of coordinates, a polygon a list
        /// of rings, a multipolygon a list of polygons.
        private DType listElement(DType storage, int depth) {
            DType current = storage;
            for (int i = 0; i < depth; i++) {
                if (!(current instanceof DType.List list)) {
                    throw new VortexException(id + " storage must nest " + depth + " List level(s) over coordinates, got " + storage);
                }
                current = list.elementType();
            }
            return current;
        }

        /// A coordinate or box: a `Struct` of non-nullable `F64` fields named for one GeoArrow
        /// dimension (xy, xyz, xym or xyzm).
        private void requireF64Struct(DType dtype, java.util.List<java.util.List<String>> dimensions) {
            if (!(dtype instanceof DType.Struct struct)) {
                throw new VortexException(id + " coordinates must be a Struct, got " + dtype);
            }
            for (DType field : struct.fieldTypes()) {
                if (!(field instanceof DType.Primitive p && p.ptype() == PType.F64 && !p.nullable())) {
                    throw new VortexException(id + " coordinate fields must be non-nullable F64, got " + field);
                }
            }
            java.util.List<String> names = struct.fieldNames().stream().map(ColumnName::value).toList();
            if (!dimensions.contains(names)) {
                throw new VortexException(id + " is not a valid GeoArrow dimension: " + names);
            }
        }

        private TimeUnit unit(byte[] meta) {
            if (meta.length == 0) {
                throw new VortexException(id + " metadata must not be empty");
            }
            try {
                return TimeUnit.fromTag(meta[0]);
            } catch (IllegalArgumentException e) {
                throw new VortexException(id + " metadata has an invalid time unit", e);
            }
        }

        private void requirePtype(DType storage, PType expected, TimeUnit unit) {
            if (!(storage instanceof DType.Primitive p && p.ptype() == expected)) {
                throw new VortexException(id + " storage dtype for " + unit + " must be " + expected + ", got " + storage);
            }
        }
    }

    /// A third-party extension id whose wire string is not part of the [WellKnown] set.
    ///
    /// @param id the wire string of this extension id; must be non-blank, free of ISO control
    ///           characters, and must not collide with a [WellKnown] wire string
    record Custom(String id) implements ExtensionId {

        /// Validates that `id` is a usable custom extension id, with the same policy as
        /// [EncodingId.Custom].
        ///
        /// @param id the wire string of this extension id
        /// @throws NullPointerException     if `id` is `null`
        /// @throws IllegalArgumentException if `id` is blank, contains an ISO control character, or
        ///                                  matches a [WellKnown] wire string
        public Custom {
            Objects.requireNonNull(id, "id");
            if (id.isBlank()) {
                throw new IllegalArgumentException("extension id must not be blank");
            }
            for (int i = 0; i < id.length(); i++) {
                if (Character.isISOControl(id.charAt(i))) {
                    throw new IllegalArgumentException(
                            "extension id contains control character U+%04X".formatted((int) id.charAt(i)));
                }
            }
            WellKnown wellKnown = WellKnown.byId(id);
            if (wellKnown != null) {
                throw new IllegalArgumentException(
                        "\"" + id + "\" is a well-known extension id; use ExtensionId." + wellKnown.name() + " instead");
            }
        }

        @Override
        public String toString() {
            return id;
        }
    }

    // Re-export every WellKnown constant, typed as WellKnown, so `ExtensionId.VORTEX_FOO` call
    // sites keep compiling and remain usable wherever a WellKnown is required.

    /// Well-known `vortex.date` id.
    WellKnown VORTEX_DATE = WellKnown.VORTEX_DATE;
    /// Well-known `vortex.time` id.
    WellKnown VORTEX_TIME = WellKnown.VORTEX_TIME;
    /// Well-known `vortex.timestamp` id.
    WellKnown VORTEX_TIMESTAMP = WellKnown.VORTEX_TIMESTAMP;
    /// Well-known `vortex.uuid` id.
    WellKnown VORTEX_UUID = WellKnown.VORTEX_UUID;
    /// Well-known `vortex.json` id.
    WellKnown VORTEX_JSON = WellKnown.VORTEX_JSON;
    /// Well-known `vortex.st.wkb` id.
    WellKnown VORTEX_ST_WKB = WellKnown.VORTEX_ST_WKB;
    /// Well-known `vortex.st.point` id.
    WellKnown VORTEX_ST_POINT = WellKnown.VORTEX_ST_POINT;
    /// Well-known `vortex.st.linestring` id.
    WellKnown VORTEX_ST_LINESTRING = WellKnown.VORTEX_ST_LINESTRING;
    /// Well-known `vortex.st.polygon` id.
    WellKnown VORTEX_ST_POLYGON = WellKnown.VORTEX_ST_POLYGON;
    /// Well-known `vortex.st.multipoint` id.
    WellKnown VORTEX_ST_MULTIPOINT = WellKnown.VORTEX_ST_MULTIPOINT;
    /// Well-known `vortex.st.multilinestring` id.
    WellKnown VORTEX_ST_MULTILINESTRING = WellKnown.VORTEX_ST_MULTILINESTRING;
    /// Well-known `vortex.st.multipolygon` id.
    WellKnown VORTEX_ST_MULTIPOLYGON = WellKnown.VORTEX_ST_MULTIPOLYGON;
    /// Well-known `vortex.st.box` id.
    WellKnown VORTEX_ST_BOX = WellKnown.VORTEX_ST_BOX;
}
