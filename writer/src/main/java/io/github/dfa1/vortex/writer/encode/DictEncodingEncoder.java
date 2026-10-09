package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.proto.ProtoDictMetadata;
import io.github.dfa1.vortex.core.proto.ProtoScalarValue;
import io.github.dfa1.vortex.core.proto.ProtoVarBinMetadata;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;

/// Write-only encoder for `vortex.dict`.
public final class DictEncodingEncoder implements EncodingEncoder {

    /// Barred from a dict's codes child: `vortex.dict` because a dictionary of codes is just
    /// another indirection the reader would have to unwrap, and `vortex.sequence` because codes are
    /// dictionary positions — describing them as an arithmetic sequence adds a level without
    /// compressing anything (issue #410).
    private static final Set<EncodingId> CODES_EXCLUDED =
            Set.of(EncodingId.VORTEX_DICT, EncodingId.VORTEX_SEQUENCE);

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_DICT;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Primitive || dtype instanceof DType.Utf8;
    }

    @Override
    public StatsOptions statsOptions() {
        return new StatsOptions(true, false);
    }

    @Override
    public Estimate expectedRatio(DType dtype, ArrayAndStats data, EncodeContext ctx) {
        // Stats path only covers Primitive (Utf8 still uses sample-encoded selection).
        if (!(dtype instanceof DType.Primitive p)) {
            return Estimate.COMPLETE;
        }
        ArrayStats stats = data.stats();
        long n = stats.valueCount();
        if (n == 0) {
            return Estimate.SKIP;
        }
        // Rust's IntDictScheme / FloatDictScheme skip rule. A capped scan already proves
        // distinct > n/2 + 1, so it skips too.
        long distinct = stats.distinctCount();
        if (stats.distinctCapped() || distinct > n / 2) {
            return Estimate.SKIP;
        }
        if (p.ptype().isFloating()) {
            // FloatDictScheme defers to the sample: a raw float dict cost would ignore the
            // cascade ALP gets credit for in its own sampled measure.
            return Estimate.COMPLETE;
        }
        return Estimate.ratio(integerDictRatio(p.ptype().bits(), n, distinct, stats.averageRunLength()));
    }

    /// Rust's `IntDictScheme` estimate (`vortex-compressor/src/builtins/dict/integer.rs`), in
    /// bits: the values at full width, plus codes either bit-packed or, when the array is runny,
    /// run-length encoded at 32 bits of run bookkeeping per run, whichever is smaller.
    static double integerDictRatio(int bitWidth, long n, long distinct, long averageRunLength) {
        long valuesSize = bitWidth * distinct;
        long codesWidth = Long.SIZE - Long.numberOfLeadingZeros(distinct);
        long runs = n / averageRunLength;
        long codesSize = Math.min(codesWidth * n, (codesWidth + 32) * runs);
        long before = n * bitWidth;
        return (double) before / (valuesSize + codesSize);
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        if (dtype instanceof DType.Utf8) {
            return encodeUtf8((String[]) data, ctx);
        }
        DictData d = buildDictData(dtype, data);
        PType codePType = d.codePType();
        int codeBytes = codePType.byteSize();

        MemorySegment codesBuf = ctx.arena().allocate((long) d.len() * codeBytes);
        for (int i = 0; i < d.len(); i++) {
            writeCodeToSeg(codesBuf, codePType, i, readCodeFromArr(d.codesArr(), codePType, i));
        }

        // Wire shape must match the Rust reference: DictMetadata proto, children [codes, values]
        // (`DictArray::new_unchecked(codes, values)`). This path used to emit a 1-byte metadata
        // holding just the code ptype with children [values, codes]; our own reader still accepts
        // that (DictEncodingDecoder#decodeLegacyJava) but the Rust reader rejected the file with
        // "failed to decode Protobuf message: invalid tag value: 0" — any primitive column where
        // dict won the cascade was unreadable by vortex-jni.
        MemorySegment meta = MemorySegment.ofArray(dictMetadata(d));
        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode rootNode = new EncodeNode(
                EncodingId.VORTEX_DICT, meta,
                new EncodeNode[]{codesNode, valuesNode},
                new int[0]);

        byte[][] stats = PrimitiveEncodingEncoder.minMaxStats(((DType.Primitive) dtype).ptype(), data);
        return new EncodeResult(rootNode, List.of(EncodedBuffer.of(d.valuesBuf(), ((DType.Primitive) dtype).ptype()), EncodedBuffer.of(codesBuf, codePType)),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats));
    }

    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (dtype instanceof DType.Utf8) {
            return encodeUtf8Cascade((String[]) data, ctx);
        }
        DictData d = buildDictData(dtype, data);
        PType codePType = d.codePType();

        // Same [codes, values] wire shape as the terminal path above.
        MemorySegment meta = MemorySegment.ofArray(dictMetadata(d));
        EncodeNode valuesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 0);
        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DICT, meta,
                new EncodeNode[]{null, valuesNode},
                new int[0]);

        DType codesDtype = new DType.Primitive(codePType, false);
        ChildSlot slot = new ChildSlot(codesDtype, d.codesArr(), 0, CODES_EXCLUDED);
        byte[][] stats = PrimitiveEncodingEncoder.minMaxStats(((DType.Primitive) dtype).ptype(), data);
        return new CascadeStep(partialRoot, List.of(EncodedBuffer.of(d.valuesBuf(), ((DType.Primitive) dtype).ptype())), List.of(slot),
                PrimitiveEncodingEncoder.minOf(stats), PrimitiveEncodingEncoder.maxOf(stats), true);
    }

    /// Cascading Utf8 dict: emit the codes leaf but expose the distinct-values pool as an open Utf8
    /// child slot so the cascade competes FSST/VarBin/Zstd on it (#299) instead of hardcoding raw
    /// varbin. [CascadingCompressor] excludes the winning `vortex.dict` from that child's
    /// competition, so the pool is never wrapped in a second dict the reader cannot unwrap.
    ///
    /// @param strings the column's string values
    /// @param ctx     encoding context supplying the arena
    /// @return a cascade step whose values child (index 1) is left open for compression
    private static CascadeStep encodeUtf8Cascade(String[] strings, EncodeContext ctx) {
        int n = strings.length;
        var valueMap = new LinkedHashMap<String, Integer>();
        for (String s : strings) {
            valueMap.computeIfAbsent(s, _ -> valueMap.size());
            // Distinct count only grows as the scan proceeds, so once it alone already exceeds
            // n/2 the final count is guaranteed to too — the same "dict can't win" rule
            // DictEncodingEncoder#expectedRatio applies to Primitive via stats, mirrored here
            // since Utf8 has no shared-stats pre-pass (#395-style: don't finish building
            // structure the competition is already guaranteed to discard). Bailing via
            // CascadeStep.notApplicable() is safe both when this call is measuring a sample
            // (never wins) and, per spliceResult's applicable() check, if it were ever re-run
            // as a winner on full data.
            if (valueMap.size() * 2 > n) {
                return CascadeStep.notApplicable();
            }
        }
        int dictSize = valueMap.size();
        PType codePType = codePType(dictSize);

        // Codes as a typed array (U8->byte[], U16->short[], U32->int[]) so the cascade can bitpack
        // them, rather than emitting a raw vortex.primitive leaf that pays the full U8/U16/U32 width
        // per row (#303). The primitive dict path already exposes its codes this way.
        Object codesArr = buildCodesArray(strings, valueMap, codePType);

        byte[] metaBytes = new ProtoDictMetadata(
                dictSize,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codePType.ordinal()),
                null,
                null
        ).encode();

        // Child order matches the terminal encodeUtf8: [codes, values]. Both children are left open
        // so the cascade bitpacks the codes and FSST/VarBin-competes the values pool.
        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_DICT, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{null, null},
                new int[0]);

        String[] distinct = valueMap.keySet().toArray(new String[0]);

        String minStr = valueMap.keySet().stream().min(String::compareTo).orElse(null);
        String maxStr = valueMap.keySet().stream().max(String::compareTo).orElse(null);
        byte[] statsMin = minStr != null ? ProtoScalarValue.ofStringValue(minStr).encode() : null;
        byte[] statsMax = maxStr != null ? ProtoScalarValue.ofStringValue(maxStr).encode() : null;

        return new CascadeStep(partialRoot, List.of(),
                List.of(new ChildSlot(new DType.Primitive(codePType, false), codesArr, 0, CODES_EXCLUDED),
                        new ChildSlot(DType.UTF8, distinct, 1, Set.of(EncodingId.VORTEX_DICT))),
                statsMin, statsMax, true);
    }

    /// Dictionary-encodes a nullable Utf8 column the way Rust's dict builder does: a null row is
    /// one more dictionary entry, marked invalid in the values pool's own validity, so the codes
    /// stay non-nullable and the column needs no row-level `vortex.masked` bitmap on top. The
    /// masked form ([MaskedEncodingEncoder]) pays for nulls twice when Dict wins its inner cascade:
    /// a placeholder pool entry and a one-bit-per-row validity.
    ///
    /// The codes are cascaded like any dict's codes; the pool goes through [MaskedEncodingEncoder]
    /// with Dict excluded, so it is never wrapped in a second dict.
    ///
    /// @param dtype    the column's nullable Utf8 dtype; also the values pool's dtype, as Rust
    ///                 requires
    /// @param strings  the row values (any element at an invalid row is ignored)
    /// @param validity per-row validity
    /// @param ctx      the encode context of the enclosing masked column
    /// @return the encoded dict, or `null` when more than half the rows are distinct (Dict cannot win)
    static EncodeResult encodeNullableUtf8(DType dtype, String[] strings, boolean[] validity, EncodeContext ctx) {
        int n = strings.length;
        String[] keys = new String[n];
        var valueMap = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < n; i++) {
            keys[i] = validity[i] ? strings[i] : null;
            valueMap.computeIfAbsent(keys[i], _ -> valueMap.size());
            // Same "dict can't win" bail-out as encodeUtf8Cascade.
            if (valueMap.size() * 2 > n) {
                return null;
            }
        }
        int dictSize = valueMap.size();
        PType codePType = codePType(dictSize);
        String[] pool = valueMap.keySet().toArray(new String[0]);
        boolean[] poolValidity = new boolean[dictSize];
        for (int i = 0; i < dictSize; i++) {
            poolValidity[i] = pool[i] != null;
        }

        EncodeContext childCtx = ctx.withDecrementedDepth();
        EncodeResult codes = new CascadingCompressor(List.copyOf(ctx.registry().encoderMap().values()))
                .encode(new DType.Primitive(codePType, false), buildCodesArray(keys, valueMap, codePType),
                        childCtx.withExcluded(CODES_EXCLUDED));
        EncodeResult values = new MaskedEncodingEncoder()
                .encode(dtype, new NullableData(pool, poolValidity), childCtx.withExcluded(EncodingId.VORTEX_DICT));

        // is_nullable_codes must be explicit: when it is absent Rust takes the codes' nullability
        // from the (nullable) dtype and would read these non-nullable codes as nullable.
        byte[] metaBytes = new ProtoDictMetadata(
                dictSize,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codePType.ordinal()),
                false,
                null
        ).encode();
        int codesBufCount = codes.buffers().size();
        List<EncodedBuffer> buffers = new java.util.ArrayList<>(codesBufCount + values.buffers().size());
        buffers.addAll(codes.encodedBuffers());
        buffers.addAll(values.encodedBuffers());
        EncodeNode root = new EncodeNode(EncodingId.VORTEX_DICT, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{codes.rootNode(), EncodeNode.remapBufferIndices(values.rootNode(), codesBufCount)},
                new int[0]);
        return new EncodeResult(root, buffers, null, null);
    }

    private static Object buildCodesArray(String[] strings, java.util.Map<String, Integer> valueMap, PType codePType) {
        int[] codes = new int[strings.length];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = valueMap.get(strings[i]);
        }
        return PrimitiveArrays.fromIntsArray(codes, codePType, EncodingId.VORTEX_DICT);
    }

    private static EncodeResult encodeUtf8(String[] strings, EncodeContext ctx) {
        int n = strings.length;

        var valueMap = new LinkedHashMap<String, Integer>();
        for (String s : strings) {
            valueMap.computeIfAbsent(s, _ -> valueMap.size());
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

        Arena arena = ctx.arena();
        MemorySegment dictBytesBuf = arena.allocate(totalDictBytes > 0 ? totalDictBytes : 1);
        MemorySegment dictOffsetsBuf = arena.allocate((long) (dictSize + 1) * Long.BYTES, Long.BYTES);

        long pos = 0;
        dictOffsetsBuf.setAtIndex(VortexFormat.LE_LONG, 0, 0L);
        for (int i = 0; i < dictSize; i++) {
            MemorySegment.copy(MemorySegment.ofArray(dictByteArrays[i]), 0, dictBytesBuf, pos, dictByteArrays[i].length);
            pos += dictByteArrays[i].length;
            dictOffsetsBuf.setAtIndex(VortexFormat.LE_LONG, (long) i + 1, pos);
        }

        MemorySegment codesBuf = arena.allocate((long) n * codeBytes);
        for (int i = 0; i < n; i++) {
            writeCodeToSeg(codesBuf, codePType, i, valueMap.get(strings[i]));
        }

        byte[] metaBytes = new ProtoDictMetadata(
                dictSize,
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codePType.ordinal()),
                null,
                null
        ).encode();

        byte[] varBinMetaBytes = new ProtoVarBinMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I64.ordinal())
        ).encode();

        EncodeNode offsetsNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 1);
        EncodeNode valuesNode = new EncodeNode(EncodingId.VORTEX_VARBIN,
                MemorySegment.ofArray(varBinMetaBytes),
                new EncodeNode[]{offsetsNode},
                new int[]{0});
        EncodeNode codesNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 2);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_DICT, MemorySegment.ofArray(metaBytes),
                new EncodeNode[]{codesNode, valuesNode},
                new int[0]);

        String minStr = valueMap.keySet().stream().min(String::compareTo).orElse(null);
        String maxStr = valueMap.keySet().stream().max(String::compareTo).orElse(null);
        byte[] statsMin = minStr != null ? ProtoScalarValue.ofStringValue(minStr).encode() : null;
        byte[] statsMax = maxStr != null ? ProtoScalarValue.ofStringValue(maxStr).encode() : null;
        return new EncodeResult(root, List.of(EncodedBuffer.bytes(dictBytesBuf), EncodedBuffer.of(dictOffsetsBuf, PType.I64),
                EncodedBuffer.of(codesBuf, codePType)), statsMin, statsMax);
    }

    private static DictData buildDictData(DType dtype, Object data) {
        PType ptype = ((DType.Primitive) dtype).ptype();
        int len = Array.getLength(data);

        // Dedup on raw value bits rather than boxed keys. This probed a
        // LinkedHashMap<Object, Integer>, boxing a value per ROW just to look it up - the same
        // cost already removed from DictColumnState#ingestDictChunk. Keys are compared as longs
        // and nothing is boxed: `firstSeenRaw` records each distinct value in first-seen order
        // for the pool, so the map is gone entirely.
        //
        // Float keying stays exactly what a boxed Float/Double map gave: dedup on
        // `floatToIntBits`/`doubleToLongBits` (canonical, so every NaN payload collapses to one
        // entry and -0.0 stays distinct from 0.0, matching Float.equals/Double.equals), while the
        // pool keeps the RAW bits of the first occurrence so the emitted value is unchanged.
        LongIntMap index = new LongIntMap(64);
        int[] codes = new int[len];
        long[] firstSeenRaw = new long[16];
        int dictSize = 0;
        for (int i = 0; i < len; i++) {
            long raw = rawBits(data, ptype, i);
            int code = index.get(canonicalBits(raw, ptype));
            if (code < 0) {
                code = dictSize;
                index.put(canonicalBits(raw, ptype), code);
                if (dictSize == firstSeenRaw.length) {
                    firstSeenRaw = java.util.Arrays.copyOf(firstSeenRaw, dictSize * 2);
                }
                firstSeenRaw[dictSize] = raw;
                dictSize++;
            }
            codes[i] = code;
        }

        PType codePType = codePType(dictSize);

        Object uniqueArray = buildUniqueArray(ptype, firstSeenRaw, dictSize);
        MemorySegment valuesBuf = PTypeIO.copyArray(ptype, uniqueArray, dictSize);

        Object codesArr = PrimitiveArrays.fromIntsArray(codes, codePType, EncodingId.VORTEX_DICT);
        return new DictData(valuesBuf, codesArr, codePType, len, dictSize);
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

    /// Builds the dictionary pool from the first-seen raw bit patterns, in order.
    ///
    /// @param ptype    the column's primitive type
    /// @param raw      first-seen raw bits, indexed by code
    /// @param dictSize number of distinct values
    /// @return a typed primitive array of the distinct values in first-seen order
    private static Object buildUniqueArray(PType ptype, long[] raw, int dictSize) {
        return PrimitiveArrays.fromBitsArray(java.util.Arrays.copyOf(raw, dictSize), ptype, EncodingId.VORTEX_DICT);
    }

    /// Raw bits of element `i`, with no boxing. Floats keep their exact payload here; see
    /// [#canonicalBits] for the form used to compare them.
    ///
    /// @param data  the column's typed primitive array
    /// @param ptype the column's primitive type
    /// @param i     element index
    /// @return the element's raw bit pattern
    private static long rawBits(Object data, PType ptype, int i) {
        return switch (ptype) {
            case I8, U8 -> ((byte[]) data)[i];
            case I16, U16, F16 -> ((short[]) data)[i];
            case I32, U32 -> ((int[]) data)[i];
            case I64, U64 -> ((long[]) data)[i];
            case F32 -> Float.floatToRawIntBits(((float[]) data)[i]) & 0xFFFFFFFFL;
            case F64 -> Double.doubleToRawLongBits(((double[]) data)[i]);
        };
    }

    /// The comparison form of [#rawBits]: identical for integers, and for floats the canonical
    /// bits, so two values dedup exactly when the boxed keys this replaced were `equals`.
    ///
    /// @param raw   a raw bit pattern from [#rawBits]
    /// @param ptype the column's primitive type
    /// @return the bits to compare and hash on
    private static long canonicalBits(long raw, PType ptype) {
        return switch (ptype) {
            case F32 -> Float.floatToIntBits(Float.intBitsToFloat((int) raw)) & 0xFFFFFFFFL;
            case F64 -> Double.doubleToLongBits(Double.longBitsToDouble(raw));
            default -> raw;
        };
    }


    private static void writeCodeToSeg(MemorySegment seg, PType codePType, int idx, int code) {
        switch (codePType) {
            case U8 -> seg.set(ValueLayout.JAVA_BYTE, idx, (byte) code);
            case U16 -> seg.set(VortexFormat.LE_SHORT, (long) idx * 2, (short) code);
            case U32 -> seg.set(VortexFormat.LE_INT, (long) idx * 4, code);
            default -> throw new VortexException(EncodingId.VORTEX_DICT, "unexpected code type: " + codePType);
        }
    }

    private static int readCodeFromArr(Object arr, PType codePType, int i) {
        return switch (codePType) {
            case U8 -> Byte.toUnsignedInt(((byte[]) arr)[i]);
            case U16 -> Short.toUnsignedInt(((short[]) arr)[i]);
            default -> ((int[]) arr)[i];
        };
    }

    /// The `vortex.encodings.DictMetadata` protobuf for a primitive dictionary, matching what the
    /// Rust reader expects and what the Utf8 path already emitted.
    private static byte[] dictMetadata(DictData d) {
        return new ProtoDictMetadata(
                d.dictSize(),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(d.codePType().ordinal()),
                null,
                null
        ).encode();
    }

    private record DictData(MemorySegment valuesBuf, Object codesArr, PType codePType, int len, int dictSize) {
    }
}
