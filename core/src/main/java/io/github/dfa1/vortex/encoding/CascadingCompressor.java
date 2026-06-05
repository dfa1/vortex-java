package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.DType;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Cascading compressor: evaluates multiple encodings on a sample and picks the one
/// producing the smallest output. With {@code allowedCascading > 0}, also recurses
/// into open child slots (e.g. ALP → Bitpacked for F64 columns).
///
/// <p>Encodings that override {@link Encoding#encodeCascade} expose intermediate
/// representations as open children; encodings that use the default are terminal.
/// At depth 0 only terminal encodings are considered.
public final class CascadingCompressor {

	private final List<Encoding> encodings;
	private final CompressorContext rootCtx;

	/// Constructs a {@code CascadingCompressor} with the given encodings and root context.
	///
	/// @param encodings candidate encodings evaluated during compression
	/// @param rootCtx   root compressor context controlling cascade depth and sampling
	public CascadingCompressor(List<Encoding> encodings, CompressorContext rootCtx) {
		this.encodings = List.copyOf(encodings);
		this.rootCtx = rootCtx;
	}

	/// Entry point: encode {@code data} using the best cascading strategy.
	///
	/// @param dtype logical type of the data to encode
	/// @param data  input data in the format expected by the candidate encodings
	/// @return the {@link EncodeResult} produced by the winning encoding
	public EncodeResult encode(DType dtype, Object data) {
		return encodeWithCtx(dtype, data, rootCtx);
	}

	private EncodeResult encodeWithCtx(DType dtype, Object data, CompressorContext ctx) {
		if (dtype instanceof DType.Struct structDtype) {
			return encodeStruct(structDtype, (StructData) data, ctx);
		}
		// Non-primitives (extension types): find the accepting encoding and splice
		// through it so its cascaded children (e.g. datetimeparts → days/seconds/subseconds)
		// are recursively compressed rather than stored as raw primitives.
		if (!(dtype instanceof DType.Primitive)) {
			return spliceResult(findPrimitiveEncoding(dtype), dtype, data, ctx);
		}
		int n = dataLength(data);

		// Build sample
		int sampleSize = (int) Math.max(ctx.minSampleSize(), Math.ceil(n * ctx.sampleFraction()));
		sampleSize = Math.min(sampleSize, n);
		Object sample = (sampleSize < n) ? sliceSample(data, sampleSize) : data;

		long primitiveBaseline = primitiveBytes(dtype, sampleSize);
		long bestSampleSize = primitiveBaseline;
		Encoding winner = null;

		for (Encoding enc : encodings) {
			if (!enc.accepts(dtype) || ctx.excluded().contains(enc.encodingId())) {
				continue;
			}
			CascadeStep step = enc.encodeCascade(dtype, sample, ctx);

			// At depth 0, skip encodings that require cascade
			if (!step.isTerminal() && ctx.allowedCascading() == 0) {
				continue;
			}

			long size = measureStep(enc, step, ctx);
			if (size < bestSampleSize) {
				bestSampleSize = size;
				winner = enc;
			}
		}

		if (winner == null) {
			// No encoding beats primitive — fall back
			return findPrimitiveEncoding(dtype).encode(dtype, data);
		}

		// Re-run winner on full data
		return spliceResult(winner, dtype, data, ctx);
	}

	// ── Size measurement (on sample) ──────────────────────────────────────────

	private long measureStep(Encoding enc, CascadeStep step, CompressorContext ctx) {
		long total = step.ownedBytes();
		for (ChildSlot slot : step.openChildren()) {
			CompressorContext childCtx = ctx.withDecrementedDepth().withExcluded(enc.encodingId());
			total += measureBestChild(slot.childDtype(), slot.childData(), childCtx);
		}
		return total;
	}

	private long measureBestChild(DType dtype, Object data, CompressorContext ctx) {
		int n = dataLength(data);
		long best = primitiveBytes(dtype, n);
		for (Encoding enc : encodings) {
			if (!enc.accepts(dtype) || ctx.excluded().contains(enc.encodingId())) {
				continue;
			}
			CascadeStep step = enc.encodeCascade(dtype, data, ctx);
			if (!step.isTerminal() && ctx.allowedCascading() == 0) {
				continue;
			}
			long size = measureStep(enc, step, ctx);
			if (size < best) {
				best = size;
			}
		}
		return best;
	}

	// ── Full-data run + buffer splicing ───────────────────────────────────────

