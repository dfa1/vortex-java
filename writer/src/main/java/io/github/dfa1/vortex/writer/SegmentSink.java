package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.fbs.FbsArray;
import io.github.dfa1.vortex.core.fbs.FbsArrayNode;
import io.github.dfa1.vortex.core.fbs.FbsArraySpec;
import io.github.dfa1.vortex.core.fbs.FbsArrayStats;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsFooter;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.fbs.FbsPrecision;
import io.github.dfa1.vortex.core.fbs.FbsSegmentSpec;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.writer.SegmentEncoder.EncodedSegment;
import io.github.dfa1.vortex.writer.SegmentEncoder.SegmentStats;
import io.github.dfa1.vortex.writer.encode.EncodeNode;
import io.github.dfa1.vortex.writer.encode.EncodeResult;
import io.github.dfa1.vortex.writer.encode.EncodedBuffer;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NodeStats;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/// The byte stream of one Vortex file: encodes segments on the executor, appends them to the
/// channel in submission order, and writes the footer, postscript and trailer at the end.
///
/// Segment indexes are known at submit time and the file bytes do not depend on the executor,
/// because segments are always written in the order they were submitted.
final class SegmentSink {

    /// Largest slice of a segment written at once: a ByteBuffer holds at most 2 GiB.
    private static final long MAX_WRITE_SLICE = 1L << 30;

    // ponytail: fixed in-flight window bounds memory to ~2 chunks per core; make it a WriteOptions
    // knob if a caller needs a tighter memory bound or a deeper pipeline.
    private static final int MAX_IN_FLIGHT_SEGMENTS = 2 * Runtime.getRuntime().availableProcessors();

    private final WritableByteChannel channel;
    private final SegmentEncoder encoder;
    private final Executor executor;
    private final List<SegRef> segs;
    private final Map<EncodingId, Integer> encodingIdx;
    private final ArrayDeque<CompletableFuture<EncodedSegment>> pending;
    private long bytesWritten;
    private int submittedSegments;

    SegmentSink(WritableByteChannel channel, SegmentEncoder encoder, Executor executor) {
        this.channel = channel;
        this.encoder = encoder;
        this.executor = executor;
        this.segs = new ArrayList<>();
        this.encodingIdx = new LinkedHashMap<>();
        this.pending = new ArrayDeque<>();
        this.bytesWritten = 0;
        this.submittedSegments = 0;
    }

    /// Submits a segment to [SegmentEncoder#encode] on the executor and returns the index it will
    /// be written at; see there for `encodingOverride` and `excludedFromCascade`.
    ///
    /// @return the index of the written segment
    /// @throws IOException if writing an earlier, completed segment to the channel fails
    int write(DType dtype, Object data, EncodingEncoder encodingOverride,
            Set<EncodingId> excludedFromCascade) throws IOException {
        pending.add(CompletableFuture.supplyAsync(
                () -> encoder.encode(dtype, data, encodingOverride, excludedFromCascade), executor));
        int segIdx = submittedSegments++;
        drain(MAX_IN_FLIGHT_SEGMENTS);
        return segIdx;
    }

    /// Writes every in-flight segment.
    void drain() throws IOException {
        drain(0);
    }

    /// The zone-map stats of a segment already written; [#drain()] first to be sure it is.
    SegmentStats stats(int segIdx) {
        return segs.get(segIdx).stats();
    }

