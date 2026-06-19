package io.github.dfa1.vortex.proto;

/// Proto3 wire-type constants.
/// Each field on the wire is preceded by a tag varint encoding `(fieldNumber << 3) | wireType`.
final class WireType {

    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LEN = 2;
    static final int FIXED32 = 5;

    private WireType() {
    }

    static int tag(int fieldNumber, int wireType) {
        return (fieldNumber << 3) | wireType;
    }
}
