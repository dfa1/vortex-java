package io.github.dfa1.vortex.encoding;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.vortex.proto.EncodingProtos;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.ByteArray;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.FloatArray;
import io.github.dfa1.vortex.core.array.IntArray;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.core.array.ShortArray;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.core.VortexException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/// Encoding for `vortex.dict` — dictionary encoding for low-cardinality columns.
///
/// Primitive: [valuesbuffer(uniquevalues)] [codesbuffer]. Metadata (1 byte): code PType ordinal.
/// Node tree: DictNode{ children=[ValuesNode{buf=0},CodesNode{buf=1}] }.
///
/// Utf8: buf 0 = dict bytes, buf 1 = dict offsets (I64 LE, n_unique+1), buf 2 = codes.
/// Metadata (2 bytes): [codePType ordinal, I64 ordinal]. Flat node (no children), bufferIndices=[0,1,2].
/// Decoded as a {@link VarBinArray} in dict mode — no string materialization at decode time.
public final class DictEncoding implements Encoding {

	private static Array decodeChildAs(DecodeContext parent, int childIdx, DType dtype, long rowCount) {
		ArrayNode childNode = parent.node().children()[childIdx];
		DecodeContext childCtx = new DecodeContext(
				childNode, dtype, rowCount, parent.segmentBuffers(), parent.registry(), parent.arena());
		return parent.registry().decode(childCtx);
	}

	private static PType codePType(int dictSize) {
		if (dictSize <= 256) {
			return PType.U8;
		}
		if (dictSize <= 65536) {
			return PType.U16;
		}
		return PType.U32;
	}

	// ── Encode ────────────────────────────────────────────────────────────────

	private static int arrayLength(Object data, PType ptype) {
		return switch (ptype) {
			case I8, U8 -> ((byte[]) data).length;
			case I16, U16 -> ((short[]) data).length;
			case I32, U32 -> ((int[]) data).length;
			case I64, U64 -> ((long[]) data).length;
			case F32 -> ((float[]) data).length;
			case F64 -> ((double[]) data).length;
			case F16 -> ((short[]) data).length;
		};
	}

	// ── Decode ────────────────────────────────────────────────────────────────

	private static Object readElement(Object data, PType ptype, int i) {
		return switch (ptype) {
			case I8, U8 -> ((byte[]) data)[i];
			case I16, U16, F16 -> ((short[]) data)[i];
			case I32, U32 -> ((int[]) data)[i];
			case I64, U64 -> ((long[]) data)[i];
			case F32 -> ((float[]) data)[i];
			case F64 -> ((double[]) data)[i];
		};
	}