    /// Writes the remaining segments, then the footer, `dtype`, `layout`, postscript and trailer.
    ///
    /// @param zonedLayout the layout id [LayoutTree]'s zoned nodes stand for
    void finish(LayoutId zonedLayout, ByteBuffer dtype, ByteBuffer layout) throws IOException {
        drain(0);
        ByteBuffer footerBuf = buildFooter(zonedLayout);
        long footerOff = bytesWritten;
        write(footerBuf);

        long dtypeOff = bytesWritten;
        write(dtype);

        long layoutOff = bytesWritten;
        write(layout);

        ByteBuffer psBuf = buildPostscript(
                footerOff, footerBuf.capacity(),
                dtypeOff, dtype.capacity(),
                layoutOff, layout.capacity());
        write(psBuf);

        // Trailer: version(u16 LE) | postscriptLen(u16 LE) | magic(4)
        var trailer = ByteBuffer.allocate(VortexFormat.TRAILER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putShort((short) VortexFormat.VERSION);
        trailer.putShort((short) psBuf.capacity());
        trailer.put(VortexFormat.MAGIC.asByteBuffer());
        trailer.flip();
        channel.write(trailer);
    }

    /// Rethrows a failed task's unchecked cause as the sequential path would have thrown it (encoders
    /// and zone accumulators throw only unchecked exceptions); any other cause is returned wrapped.
    static IOException rethrowCause(CompletionException e) {
        switch (e.getCause()) {
            case RuntimeException re -> throw re;
            case Error err -> throw err;
            default -> {
                return new IOException(e.getCause());
            }
        }
    }

    /// Writes completed segments from the head of the queue, in submission order, blocking on the
    /// head while more than `keep` are still in flight. `drain(0)` writes them all.
    private void drain(int keep) throws IOException {
        while (!pending.isEmpty() && (pending.size() > keep || pending.peek().isDone())) {
            EncodedSegment encoded;
            try {
                encoded = pending.poll().join();
            } catch (CompletionException e) {
                discardPending();
                throw rethrowCause(e);
            }
            try (Arena _ = encoded.arena()) {
                writeEncoded(encoded);
            }
        }
    }

    /// Waits out every in-flight segment after a failure so none of their arenas leaks.
    private void discardPending() {
        while (!pending.isEmpty()) {
            pending.poll().handle((encoded, _) -> {
                if (encoded != null) {
                    encoded.arena().close();
                }
                return null;
            }).join();
        }
    }

    /// Appends one encoded segment to the channel: the ordered, I/O half of a segment write.
    private void writeEncoded(EncodedSegment encoded) throws IOException {
        EncodeResult result = encoded.result();
        // Register all encoding IDs found in the node tree
        registerEncodingIds(result.rootNode());

        // Align segment start to 64 bytes so each buffer is Arrow-compatible
        long prePad = (64 - bytesWritten % 64) % 64;
        if (prePad > 0) {
            writePadding((int) prePad);
        }

        long offset = bytesWritten;
        List<EncodedBuffer> buffers = result.encodedBuffers();
        int[] paddings = bufferPaddings(buffers);
        ByteBuffer fbBuf = buildArrayFlatBuffer(result, paddings);

        // Segment format: [padding, buffer]... [FlatBuffer Array bytes] [4-byte LE u32 = fbLen]
        int fbLen = fbBuf.remaining();
        for (int i = 0; i < buffers.size(); i++) {
            if (paddings[i] > 0) {
                writePadding(paddings[i]);
            }
            write(buffers.get(i).data());
        }
        write(fbBuf);
        var sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fbLen);
        sizeBuf.flip();
        channel.write(sizeBuf);
        bytesWritten += 4;

        segs.add(new SegRef(offset, bytesWritten - offset, encoded.stats()));
    }

    private void registerEncodingIds(EncodeNode node) {
        EncodingId id = node.encodingId();
        encoder.checkEdition(id);
        encodingIdx.computeIfAbsent(id, _ -> encodingIdx.size());
        for (EncodeNode child : node.children()) {
            registerEncodingIds(child);
        }
    }

    private void write(MemorySegment seg) throws IOException {
        // A segment may pass 2 GiB (a u32 length on the wire, #629) but a ByteBuffer cannot, so the
        // segment is written in slices that each fit one
        long size = seg.byteSize();
        for (long at = 0; at < size; at += MAX_WRITE_SLICE) {
            ByteBuffer buf = seg.asSlice(at, Math.min(MAX_WRITE_SLICE, size - at)).asByteBuffer();
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
        }
        bytesWritten += size;
    }

    private void write(ByteBuffer buf) throws IOException {
        buf.rewind();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        bytesWritten += buf.capacity();
    }

    private void writePadding(int n) throws IOException {
        ByteBuffer pad = ByteBuffer.allocate(n);
        while (pad.hasRemaining()) {
            channel.write(pad);
        }
        bytesWritten += n;
    }

