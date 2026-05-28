package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.fbs.Buffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import io.github.dfa1.vortex.core.DType;

/// Registry mapping encoding IDs to [Decoder] implementations.
public final class DecoderRegistry {

    private final Map<String, Decoder> decoders = new HashMap<>();

    private DecoderRegistry() {}

    /// Load all [Decoder]s registered via `ServiceLoader`.
    public static DecoderRegistry loadAll() {
        var registry = new DecoderRegistry();
        for (Decoder d : ServiceLoader.load(Decoder.class)) {
            registry.register(d);
        }
        return registry;
    }

    public static DecoderRegistry empty() {
        return new DecoderRegistry();
    }

    public void register(String id, Decoder decoder) {
        decoders.put(id, decoder);
    }

    public void register(Decoder decoder) {
        decoders.put(decoder.encodingId().id(), decoder);
    }

    public void register(EncodingId id, Decoder decoder) {
        register(id.id(), decoder);
    }

    /// Decode a flat segment from the file's memory-mapped region.
    ///
    /// Segment format: [buffer data...] [FlatBuffer Array] [4-byte LE u32 = FlatBuffer size].
    public Array decodeSegment(MemorySegment seg, List<String> encodingSpecs,
                                DType dtype, long rowCount, Arena arena) {
        int segLen = (int) seg.byteSize();
        ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

        int fbLen   = bb.getInt(segLen - 4);
        int fbStart = segLen - 4 - fbLen;
        ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
        var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

        int numBuffers       = fbArray.buffersLength();
        MemorySegment[] bufs = new MemorySegment[numBuffers];
        long dataOffset      = 0;
        for (int i = 0; i < numBuffers; i++) {
            Buffer bufDesc = fbArray.buffers(i);
            dataOffset += bufDesc.padding();
            bufs[i]     = seg.asSlice(dataOffset, bufDesc.length());
            dataOffset += bufDesc.length();
        }

        ArrayNode rootNode = convertArrayNode(fbArray.root(), encodingSpecs);
        var ctx = new DecodeContext(rootNode, dtype, rowCount, bufs, this, arena);
        return decode(ctx);
    }

    Array decode(DecodeContext ctx) {
        String id = ctx.node().encodingId().id();
        Decoder d = decoders.get(id);
        if (d == null) {
            throw new IllegalArgumentException("no decoder for encoding: " + id);
        }
        return d.decode(ctx);
    }

    public boolean has(String id) { return decoders.containsKey(id); }

    public boolean has(EncodingId id) { return decoders.containsKey(id.id()); }

    private static ArrayNode convertArrayNode(
        io.github.dfa1.vortex.fbs.ArrayNode fbs,
        List<String> encodingSpecs
    ) {
        String encodingId = encodingSpecs.get(fbs.encoding());
        EncodingId encId = EncodingId.from(encodingId);

        ArrayNode[] children = new ArrayNode[fbs.childrenLength()];
        for (int i = 0; i < children.length; i++) {
            children[i] = convertArrayNode(fbs.children(i), encodingSpecs);
        }

        int[] bufferIndices = new int[fbs.buffersLength()];
        for (int i = 0; i < bufferIndices.length; i++) {
            bufferIndices[i] = fbs.buffers(i);
        }

        // metadataAsByteBuffer() returns duplicate with position=vectorStart; slice to normalize to 0
        ByteBuffer rawMeta = fbs.metadataAsByteBuffer();
        ByteBuffer meta = (rawMeta != null) ? rawMeta.slice() : null;
        return new ArrayNode(encId, meta, children, bufferIndices, ArrayStats.empty());
    }
}
