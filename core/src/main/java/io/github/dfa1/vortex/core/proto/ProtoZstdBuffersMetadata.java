package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.encodings.ZstdBuffersMetadata}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param inner_encoding_id field tag 1
/// @param inner_metadata field tag 2
/// @param uncompressed_sizes field tag 3
/// @param buffer_alignments field tag 4
/// @param child_dtypes field tag 5
/// @param child_lens field tag 6
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoZstdBuffersMetadata(
        String inner_encoding_id,
        byte[] inner_metadata,
        java.util.List<Long> uncompressed_sizes,
        java.util.List<Integer> buffer_alignments,
        java.util.List<ProtoDType> child_dtypes,
        java.util.List<Long> child_lens
) {

    /// Decodes a {@code vortex.encodings.ZstdBuffersMetadata} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoZstdBuffersMetadata decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        String inner_encoding_id = "";
        byte[] inner_metadata = new byte[0];
        java.util.List<Long> uncompressed_sizes = new java.util.ArrayList<>();
        java.util.List<Integer> buffer_alignments = new java.util.ArrayList<>();
        java.util.List<ProtoDType> child_dtypes = new java.util.ArrayList<>();
        java.util.List<Long> child_lens = new java.util.ArrayList<>();
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    inner_encoding_id = r.readString();
                }
                case 2 -> {
                    inner_metadata = r.readBytes();
                }
                case 3 -> {
                    int wt = tag & 7;
                    if (wt == 2) {
                        int len = r.readVarint32();
                        java.util.List<Long> __target = uncompressed_sizes;
                        r.readPacked(len, reader -> __target.add(reader.readVarint64()));
                    } else {
                        uncompressed_sizes.add(r.readVarint64());
                    }
                }
                case 4 -> {
                    int wt = tag & 7;
                    if (wt == 2) {
                        int len = r.readVarint32();
                        java.util.List<Integer> __target = buffer_alignments;
                        r.readPacked(len, reader -> __target.add(reader.readVarint32()));
                    } else {
                        buffer_alignments.add(r.readVarint32());
                    }
                }
                case 5 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    child_dtypes.add(ProtoDType.decode(__slice, 0, __slice.byteSize()));
                }
                case 6 -> {
                    int wt = tag & 7;
                    if (wt == 2) {
                        int len = r.readVarint32();
                        java.util.List<Long> __target = child_lens;
                        r.readPacked(len, reader -> __target.add(reader.readVarint64()));
                    } else {
                        child_lens.add(r.readVarint64());
                    }
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoZstdBuffersMetadata(inner_encoding_id, inner_metadata, uncompressed_sizes, buffer_alignments, child_dtypes, child_lens);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        if (inner_encoding_id != null && !inner_encoding_id.isEmpty()) {
            w.writeTag(1, 2);
            w.writeString(inner_encoding_id);
        }
        if (inner_metadata != null && inner_metadata.length != 0) {
            w.writeTag(2, 2);
            w.writeBytes(inner_metadata);
        }
        if (!uncompressed_sizes.isEmpty()) {
            w.writeTag(3, 2);
            int __mark = w.beginLenDelim();
            for (Long __v : uncompressed_sizes) {
                w.writeVarint64(__v);
            }
            w.endLenDelim(__mark);
        }
        if (!buffer_alignments.isEmpty()) {
            w.writeTag(4, 2);
            int __mark = w.beginLenDelim();
            for (Integer __v : buffer_alignments) {
                w.writeVarint32(__v);
            }
            w.endLenDelim(__mark);
        }
        for (ProtoDType __v : child_dtypes) {
            w.writeTag(5, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (!child_lens.isEmpty()) {
            w.writeTag(6, 2);
            int __mark = w.beginLenDelim();
            for (Long __v : child_lens) {
                w.writeVarint64(__v);
            }
            w.endLenDelim(__mark);
        }
    }

    @Override
    public boolean equals(Object __o) {
        if (this == __o) {
            return true;
        }
        if (!(__o instanceof ProtoZstdBuffersMetadata __that)) {
            return false;
        }
        return java.util.Objects.equals(inner_encoding_id, __that.inner_encoding_id)
            && java.util.Arrays.equals(inner_metadata, __that.inner_metadata)
            && java.util.Objects.equals(uncompressed_sizes, __that.uncompressed_sizes)
            && java.util.Objects.equals(buffer_alignments, __that.buffer_alignments)
            && java.util.Objects.equals(child_dtypes, __that.child_dtypes)
            && java.util.Objects.equals(child_lens, __that.child_lens);
    }

    @Override
    public int hashCode() {
        int __h = 1;
        __h = 31 * __h + java.util.Objects.hashCode(inner_encoding_id);
        __h = 31 * __h + java.util.Arrays.hashCode(inner_metadata);
        __h = 31 * __h + java.util.Objects.hashCode(uncompressed_sizes);
        __h = 31 * __h + java.util.Objects.hashCode(buffer_alignments);
        __h = 31 * __h + java.util.Objects.hashCode(child_dtypes);
        __h = 31 * __h + java.util.Objects.hashCode(child_lens);
        return __h;
    }
}