    /// Zero bytes to write before each buffer so it starts on a multiple of its own alignment. The
    /// segment starts 64-byte aligned (more than any element needs), so offsets relative to the
    /// segment start are enough.
    private static int[] bufferPaddings(List<EncodedBuffer> buffers) {
        int[] paddings = new int[buffers.size()];
        long position = 0;
        for (int i = 0; i < buffers.size(); i++) {
            int alignment = buffers.get(i).alignment();
            paddings[i] = (int) ((alignment - position % alignment) % alignment);
            position += paddings[i] + buffers.get(i).data().byteSize();
        }
        return paddings;
    }

    private ByteBuffer buildArrayFlatBuffer(EncodeResult result, int[] paddings) {
        var fbb = new FbsBuilder(256);

        int rootNodeOff = buildArrayNodeFlatBuffer(fbb, result.rootNode());

        // Buffer struct vector — one entry per buffer in result.
        // FbsLayout (LE): padding(u16) | alignment_exponent(u8) | compression(u8) | length(u32)
        // FlatBuffers builds backward: iterate in reverse.
        // alignment_exponent is the element's own alignment, as Rust declares it: the Rust reader
        // keeps it on the buffer, failing array construction when it is less than the element needs
        // and aborting on any row slice off a multiple of it when it is more (this writer used to
        // declare 64 for every buffer, which crashed filtered vortex-jni scans of zone-mapped files).
        var bufs = result.encodedBuffers();
        FbsArray.startBuffersVector(fbb, bufs.size());
        for (int i = bufs.size() - 1; i >= 0; i--) {
            fbb.prep(4, 8);
            fbb.putInt((int) bufs.get(i).data().byteSize());
            fbb.putByte((byte) 0);   // compression = None
            fbb.putByte((byte) Integer.numberOfTrailingZeros(bufs.get(i).alignment()));
            fbb.putShort((short) paddings[i]);
        }
        int bufVec = fbb.endVector();

        int arrayOff = FbsArray.createFbsArray(fbb, rootNodeOff, bufVec);
        FbsArray.finishFbsArrayBuffer(fbb, arrayOff);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// The `ArrayStats` table of a node's stats, or `0` when it carries none. Every stat present is
    /// written, a `false` flag or a zero count included, as Rust writes an exact stat of any value.
    private static int buildStats(FbsBuilder fbb, NodeStats stats) {
        if (stats == null || stats.isEmpty()) {
            return 0;
        }
        // vectors first: FlatBuffers builds bottom-up
        int minVec = 0;
        if (stats.min() != null) {
            minVec = FbsArrayStats.createMinVector(fbb, stats.min().encode());
        }
        int maxVec = 0;
        if (stats.max() != null) {
            maxVec = FbsArrayStats.createMaxVector(fbb, stats.max().encode());
        }
        int sumVec = 0;
        if (stats.sum() != null) {
            sumVec = FbsArrayStats.createSumVector(fbb, stats.sum().encode());
        }
        // forceDefaults only while building ArrayStats, so a scalar equal to its default is still
        // serialized; reset immediately so the Array/ArrayNode tables keep their normal layout
        fbb.forceDefaults(true);
        FbsArrayStats.startFbsArrayStats(fbb);
        if (stats.min() != null) {
            FbsArrayStats.addMin(fbb, minVec);
            FbsArrayStats.addMinPrecision(fbb, FbsPrecision.Exact);
        }
        if (stats.max() != null) {
            FbsArrayStats.addMax(fbb, maxVec);
            FbsArrayStats.addMaxPrecision(fbb, FbsPrecision.Exact);
        }
        if (stats.sum() != null) {
            FbsArrayStats.addSum(fbb, sumVec);
        }
        if (stats.isSorted() != null) {
            FbsArrayStats.addIsSorted(fbb, stats.isSorted());
        }
        if (stats.isStrictSorted() != null) {
            FbsArrayStats.addIsStrictSorted(fbb, stats.isStrictSorted());
        }
        if (stats.isConstant() != null) {
            FbsArrayStats.addIsConstant(fbb, stats.isConstant());
        }
        if (stats.nullCount() != null) {
            FbsArrayStats.addNullCount(fbb, stats.nullCount());
        }
        if (stats.uncompressedSize() != null) {
            FbsArrayStats.addUncompressedSizeInBytes(fbb, stats.uncompressedSize());
        }
        if (stats.nanCount() != null) {
            FbsArrayStats.addNanCount(fbb, stats.nanCount());
        }
        int off = FbsArrayStats.endFbsArrayStats(fbb);
        fbb.forceDefaults(false);
        return off;
    }

    private int buildArrayNodeFlatBuffer(FbsBuilder fbb, EncodeNode node) {
        // Build children first (FlatBuffers bottom-up: nested objects before parent table)
        int[] childOffsets = new int[node.children().length];
        for (int i = 0; i < childOffsets.length; i++) {
            childOffsets[i] = buildArrayNodeFlatBuffer(fbb, node.children()[i]);
        }
        int statsOff = buildStats(fbb, node.stats());

        int metaOff = 0;
        if (node.metadata() != null && node.metadata().byteSize() > 0) {
            byte[] metaBytes = node.metadata().toArray(ValueLayout.JAVA_BYTE);
            metaOff = FbsArrayNode.createMetadataVector(fbb, metaBytes);
        }

        int childVec = 0;
        if (childOffsets.length > 0) {
            childVec = FbsArrayNode.createChildrenVector(fbb, childOffsets);
        }

        int bufIdxVec = FbsArrayNode.createBuffersVector(fbb, node.bufferIndices());
        int encIdx = encodingIdx.get(node.encodingId());
        return FbsArrayNode.createFbsArrayNode(
                fbb, encIdx, metaOff, childVec, bufIdxVec, statsOff);
    }

    private ByteBuffer buildFooter(LayoutId zonedLayout) {
        var fbb = new FbsBuilder(512);

        // array_specs: all encoding IDs used across all written segments, in registration order
        EncodingId[] encIds = encodingIdx.entrySet().stream()
                                      .sorted(Map.Entry.comparingByValue())
                                      .map(Map.Entry::getKey)
                                      .toArray(EncodingId[]::new);
        int[] asOffsets = new int[encIds.length];
        for (int i = 0; i < encIds.length; i++) {
            asOffsets[i] = FbsArraySpec.createFbsArraySpec(fbb, fbb.createString(encIds[i].id()));
        }
        int asv = FbsFooter.createArraySpecsVector(fbb, asOffsets);

        int lsv = LayoutTree.specs(fbb, zonedLayout);

        // segment_specs (inline struct vector — write in reverse order)
        FbsFooter.startSegmentSpecsVector(fbb, segs.size());
        for (int i = segs.size() - 1; i >= 0; i--) {
            SegRef s = segs.get(i);
            FbsSegmentSpec.createFbsSegmentSpec(fbb, s.offset(), s.len(), 6, 0, 0);
        }
        int ssv = fbb.endVector();

        int off = FbsFooter.createFbsFooter(fbb, asv, lsv, ssv, 0, 0);
        fbb.finish(off);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer buildPostscript(
            long footerOff, int footerLen,
            long dtypeOff, int dtypeLen,
            long layoutOff, int layoutLen
    ) {
        var fbb = new FbsBuilder(256);

        int footerSegOff = FbsPostscriptSegment.createFbsPostscriptSegment(
                fbb, footerOff, footerLen, 0, 0, 0);
        int dtypeSegOff = FbsPostscriptSegment.createFbsPostscriptSegment(
                fbb, dtypeOff, dtypeLen, 0, 0, 0);
        int layoutSegOff = FbsPostscriptSegment.createFbsPostscriptSegment(
                fbb, layoutOff, layoutLen, 0, 0, 0);

        int psOff = FbsPostscript.createFbsPostscript(fbb, dtypeSegOff, layoutSegOff, 0, footerSegOff);
        FbsPostscript.finishFbsPostscriptBuffer(fbb, psOff);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private record SegRef(long offset, long len, SegmentStats stats) {
    }
}
