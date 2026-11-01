package io.github.dfa1.vortex.reader.extension;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.ExtensionMetadata;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimeUnitMetadata;
import io.github.dfa1.vortex.core.model.TimestampMetadata;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

import static io.github.dfa1.vortex.core.io.VortexFormat.LE_SHORT;

/// Parses the wire fields of an extension dtype from untrusted input: its id, and its metadata as
/// Rust's `deserialize_metadata` does — [TimeUnitMetadata] for `vortex.date` and `vortex.time`,
/// [TimestampMetadata] for `vortex.timestamp`, and the bytes as [OpaqueMetadata] for every other id.
/// The writer's `DTypeFbsSerializer` is the inverse.
public final class ExtensionMetadataParser {

    private ExtensionMetadataParser() {
    }

    /// Parses an extension id read from a file; an id [ExtensionId#parse(String)] refuses is
    /// corrupt input.
    ///
    /// @param raw the id string, possibly `null`
    /// @return the parsed id
    /// @throws VortexException if `raw` is `null` or not a valid extension id
    public static ExtensionId parseId(String raw) {
        if (raw == null) {
            throw new VortexException("extension dtype missing id");
        }
        try {
            return ExtensionId.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new VortexException("invalid extension id: " + e.getMessage(), e);
        }
    }

    /// Parses the metadata of extension `id`.
    ///
    /// ponytail: the well-known temporal ids are parsed here rather than through their decoders'
    /// `ExtensionDecoder#parseMetadata`, so the file parser needs no registry and a reader without
    /// those decoders still types them; route them through the decoders if one ever needs to
    /// override the parsing.
    ///
    /// @param id       the extension the bytes belong to
    /// @param metadata the wire bytes, from an untrusted file
    /// @return the parsed metadata
    /// @throws VortexException if the bytes are malformed for `id`, or longer than
    ///         [OpaqueMetadata#MAX_SIZE]
    public static ExtensionMetadata parse(ExtensionId id, MemorySegment metadata) {
        // every id, typed or not: an untrusted file must not make the reader take a huge blob
        if (metadata.byteSize() > OpaqueMetadata.MAX_SIZE.bytes()) {
            throw new VortexException("extension metadata too large: "
                    + metadata.byteSize() + " > " + OpaqueMetadata.MAX_SIZE.bytes());
        }
        if (id == ExtensionId.VORTEX_DATE || id == ExtensionId.VORTEX_TIME) {
            return new TimeUnitMetadata(unit(id, metadata));
        }
        if (id == ExtensionId.VORTEX_TIMESTAMP) {
            return timestamp(id, metadata);
        }
        return new OpaqueMetadata(metadata);
    }

    /// `byte[0] = TimeUnit tag, bytes[1..3] = tz_len (u16 LE), bytes[3..3+tz_len] = tz UTF-8`. The
    /// timezone is kept as stored, as Rust keeps it: no zone database decides whether a file opens.
    private static TimestampMetadata timestamp(ExtensionId id, MemorySegment metadata) {
        if (metadata.byteSize() < 3) {
            throw new VortexException(id + " metadata must have at least 3 bytes, got " + metadata.byteSize());
        }
        TimeUnit unit = unit(id, metadata);
        int tzLen = Short.toUnsignedInt(metadata.get(LE_SHORT, 1));
        if (metadata.byteSize() < 3 + tzLen) {
            throw new VortexException(id + " metadata is truncated: declared timezone length "
                    + tzLen + " but only " + (metadata.byteSize() - 3) + " bytes available");
        }
        try {
            String timezone = StandardCharsets.UTF_8.newDecoder()
                    .decode(metadata.asSlice(3, tzLen).asByteBuffer()).toString();
            return new TimestampMetadata(unit, timezone);
        } catch (CharacterCodingException e) {
            throw new VortexException(id + " timezone is not valid UTF-8", e);
        }
    }

    /// The unit tag in the first byte.
    private static TimeUnit unit(ExtensionId id, MemorySegment metadata) {
        if (metadata.byteSize() == 0) {
            throw new VortexException(id + " metadata must not be empty");
        }
        try {
            return TimeUnit.fromTag(metadata.get(ValueLayout.JAVA_BYTE, 0));
        } catch (IllegalArgumentException e) {
            throw new VortexException(id + " metadata has an invalid time unit", e);
        }
    }
}
