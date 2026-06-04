package io.github.dfa1.vortex.encoding;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// Static factories for LE-encoded MemorySegments used in encoding tests.
final class TestSegments {

    static MemorySegment leLongs(long... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * Long.BYTES);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_LONG, i, values[i]);
        }
        return seg;
    }

    static MemorySegment leInts(int... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * Integer.BYTES);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_INT, i, values[i]);
        }
        return seg;
    }

    static MemorySegment leDoubles(double... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * Double.BYTES);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_DOUBLE, i, values[i]);
        }
        return seg;
    }

    static MemorySegment leFloats(float... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * Float.BYTES);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_FLOAT, i, values[i]);
        }
        return seg;
    }

    static MemorySegment leShorts(short... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * Short.BYTES);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(PTypeIO.LE_SHORT, i, values[i]);
        }
        return seg;
    }

    private TestSegments() {}
}
