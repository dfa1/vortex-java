package io.github.dfa1.vortex.core.proto;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import javax.annotation.processing.Generated;

/// Generated from proto3 message {@code vortex.dtype.Union}.
/// Do not edit by hand — regenerate via {@code ./mvnw generate-sources -pl core -P regenerate-sources}.
/// @param names field tag 1
/// @param dtypes field tag 2
/// @param type_ids field tag 3
/// @param nullable field tag 4
@Generated("io.github.dfa1.vortex.protogen.CodeGen")
public record ProtoUnion(
        java.util.List<String> names,
        java.util.List<ProtoDType> dtypes,
        java.util.List<Integer> type_ids,
        boolean nullable
) {

    /// Decodes a {@code vortex.dtype.Union} from a slice of a memory segment.
    /// @param __seg backing segment
    /// @param __off start offset in bytes
    /// @param __len payload length in bytes
    /// @return decoded record
    /// @throws IOException if the slice is malformed or truncated
    public static ProtoUnion decode(MemorySegment __seg, long __off, long __len) throws IOException {
        ProtoReader r = new ProtoReader(__seg, __off, __len);
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<ProtoDType> dtypes = new java.util.ArrayList<>();
        java.util.List<Integer> type_ids = new java.util.ArrayList<>();
        boolean nullable = false;
        while (r.hasMore()) {
            int tag = r.readVarint32();
            switch (tag >>> 3) {
                case 1 -> {
                    names.add(r.readString());
                }
                case 2 -> {
                    MemorySegment __slice = r.readLenDelimSegment();
                    dtypes.add(ProtoDType.decode(__slice, 0, __slice.byteSize()));
                }
                case 3 -> {
                    int wt = tag & 7;
                    if (wt == 2) {
                        int len = r.readVarint32();
                        java.util.List<Integer> __target = type_ids;
                        r.readPacked(len, reader -> __target.add(reader.readVarint32()));
                    } else {
                        type_ids.add(r.readVarint32());
                    }
                }
                case 4 -> {
                    nullable = r.readBool();
                }
                default -> r.skipField(tag & 7);
            }
        }
        return new ProtoUnion(names, dtypes, type_ids, nullable);
    }

    /// Encodes this record to a proto3-wire-format byte array.
    /// @return encoded bytes
    public byte[] encode() {
        ProtoWriter w = new ProtoWriter();
        encodeTo(w);
        return w.toByteArray();
    }

    void encodeTo(ProtoWriter w) {
        for (String __v : names) {
            w.writeTag(1, 2);
            w.writeString(__v);
        }
        for (ProtoDType __v : dtypes) {
            w.writeTag(2, 2);
            int __mark = w.beginLenDelim();
            __v.encodeTo(w);
            w.endLenDelim(__mark);
        }
        if (!type_ids.isEmpty()) {
            w.writeTag(3, 2);
            int __mark = w.beginLenDelim();
            for (Integer __v : type_ids) {
                w.writeVarint32(__v);
            }
            w.endLenDelim(__mark);
        }
        if (nullable) {
            w.writeTag(4, 0);
            w.writeBool(nullable);
        }
    }
}
