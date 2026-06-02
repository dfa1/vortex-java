package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.fbs.Buffer;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/// Registry mapping encoding IDs to [Encoding] implementations.
public final class EncodingRegistry {

	private final Map<EncodingId, Encoding> encodings = new HashMap<>();

	private EncodingRegistry() {
	}

	/// Load all [Encoding]s registered via `ServiceLoader`.
	public static EncodingRegistry loadAll() {
		var registry = new EncodingRegistry();
		for (Encoding encoding : ServiceLoader.load(Encoding.class)) {
			registry.register(encoding);
		}
		return registry;
	}

	public static EncodingRegistry empty() {
		return new EncodingRegistry();
	}

	public boolean hasEncoding(EncodingId encodingId) {
		return encodings.containsKey(encodingId);
	}

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
		return new ArrayNode(encId, meta, children, bufferIndices, ArrayStats.fromFbs(fbs.stats()));
	}

	public void register(Encoding encoding) {
		Encoding old = encodings.put(encoding.encodingId(), encoding);
		if (old != null) {
			throw new VortexException("encoding %s already registered".formatted(encoding.encodingId()));
		}
	}

	/// Decode a flat segment from the file's memory-mapped region.
	///
	/// Segment format: [bufferdata...] [FlatBufferArray] [4-byteLEu32=FlatBuffersize].
	public Array decodeSegment(MemorySegment seg, List<String> encodingSpecs,
	                           DType dtype, long rowCount, SegmentAllocator arena) {
		int segLen = (int) seg.byteSize();
		ByteBuffer bb = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);

		int fbLen = bb.getInt(segLen - 4);
		int fbStart = segLen - 4 - fbLen;
		ByteBuffer fbBuf = bb.slice(fbStart, fbLen).order(ByteOrder.LITTLE_ENDIAN);
		var fbArray = io.github.dfa1.vortex.fbs.Array.getRootAsArray(fbBuf);

		int numBuffers = fbArray.buffersLength();
		MemorySegment[] bufs = new MemorySegment[numBuffers];
		long dataOffset = 0;
		for (int i = 0; i < numBuffers; i++) {
			Buffer bufDesc = fbArray.buffers(i);
			dataOffset += bufDesc.padding();
			bufs[i] = seg.asSlice(dataOffset, bufDesc.length());
			dataOffset += bufDesc.length();
		}

		ArrayNode rootNode = convertArrayNode(fbArray.root(), encodingSpecs);
		var ctx = new DecodeContext(rootNode, dtype, rowCount, bufs, this, arena);
		return decode(ctx);
	}

	Array decode(DecodeContext ctx) {
		EncodingId id = ctx.node().encodingId();
		Encoding encoding = encodings.get(id);
		if (encoding == null) {
			throw new VortexException(id, "no encoding registered");
		}
		return encoding.decode(ctx);
	}
}