	private EncodeResult spliceResult(Encoding winner, DType dtype, Object data, CompressorContext ctx) {
		CascadeStep step = winner.encodeCascade(dtype, data, ctx);

		if (!step.applicable()) {
			// Winner was selected on a sample that looked applicable (e.g. all-constant prefix),
			// but full data is not. Re-run without this encoding.
			return encodeWithCtx(dtype, data, ctx.withExcluded(winner.encodingId()));
		}

		if (step.isTerminal()) {
			return new EncodeResult(step.partialRoot(), step.ownedBuffers(), step.statsMin(), step.statsMax());
		}

		List<MemorySegment> allBuffers = new ArrayList<>(step.ownedBuffers());
		EncodeNode[] children = step.partialRoot().children().clone();

		for (ChildSlot slot : step.openChildren()) {
			CompressorContext childCtx = ctx.withDecrementedDepth().withExcluded(winner.encodingId());
			EncodeResult childResult = encodeWithCtx(slot.childDtype(), slot.childData(), childCtx);

			int bufOffset = allBuffers.size();
			children[slot.parentChildIdx()] = EncodeNode.remapBufferIndices(childResult.rootNode(), bufOffset);
			allBuffers.addAll(childResult.buffers());
		}

		EncodeNode root = new EncodeNode(
				step.partialRoot().encodingId(),
				step.partialRoot().metadata(),
				children,
				step.partialRoot().bufferIndices());
		return new EncodeResult(root, List.copyOf(allBuffers), step.statsMin(), step.statsMax());
	}

	// ── Struct encoding ───────────────────────────────────────────────────────

	private EncodeResult encodeStruct(DType.Struct dtype, StructData data, CompressorContext ctx) {
		List<Object> fields = data.fieldArrays();
		List<DType> fieldTypes = dtype.fieldTypes();
		List<MemorySegment> allBuffers = new ArrayList<>();
		EncodeNode[] children = new EncodeNode[fields.size()];
		for (int i = 0; i < fields.size(); i++) {
			EncodeResult fieldResult = encodeWithCtx(fieldTypes.get(i), fields.get(i), ctx);
			int bufOffset = allBuffers.size();
			children[i] = EncodeNode.remapBufferIndices(fieldResult.rootNode(), bufOffset);
			allBuffers.addAll(fieldResult.buffers());
		}
		EncodeNode root = new EncodeNode(EncodingId.VORTEX_STRUCT, null, children, new int[0]);
		return new EncodeResult(root, List.copyOf(allBuffers), null, null);
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static int dataLength(Object data) {
		return switch (data) {
			case StructData sd -> sd.fieldArrays().isEmpty() ? 0 : dataLength(sd.fieldArrays().getFirst());
			case byte[] a -> a.length;
			case short[] a -> a.length;
			case int[] a -> a.length;
			case long[] a -> a.length;
			case float[] a -> a.length;
			case double[] a -> a.length;
			default -> throw new IllegalArgumentException("unsupported data type: " + data.getClass());
		};
	}

	private static Object sliceSample(Object data, int n) {
		return switch (data) {
			case StructData sd -> {
				List<Object> sliced = sd.fieldArrays().stream().map(f -> sliceSample(f, n)).toList();
				yield new StructData(sliced);
			}
			case byte[] a -> Arrays.copyOf(a, n);
			case short[] a -> Arrays.copyOf(a, n);
			case int[] a -> Arrays.copyOf(a, n);
			case long[] a -> Arrays.copyOf(a, n);
			case float[] a -> Arrays.copyOf(a, n);
			case double[] a -> Arrays.copyOf(a, n);
			default -> throw new IllegalArgumentException("unsupported data type: " + data.getClass());
		};
	}

	private static long primitiveBytes(DType dtype, int n) {
		if (!(dtype instanceof DType.Primitive p)) {
			return (long) n * 8;
		}
		return (long) n * p.ptype().byteSize();
	}

	private Encoding findPrimitiveEncoding(DType dtype) {
		for (Encoding enc : encodings) {
			if (enc.encodingId().equals(EncodingId.VORTEX_PRIMITIVE) && enc.accepts(dtype)) {
				return enc;
			}
		}
		// Fall through to any accepting encoding
		for (Encoding enc : encodings) {
			if (enc.accepts(dtype)) {
				return enc;
			}
		}
		throw new UnsupportedOperationException("no encoding for dtype: " + dtype);
	}
}