	private static Object buildUniqueArray(PType ptype, Iterable<Object> uniques, int dictSize) {
		return switch (ptype) {
			case I8, U8 -> {
				byte[] a = new byte[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Byte) v;
				}
				yield a;
			}
			case I16, U16 -> {
				short[] a = new short[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Short) v;
				}
				yield a;
			}
			case I32, U32 -> {
				int[] a = new int[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Integer) v;
				}
				yield a;
			}
			case I64, U64 -> {
				long[] a = new long[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Long) v;
				}
				yield a;
			}
			case F32 -> {
				float[] a = new float[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Float) v;
				}
				yield a;
			}
			case F64 -> {
				double[] a = new double[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Double) v;
				}
				yield a;
			}
			case F16 -> {
				short[] a = new short[dictSize];
				int i = 0;
				for (Object v : uniques) {
					a[i++] = (Short) v;
				}
				yield a;
			}
		};
	}

	private static void writeCode(ByteBuffer buf, PType codePType, int code) {
		switch (codePType) {
			case U8 -> buf.put((byte) code);
			case U16 -> buf.putShort((short) code);
			case U32 -> buf.putInt(code);
			default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
		}
	}

	private static void writeCodeToSeg(MemorySegment seg, PType codePType, int idx, int code) {
		switch (codePType) {
			case U8 -> seg.set(ValueLayout.JAVA_BYTE, idx, (byte) code);
			case U16 -> seg.set(PTypeIO.LE_SHORT, (long) idx * 2, (short) code);
			case U32 -> seg.set(PTypeIO.LE_INT, (long) idx * 4, code);
			default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
		}
	}

	private static void expandU8(
			MemorySegment codes, MemorySegment values, MemorySegment out,
			long rowCount, int elemSize
	) {
		for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
			long code = Byte.toUnsignedLong(codes.get(ValueLayout.JAVA_BYTE, i));
			MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static void expandU16(
			MemorySegment codes, MemorySegment values, MemorySegment out,
			long rowCount, int elemSize
	) {
		for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
			long code = Short.toUnsignedLong(codes.get(PTypeIO.LE_SHORT, i * 2));
			MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
		}
	}

	private static void expandU32(
			MemorySegment codes, MemorySegment values, MemorySegment out,
			long rowCount, int elemSize
	) {
		for (long i = 0, outOff = 0; i < rowCount; i++, outOff += elemSize) {
			long code = Integer.toUnsignedLong(codes.get(PTypeIO.LE_INT, i * 4));
			MemorySegment.copy(values, code * elemSize, out, outOff, elemSize);
		}
	}

	private static long readCode(MemorySegment buf, PType codePType, long i) {
		return switch (codePType) {
			case U8 -> Byte.toUnsignedLong(buf.get(ValueLayout.JAVA_BYTE, i));
			case U16 -> Short.toUnsignedLong(buf.get(PTypeIO.LE_SHORT, i * 2));
			case U32 -> Integer.toUnsignedLong(buf.get(PTypeIO.LE_INT, i * 4));
			default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
		};
	}

	@Override
	public EncodingId encodingId() {
		return EncodingId.VORTEX_DICT;
	}

	@Override
	public boolean accepts(DType dtype) {
		return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8;
	}

	private record DictData(MemorySegment valuesBuf, Object codesArr, PType codePType, int len) {}

	private static DictData buildDictData(DType dtype, Object data) {
		PType ptype = ((DType.Primitive) dtype).ptype();
		var valueMap = new LinkedHashMap<Object, Integer>();
		int len = arrayLength(data, ptype);
		for (int i = 0; i < len; i++) {
			Object v = readElement(data, ptype, i);
			valueMap.computeIfAbsent(v, k -> valueMap.size());
		}

		int dictSize = valueMap.size();
		PType codePType = codePType(dictSize);
		int codeBytes = codePType.byteSize();

		Object uniqueArray = buildUniqueArray(ptype, valueMap.keySet(), dictSize);
		MemorySegment valuesBuf = PTypeIO.copyArray(ptype, uniqueArray, dictSize);

		MemorySegment codesBuf = java.lang.foreign.Arena.ofAuto().allocate((long) len * codeBytes);
		for (int i = 0; i < len; i++) {
			Object v = readElement(data, ptype, i);
			int code = valueMap.get(v);
			writeCodeToSeg(codesBuf, codePType, i, code);
		}

		// Also build native array for cascade use
		Object codesArr = switch (codePType) {
			case U8 -> {
				byte[] a = new byte[len];
				for (int i = 0; i < len; i++) {
					a[i] = codesBuf.get(ValueLayout.JAVA_BYTE, i);
				}
				yield a;
			}
			case U16 -> {
				short[] a = new short[len];
				for (int i = 0; i < len; i++) {
					a[i] = codesBuf.get(PTypeIO.LE_SHORT, (long) i * 2);
				}
				yield a;
			}
			default -> {
				int[] a = new int[len];
				for (int i = 0; i < len; i++) {
					a[i] = codesBuf.get(PTypeIO.LE_INT, (long) i * 4);
				}
				yield a;
			}
		};
		return new DictData(valuesBuf, codesArr, codePType, len);
	}

	@Override
	public EncodeResult encode(DType dtype, Object data) {
		if (dtype instanceof DType.Utf8) {
			return encodeUtf8((String[]) data, dtype);
		}
		DictData d = buildDictData(dtype, data);
		PType codePType = d.codePType();
		int codeBytes = codePType.byteSize();

		MemorySegment codesBuf = java.lang.foreign.Arena.ofAuto().allocate((long) d.len() * codeBytes);
		for (int i = 0; i < d.len(); i++) {
			writeCodeToSeg(codesBuf, codePType, i, readCodeFromArr(d.codesArr(), codePType, i));
		}

		ByteBuffer meta = ByteBuffer.allocate(1).put(0, (byte) codePType.ordinal());
		EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
		EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
		EncodeNode rootNode = new EncodeNode(
				EncodingId.VORTEX_DICT, meta,
				new EncodeNode[]{valuesNode, codesNode},
				new int[0]);

		return new EncodeResult(rootNode, List.of(d.valuesBuf(), codesBuf), null, null);
	}

	@Override
	public CascadeStep encodeCascade(DType dtype, Object data, CompressorContext ctx) {
		if (dtype instanceof DType.Utf8) {
			return CascadeStep.terminal(encodeUtf8((String[]) data, dtype));
		}
		DictData d = buildDictData(dtype, data);
		PType codePType = d.codePType();

		// valuesBuf stays owned; codes become open child slot so cascade can bitpack them
		ByteBuffer meta = ByteBuffer.allocate(1).put(0, (byte) codePType.ordinal());
		EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
		EncodeNode partialRoot = new EncodeNode(
				EncodingId.VORTEX_DICT, meta,
				new EncodeNode[]{valuesNode, null},
				new int[0]);

		DType codesDtype = new DType.Primitive(codePType, false);
		ChildSlot slot = new ChildSlot(codesDtype, d.codesArr(), 1);
		return new CascadeStep(partialRoot, List.of(d.valuesBuf()), List.of(slot), null, null);
	}

	private static int readCodeFromArr(Object arr, PType codePType, int i) {
		return switch (codePType) {
			case U8 -> Byte.toUnsignedInt(((byte[]) arr)[i]);
			case U16 -> Short.toUnsignedInt(((short[]) arr)[i]);
			default -> ((int[]) arr)[i];
		};
	}

	// ── Utf8 encode ───────────────────────────────────────────────────────────

	private EncodeResult encodeUtf8(String[] strings, DType dtype) {
		int n = strings.length;

		var valueMap = new LinkedHashMap<String, Integer>();
		for (String s : strings) {
			valueMap.computeIfAbsent(s, k -> valueMap.size());
		}

		int dictSize = valueMap.size();
		PType codePType = codePType(dictSize);
		int codeBytes = codePType.byteSize();

		byte[][] dictByteArrays = new byte[dictSize][];
		int j = 0;
		long totalDictBytes = 0;
		for (String s : valueMap.keySet()) {
			dictByteArrays[j] = s.getBytes(StandardCharsets.UTF_8);
			totalDictBytes += dictByteArrays[j].length;
			j++;
		}

		Arena arena = Arena.ofAuto();
		MemorySegment dictBytesBuf = arena.allocate(totalDictBytes > 0 ? totalDictBytes : 1);
		MemorySegment dictOffsetsBuf = arena.allocate((long) (dictSize + 1) * Long.BYTES, Long.BYTES);

		long pos = 0;
		dictOffsetsBuf.setAtIndex(PTypeIO.LE_LONG, 0, 0L);
		for (int i = 0; i < dictSize; i++) {
			MemorySegment.copy(MemorySegment.ofArray(dictByteArrays[i]), 0, dictBytesBuf, pos, dictByteArrays[i].length);
			pos += dictByteArrays[i].length;
			dictOffsetsBuf.setAtIndex(PTypeIO.LE_LONG, i + 1, pos);
		}

		MemorySegment codesBuf = Arena.ofAuto().allocate((long) n * codeBytes);
		for (int i = 0; i < n; i++) {
			writeCodeToSeg(codesBuf, codePType, i, valueMap.get(strings[i]));
		}

		// 2 bytes: [codePType ordinal, I64 ordinal] — distinguishes from 1-byte primitive format
		ByteBuffer meta = ByteBuffer.allocate(2)
				.put(0, (byte) codePType.ordinal())
				.put(1, (byte) PType.I64.ordinal());

		EncodeNode root = new EncodeNode(
				EncodingId.VORTEX_DICT, meta,
				new EncodeNode[0],
				new int[]{0, 1, 2});

		return new EncodeResult(root, List.of(dictBytesBuf, dictOffsetsBuf, codesBuf), null, null);
	}

	// ── Utf8 decode ───────────────────────────────────────────────────────────

	private static Array decodeUtf8Dict(DecodeContext ctx, ByteBuffer meta) {
		PType codePType = PType.values()[Byte.toUnsignedInt(meta.get(0))];
		long n = ctx.rowCount();

		MemorySegment dictBytes   = ctx.buffer(0);
		MemorySegment dictOffsets = ctx.buffer(1);
		MemorySegment codes       = ctx.buffer(2);

		return VarBinArray.ofDict(ctx.dtype(), n,
				dictBytes, dictOffsets, PType.I64,
				codes, codePType,
				ArrayStats.empty());
	}

	@Override
	public Array decode(DecodeContext ctx) {
		if (ctx.metadata() == null || !ctx.metadata().hasRemaining()) {
			throw new VortexException(EncodingId.VORTEX_DICT, "missing metadata");
		}

		ByteBuffer meta = ctx.metadata();

		if (ctx.dtype() instanceof DType.Utf8) {
			return decodeUtf8Dict(ctx, meta);
		}

		// 1-byte = legacy Java format; multi-byte = Rust proto format
		if (meta.remaining() == 1) {
			return decodeLegacyJava(ctx, meta.get(0));
		}
		return decodeRustProto(ctx, meta.duplicate());
	}

	private Array decodeLegacyJava(DecodeContext ctx, byte codeTypeByte) {
		PType codePType = PType.values()[Byte.toUnsignedInt(codeTypeByte)];
		PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
		int elemSize = valPType.byteSize();
		long rowCount = ctx.rowCount();

		// Values: always VORTEX_PRIMITIVE leaf, read direct
		MemorySegment valuesBuf = ctx.segmentBuffers()[ctx.node().children()[0].bufferIndices()[0]];

		// Codes: decode through registry — supports both raw (VORTEX_PRIMITIVE) and cascade (FASTLANES_BITPACKED) children
		DType codesDtype = new DType.Primitive(codePType, false);
		Array codesArr = decodeChildAs(ctx, 1, codesDtype, rowCount);
		MemorySegment codesBuf = codesArr.buffer(0);

		MemorySegment out = ctx.arena().allocate(rowCount * (long) elemSize);
		switch (codePType) {
			case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
			case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
			case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
			default -> {
				for (long i = 0; i < rowCount; i++) {
					long code = readCode(codesBuf, codePType, i);
					MemorySegment.copy(valuesBuf, code * elemSize, out, i * elemSize, elemSize);
				}
			}
		}
		return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
	}

	private Array decodeRustProto(DecodeContext ctx, ByteBuffer metaBuf) {
		EncodingProtos.DictMetadata meta;
		try {
			meta = EncodingProtos.DictMetadata.parseFrom(metaBuf);
		} catch (InvalidProtocolBufferException e) {
			throw new VortexException(EncodingId.VORTEX_DICT, "invalid proto metadata", e);
		}

		PType codePType = PType.values()[meta.getCodesPtype().getNumber()];
		long valuesLen = meta.getValuesLen();
		long rowCount = ctx.rowCount();
		PType valPType = ((DType.Primitive) ctx.dtype()).ptype();
		int elemSize = valPType.byteSize();

		// Rust layout: children[0]=codes, children[1]=values
		DType codesDtype = new DType.Primitive(codePType, false);
		Array codesArr = decodeChildAs(ctx, 0, codesDtype, rowCount);
		Array valuesArr = decodeChildAs(ctx, 1, ctx.dtype(), valuesLen);

		MemorySegment codesBuf = codesArr.buffer(0);
		MemorySegment valuesBuf = valuesArr.buffer(0);

		MemorySegment out = ctx.arena().allocate(rowCount * (long) elemSize);
		// Loop-unswitch: pull the codePType switch outside the hot loop so the JIT
		// sees a tight, type-specific loop with predictable branches.
		switch (codePType) {
			case U8 -> expandU8(codesBuf, valuesBuf, out, rowCount, elemSize);
			case U16 -> expandU16(codesBuf, valuesBuf, out, rowCount, elemSize);
			case U32 -> expandU32(codesBuf, valuesBuf, out, rowCount, elemSize);
			default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
		}
		return typedArray(ctx.dtype(), valPType, rowCount, out.asReadOnly());
	}

	private static Array typedArray(DType dtype, PType ptype, long n, MemorySegment seg) {
		return switch (ptype) {
			case I64, U64 -> new LongArray(dtype, n, seg, ArrayStats.empty());
			case I32, U32 -> new IntArray(dtype, n, seg, ArrayStats.empty());
			case F64 -> new DoubleArray(dtype, n, seg, ArrayStats.empty());
			case F32 -> new FloatArray(dtype, n, seg, ArrayStats.empty());
			case I16, U16 -> new ShortArray(dtype, n, seg, ArrayStats.empty());
			case I8, U8   -> new ByteArray(dtype, n, seg, ArrayStats.empty());
			default -> throw new VortexException(EncodingId.VORTEX_DICT, "unsupported ptype " + ptype);
		};
	}
}
